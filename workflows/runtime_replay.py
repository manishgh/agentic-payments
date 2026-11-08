"""Read-only safe trace snapshots and offline recorded-step verification."""
from __future__ import annotations

import json
from pathlib import Path
import sqlite3

from workflows.payment_workflow import WorkflowError
from workflows.runtime_contract import DECISION_VERSION, MAX_PAYLOAD_BYTES, checked_envelope, encoded, start_from_data, validate_activity_result
from workflows.runtime_trace import TRACE_CONTRACT, MAX_EVENTS, MAX_ACTIVITIES, MAX_INVOCATIONS, CONFLICT_CODES, checked_failure, digest, trace_limits, valid_trace_limits

MAX_EXPORT_BYTES = 100 * 1024 * 1024


def _pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError()
        result[key] = value
    return result


def _json(raw):
    def invalid_constant(_):
        raise ValueError()
    return json.loads(raw, object_pairs_hook=_pairs, parse_constant=invalid_constant)


def load_trace(filename):
    failure = None
    try:
        with Path(filename).open("rb") as stream:
            raw = stream.read(MAX_EXPORT_BYTES + 1)
        if len(raw) > MAX_EXPORT_BYTES:
            failure = "WORKFLOW_TRACE_LIMIT"
        else:
            try:
                return _json(raw.decode("utf-8", errors="strict"))
            except (ValueError, UnicodeError, RecursionError):
                failure = "WORKFLOW_TRACE_INCOMPLETE"
    except OSError:
        failure = "WORKFLOW_TRACE_UNAVAILABLE"
    raise WorkflowError(failure) from None


class SqliteWorkflowTraceReader:
    """No writer construction or schema changes; one snapshot read transaction."""
    def __init__(self, filename):
        self._db = None
        failed = False
        try:
            uri = Path(filename).resolve().as_uri() + "?mode=ro"
            self._db = sqlite3.connect(uri, uri=True, timeout=10)
            self._db.row_factory = sqlite3.Row
            self._db.execute("PRAGMA query_only=ON")
        except (OSError, ValueError, sqlite3.Error):
            failed = True
        if failed:
            self.close()
            raise WorkflowError("WORKFLOW_TRACE_UNAVAILABLE") from None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        if self._db is not None:
            self._db.close()
            self._db = None

    def read_trace(self, reference):
        failure = None
        try:
            self._db.execute("BEGIN")
            row = self._db.execute("SELECT start,fingerprint FROM workflow_registrations WHERE id=?", (reference.runtime_id,)).fetchone()
            if row is None:
                raise WorkflowError("WORKFLOW_NOT_REGISTERED")
            if len(row["start"]) > MAX_PAYLOAD_BYTES:
                raise WorkflowError("WORKFLOW_TRACE_LIMIT")
            start_data = _json(row["start"])
            start = start_from_data(start_data)
            if start.reference() != reference:
                raise WorkflowError("WORKFLOW_SCOPE_DENIED")
            tables = {item[0] for item in self._db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('workflow_trace_headers','workflow_trace_events')")}
            header = None
            if "workflow_trace_headers" in tables:
                header = self._db.execute("SELECT policy FROM workflow_trace_headers WHERE workflow_id=?", (reference.runtime_id,)).fetchone()
            events = []
            policy = {"coverage": "HISTORICAL_TRACE_UNAVAILABLE", "contract": TRACE_CONTRACT, "limits": trace_limits()}
            if header is not None:
                if len(header[0]) > 1024:
                    raise WorkflowError("WORKFLOW_TRACE_LIMIT")
                policy = _json(header[0])
                if (type(policy) is not dict or set(policy) != {"contract", "coverage", "limits"}):
                    raise WorkflowError("WORKFLOW_TRACE_INVALID")
                if (type(policy["limits"]) is not dict or set(policy["limits"]) != set(trace_limits())
                        or any(type(item) is not int for item in policy["limits"].values())):
                    raise WorkflowError("WORKFLOW_TRACE_INVALID")
                if policy["contract"] != TRACE_CONTRACT or policy["limits"] != trace_limits():
                    raise WorkflowError("WORKFLOW_TRACE_UNSUPPORTED_VERSION")
                if policy["coverage"] != "FROM_REGISTRATION":
                    raise WorkflowError("WORKFLOW_TRACE_INVALID")
                if "workflow_trace_events" not in tables:
                    raise WorkflowError("WORKFLOW_TRACE_INCOMPLETE")
                oversize = self._db.execute("SELECT 1 FROM workflow_trace_events WHERE workflow_id=? AND length(payload)>? LIMIT 1", (reference.runtime_id, MAX_PAYLOAD_BYTES + 256)).fetchone()
                if oversize:
                    raise WorkflowError("WORKFLOW_TRACE_LIMIT")
                rows = self._db.execute("SELECT ordinal,kind,activity_id,invocation,payload FROM workflow_trace_events WHERE workflow_id=? ORDER BY ordinal LIMIT ?", (reference.runtime_id, MAX_EVENTS + 1)).fetchall()
                if len(rows) > MAX_EVENTS:
                    raise WorkflowError("WORKFLOW_TRACE_LIMIT")
                events = [{"ordinal": item[0], "kind": item[1], "activityId": item[2],
                           "invocation": item[3], "payload": _json(item[4])} for item in rows]
            value = {"contract": policy.get("contract"), "coverage": policy.get("coverage"), "limits": policy.get("limits"),
                     "start": start_data, "fingerprint": row["fingerprint"], "events": events}
            value["head"] = {"eventCount": len(events), "digest": digest(value)}
            self._db.rollback()
            # Returning a snapshot never exposes corrupt/unallowlisted stored values.
            try:
                _validated(value)
            except _Problem as problem:
                code = {"UNSUPPORTED_VERSION": "WORKFLOW_TRACE_UNSUPPORTED_VERSION", "INCOMPLETE": "WORKFLOW_TRACE_INCOMPLETE"}.get(problem.verdict, "WORKFLOW_TRACE_INVALID")
                raise WorkflowError(code) from None
            except WorkflowError as error:
                code = "WORKFLOW_TRACE_UNSUPPORTED_VERSION" if error.args == ("UNSUPPORTED_RUNTIME_VERSION",) else "WORKFLOW_TRACE_INVALID"
                raise WorkflowError(code) from None
            return value
        except WorkflowError as error:
            failure = error.args[0] if error.args and error.args[0] in {
                "WORKFLOW_NOT_REGISTERED", "WORKFLOW_SCOPE_DENIED", "WORKFLOW_TRACE_LIMIT",
                "WORKFLOW_TRACE_INCOMPLETE", "WORKFLOW_TRACE_INVALID", "WORKFLOW_TRACE_UNSUPPORTED_VERSION"} else (
                    "WORKFLOW_TRACE_UNSUPPORTED_VERSION" if error.args == ("UNSUPPORTED_RUNTIME_VERSION",) else "WORKFLOW_TRACE_UNAVAILABLE")
        except Exception:
            failure = "WORKFLOW_TRACE_UNAVAILABLE"
        finally:
            if self._db is not None and self._db.in_transaction:
                self._db.rollback()
        raise WorkflowError(failure) from None


class _Problem(Exception):
    def __init__(self, verdict, code, activity=None, invocation=None):
        self.verdict, self.code = verdict, code
        self.activity, self.invocation = activity, invocation


def _require(condition, code="INVALID_TRACE_RECORD"):
    if not condition:
        raise _Problem("MISMATCH", code)


def _validated(snapshot):
    _require(type(snapshot) is dict and set(snapshot) == {"contract", "coverage", "start", "fingerprint", "limits", "events", "head"})
    if snapshot["contract"] != TRACE_CONTRACT:
        raise _Problem("UNSUPPORTED_VERSION", "UNSUPPORTED_TRACE_VERSION")
    _require(snapshot["coverage"] in ("FROM_REGISTRATION", "HISTORICAL_TRACE_UNAVAILABLE"))
    _require(valid_trace_limits(snapshot["limits"]))
    start = start_from_data(snapshot["start"])
    _require(start.data() == snapshot["start"] and snapshot["fingerprint"] == start.fingerprint())
    events, head = snapshot["events"], snapshot["head"]
    _require(type(events) is list and len(events) <= MAX_EVENTS)
    _require(type(head) is dict and set(head) == {"eventCount", "digest"} and type(head["eventCount"]) is int and 0 <= head["eventCount"] <= MAX_EVENTS)
    if len(events) < head["eventCount"]:
        raise _Problem("INCOMPLETE", "MISSING_TRACE_RECORDS")
    _require(len(events) == head["eventCount"])
    _require(head["digest"] == digest({key: value for key, value in snapshot.items() if key != "head"}), "TRACE_DIGEST_MISMATCH")
    logical, invocations, conflicts, sequences, conflict_slots = {}, {}, [], set(), set()
    for index, event in enumerate(events, 1):
        _require(type(event) is dict and set(event) == {"ordinal", "kind", "activityId", "invocation", "payload"})
        if type(event["ordinal"]) is int and event["ordinal"] > index:
            raise _Problem("INCOMPLETE", "MISSING_TRACE_RECORDS")
        _require(type(event["ordinal"]) is int and event["ordinal"] == index)
        kind, activity, number, payload = event["kind"], event["activityId"], event["invocation"], event["payload"]
        _require(type(kind) is str and kind in {"ACTIVITY", "BEGIN", "SUCCEEDED", "FAILED", "CONFLICT"})
        _require(type(activity) is str and len(activity) <= 256 and type(number) is int and 0 <= number <= MAX_INVOCATIONS)
        _require(type(payload) is dict and len(encoded(payload).encode("ascii")) <= MAX_PAYLOAD_BYTES + 256)
        if kind == "ACTIVITY":
            _require(number == 0 and activity not in logical and len(logical) < MAX_ACTIVITIES)
            _require(set(payload) == {"envelope", "digest"})
            registered, envelope = checked_envelope(payload["envelope"])
            _require(registered.fingerprint() == start.fingerprint() and activity == envelope["activityId"] and payload["digest"] == digest(envelope))
            _require(envelope["sequence"] not in sequences)
            sequences.add(envelope["sequence"])
            logical[activity] = {"envelope": envelope, "success": None}
            continue
        _require(activity in logical)
        if kind == "BEGIN":
            _require(number == len(invocations) + 1 and number <= MAX_INVOCATIONS and payload == {})
            invocations[number] = {"activityId": activity, "invocation": number, "beganAt": index, "outcome": None}
        elif kind == "CONFLICT":
            _require(set(payload) == {"code"} and type(payload["code"]) is str and payload["code"] in CONFLICT_CODES)
            _require((activity, number) not in conflict_slots)
            if number:
                _require(number in invocations and invocations[number]["activityId"] == activity)
                _require(payload["code"] != "TRACE_ENVELOPE_CONFLICT")
            else:
                _require(payload["code"] == "TRACE_ENVELOPE_CONFLICT")
            conflict_slots.add((activity, number))
            conflicts.append(event)
        else:
            _require(number in invocations and invocations[number]["activityId"] == activity and invocations[number]["outcome"] is None)
            if kind == "SUCCEEDED":
                value = validate_activity_result(logical[activity]["envelope"], payload)
                previous = logical[activity]["success"]
                if logical[activity]["envelope"]["kind"] != "read_status":
                    if previous is not None and previous != value:
                        raise _Problem("MISMATCH", "TRACE_RESULT_CONFLICT", activity, number)
                    logical[activity]["success"] = value
            else:
                value = checked_failure(payload)
            invocations[number]["outcome"] = {"status": kind, "value": value, "recordedAt": index}
    _require(snapshot["coverage"] != "HISTORICAL_TRACE_UNAVAILABLE" or not events)
    return start, logical, invocations, conflicts


def _report(snapshot, replay, decision_catalog=None):
    report = {"verdict": "MISMATCH", "coverage": "UNVERIFIED", "authoritativePaymentState": False,
              "schedulerConsumptionVerified": False, "decisionsReplayed": False, "logicalSteps": 0, "invocations": 0,
              "failures": 0, "unresolved": 0, "steps": []}
    try:
        start, logical, invocations, conflicts = _validated(snapshot)
        report.update(coverage=snapshot["coverage"], logicalSteps=len(logical), invocations=len(invocations),
                      failures=sum(item["outcome"] is not None and item["outcome"]["status"] == "FAILED" for item in invocations.values()),
                      unresolved=sum(item["outcome"] is None for item in invocations.values()))
        report["steps"] = [{"activityId": activity, "sequence": item["envelope"]["sequence"], "kind": item["envelope"]["kind"],
                            "invocations": [dict(inv) for inv in invocations.values() if inv["activityId"] == activity]}
                           for activity, item in sorted(logical.items(), key=lambda pair: pair[1]["envelope"]["sequence"])]
        if conflicts:
            report["conflicts"] = [{"activityId": item["activityId"], "invocation": item["invocation"], "code": item["payload"]["code"]} for item in conflicts]
            raise _Problem("MISMATCH", "TRACE_EVIDENCE_CONFLICT", conflicts[0]["activityId"], conflicts[0]["invocation"])
        if replay:
            if decision_catalog is None:
                from workflows.runtime_logic import select_decision
                decision_catalog = {DECISION_VERSION: select_decision}
            decision = decision_catalog.get(start.decision_version)
            if decision is None:
                raise _Problem("UNSUPPORTED_VERSION", "UNSUPPORTED_DECISION_VERSION")
            for inv in invocations.values():
                envelope = logical[inv["activityId"]]["envelope"]
                outcome = inv["outcome"]
                if envelope["kind"] != "decide" or outcome is None or outcome["status"] != "SUCCEEDED":
                    continue
                failed = False
                try:
                    expected = {"decision": decision(json.loads(encoded(envelope["state"])))}
                except Exception:
                    failed = True
                if failed:
                    raise _Problem("MISMATCH", "DECISION_REPLAY_FAILED", inv["activityId"], inv["invocation"])
                if expected != outcome["value"]:
                    raise _Problem("MISMATCH", "DECISION_REPLAY_MISMATCH", inv["activityId"], inv["invocation"])
            report["decisionsReplayed"] = True
        sequences = {item["envelope"]["sequence"] for item in logical.values()}
        missing_steps = bool(sequences and sequences != set(range(max(sequences) + 1)))
        missing_begins = any(not any(inv["activityId"] == activity for inv in invocations.values()) for activity in logical)
        if report["unresolved"] or report["coverage"] != "FROM_REGISTRATION" or missing_steps or missing_begins:
            report["verdict"] = "INCOMPLETE"
        else:
            report["verdict"] = "VERIFIED_WITH_RECORDED_FAILURE" if report["failures"] else "VERIFIED_RECORDED_STEPS"
    except _Problem as problem:
        report.update(verdict=problem.verdict, code=problem.code)
        if problem.activity is not None:
            report["location"] = {"activityId": problem.activity, "invocation": problem.invocation}
    except WorkflowError as error:
        unsupported = error.args == ("UNSUPPORTED_RUNTIME_VERSION",)
        report.update(verdict="UNSUPPORTED_VERSION" if unsupported else "MISMATCH", code="UNSUPPORTED_DECISION_VERSION" if unsupported else "INVALID_TRACE_RECORD")
    except Exception:
        report.update(verdict="MISMATCH", code="INVALID_TRACE_RECORD")
    return report


def debug_trace(snapshot):
    """Inspect safe records without invoking even the pure decision implementation."""
    return _report(snapshot, False)


def replay_trace(snapshot, decision_catalog=None):
    """The catalog is trusted local code, never a selector for arbitrary imports."""
    return _report(snapshot, True, decision_catalog)
