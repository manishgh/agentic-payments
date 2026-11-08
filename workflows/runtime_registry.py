"""Durable local registration/tombstones; no delete/reuse operation is exposed."""
from __future__ import annotations

import json
import sqlite3
from threading import RLock

from workflows.runtime_contract import encoded, fail, start_from_data, checked_envelope, validate_activity_result, safe_state, RUNTIME_CATALOG
from workflows.payment_workflow import WorkflowError
from workflows.runtime_trace import (
    TRACE_CONTRACT, MAX_INVOCATIONS, MAX_ACTIVITIES, InvocationRef, TraceWriterDescriptor,
    checked_failure, safe_failure, digest, trace_limits, require_trace_writer, valid_trace_policy,
)


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
        self._db.execute("CREATE TABLE IF NOT EXISTS workflow_trace_headers (workflow_id TEXT PRIMARY KEY REFERENCES workflow_registrations(id), policy TEXT NOT NULL)")
        self._db.execute("CREATE TABLE IF NOT EXISTS workflow_trace_events (workflow_id TEXT NOT NULL REFERENCES workflow_trace_headers(workflow_id), ordinal INTEGER NOT NULL, kind TEXT NOT NULL, activity_id TEXT NOT NULL, invocation INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(workflow_id,ordinal))")
        self._db.execute("CREATE UNIQUE INDEX IF NOT EXISTS workflow_trace_activity ON workflow_trace_events(workflow_id,activity_id) WHERE kind='ACTIVITY'")
        self._db.execute("CREATE UNIQUE INDEX IF NOT EXISTS workflow_trace_begin ON workflow_trace_events(workflow_id,invocation) WHERE kind='BEGIN'")
        self._db.execute("CREATE UNIQUE INDEX IF NOT EXISTS workflow_trace_terminal ON workflow_trace_events(workflow_id,invocation) WHERE kind IN ('SUCCEEDED','FAILED')")
        self._db.execute("CREATE UNIQUE INDEX IF NOT EXISTS workflow_trace_conflict ON workflow_trace_events(workflow_id,activity_id,invocation) WHERE kind='CONFLICT'")
        self._db.commit()

    def close(self):
        self._db.close()

    def reserve(self, start):
        require_trace_writer(self)
        start.checked(RUNTIME_CATALOG.resolve(start.runtime).capabilities)
        ref = start.reference()
        with self._lock, self._db:
            self._db.execute("BEGIN IMMEDIATE")
            inserted = self._db.execute("INSERT OR IGNORE INTO workflow_registrations(id,fingerprint,start) VALUES(?,?,?)",
                             (ref.runtime_id, start.fingerprint(), encoded(start.data()))).rowcount
            row = self._db.execute("SELECT * FROM workflow_registrations WHERE id=?", (ref.runtime_id,)).fetchone()
            if row["fingerprint"] != start.fingerprint():
                fail("WORKFLOW_REGISTRATION_CONFLICT")
            if inserted:
                self._db.execute("INSERT INTO workflow_trace_headers VALUES(?,?)", (ref.runtime_id, encoded(self._new_trace_policy())))
        return dict(row)

    @staticmethod
    def describe_trace():
        return TraceWriterDescriptor()

    @staticmethod
    def _new_trace_policy():
        return {"contract": TRACE_CONTRACT, "coverage": "FROM_REGISTRATION", "limits": trace_limits()}

    def trace_policy(self, ref):
        with self._lock:
            self.get(ref)  # Header absence is permitted only for an existing row.
            row = self._db.execute("SELECT policy FROM workflow_trace_headers WHERE workflow_id=?", (ref.runtime_id,)).fetchone()
            return json.loads(row[0]) if row else {"coverage": "HISTORICAL_TRACE_UNAVAILABLE"}

    def _event(self, workflow, kind, activity, invocation, payload):
        ordinal = self._db.execute("SELECT COALESCE(MAX(ordinal),0)+1 FROM workflow_trace_events WHERE workflow_id=?", (workflow,)).fetchone()[0]
        self._db.execute("INSERT INTO workflow_trace_events VALUES(?,?,?,?,?,?)", (workflow, ordinal, kind, activity, invocation, encoded(payload)))

    def _conflict(self, workflow, activity, invocation, code):
        existing = self._db.execute("SELECT 1 FROM workflow_trace_events WHERE workflow_id=? AND activity_id=? AND invocation=? AND kind='CONFLICT'", (workflow, activity, invocation)).fetchone()
        if existing is None:
            self._event(workflow, "CONFLICT", activity, invocation, {"code": code})

    def begin_activity(self, envelope):
        require_trace_writer(self)
        start, request = checked_envelope(envelope)
        conflict = False
        reference = start.reference()
        with self._lock, self._db:
            self._db.execute("BEGIN IMMEDIATE")
            self.validate_worker(start)
            if not valid_trace_policy(self.trace_policy(reference)):
                fail("UNSUPPORTED_TRACE_WRITER")
            workflow, activity = reference.runtime_id, request["activityId"]
            logical = self._db.execute("SELECT payload FROM workflow_trace_events WHERE workflow_id=? AND activity_id=? AND kind='ACTIVITY'", (workflow, activity)).fetchone()
            expected = {"envelope": request, "digest": digest(request)}
            sequence_owner = None
            if logical is None:
                for previous in self._db.execute("SELECT activity_id,payload FROM workflow_trace_events WHERE workflow_id=? AND kind='ACTIVITY'", (workflow,)):
                    if json.loads(previous[1])["envelope"]["sequence"] == request["sequence"]:
                        sequence_owner = previous[0]
                        break
            if sequence_owner is not None:
                self._conflict(workflow, sequence_owner, 0, "TRACE_ENVELOPE_CONFLICT")
                conflict = True
            elif logical is not None and json.loads(logical[0]) != expected:
                self._conflict(workflow, activity, 0, "TRACE_ENVELOPE_CONFLICT")
                conflict = True
            else:
                counts = dict(self._db.execute("SELECT kind,COUNT(*) FROM workflow_trace_events WHERE workflow_id=? AND kind IN ('BEGIN','ACTIVITY') GROUP BY kind", (workflow,)).fetchall())
                if counts.get("BEGIN", 0) >= MAX_INVOCATIONS or (logical is None and counts.get("ACTIVITY", 0) >= MAX_ACTIVITIES):
                    fail("WORKFLOW_TRACE_LIMIT")
                if logical is None:
                    self._event(workflow, "ACTIVITY", activity, 0, expected)
                ordinal = counts.get("BEGIN", 0) + 1
                self._event(workflow, "BEGIN", activity, ordinal, {})
        if conflict:
            fail("WORKFLOW_TRACE_CONFLICT")
        return InvocationRef(workflow, activity, ordinal)

    def _invocation(self, start, request, invocation):
        if (type(invocation) is not InvocationRef or type(invocation.ordinal) is not int or not 1 <= invocation.ordinal <= MAX_INVOCATIONS
                or invocation.workflow_id != start.reference().runtime_id or invocation.activity_id != request["activityId"]):
            fail("INVALID_TRACE_INPUT")
        row = self._db.execute("SELECT activity_id FROM workflow_trace_events WHERE workflow_id=? AND invocation=? AND kind='BEGIN'", (invocation.workflow_id, invocation.ordinal)).fetchone()
        if row is None or row[0] != invocation.activity_id:
            fail("INVALID_TRACE_INPUT")
        logical = self._db.execute("SELECT payload FROM workflow_trace_events WHERE workflow_id=? AND activity_id=? AND kind='ACTIVITY'", (invocation.workflow_id, invocation.activity_id)).fetchone()
        if logical is None or json.loads(logical[0]) != {"envelope": request, "digest": digest(request)}:
            self._conflict(invocation.workflow_id, invocation.activity_id, 0, "TRACE_ENVELOPE_CONFLICT")
            return False
        return True

    def _terminal(self, invocation):
        return self._db.execute("SELECT kind,payload FROM workflow_trace_events WHERE workflow_id=? AND invocation=? AND kind IN ('SUCCEEDED','FAILED')", (invocation.workflow_id, invocation.ordinal)).fetchone()

    def record_failure(self, envelope, failure, invocation):
        start, request = checked_envelope(envelope)
        value = checked_failure(failure)
        conflict = False
        with self._lock, self._db:
            self._db.execute("BEGIN IMMEDIATE")
            self.validate_worker(start)
            if not self._invocation(start, request, invocation):
                conflict = True
            else:
                old = self._terminal(invocation)
                if old is not None:
                    if old[0] != "FAILED" or json.loads(old[1]) != value:
                        self._conflict(invocation.workflow_id, invocation.activity_id, invocation.ordinal, "TRACE_TERMINAL_CONFLICT")
                        conflict = True
                else:
                    self._event(invocation.workflow_id, "FAILED", invocation.activity_id, invocation.ordinal, value)
        if conflict:
            fail("WORKFLOW_TRACE_CONFLICT")

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

    def record_activity(self, envelope, result, invocation=None):
        start, request = checked_envelope(envelope)
        output = validate_activity_result(request, result)
        conflict = False
        with self._lock, self._db:
            self._db.execute("BEGIN IMMEDIATE")
            self.validate_worker(start)
            if invocation is None:
                if self.trace_policy(start.reference()) != {"coverage": "HISTORICAL_TRACE_UNAVAILABLE"}:
                    fail("INVALID_TRACE_INPUT")
                self._record_recovery(start, request, output)
                return
            if not self._invocation(start, request, invocation):
                conflict = True
            else:
                old = self._terminal(invocation)
                if old is not None:
                    if old[0] != "SUCCEEDED" or json.loads(old[1]) != output:
                        self._conflict(invocation.workflow_id, invocation.activity_id, invocation.ordinal, "TRACE_TERMINAL_CONFLICT")
                        conflict = True
                else:
                    previous = self._db.execute("SELECT payload FROM workflow_trace_events WHERE workflow_id=? AND activity_id=? AND kind='SUCCEEDED' ORDER BY ordinal LIMIT 1", (invocation.workflow_id, invocation.activity_id)).fetchone()
                    if request["kind"] != "read_status" and previous is not None and json.loads(previous[0]) != output:
                        conflict = True
                    else:
                        # Recovery validation has no writes before its final upsert.
                        try:
                            self._record_recovery(start, request, output)
                        except WorkflowError as error:
                            if error.args != ("RECOVERY_PROJECTION_CONFLICT",):
                                raise
                            conflict = True
                    if conflict:
                        self._conflict(invocation.workflow_id, invocation.activity_id, invocation.ordinal, "TRACE_RESULT_CONFLICT")
                        self._event(invocation.workflow_id, "FAILED", invocation.activity_id, invocation.ordinal,
                                    safe_failure("RECOVERY_PROJECTION_CONFLICT", "RESULT_VALIDATION"))
                    else:
                        self._event(invocation.workflow_id, "SUCCEEDED", invocation.activity_id, invocation.ordinal, output)
        if conflict:
            fail("WORKFLOW_TRACE_CONFLICT")

    def _record_recovery(self, start, request, output):
        candidate = safe_state({**request["state"], **({} if request["kind"] == "decide" else output)})
        result_digest = digest(output)
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
                if request["kind"] != "read_status" and result_digest != old["result_digest"]:
                    fail("RECOVERY_PROJECTION_CONFLICT")
            # A timed-out read can finish after a newer retry, without regressing recovery.
            if candidate.get("version", 0) < retained.get("version", 0):
                if request["sequence"] == old["sequence"]:
                    return
                candidate = retained
            for field in ("intentId", "attemptId", "operationId"):
                if candidate.get(field) is None and retained.get(field) is not None:
                    candidate[field] = retained[field]
            candidate = safe_state({**retained, **candidate})
        self._db.execute("INSERT INTO workflow_recovery_observations VALUES(?,?,?,?,?,?) ON CONFLICT(workflow_id) DO UPDATE SET sequence=excluded.sequence,activity_id=excluded.activity_id,kind=excluded.kind,state=excluded.state,result_digest=excluded.result_digest",
            (start.reference().runtime_id, request["sequence"], request["activityId"], request["kind"], encoded(candidate), result_digest))

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
