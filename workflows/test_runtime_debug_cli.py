"""Read-only CLI diagnostics and offline replay checks using actual subprocess boundaries."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sqlite3
import sys
import tempfile
import unittest
from uuid import uuid4
from contextlib import closing, redirect_stdout
import io
from unittest.mock import AsyncMock, patch

from workflows import runtime_cli
from workflows.runtime_contract import StartWorkflow, activity_envelope
from workflows.runtime_registry import SqliteRegistrationStore
from workflows.runtime_trace import TraceActivityError, safe_failure


ROOT = Path(__file__).resolve().parents[1]
SENTINELS = """
import sys
from workflows import runtime_cli
from temporalio.client import Client
from temporalio import worker
import socket
import urllib.request

def forbidden(*args, **kwargs):
    raise AssertionError('FORBIDDEN_LIVE_CONSTRUCTOR_CANARY')

runtime_cli.LocalPaymentClient = forbidden
runtime_cli.PaymentActivities = forbidden
runtime_cli.SqliteRegistrationStore = forbidden
runtime_cli.SqliteWorkflowRuntime = forbidden
runtime_cli.local = forbidden
runtime_cli.temporal = forbidden
Client.connect = forbidden
worker.Worker = forbidden
socket.create_connection = forbidden
urllib.request.OpenerDirector.open = forbidden
sys.argv = ['runtime_cli', *sys.argv[1:]]
raise SystemExit(runtime_cli.main())
"""


class RuntimeDebugCliTests(unittest.TestCase):
    def setUp(self):
        test_root = ROOT / ".tools/runtime-debug-cli-tests"
        test_root.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=test_root)
        self.addCleanup(self.directory.cleanup)
        self.folder = Path(self.directory.name)
        self.registry_file = self.folder / "registry.sqlite"
        self.trace_file = self.folder / "trace.json"
        self.start = StartWorkflow("tenant-local", "agent-demo", str(uuid4()), str(uuid4()))

    def record_decision(self, complete=True):
        store = SqliteRegistrationStore(self.registry_file)
        try:
            store.reserve(self.start)
            store.claim_attempt(self.start.reference())
            envelope = activity_envelope(self.start, 0, "decide", {})
            invocation = store.begin_activity(envelope)
            if complete:
                store.record_activity(envelope, {"decision": "create_intent"}, invocation)
        finally:
            store.close()

    def command(self, command, *options, sentinels=False):
        environment = dict(os.environ)
        for key in ("PAYMENTS_GRAPH_TOKEN", "PAYMENTS_UI_TOKEN", "PAYMENTS_RUN_ID", "TEMPORAL_ADDRESS"):
            environment.pop(key, None)
        invocation = [sys.executable, "-c", SENTINELS] if sentinels else [sys.executable, "-m", "workflows.runtime_cli"]
        result = subprocess.run([*invocation, command, *options], cwd=ROOT, env=environment,
                                capture_output=True, text=True, timeout=30, check=False)
        self.assertNotIn("FORBIDDEN_LIVE_CONSTRUCTOR_CANARY", result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stdout + result.stderr)
        self.assertNotIn("PRIVATE_PATH_CANARY", result.stdout + result.stderr)
        try:
            report = json.loads(result.stdout)
        except (ValueError, TypeError):
            self.fail(f"Diagnostic command did not emit its JSON contract (exit {result.returncode}).")
        self.assertIs(report["authoritativePaymentState"], False)
        self.assertIs(report["schedulerConsumptionVerified"], False)
        return result.returncode, report

    def debug_options(self, **changes):
        options = {"--registry": str(self.registry_file), "--run-id": self.start.run_id,
                   "--workflow-id": self.start.workflow_id, "--runtime": self.start.runtime,
                   # Invalid live endpoints are deliberately irrelevant to offline diagnostics.
                   "--api-url": "http://127.0.0.1:1", "--server": "offline.invalid"}
        options.update(changes)
        return [part for pair in options.items() for part in pair]

    def registry_digest(self):
        return hashlib.sha256(self.registry_file.read_bytes()).hexdigest()

    def test_debug_export_and_replay_work_offline_with_live_constructors_forbidden(self):
        self.record_decision()
        before = self.registry_digest()
        status, debug = self.command("debug", *self.debug_options(), "--export", str(self.trace_file), sentinels=True)
        self.assertEqual(0, status)
        self.assertEqual("VERIFIED_RECORDED_STEPS", debug["verdict"])
        self.assertIs(debug["exported"], True)
        self.assertIn("logicalSteps", debug)
        self.assertIn("invocations", debug)
        self.assertIn("failures", debug)
        self.assertIn("unresolved", debug)
        self.assertEqual(before, self.registry_digest())
        absent_registry = self.folder / "never-created.sqlite"
        status, replay = self.command("replay", "--trace", str(self.trace_file),
                                      "--registry", str(absent_registry), sentinels=True)
        self.assertEqual(0, status)
        self.assertEqual("VERIFIED_RECORDED_STEPS", replay["verdict"])
        self.assertFalse(absent_registry.exists())
        self.assertEqual(before, self.registry_digest())

    def test_unresolved_invocation_is_visible_and_cannot_receive_complete_replay_verdict(self):
        self.record_decision(complete=False)
        status, debug = self.command("debug", *self.debug_options(), "--export", str(self.trace_file))
        self.assertEqual(0, status)
        self.assertEqual("INCOMPLETE", debug["verdict"])
        self.assertTrue(debug["unresolved"])
        status, replay = self.command("replay", "--trace", str(self.trace_file), sentinels=True)
        self.assertEqual(2, status)
        self.assertEqual("INCOMPLETE", replay["verdict"])
        self.assertTrue(replay["unresolved"])

    def test_existing_export_is_preserved_and_export_path_failures_are_sanitized(self):
        self.record_decision()
        self.trace_file.write_bytes(b"existing-local-artifact")
        status, report = self.command("debug", *self.debug_options(), "--export", str(self.trace_file))
        self.assertEqual(2, status)
        self.assertEqual("WORKFLOW_TRACE_EXPORT_EXISTS", report["failure"]["code"])
        self.assertEqual(b"existing-local-artifact", self.trace_file.read_bytes())
        missing_parent = self.folder / "PRIVATE_PATH_CANARY" / "trace.json"
        status, report = self.command("debug", *self.debug_options(), "--export", str(missing_parent))
        self.assertEqual(2, status)
        self.assertEqual("WORKFLOW_TRACE_EXPORT_UNAVAILABLE", report["failure"]["code"])
        self.assertFalse(missing_parent.parent.exists())

    def test_missing_registry_or_trace_never_creates_storage_and_never_outputs_paths(self):
        missing = self.folder / "PRIVATE_PATH_CANARY" / "absent.sqlite"
        status, report = self.command("debug", *self.debug_options(**{"--registry": str(missing)}), sentinels=True)
        self.assertEqual(2, status)
        self.assertEqual("INCOMPLETE", report["verdict"])
        self.assertFalse(missing.parent.exists())
        status, report = self.command("replay", "--trace", str(missing), sentinels=True)
        self.assertEqual(2, status)
        self.assertEqual("INCOMPLETE", report["verdict"])
        self.assertFalse(missing.parent.exists())

    def test_invalid_and_truncated_json_are_safe_incomplete_snapshots(self):
        for payload in (b'{"private":"RAW_EXCEPTION_CANARY",', b"not-json-RAW_EXCEPTION_CANARY"):
            with self.subTest(length=len(payload)):
                self.trace_file.write_bytes(payload)
                status, report = self.command("replay", "--trace", str(self.trace_file), sentinels=True)
                self.assertEqual(2, status)
                self.assertEqual("INCOMPLETE", report["verdict"])
                self.assertNotIn("RAW_EXCEPTION_CANARY", json.dumps(report))

    def test_scope_mismatch_is_denied_without_modifying_the_registration(self):
        self.record_decision()
        before = self.registry_digest()
        status, report = self.command("debug", *self.debug_options(**{"--run-id": str(uuid4())}), sentinels=True)
        self.assertEqual(2, status)
        self.assertEqual("INCOMPLETE", report["verdict"])
        self.assertEqual(before, self.registry_digest())

    def test_recorded_core_failure_is_displayed_without_reexecuting_the_effect(self):
        store = SqliteRegistrationStore(self.registry_file)
        try:
            store.reserve(self.start)
            store.claim_attempt(self.start.reference())
            envelope = activity_envelope(self.start, 0, "create_intent", {})
            invocation = store.begin_activity(envelope)
            store.record_failure(envelope, safe_failure("PAYMENT_COMMAND_REJECTED", "CORE_CALL"), invocation)
        finally:
            store.close()
        status, report = self.command("debug", *self.debug_options(), "--export", str(self.trace_file), sentinels=True)
        self.assertEqual(0, status)
        self.assertEqual("VERIFIED_WITH_RECORDED_FAILURE", report["verdict"])
        self.assertEqual(1, report["failures"])
        self.assertIn("PAYMENT_COMMAND_REJECTED", json.dumps(report["steps"]))
        status, report = self.command("replay", "--trace", str(self.trace_file), sentinels=True)
        self.assertEqual(0, status)
        self.assertEqual("VERIFIED_WITH_RECORDED_FAILURE", report["verdict"])
        self.assertIn("CORE_CALL", json.dumps(report["steps"]))

    def test_unknown_journal_version_is_not_replayed_using_a_current_default(self):
        self.record_decision()
        status, _ = self.command("debug", *self.debug_options(), "--export", str(self.trace_file))
        self.assertEqual(0, status)
        trace = json.loads(self.trace_file.read_text(encoding="utf-8"))
        trace["contract"] = "unsupported-journal-version"
        self.trace_file.write_text(json.dumps(trace), encoding="utf-8")
        before = self.registry_digest()
        status, report = self.command("replay", "--trace", str(self.trace_file), sentinels=True)
        self.assertEqual(2, status)
        self.assertEqual("UNSUPPORTED_VERSION", report["verdict"])
        self.assertEqual(before, self.registry_digest())

    def test_unsupported_stored_journal_metadata_remains_unsupported_and_is_not_exported(self):
        self.record_decision()
        database = sqlite3.connect(self.registry_file)
        try:
            policy = json.loads(database.execute("SELECT policy FROM workflow_trace_headers").fetchone()[0])
            policy["contract"] = "unsupported-journal-version"
            database.execute("UPDATE workflow_trace_headers SET policy=?", (json.dumps(policy),))
            database.commit()
        finally:
            database.close()
        before = self.registry_digest()
        status, report = self.command("debug", *self.debug_options(), "--export", str(self.trace_file), sentinels=True)
        self.assertEqual(2, status)
        self.assertEqual("UNSUPPORTED_VERSION", report["verdict"])
        self.assertEqual("WORKFLOW_TRACE_UNSUPPORTED_VERSION", report["failure"]["code"])
        self.assertIs(report["decisionsReplayed"], False)
        self.assertFalse(self.trace_file.exists())
        self.assertEqual(before, self.registry_digest())

    def test_corrupt_known_stored_records_remain_mismatched_and_do_not_expose_rejected_payload(self):
        self.record_decision()
        database = sqlite3.connect(self.registry_file)
        try:
            database.execute("UPDATE workflow_trace_events SET payload=? WHERE kind='SUCCEEDED'",
                             (json.dumps({"private": "RAW_RECORD_CANARY"}),))
            database.commit()
        finally:
            database.close()
        before = self.registry_digest()
        status, report = self.command("debug", *self.debug_options(), "--export", str(self.trace_file), sentinels=True)
        self.assertEqual(2, status)
        self.assertEqual("MISMATCH", report["verdict"])
        self.assertEqual("WORKFLOW_TRACE_INVALID", report["failure"]["code"])
        self.assertNotIn("RAW_RECORD_CANARY", json.dumps(report))
        self.assertFalse(self.trace_file.exists())
        self.assertEqual(before, self.registry_digest())

    def test_historical_registration_is_explicitly_incomplete_without_creating_journal_schema(self):
        with closing(sqlite3.connect(self.registry_file)) as database:
            database.execute("CREATE TABLE workflow_registrations (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, start TEXT NOT NULL, attempted INTEGER NOT NULL DEFAULT 0, confirmed INTEGER NOT NULL DEFAULT 0, progress TEXT NOT NULL DEFAULT '{}', pause_requested INTEGER NOT NULL DEFAULT 0)")
            database.execute("INSERT INTO workflow_registrations(id,fingerprint,start) VALUES(?,?,?)",
                             (self.start.reference().runtime_id, self.start.fingerprint(), json.dumps(self.start.data())))
            database.commit()
        before = self.registry_digest()
        status, report = self.command("debug", *self.debug_options(), sentinels=True)
        self.assertEqual(0, status)
        self.assertEqual("INCOMPLETE", report["verdict"])
        self.assertEqual("HISTORICAL_TRACE_UNAVAILABLE", report["coverage"])
        self.assertEqual(before, self.registry_digest())

    def test_live_failure_output_preserves_primary_refusal_and_secondary_journal_uncertainty(self):
        error = TraceActivityError(safe_failure("PAYMENT_COMMAND_REJECTED", "CORE_CALL"), "UNCONFIRMED")
        argv = ["runtime_cli", "start", "--run-id", self.start.run_id, "--workflow-id", self.start.workflow_id,
                "--registry", str(self.registry_file)]
        with (patch("sys.argv", argv), redirect_stdout(io.StringIO()) as output,
              patch.object(runtime_cli, "SqliteRegistrationStore"),
              patch.object(runtime_cli, "local", new_callable=AsyncMock) as dispatch):
            dispatch.side_effect = error
            self.assertEqual(2, runtime_cli.main())
        report = json.loads(output.getvalue())
        self.assertEqual(safe_failure("PAYMENT_COMMAND_REJECTED", "CORE_CALL"), report["failure"])
        self.assertEqual(safe_failure("WORKFLOW_TRACE_UNCONFIRMED", "JOURNAL"), report["secondary"])
        self.assertEqual("UNCONFIRMED", report["journalStatus"])

    def test_malformed_typed_failure_never_exports_an_arbitrary_message(self):
        error = TraceActivityError(safe_failure("PAYMENT_COMMAND_REJECTED", "CORE_CALL"))
        error.failure["code"] = "PRIVATE_CAUSE_CANARY"
        report = runtime_cli.live_failure(error)
        self.assertEqual(safe_failure("UNEXPECTED_ACTIVITY_FAILURE", "JOURNAL"), report["failure"])
        self.assertNotIn("PRIVATE_CAUSE_CANARY", json.dumps(report))


if __name__ == "__main__":
    unittest.main()
