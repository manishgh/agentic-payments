"""Actual separate Python process, Java API, PostgreSQL and synthetic ACP merchant."""
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from uuid import uuid4

from integrations.checkout_demo import CANARY, CREDENTIAL, SyntheticCheckoutPeer
from scripts.checkout_test_support import ROOT, CheckoutApiProcess, template


class LiveCheckoutWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.peer = SyntheticCheckoutPeer()
        self.addCleanup(self.peer.close)
        self.api = CheckoutApiProcess(self.peer.origin)
        self.addCleanup(self.api.stop)
        self.workflow, self.purchase = str(uuid4()), str(uuid4())
        self.checkpoint = Path(self.temp.name) / "graph.sqlite"
        self.template_path = Path(self.temp.name) / "template.json"
        self.template_path.write_text(json.dumps(template()), encoding="utf-8")

    def invoke(self, action="start", expected=0):
        result = subprocess.run([sys.executable, "-m", "workflows.checkout_workflow", action,
            "--workflow-id", self.workflow, "--purchase-id", self.purchase, "--template", str(self.template_path),
            "--checkpoint", str(self.checkpoint), "--api-url", self.api.base],
            env=self.api.environment, cwd=ROOT, capture_output=True, text=True, timeout=30)
        self.assertEqual(expected, result.returncode, result.stdout)
        for secret in (CANARY, CREDENTIAL, "protected-item-97", self.api.graph_token):
            self.assertNotIn(secret, result.stdout + result.stderr)
        return json.loads(result.stdout) if expected == 0 else result.stdout

    def test_actual_journey_and_process_restart_preserve_one_original_command(self):
        created = self.invoke()
        self.assertEqual(("RECORDED", "OK"), (created["commandState"], created["outcomeCode"]))
        self.api.restart()
        self.assertEqual(created, self.invoke("resume"))
        self.assertEqual(created, self.invoke("start"))
        self.assertEqual(1, len(self.peer.requests))
        latest = created
        for operation in ("RETRIEVE", "UPDATE", "CANCEL"):
            self.workflow = str(uuid4())
            self.template_path.write_text(json.dumps(template(operation, latest["revision"])), encoding="utf-8")
            latest = self.invoke()
            self.assertEqual("OK", latest["outcomeCode"])
        self.assertEqual("canceled", latest["remoteStatus"])
        with closing(sqlite3.connect(self.checkpoint)) as connection:
            raw = b"\n".join(str(row).encode() for table in ("checkpoints", "writes", "checkout_workflow_bindings")
                             for row in connection.execute(f"SELECT * FROM {table}"))
        for secret in (CANARY, CREDENTIAL, "protected-item-97", self.api.graph_token, next(iter(self.peer.sessions))):
            self.assertNotIn(secret.encode(), raw)

    def test_accepted_create_response_loss_pauses_across_process_restart(self):
        self.peer.drop_next_mutation = True
        unknown = self.invoke()
        self.assertEqual("UNKNOWN", unknown["commandState"])
        self.assertEqual("TRUSTED_RESOLUTION_REQUIRED", unknown["recovery"])
        self.api.restart()
        self.assertEqual("UNKNOWN", self.invoke("resume")["commandState"])
        self.assertEqual("UNKNOWN", self.invoke("recover-claim")["commandState"])
        self.assertEqual(1, len(self.peer.requests))
        changed = template(); changed["itemIds"] = ["replacement"]
        self.template_path.write_text(json.dumps(changed), encoding="utf-8")
        self.invoke("start", expected=2)
        self.assertEqual(1, len(self.peer.requests))


if __name__ == "__main__":
    unittest.main()
