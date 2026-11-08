"""Durable trace faults, concurrent attempts, privacy and offline replay contracts."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import json
from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from uuid import uuid4

from workflows.payment_workflow import WorkflowError, WorkflowRejectedError
from workflows.runtime_contract import StartWorkflow, activity_envelope, encoded
from workflows.runtime_registry import SqliteRegistrationStore
from workflows.runtime_trace import (
    TRACE_CONTRACT, MAX_INVOCATIONS, TraceWriterDescriptor, TraceActivityError,
    digest, invoke_recorded, require_trace_writer, safe_failure, classify_failure, safe_diagnostic,
)
from workflows.runtime_replay import SqliteWorkflowTraceReader, debug_trace, replay_trace, load_trace
from workflows.sqlite_runtime import SqliteWorkflowRuntime


class Forwarder:
    def __init__(self, target):
        self.target = target

    def __getattr__(self, name):
        return getattr(self.target, name)


class TraceTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[1] / ".tools/trace-tests"
        root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "registry.sqlite"
        self.store = SqliteRegistrationStore(self.path)
        self.addCleanup(self.store.close)
        self.start = StartWorkflow("tenant-local", "agent-demo", str(uuid4()), str(uuid4()))
        self.ref = self.start.reference()
        self.store.reserve(self.start)
        self.store.claim_attempt(self.ref)
        self.envelope = activity_envelope(self.start, 0, "decide", {})
        self.result = {"decision": "create_intent"}

    def activities(self, run=None, registration=None):
        return SimpleNamespace(registration=registration or self.store, run=run or (lambda _: dict(self.result)))

    def snapshot(self):
        with SqliteWorkflowTraceReader(self.path) as reader:
            return reader.read_trace(self.ref)

    def events(self, kind):
        return [event for event in self.snapshot()["events"] if event["kind"] == kind]

    def test_recorded_decision_and_effect_replay_are_offline_and_read_only(self):
        self.assertEqual(self.result, invoke_recorded(self.activities(), self.envelope))
        create = activity_envelope(self.start, 1, "create_intent", {})
        created = {"intentId": str(uuid4()), "createdVersion": 1}
        invoke_recorded(self.activities(lambda _: created), create)
        snapshot = self.snapshot()
        before = self.path.read_bytes()
        with patch("workflows.payment_workflow.LocalPaymentClient", side_effect=AssertionError("network")), \
                patch("workflows.runtime_registry.SqliteRegistrationStore", side_effect=AssertionError("writer")):
            report = replay_trace(snapshot)
        self.assertEqual("VERIFIED_RECORDED_STEPS", report["verdict"])
        self.assertEqual((2, 2, 0), (report["logicalSteps"], report["invocations"], report["unresolved"]))
        self.assertFalse(report["authoritativePaymentState"])
        self.assertFalse(report["schedulerConsumptionVerified"])
        self.assertEqual(before, self.path.read_bytes())

    def test_rejected_activity_is_safe_and_failed_decision_is_not_rerun(self):
        def rejected(_):
            raise WorkflowError("PAYMENT_COMMAND_REJECTED")
        with self.assertRaises(TraceActivityError) as caught:
            invoke_recorded(self.activities(rejected), self.envelope)
        self.assertEqual("PAYMENT_COMMAND_REJECTED", caught.exception.failure["code"])
        def forbidden(_):
            raise AssertionError("Failed decision must not run during replay")
        report = replay_trace(self.snapshot(), {self.start.decision_version: forbidden})
        self.assertEqual("VERIFIED_WITH_RECORDED_FAILURE", report["verdict"])
        self.assertEqual(1, report["failures"])

    def test_unknown_exception_and_decorated_bad_result_never_persist_canaries(self):
        canary = "RAW_TOKEN_ACP_EXCEPTION_CANARY"
        def broken(_):
            raise RuntimeError(canary)
        for index, run in enumerate((broken, lambda _: {"decision": "create_intent", "token": canary})):
            with self.assertRaises(TraceActivityError) as caught:
                invoke_recorded(self.activities(run), activity_envelope(self.start, index, "decide", {}))
            self.assertNotIn(canary, str(caught.exception))
        self.assertNotIn(canary, encoded(self.snapshot()))
        self.assertEqual("UNEXPECTED_ACTIVITY_FAILURE", self.events("FAILED")[0]["payload"]["code"])
        self.assertEqual("RESULT_VALIDATION", self.events("FAILED")[1]["payload"]["phase"])

    def test_begin_failure_makes_no_activity_call_or_invented_outcome(self):
        class BrokenBegin(Forwarder):
            def begin_activity(self, envelope):
                raise OSError("PRIVATE_PATH_TOKEN")
        calls = []
        with self.assertRaises(TraceActivityError) as caught:
            invoke_recorded(self.activities(lambda _: calls.append(True), BrokenBegin(self.store)), self.envelope)
        self.assertEqual([], calls)
        self.assertEqual("WORKFLOW_TRACE_UNAVAILABLE", caught.exception.failure["code"])
        self.assertEqual([], self.snapshot()["events"])

    def test_begin_commit_reply_loss_leaves_unresolved_without_effect(self):
        class LostBegin(Forwarder):
            def begin_activity(self, envelope):
                self.target.begin_activity(envelope)
                raise OSError("PRIVATE_COMMIT_ACK")
        calls = []
        with self.assertRaises(TraceActivityError):
            invoke_recorded(self.activities(lambda _: calls.append(True), LostBegin(self.store)), self.envelope)
        self.assertEqual([], calls)
        self.assertEqual("INCOMPLETE", replay_trace(self.snapshot())["verdict"])

    def test_success_commit_reply_loss_does_not_manufacture_failure_or_conflict(self):
        class LostSuccess(Forwarder):
            def __init__(self, target):
                super().__init__(target)
                self.first, self.failures = True, 0
            def record_activity(self, *args):
                self.target.record_activity(*args)
                if self.first:
                    self.first = False
                    raise OSError("PRIVATE_COMMIT_ACK")
            def record_failure(self, *args):
                self.failures += 1
                return self.target.record_failure(*args)
        writer = LostSuccess(self.store)
        with self.assertRaises(TraceActivityError) as caught:
            invoke_recorded(self.activities(registration=writer), self.envelope)
        self.assertEqual("WORKFLOW_TRACE_UNCONFIRMED", caught.exception.failure["code"])
        self.assertEqual("VERIFIED_RECORDED_STEPS", replay_trace(self.snapshot())["verdict"])
        self.assertEqual(self.result, invoke_recorded(self.activities(registration=writer), self.envelope))
        self.assertEqual(0, writer.failures)
        self.assertEqual(2, len(self.events("BEGIN")))
        self.assertEqual([], self.events("CONFLICT"))

    def test_failure_commit_reply_loss_preserves_primary_and_actual_terminal(self):
        class LostFailure(Forwarder):
            def record_failure(self, *args):
                self.target.record_failure(*args)
                raise OSError("PRIVATE_FAILURE_ACK")
        def rejected(_):
            raise WorkflowError("PAYMENT_COMMAND_REJECTED")
        with self.assertRaises(TraceActivityError) as caught:
            invoke_recorded(self.activities(rejected, LostFailure(self.store)), self.envelope)
        diagnostic = caught.exception.diagnostic()
        self.assertEqual("PAYMENT_COMMAND_REJECTED", diagnostic["failure"]["code"])
        self.assertEqual("WORKFLOW_TRACE_UNCONFIRMED", diagnostic["secondary"]["code"])
        self.assertEqual("VERIFIED_WITH_RECORDED_FAILURE", replay_trace(self.snapshot())["verdict"])

    def test_terminal_storage_fault_rolls_back_recovery_but_not_remote_effect(self):
        create = activity_envelope(self.start, 0, "create_intent", {})
        receipt = {"intentId": str(uuid4()), "createdVersion": 1}
        calls, effects = [], set()
        def remote(_):
            calls.append("same-command")
            effects.add("same-command")
            return receipt
        self.store._db.execute("CREATE TRIGGER reject_trace_success BEFORE INSERT ON workflow_trace_events WHEN NEW.kind='SUCCEEDED' BEGIN SELECT RAISE(ABORT,'PRIVATE_SQL_FAILURE'); END")
        self.store._db.commit()
        with self.assertRaises(TraceActivityError):
            invoke_recorded(self.activities(remote), create)
        self.assertEqual({}, self.store.recovery(self.ref)["state"])
        self.assertEqual("INCOMPLETE", replay_trace(self.snapshot())["verdict"])
        self.store._db.execute("DROP TRIGGER reject_trace_success")
        self.store._db.commit()
        invoke_recorded(self.activities(remote), create)
        self.assertEqual((2, 1), (len(calls), len(effects)))
        self.assertEqual(receipt["intentId"], self.store.recovery(self.ref)["state"]["intentId"])
        self.assertEqual(1, debug_trace(self.snapshot())["unresolved"])

    def test_immutable_envelope_conflict_survives_raise_and_is_bounded(self):
        invocation = self.store.begin_activity(self.envelope)
        self.store.record_activity(self.envelope, self.result, invocation)
        previous = self.store.recovery(self.ref)
        changed = activity_envelope(self.start, 0, "decide", {"profile": self.start.profile})
        for _ in range(2):
            with self.assertRaisesRegex(WorkflowError, "WORKFLOW_TRACE_CONFLICT"):
                self.store.begin_activity(changed)
        self.assertEqual(1, len(self.events("CONFLICT")))
        self.assertEqual(previous, self.store.recovery(self.ref))
        self.assertEqual("MISMATCH", replay_trace(self.snapshot())["verdict"])

    def test_changed_kind_cannot_reuse_a_logical_sequence_before_an_effect(self):
        invoke_recorded(self.activities(), self.envelope)
        calls = []
        conflicting = activity_envelope(self.start, 0, "create_intent", {})
        with self.assertRaisesRegex(WorkflowError, "WORKFLOW_TRACE_CONFLICT"):
            invoke_recorded(self.activities(lambda _: calls.append(True)), conflicting)
        self.assertEqual([], calls)
        self.assertEqual(1, len(self.events("BEGIN")))
        self.assertEqual(self.envelope["activityId"], self.events("CONFLICT")[0]["activityId"])
        self.assertEqual("MISMATCH", replay_trace(self.snapshot())["verdict"])

    def test_contradictory_terminal_commits_marker_and_preserves_original_success(self):
        invocation = self.store.begin_activity(self.envelope)
        self.store.record_activity(self.envelope, self.result, invocation)
        self.store.record_activity(self.envelope, self.result, invocation)
        before = self.store.recovery(self.ref)
        for _ in range(2):
            with self.assertRaisesRegex(WorkflowError, "WORKFLOW_TRACE_CONFLICT"):
                self.store.record_failure(self.envelope, safe_failure("PAYMENT_COMMAND_REJECTED", "CORE_CALL"), invocation)
        self.assertEqual(1, len(self.events("SUCCEEDED")))
        self.assertEqual(1, len(self.events("CONFLICT")))
        self.assertEqual([], self.events("FAILED"))
        self.assertEqual(before, self.store.recovery(self.ref))
        self.assertEqual("MISMATCH", replay_trace(self.snapshot())["verdict"])

    def test_distinct_nonread_successes_conflict_in_writer_and_independent_verifier(self):
        first = self.store.begin_activity(self.envelope)
        self.store.record_activity(self.envelope, self.result, first)
        second = self.store.begin_activity(self.envelope)
        with self.assertRaisesRegex(WorkflowError, "WORKFLOW_TRACE_CONFLICT"):
            self.store.record_activity(self.envelope, {"decision": "wait"}, second)
        self.assertEqual("MISMATCH", replay_trace(self.snapshot())["verdict"])
        # Construct internally consistent checksums that still violate retry semantics.
        snapshot = self.snapshot()
        snapshot["events"] = [item for item in snapshot["events"] if item["kind"] != "CONFLICT"]
        for index, event in enumerate(snapshot["events"], 1):
            event["ordinal"] = index
            if event["kind"] == "FAILED":
                event.update(kind="SUCCEEDED", payload={"decision": "wait"})
        snapshot["head"] = {"eventCount": len(snapshot["events"]), "digest": digest({k: v for k, v in snapshot.items() if k != "head"})}
        self.assertEqual("MISMATCH", replay_trace(snapshot)["verdict"])

    def test_read_retries_retain_both_versions_and_recovery_does_not_regress(self):
        intent = str(uuid4())
        envelope = activity_envelope(self.start, 0, "read_status", {"intentId": intent})
        low = {"intentId": intent, "state": "QUOTED", "version": 2, "profile": self.start.profile,
               "money": None, "attemptId": None, "operationId": None, "actionId": None,
               "outcomeEvidenceId": None, "exposureState": "NONE", "reconciliationRequired": False}
        high = {**low, "version": 3}
        late, latest = self.store.begin_activity(envelope), self.store.begin_activity(envelope)
        self.store.record_activity(envelope, high, latest)
        self.store.record_activity(envelope, low, late)
        self.assertEqual(3, self.store.recovery(self.ref)["state"]["version"])
        self.assertEqual([3, 2], [item["payload"]["version"] for item in self.events("SUCCEEDED")])
        self.assertEqual("VERIFIED_RECORDED_STEPS", replay_trace(self.snapshot())["verdict"])

    def test_concurrent_connections_allocate_distinct_attempts_and_identical_writes_converge(self):
        other = SqliteRegistrationStore(self.path)
        self.addCleanup(other.close)
        with ThreadPoolExecutor(max_workers=2) as pool:
            refs = list(pool.map(lambda writer: writer.begin_activity(self.envelope), (self.store, other)))
            list(pool.map(lambda pair: pair[0].record_activity(self.envelope, self.result, pair[1]), zip((self.store, other), refs)))
        self.assertEqual({1, 2}, {item.ordinal for item in refs})
        self.store.record_activity(self.envelope, self.result, refs[0])
        self.assertEqual(2, len(self.events("SUCCEEDED")))

    def test_limit_reserves_terminal_and_conflict_slots_and_debug_still_works(self):
        refs = [self.store.begin_activity(self.envelope) for _ in range(MAX_INVOCATIONS)]
        with self.assertRaisesRegex(WorkflowError, "WORKFLOW_TRACE_LIMIT"):
            self.store.begin_activity(self.envelope)
        self.store.record_activity(self.envelope, self.result, refs[-1])
        with self.assertRaisesRegex(WorkflowError, "WORKFLOW_TRACE_CONFLICT"):
            self.store.record_failure(self.envelope, safe_failure("PAYMENT_COMMAND_REJECTED", "CORE_CALL"), refs[-1])
        self.assertEqual(MAX_INVOCATIONS, debug_trace(self.snapshot())["invocations"])
        self.assertEqual(1, len(self.events("CONFLICT")))

    def test_declared_writer_and_input_validation_are_required_before_new_work(self):
        class WrongWriter(Forwarder):
            def describe_trace(self):
                return replace(TraceWriterDescriptor(), guarantees=frozenset())
            def reserve(self, _):
                raise AssertionError("Unsupported writer was used")
        bad = WrongWriter(self.store)
        with self.assertRaisesRegex(WorkflowError, "UNSUPPORTED_TRACE_WRITER"):
            require_trace_writer(bad)
        with self.assertRaisesRegex(WorkflowError, "UNSUPPORTED_TRACE_WRITER"):
            SqliteWorkflowRuntime(bad, self.activities(registration=bad)).describe()
        invalid = replace(self.start, workflow_id=str(uuid4()), activity_limit=0)
        before = self.store._db.execute("SELECT COUNT(*) FROM workflow_registrations").fetchone()[0]
        with self.assertRaises(WorkflowError):
            self.store.reserve(invalid)
        self.assertEqual(before, self.store._db.execute("SELECT COUNT(*) FROM workflow_registrations").fetchone()[0])

    def test_old_registration_is_positive_history_not_new_store_fallback(self):
        old = replace(self.start, workflow_id=str(uuid4()))
        self.store._db.execute("INSERT INTO workflow_registrations(id,fingerprint,start,attempted) VALUES(?,?,?,1)",
                               (old.reference().runtime_id, old.fingerprint(), encoded(old.data())))
        self.store._db.commit()
        with SqliteWorkflowTraceReader(self.path) as reader:
            report = replay_trace(reader.read_trace(old.reference()))
        self.assertEqual("INCOMPLETE", report["verdict"])
        self.assertEqual("HISTORICAL_TRACE_UNAVAILABLE", report["coverage"])
        invoke_recorded(self.activities(), activity_envelope(old, 0, "decide", {}))
        self.assertEqual({"coverage": "HISTORICAL_TRACE_UNAVAILABLE"}, self.store.trace_policy(old.reference()))
        with self.assertRaisesRegex(WorkflowError, "WORKFLOW_NOT_REGISTERED"):
            self.store.trace_policy(replace(old, workflow_id=str(uuid4())).reference())

    def test_missing_records_unknown_versions_and_failed_success_replay_have_distinct_verdicts(self):
        invoke_recorded(self.activities(), self.envelope)
        snapshot = self.snapshot()
        missing = {**snapshot, "events": snapshot["events"][:-1]}
        self.assertEqual("INCOMPLETE", replay_trace(missing)["verdict"])
        self.assertEqual("UNSUPPORTED_VERSION", replay_trace({**snapshot, "contract": "future"})["verdict"])
        mismatch = replay_trace(snapshot, {self.start.decision_version: lambda _: "wait"})
        self.assertEqual("MISMATCH", mismatch["verdict"])
        self.assertEqual({"activityId": self.envelope["activityId"], "invocation": 1}, mismatch["location"])
        def unexpected(_):
            raise RuntimeError("PRIVATE_REPLAY_CANARY")
        result = replay_trace(snapshot, {self.start.decision_version: unexpected})
        self.assertEqual(("MISMATCH", "DECISION_REPLAY_FAILED"), (result["verdict"], result["code"]))
        self.assertNotIn("PRIVATE_REPLAY_CANARY", encoded(result))
        truncated = Path(self.temp.name) / "trace.json"
        truncated.write_text('{"contract":', encoding="utf-8")
        with self.assertRaisesRegex(WorkflowError, "WORKFLOW_TRACE_INCOMPLETE"):
            load_trace(truncated)

    def test_activity_without_begin_is_incomplete_and_debug_does_not_claim_decision_replay(self):
        invoke_recorded(self.activities(), self.envelope)
        snapshot = self.snapshot()
        self.assertFalse(debug_trace(snapshot)["decisionsReplayed"])
        self.assertTrue(replay_trace(snapshot)["decisionsReplayed"])
        snapshot["events"] = snapshot["events"][:1]
        snapshot["head"] = {"eventCount": 1, "digest": digest({k: v for k, v in snapshot.items() if k != "head"})}
        self.assertEqual("INCOMPLETE", replay_trace(snapshot)["verdict"])

    def test_hostile_exception_properties_never_escape_classifier_or_wrapper(self):
        canary = "PRIVATE_EXCEPTION_PROPERTY_CANARY"
        class BadCode(WorkflowRejectedError):
            @property
            def code(self):
                raise RuntimeError(canary)
        class BadArgs(WorkflowError):
            def __getattribute__(self, name):
                if name == "args":
                    raise RuntimeError(canary)
                return super().__getattribute__(name)
        class BadFailure(TraceActivityError):
            def __getattribute__(self, name):
                if name == "failure" and object.__getattribute__(self, "__dict__").get("_armed", False):
                    raise RuntimeError(canary)
                return super().__getattribute__(name)
        class BadDiagnostic(TraceActivityError):
            def diagnostic(self):
                raise RuntimeError(canary)
        errors = (BadCode(canary), BadArgs(canary), BadFailure(safe_failure("PAYMENT_COMMAND_REJECTED", "CORE_CALL")),
                  BadDiagnostic(safe_failure("PAYMENT_COMMAND_REJECTED", "CORE_CALL")))
        errors[2]._armed = True
        for index, error in enumerate(errors):
            self.assertNotIn(canary, encoded(classify_failure(error, "CORE_CALL")))
            self.assertNotIn(canary, encoded(safe_diagnostic(error)))
            def rejected(_, failure=error):
                raise failure
            with self.assertRaises(TraceActivityError) as caught:
                invoke_recorded(self.activities(rejected), activity_envelope(self.start, index, "decide", {}))
            self.assertNotIn(canary, encoded(caught.exception.diagnostic()))
        self.assertNotIn(canary, encoded(self.snapshot()))

    def test_temporal_boundary_does_not_call_throwing_diagnostic(self):
        import asyncio
        from temporalio.exceptions import ApplicationError
        from workflows.temporal_runtime import registered_activity
        class BadDiagnostic(TraceActivityError):
            def diagnostic(self):
                raise RuntimeError("PRIVATE_DIAGNOSTIC_CANARY")
        failure = BadDiagnostic(safe_failure("PAYMENT_COMMAND_REJECTED", "CORE_CALL"))
        wrapped = registered_activity(self.activities())
        with patch("workflows.temporal_runtime.invoke_recorded", side_effect=failure):
            with self.assertRaises(ApplicationError) as caught:
                asyncio.run(wrapped(self.envelope))
        self.assertTrue(caught.exception.non_retryable)
        self.assertNotIn("PRIVATE_DIAGNOSTIC_CANARY", str(caught.exception))
        self.assertNotIn("PRIVATE_DIAGNOSTIC_CANARY", encoded(caught.exception.details))
        self.assertIsNone(caught.exception.__cause__)

    def test_reader_classifies_invalid_result_as_invalid_trace_not_availability(self):
        invoke_recorded(self.activities(), self.envelope)
        self.store._db.execute("UPDATE workflow_trace_events SET payload=? WHERE kind='SUCCEEDED'", (encoded({"decision": "bogus"}),))
        self.store._db.commit()
        with SqliteWorkflowTraceReader(self.path) as reader:
            with self.assertRaisesRegex(WorkflowError, "WORKFLOW_TRACE_INVALID"):
                reader.read_trace(self.ref)

    def test_limits_require_exact_integer_types_in_export_and_writer_policy(self):
        invoke_recorded(self.activities(), self.envelope)
        snapshot = self.snapshot()
        snapshot["limits"]["maxActivities"] = 512.0
        snapshot["head"]["digest"] = digest({k: v for k, v in snapshot.items() if k != "head"})
        self.assertEqual("MISMATCH", replay_trace(snapshot)["verdict"])
        policy = self.store.trace_policy(self.ref)
        policy["limits"]["maxActivities"] = 512.0
        self.store._db.execute("UPDATE workflow_trace_headers SET policy=? WHERE workflow_id=?", (encoded(policy), self.ref.runtime_id))
        self.store._db.commit()
        with SqliteWorkflowTraceReader(self.path) as reader:
            with self.assertRaisesRegex(WorkflowError, "WORKFLOW_TRACE_INVALID"):
                reader.read_trace(self.ref)
        with self.assertRaisesRegex(WorkflowError, "UNSUPPORTED_TRACE_WRITER"):
            self.store.begin_activity(self.envelope)


if __name__ == "__main__":
    unittest.main()
