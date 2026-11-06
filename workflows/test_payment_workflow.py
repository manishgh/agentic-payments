from __future__ import annotations

from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest
from uuid import uuid4

from workflows.payment_workflow import (
    CONTRACT, PROFILE, LocalPaymentClient, PaymentWorkflow, WorkflowError,
)


class FakeCore:
    """Separate authoritative port; graph resume input never writes this state."""

    def __init__(self, run_id):
        self.run_id = run_id
        self.intent_id = str(uuid4())
        self.attempt_id = str(uuid4())
        self.operation_id = str(uuid4())
        self.action_id = str(uuid4())
        self.state = "DRAFT"
        self.version = 1
        self.commands = {}
        self.calls = []
        self.executions = []
        self.lose_execute_response = False
        self.create_count = 0
        self.canary = "SESSION_CREDENTIAL_ACTION_SECRET_CANARY_9e17"

    def create(self, key, scenario):
        self.calls.append(("create", key))
        if key not in self.commands:
            self.create_count += 1
            self.commands[key] = {"intentId": self.intent_id, "version": 1}
        return {**self.commands[key], "secret": self.canary}

    def quote(self, intent_id, key, version):
        self.calls.append(("quote", key))
        if key not in self.commands:
            assert version == 1
            self.state = "QUOTED"
            self.version = 2
            self.commands[key] = {"intentId": self.intent_id, "checkoutRevision": 1,
                                  "checkoutDigest": "sha256:" + "a" * 64,
                                  "money": {"currency": "EUR", "minorUnits": "1250"}}
        return {**self.commands[key], "credentialReference": self.canary}

    def approve_as_trusted_subject(self):
        assert self.state == "QUOTED"
        self.state = "APPROVED"
        self.version = 3

    def status(self, intent_id):
        assert intent_id == self.intent_id
        self.calls.append(("status", intent_id))
        approved = self.state not in ("DRAFT", "QUOTED")
        executing = self.state not in ("DRAFT", "QUOTED", "APPROVED")
        return {"contractVersion": CONTRACT, "intentId": intent_id, "state": self.state,
                "version": self.version, "profile": PROFILE, "runId": self.run_id,
                "attemptId": self.attempt_id if approved else None,
                "operationId": self.operation_id if executing else None,
                "money": {"currency": "EUR", "minorUnits": "1250"},
                "exposureState": "CONSUMED" if self.state == "SUCCEEDED" else "HELD" if approved else "NONE",
                "reconciliationRequired": self.state == "UNKNOWN",
                "actionId": self.action_id if self.state == "WAITING_ACTION" else None,
                "outcomeEvidenceId": str(uuid4()) if self.state == "SUCCEEDED" else None,
                "credentialReference": self.canary, "actionPayload": self.canary}

    def execute(self, intent_id, key, version, attempt_id):
        self.calls.append(("execute", key))
        if key not in self.commands:
            assert self.state == "APPROVED"
            assert version == 3
            assert attempt_id == self.attempt_id
            self.executions.append((intent_id, key, attempt_id, self.operation_id))
            self.state = "PREPARING"
            self.version = 4
            self.commands[key] = {"state": "PREPARING", "operationId": self.operation_id}
        if self.lose_execute_response:
            self.lose_execute_response = False
            raise WorkflowError("Local API unavailable. Resume this workflow identity; do not start another payment.")
        return self.commands[key]


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.test_root = Path(__file__).resolve().parents[1] / ".tools" / "workflow-tests"
        self.test_root.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=self.test_root)
        self.addCleanup(self.cleanup_directory)
        self.database = Path(self.directory.name) / "graph.sqlite"
        self.run_id = str(uuid4())
        self.workflow_id = str(uuid4())
        self.core = FakeCore(self.run_id)

    def cleanup_directory(self):
        target = Path(self.directory.name).resolve()
        self.assertTrue(target.is_relative_to(self.test_root.resolve()))
        self.assertNotEqual(target, self.test_root.resolve())
        self.directory.cleanup()

    def workflow(self, run_id=None, workflow_id=None):
        return PaymentWorkflow(self.core, self.database, run_id or self.run_id, workflow_id or self.workflow_id)

    def all_checkpoint_bytes(self):
        with closing(sqlite3.connect(self.database)) as connection:
            return b"\n".join(str(row).encode() for table in ("checkpoints", "writes")
                              for row in connection.execute(f"SELECT * FROM {table}"))

    def test_sqlite_restart_and_spoofed_resume_cannot_approve(self):
        with self.workflow() as graph:
            first = graph.start("challenge-then-success")
            self.assertEqual("QUOTED", first["state"])
            self.assertTrue(first["paused"])
        with self.workflow() as restarted:
            result = restarted.resume({"approved": True, "state": "APPROVED", "token": self.core.canary})
            self.assertEqual("QUOTED", result["state"])
            self.assertEqual([], self.core.executions)
            self.assertNotIn(self.core.canary.encode(), self.all_checkpoint_bytes())
            self.core.approve_as_trusted_subject()
            result = restarted.resume({"approved": False})
            self.assertEqual("PREPARING", result["state"])
            self.assertEqual(1, len(self.core.executions))

    def test_unknown_repeated_resume_never_creates_or_executes_again(self):
        with self.workflow() as graph:
            graph.start()
            self.core.approve_as_trusted_subject()
            graph.resume()
        self.core.state = "UNKNOWN"
        self.core.version = 8
        with self.workflow() as restarted:
            for _ in range(3):
                status = restarted.resume({"retry": "new-provider", "approved": True})
                self.assertEqual("UNKNOWN", status["state"])
                self.assertEqual(self.core.operation_id, status["operationId"])
                self.assertEqual("HELD", status["exposureState"])
            self.assertEqual(1, self.core.create_count)
            self.assertEqual(1, len(self.core.executions))
            self.core.state = "SUCCEEDED"
            self.core.version = 9
            final = restarted.resume()
            self.assertEqual("SUCCEEDED", final["state"])
            self.assertFalse(final["paused"])

    def test_lost_execute_response_restarts_without_new_operation(self):
        with self.workflow() as graph:
            graph.start()
            self.core.approve_as_trusted_subject()
            self.core.lose_execute_response = True
            with self.assertRaises(WorkflowError):
                graph.resume()
        stable_key = self.core.executions[0][1]
        with self.workflow() as restarted:
            result = restarted.resume()
            self.assertEqual("PREPARING", result["state"])
            self.assertEqual(1, len(self.core.executions))
            self.assertEqual(stable_key, self.core.executions[0][1])
            self.assertNotIn(self.core.canary.encode(), self.all_checkpoint_bytes())

    def test_lost_quote_response_replay_reads_current_approval(self):
        actual_quote = self.core.quote
        lose_once = True

        def quote_with_lost_response(intent, key, version):
            nonlocal lose_once
            response = actual_quote(intent, key, version)
            if lose_once:
                lose_once = False
                raise WorkflowError("Command was not confirmed.")
            return response

        self.core.quote = quote_with_lost_response
        with self.workflow() as graph:
            with self.assertRaises(WorkflowError):
                graph.start()
        self.core.approve_as_trusted_subject()
        with self.workflow() as restarted:
            result = restarted.resume()
            self.assertEqual("PREPARING", result["state"])
            self.assertEqual(1, len(self.core.executions))
        quote_keys = [key for command, key in self.core.calls if command == "quote"]
        self.assertEqual(2, len(quote_keys))
        self.assertEqual(quote_keys[0], quote_keys[1])

    def test_failed_before_execute_commit_retries_identical_key(self):
        actual_execute = self.core.execute
        observed_keys = []

        def lose_before_acceptance(intent, key, version, attempt):
            observed_keys.append(key)
            if len(observed_keys) == 1:
                raise WorkflowError("Command was not confirmed.")
            return actual_execute(intent, key, version, attempt)

        self.core.execute = lose_before_acceptance
        with self.workflow() as graph:
            graph.start()
            self.core.approve_as_trusted_subject()
            with self.assertRaises(WorkflowError):
                graph.resume()
        with self.workflow() as restarted:
            restarted.resume()
        self.assertEqual(2, len(observed_keys))
        self.assertEqual(observed_keys[0], observed_keys[1])
        self.assertEqual(1, len(self.core.executions))

    def test_action_state_only_interrupts_and_has_no_completion_port(self):
        with self.workflow() as graph:
            graph.start("challenge-then-success")
            self.core.approve_as_trusted_subject()
            graph.resume()
            self.core.state = "WAITING_ACTION"
            self.core.version = 5
            status = graph.resume({"decision": "approve", "actionPayload": self.core.canary})
            self.assertEqual("WAITING_ACTION", status["state"])
            self.assertEqual(self.core.action_id, status["actionId"])
            self.assertTrue(status["paused"])
            self.assertFalse(hasattr(LocalPaymentClient, "approve"))
            self.assertFalse(hasattr(LocalPaymentClient, "complete_action"))
            self.assertNotIn(self.core.canary.encode(), self.all_checkpoint_bytes())

    def test_unknown_cannot_switch_authoritative_execution_identity(self):
        with self.workflow() as graph:
            graph.start()
            self.core.approve_as_trusted_subject()
            graph.resume()
            self.core.state = "UNKNOWN"
            self.core.version = 8
            graph.resume()
            self.core.operation_id = str(uuid4())
            with self.assertRaises(WorkflowError):
                graph.resume()
            self.assertEqual(1, len(self.core.executions))

    def test_different_run_cannot_resume_checkpoint(self):
        with self.workflow() as graph:
            graph.start()
            with self.assertRaises(WorkflowError):
                graph.start()
        with self.workflow(run_id=str(uuid4())) as other_run:
            with self.assertRaises(WorkflowError):
                other_run.resume()

    def test_http_client_rejects_remote_urls_and_credential_injection(self):
        for url in ("https://example.com", "http://localhost:8080", "http://127.0.0.1:8080@evil.test",
                    "http://127.0.0.1:8080/callback", "http://127.0.0.1:8080?token=secret"):
            with self.subTest(url=url), self.assertRaises(WorkflowError):
                LocalPaymentClient(url, "x" * 40, self.run_id)
        with self.assertRaises(WorkflowError):
            LocalPaymentClient("http://127.0.0.1:8080", "x" * 40 + "\r\nInjected: header", self.run_id)

    def test_cross_scope_authoritative_response_fails_closed(self):
        with self.workflow() as graph:
            graph.start()
            self.core.run_id = str(uuid4())
            with self.assertRaises(WorkflowError):
                graph.resume()
            self.assertEqual([], self.core.executions)


if __name__ == "__main__":
    unittest.main()
