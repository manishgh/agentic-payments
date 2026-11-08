"""Compatibility with actual histories captured before external handoff support existed."""
import hashlib
import json
from pathlib import Path
import sqlite3
from contextlib import closing
import tempfile
import unittest

from temporalio.client import WorkflowHistory
from temporalio.worker import Replayer
from workflows.runtime_contract import activity_envelope, start_from_data
from workflows.runtime_registry import SqliteRegistrationStore
from workflows.runtime_replay import SqliteWorkflowTraceReader, replay_trace
from workflows.temporal_workflow import PaymentOrchestration

FIXTURES = Path(__file__).parent / "fixtures/legacy-runtime-0113656"
BASELINE = "01136568bfc087684648ec873964ffbbc28510bf"


class LegacyHistoryCompatibilityTests(unittest.IsolatedAsyncioTestCase):
    async def test_original_start_bytes_registration_and_temporal_histories_replay(self):
        manifest = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(2, len(manifest["fixtures"]))
        for fixture in manifest["fixtures"]:
            with self.subTest(profile=fixture["start"]["profile"]):
                self.assertEqual(BASELINE, fixture["sourceCommit"])
                raw = (FIXTURES / fixture["file"]).read_bytes()
                self.assertEqual(fixture["sha256"], hashlib.sha256(raw).hexdigest())
                start = start_from_data(fixture["start"])
                self.assertEqual(fixture["start"], start.data())
                self.assertNotIn("checkout_source", start.data())
                self.assertEqual(fixture["fingerprint"], start.fingerprint())
                self.assertEqual(fixture["firstEnvelope"], activity_envelope(start, 0, "decide", {}))
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "registry.sqlite"
                    # Seed the pre-journal schema and actual retained baseline bytes,
                    # rather than using a new reserve operation that would add a trace header.
                    with closing(sqlite3.connect(path)) as old:
                        old.execute("CREATE TABLE workflow_registrations (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, "
                            "start TEXT NOT NULL, attempted INTEGER NOT NULL DEFAULT 0, confirmed INTEGER NOT NULL DEFAULT 0, "
                            "progress TEXT NOT NULL DEFAULT '{}', pause_requested INTEGER NOT NULL DEFAULT 0)")
                        old.execute("INSERT INTO workflow_registrations(id,fingerprint,start,attempted,confirmed) VALUES(?,?,?,?,?)",
                            (start.reference().runtime_id, fixture["fingerprint"],
                             json.dumps(fixture["start"], sort_keys=True, separators=(",", ":"), ensure_ascii=True), 1, 1))
                        old.commit()
                    registry = SqliteRegistrationStore(path)
                    try:
                        registry.validate_worker(start)
                        self.assertEqual(fixture["fingerprint"], registry.get(start.reference())["fingerprint"])
                        self.assertEqual({"coverage": "HISTORICAL_TRACE_UNAVAILABLE"}, registry.trace_policy(start.reference()))
                    finally:
                        registry.close()
                    with SqliteWorkflowTraceReader(path) as reader:
                        report = replay_trace(reader.read_trace(start.reference()))
                    self.assertEqual("INCOMPLETE", report["verdict"])
                    self.assertEqual("HISTORICAL_TRACE_UNAVAILABLE", report["coverage"])
                history = WorkflowHistory.from_json(start.reference().runtime_id, raw.decode("utf-8"))
                await Replayer(workflows=[PaymentOrchestration]).replay_workflow(history)


if __name__ == "__main__":
    unittest.main()
