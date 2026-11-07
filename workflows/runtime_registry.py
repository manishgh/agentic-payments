"""Durable local registration/tombstones; no delete/reuse operation is exposed."""
from __future__ import annotations

import json
import hashlib
import sqlite3
from threading import RLock

from workflows.runtime_contract import encoded, fail, start_from_data, checked_envelope, validate_activity_result, safe_state


class SqliteRegistrationStore:
    def __init__(self, filename):
        self._db = sqlite3.connect(str(filename), timeout=10, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = RLock()
        self._db.execute("PRAGMA foreign_keys=ON")
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.execute("CREATE TABLE IF NOT EXISTS workflow_registrations (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, start TEXT NOT NULL, attempted INTEGER NOT NULL DEFAULT 0, confirmed INTEGER NOT NULL DEFAULT 0, progress TEXT NOT NULL DEFAULT '{}', pause_requested INTEGER NOT NULL DEFAULT 0)")
        self._db.execute("CREATE TABLE IF NOT EXISTS workflow_wakes (workflow_id TEXT NOT NULL REFERENCES workflow_registrations(id), event_id TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(workflow_id,event_id))")
        self._db.execute("CREATE TABLE IF NOT EXISTS workflow_runtime_bindings (workflow_id TEXT PRIMARY KEY REFERENCES workflow_registrations(id), binding_id TEXT NOT NULL)")
        self._db.execute("CREATE TABLE IF NOT EXISTS workflow_recovery_observations (workflow_id TEXT PRIMARY KEY REFERENCES workflow_registrations(id), sequence INTEGER NOT NULL, activity_id TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, result_digest TEXT NOT NULL)")
        self._db.commit()

    def close(self):
        self._db.close()

    def reserve(self, start):
        ref = start.reference()
        with self._lock, self._db:
            self._db.execute("INSERT OR IGNORE INTO workflow_registrations(id,fingerprint,start) VALUES(?,?,?)",
                             (ref.runtime_id, start.fingerprint(), encoded(start.data())))
            row = self._db.execute("SELECT * FROM workflow_registrations WHERE id=?", (ref.runtime_id,)).fetchone()
            if row["fingerprint"] != start.fingerprint():
                fail("WORKFLOW_REGISTRATION_CONFLICT")
        return dict(row)

    def get(self, ref):
        with self._lock:
            row = self._db.execute("SELECT * FROM workflow_registrations WHERE id=?", (ref.runtime_id,)).fetchone()
            if row is None:
                fail("WORKFLOW_NOT_REGISTERED")
            start = start_from_data(json.loads(row["start"]))
            if start.reference() != ref:
                fail("WORKFLOW_SCOPE_DENIED")
            return dict(row)

    def claim_attempt(self, ref):
        self.get(ref)
        with self._lock, self._db:
            return self._db.execute("UPDATE workflow_registrations SET attempted=1 WHERE id=? AND attempted=0",
                                    (ref.runtime_id,)).rowcount == 1

    def confirm(self, ref):
        self.get(ref)
        with self._lock, self._db:
            self._db.execute("UPDATE workflow_registrations SET confirmed=1 WHERE id=?", (ref.runtime_id,))

    def save(self, ref, progress):
        self.get(ref)
        with self._lock, self._db:
            self._db.execute("UPDATE workflow_registrations SET progress=? WHERE id=?", (encoded(progress), ref.runtime_id))

    def validate_worker(self, start):
        row = self.get(start.reference())
        if row["fingerprint"] != start.fingerprint() or not row["attempted"]:
            fail("WORKFLOW_REGISTRATION_CONFLICT")

    def pause(self, ref, requested):
        self.get(ref)
        with self._lock, self._db:
            self._db.execute("UPDATE workflow_registrations SET pause_requested=? WHERE id=?", (int(requested), ref.runtime_id))
            return dict(self._db.execute("SELECT * FROM workflow_registrations WHERE id=?", (ref.runtime_id,)).fetchone())

    def stage_activity(self, ref, progress, envelope):
        """Atomic scheduler pause-versus-dispatch ordering; no financial admission."""
        checked_envelope(envelope)
        with self._lock, self._db:
            self._db.execute("BEGIN IMMEDIATE")
            row = self.get(ref)
            if row["pause_requested"]:
                progress["lifecycle"] = "PAUSED"
                allowed = False
            else:
                progress.update(pending=envelope, lifecycle="RUNNING")
                allowed = True
            self._db.execute("UPDATE workflow_registrations SET progress=? WHERE id=?", (encoded(progress), ref.runtime_id))
            return allowed

    def record_activity(self, envelope, result):
        start, request = checked_envelope(envelope)
        output = validate_activity_result(request, result)
        self.validate_worker(start)
        candidate = safe_state({**request["state"], **({} if request["kind"] == "decide" else output)})
        digest = hashlib.sha256(encoded(output).encode("ascii")).hexdigest()
        with self._lock, self._db:
            self._db.execute("BEGIN IMMEDIATE")
            old = self._db.execute("SELECT * FROM workflow_recovery_observations WHERE workflow_id=?", (start.reference().runtime_id,)).fetchone()
            if old is not None:
                retained = safe_state(json.loads(old["state"]))
                for field in ("intentId", "attemptId", "operationId"):
                    if retained.get(field) is not None and candidate.get(field) is not None and retained[field] != candidate[field]:
                        fail("RECOVERY_PROJECTION_CONFLICT")
                if request["sequence"] < old["sequence"]:
                    return
                if request["sequence"] == old["sequence"]:
                    if request["activityId"] != old["activity_id"] or request["kind"] != old["kind"]:
                        fail("RECOVERY_PROJECTION_CONFLICT")
                    if request["kind"] != "read_status" and digest != old["result_digest"]:
                        fail("RECOVERY_PROJECTION_CONFLICT")
                # Read retries may observe newer core truth before their SDK
                # completion was acknowledged. A late older thread cannot regress it.
                if candidate.get("version", 0) < retained.get("version", 0):
                    if request["sequence"] == old["sequence"]:
                        return
                    candidate = retained
                for field in ("intentId", "attemptId", "operationId"):
                    if candidate.get(field) is None and retained.get(field) is not None:
                        candidate[field] = retained[field]
                candidate = safe_state({**retained, **candidate})
            self._db.execute("INSERT INTO workflow_recovery_observations VALUES(?,?,?,?,?,?) ON CONFLICT(workflow_id) DO UPDATE SET sequence=excluded.sequence,activity_id=excluded.activity_id,kind=excluded.kind,state=excluded.state,result_digest=excluded.result_digest",
                (start.reference().runtime_id, request["sequence"], request["activityId"], request["kind"], encoded(candidate), digest))

    def recovery(self, ref):
        self.get(ref)
        with self._lock:
            row = self._db.execute("SELECT * FROM workflow_recovery_observations WHERE workflow_id=?", (ref.runtime_id,)).fetchone()
            return {"state": safe_state(json.loads(row["state"])) if row else {},
                    "sequence": row["sequence"] if row else 0,
                    "outstandingActivityId": None, "lifecycle": "RECOVERY_REQUIRED",
                    "source": "REGISTRATION_RECOVERY_CACHE", "authoritativePaymentState": False}

    def wake(self, ref, notice):
        self.get(ref)
        body = notice.checked()
        with self._lock, self._db:
            self._db.execute("INSERT OR IGNORE INTO workflow_wakes VALUES(?,?,?)", (ref.runtime_id, body["event_id"], body["reason"]))

    def bind(self, ref, binding_id):
        with self._lock, self._db:
            self._db.execute("BEGIN IMMEDIATE")
            row = self.get(ref)
            existing = self._db.execute("SELECT binding_id FROM workflow_runtime_bindings WHERE workflow_id=?", (ref.runtime_id,)).fetchone()
            if existing is None:
                if row["attempted"]:
                    fail("WORKFLOW_RECOVERY_REQUIRED")
                self._db.execute("INSERT INTO workflow_runtime_bindings VALUES(?,?)", (ref.runtime_id, binding_id))
            elif existing["binding_id"] != binding_id:
                fail("WORKFLOW_REGISTRATION_CONFLICT")
