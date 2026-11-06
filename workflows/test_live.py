"""Actual Java/PostgreSQL/LangGraph integration, with separate trusted caller."""
from __future__ import annotations

from contextlib import closing
import json
import os
from pathlib import Path
import secrets
import sqlite3
import subprocess
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


class LiveWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for name in ("PAYMENTS_DB_URL", "PAYMENTS_DB_USER", "PAYMENTS_DB_PASSWORD", "JAVA_HOME"):
            if not os.environ.get(name):
                raise RuntimeError("Local PostgreSQL and Java configuration is required.")
        cls.run_id = str(uuid4())
        cls.graph_token = secrets.token_hex(32)
        cls.ui_token = secrets.token_hex(32)
        cls.environment = dict(os.environ, PAYMENTS_GRAPH_TOKEN=cls.graph_token,
                               PAYMENTS_UI_TOKEN=cls.ui_token, PAYMENTS_RUN_ID=cls.run_id, PAYMENTS_PORT="0")
        classpath = str(ROOT / "target/classes") + os.pathsep + (ROOT / "target/classpath.txt").read_text().strip()
        java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        cls.server = subprocess.Popen([str(java), "-cp", classpath, "io.agenticpayments.http.Main"],
                                      cwd=ROOT, env=cls.environment, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE, text=True)
        from concurrent.futures import ThreadPoolExecutor, TimeoutError
        with ThreadPoolExecutor(max_workers=1) as pool:
            readiness = pool.submit(cls.server.stdout.readline)
            try:
                line = readiness.result(timeout=20)
            except TimeoutError:
                cls.server.kill()
                raise RuntimeError("Local API did not start within its test deadline.") from None
        if "http://127.0.0.1:" not in line:
            cls.server.kill()
            raise RuntimeError("Local API startup failed.")
        cls.base = line.strip().split(" at ", 1)[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.terminate()
        try:
            cls.server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            cls.server.kill()
            cls.server.wait(timeout=5)
        cls.server.stdout.close()
        cls.server.stderr.close()

    def workflow_process(self, command, workflow_id, scenario="success"):
        import sys
        result = subprocess.run([
            sys.executable, "-m", "workflows.payment_workflow", command,
            "--workflow-id", workflow_id, "--run-id", self.run_id,
            "--api-url", self.base, "--scenario", scenario,
        ], cwd=ROOT, env=self.environment, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stdout)
        return json.loads(result.stdout)

    def trusted_request(self, path, body=None, graph=False):
        token = self.graph_token if graph else self.ui_token
        headers = {"Authorization": "Bearer " + token}
        data = None
        if body is not None:
            headers.update({"Content-Type": "application/json", "Idempotency-Key": str(uuid4())})
            data = json.dumps(body).encode()
        request = Request(self.base + "/internal/v0.1" + path, data=data, headers=headers)
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except HTTPError as response:
            return response.code, json.load(response)

    def approve_independently(self, intent_id):
        status, review = self.trusted_request(f"/intents/{intent_id}/checkout")
        self.assertEqual(200, status)
        status, approved = self.trusted_request(f"/intents/{intent_id}/approve", {
            "expectedVersion": review["version"], "checkoutRevision": review["checkoutRevision"],
            "checkoutDigest": review["checkoutDigest"], "authorityReference": "fixture-grant-1",
        })
        self.assertEqual(200, status, approved)

    def resume_until(self, workflow_id, expected):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            state = self.workflow_process("resume", workflow_id)
            if state["state"] in expected:
                return state
            time.sleep(.05)
        self.fail("Workflow did not reach its bounded expected local state.")

    def assert_checkpoint_has_no_secrets(self):
        with closing(sqlite3.connect(ROOT / ".tools/workflow-checkpoints.sqlite")) as connection:
            raw = b"\n".join(str(row).encode() for table in ("checkpoints", "writes")
                             for row in connection.execute(f"SELECT * FROM {table}"))
        for secret in (self.graph_token, self.ui_token, "fixture-credential-1"):
            self.assertNotIn(secret.encode(), raw)

    def test_live_success_with_graph_process_restart(self):
        workflow_id = str(uuid4())
        quoted = self.workflow_process("start", workflow_id)
        self.assertEqual("QUOTED", quoted["state"])
        # Separate Python process reopens SQLite and still cannot approve.
        still_quoted = self.workflow_process("resume", workflow_id)
        self.assertEqual("QUOTED", still_quoted["state"])
        self.approve_independently(quoted["intentId"])
        final = self.resume_until(workflow_id, {"SUCCEEDED"})
        self.assertEqual("CONSUMED", final["exposureState"])
        self.assertIsNotNone(final["operationId"])
        self.assert_checkpoint_has_no_secrets()

    def test_live_trusted_challenge_is_separate_from_checkpoint(self):
        workflow_id = str(uuid4())
        quoted = self.workflow_process("start", workflow_id, "challenge-then-success")
        self.approve_independently(quoted["intentId"])
        waiting = self.resume_until(workflow_id, {"WAITING_ACTION"})
        operation_id = waiting["operationId"]
        action_path = f"/actions/{waiting['actionId']}"
        self.assertEqual(403, self.trusted_request(action_path, graph=True)[0])
        self.assertEqual(403, self.trusted_request(action_path + "/complete", {
            "expectedVersion": 1, "decision": "approve"}, graph=True)[0])
        self.assertEqual("WAITING_ACTION", self.workflow_process("resume", workflow_id)["state"])
        status, action = self.trusted_request(action_path)
        self.assertEqual(200, status)
        status, _ = self.trusted_request(action_path + "/complete", {
            "expectedVersion": action["version"], "decision": "approve"})
        self.assertEqual(200, status)
        final = self.resume_until(workflow_id, {"SUCCEEDED"})
        self.assertEqual(operation_id, final["operationId"])
        self.assertEqual("CONSUMED", final["exposureState"])
        self.assert_checkpoint_has_no_secrets()


if __name__ == "__main__":
    unittest.main()
