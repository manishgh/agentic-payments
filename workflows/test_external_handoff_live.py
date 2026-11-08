"""Source-bound commerce/payment handoff across real processes, PostgreSQL and Temporal."""
import asyncio
from contextlib import closing
from dataclasses import replace
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from uuid import uuid4

from integrations.checkout_demo import SyntheticCheckoutPeer, CREDENTIAL, CANARY
from scripts.checkout_test_support import ROOT, CheckoutApiProcess, template
from workflows.runtime_contract import (CheckoutSource, StartWorkflow, EXTERNAL_PROFILE,
    EXTERNAL_TEMPLATE_VERSION, EXTERNAL_ACTIVITY_VERSION, start_from_data)
from workflows.runtime_registry import SqliteRegistrationStore
from workflows.payment_workflow import WorkflowError, canonical_uuid
from workflows import test_temporal_runtime as temporal_tests


class HandoffHarness(unittest.TestCase):
    def setUp(self):
        directory = ROOT / ".tools/handoff-tests"
        directory.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=directory)
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.peer = SyntheticCheckoutPeer(financial=True)
        self.addCleanup(self.peer.close)
        self.api = CheckoutApiProcess(self.peer.origin, financial=True)
        self.addCleanup(self.api.stop)
        self.run_id = self.api.run_id
        self.workflow_id = str(uuid4())
        self.registry_path = self.path / "registrations.sqlite"

    def sql(self, statement):
        return temporal_tests.TemporalPaymentTests.sql(self, statement)

    def one_effect(self, intent, operation, unknown=False):
        temporal_tests.TemporalPaymentTests.assert_one_effect(self, intent, operation, unknown)
        canonical_uuid(intent)
        self.assertEqual("1|1", self.sql("SELECT "
            f"(SELECT count(*) FROM external_checkout_bindings WHERE run_id='{self.run_id}' AND intent_id='{intent}'),"
            f"(SELECT count(*) FROM checkout_snapshots WHERE run_id='{self.run_id}' AND intent_id='{intent}')"))

    def payment(self, path, body=None, trusted=False):
        code, result, _ = self.api.payment(path, body, token=self.api.ui_token if trusted else None)
        self.assertLess(code, 400, result)
        return result

    def observe(self, item="demo-item-alt", currency="eur"):
        purchase, workflow = str(uuid4()), str(uuid4())
        filename = self.path / (purchase + ".json")
        filename.write_text(json.dumps({**template(), "itemIds": [item], "currency": currency}), encoding="utf-8")
        result = subprocess.run([sys.executable, "-m", "workflows.checkout_workflow", "start",
            "--workflow-id", workflow, "--purchase-id", purchase, "--template", str(filename),
            "--checkpoint", str(self.path / "commerce.sqlite"), "--api-url", self.api.base],
            cwd=ROOT, env=self.api.environment, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stdout)
        self.private(result.stdout + result.stderr)
        observed = json.loads(result.stdout)
        self.assertEqual(("RECORDED", "OK"), (observed["commandState"], observed["outcomeCode"]))
        return CheckoutSource(purchase, observed["revision"], observed["responseReference"])

    def start_value(self, source, *, runtime="sqlite", scenario="success", workflow_id=None):
        return StartWorkflow("tenant-local", "agent-demo", self.run_id, workflow_id or self.workflow_id,
            runtime=runtime, scenario=scenario, profile=EXTERNAL_PROFILE,
            template_version=EXTERNAL_TEMPLATE_VERSION, activity_version=EXTERNAL_ACTIVITY_VERSION,
            checkout_source=source)

    def invoke(self, command="start", source=None, scenario="success", expected=0, crash=None):
        module = "workflows.handoff_test_worker" if crash else "workflows.runtime_cli"
        args = [sys.executable, "-m", module, command, "--workflow-id", self.workflow_id,
                "--run-id", self.run_id, "--registry", str(self.registry_path), "--api-url", self.api.base]
        if crash:
            args += ["--crash-step", crash, "--marker", str(self.path / (crash + ".accepted"))]
        if command == "start":
            if not crash:
                args += ["--profile", EXTERNAL_PROFILE]
            args += ["--scenario", scenario, "--purchase-id", source.purchase_id,
                     "--purchase-revision", str(source.expected_purchase_revision),
                     "--response-reference", source.response_reference]
        result = subprocess.run(args, cwd=ROOT, env=self.api.environment, capture_output=True, text=True, timeout=35)
        self.assertEqual(expected, result.returncode, result.stdout)
        self.private(result.stdout + result.stderr)
        return json.loads(result.stdout) if expected == 0 else result.stdout

    def private(self, value):
        raw = value if isinstance(value, bytes) else value.encode("utf-8")
        for secret in (CREDENTIAL, CANARY, self.api.graph_token, self.api.ui_token, "fixture-credential-1",
                       *self.peer.sessions.keys()):
            self.assertNotIn(secret.encode("utf-8"), raw)

    def registry_private(self):
        with closing(sqlite3.connect(self.registry_path)) as database:
            for table in ("workflow_registrations", "workflow_recovery_observations", "workflow_trace_headers", "workflow_trace_events"):
                self.private(json.dumps(database.execute("SELECT * FROM " + table).fetchall()))

    def offline_replay(self, verdict, *, registry=None, runtime="sqlite", workflow_id=None):
        """A fresh diagnostic process has neither credentials nor usable live endpoints."""
        environment = {key: value for key, value in self.api.environment.items()
                       if key not in ("PAYMENTS_GRAPH_TOKEN", "PAYMENTS_UI_TOKEN")}
        trace = self.path / ("trace-" + uuid4().hex + ".json")
        common = [sys.executable, "-m", "workflows.runtime_cli"]
        exported = subprocess.run(common + ["debug", "--run-id", self.run_id,
            "--workflow-id", workflow_id or self.workflow_id, "--runtime", runtime,
            "--registry", str(registry or self.registry_path), "--export", str(trace),
            "--api-url", "unavailable://offline", "--server", "unavailable"],
            cwd=ROOT, env=environment, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, exported.returncode, exported.stdout)
        self.private(exported.stdout + exported.stderr)
        self.private(trace.read_bytes())
        replayed = subprocess.run(common + ["replay", "--trace", str(trace)],
            cwd=ROOT, env=environment, capture_output=True, text=True, timeout=20)
        self.assertEqual(2 if verdict == "INCOMPLETE" else 0, replayed.returncode, replayed.stdout)
        self.private(replayed.stdout + replayed.stderr)
        report = json.loads(replayed.stdout)
        self.assertEqual(verdict, report["verdict"])
        self.assertFalse(report["authoritativePaymentState"])
        self.assertFalse(report["schedulerConsumptionVerified"])
        if verdict == "INCOMPLETE":
            self.assertGreater(report["unresolved"], 0)
        return report

    def approve(self, intent):
        view = self.payment("/intents/" + intent + "/checkout", trusted=True)
        body = {"expectedVersion": view["version"], "checkoutRevision": view["checkoutRevision"],
                "checkoutDigest": view["checkoutDigest"],
                "authorityReference": "fixture-grant-usd" if view["money"]["currency"] == "USD" else "fixture-grant-1"}
        self.assertEqual(403, self.api.payment("/intents/" + intent + "/approve", body)[0])
        return self.payment("/intents/" + intent + "/approve", body, trusted=True)

    def core_until(self, intent, state):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            current = self.payment("/intents/" + intent)
            if current["state"] == state:
                return current
            time.sleep(.03)
        self.fail("Payment did not reach expected synthetic state")

    def complete_action(self, action):
        path = "/actions/" + action
        view = self.payment(path, trusted=True)
        body = {"expectedVersion": view["version"], "decision": "approve"}
        self.assertEqual(403, self.api.payment(path + "/complete", body)[0])
        self.payment(path + "/complete", body, trusted=True)


class ExternalHandoffSqliteTests(HandoffHarness):
    def test_selected_prices_and_trusted_challenge_survive_fresh_processes(self):
        for item, currency, amount, scenario in (("demo-item", "usd", "1250", "success"),
                ("demo-item-alt", "eur", "1750", "challenge-then-success")):
            with self.subTest(currency=currency):
                self.workflow_id = str(uuid4())
                source = self.observe(item, currency)
                quoted = self.invoke(source=source, scenario=scenario)
                self.assertEqual("QUOTED", quoted["state"]["state"])
                self.assertEqual({"currency": currency.upper(), "minorUnits": amount}, quoted["state"]["money"])
                intent = quoted["state"]["intentId"]
                approved = self.approve(intent)
                self.api.restart()
                self.invoke("resume")
                if scenario == "challenge-then-success":
                    waiting = self.core_until(intent, "WAITING_ACTION")
                    self.invoke("stop")
                    self.api.restart()
                    self.complete_action(waiting["actionId"])
                final = self.core_until(intent, "SUCCEEDED")
                resumed = self.invoke("resume")
                self.assertEqual(("COMPLETED", False), (resumed["lifecycle"], resumed["authoritativePaymentState"]))
                self.assertEqual(approved["attemptId"], final["attemptId"])
                self.assertEqual(final["operationId"], resumed["state"]["operationId"])
                self.one_effect(intent, final["operationId"])
                self.registry_private()
        self.assertEqual(2, len(self.peer.requests))
        self.assertTrue(all(request["path"] == "/checkout_sessions" for request in self.peer.requests))
        self.api.stop()
        self.offline_replay("VERIFIED_RECORDED_STEPS")

    def test_lost_accepted_quote_replays_after_configuration_drift_without_reselecting_source(self):
        source = self.observe()
        self.invoke(source=source, expected=73, crash="quote_intent")
        self.offline_replay("INCOMPLETE")
        start = self.start_value(source)
        registry = SqliteRegistrationStore(self.registry_path)
        try:
            intent = registry.recovery(start.reference())["state"]["intentId"]
        finally:
            registry.close()
        accepted = self.payment("/intents/" + intent + "/checkout", trusted=True)
        replacement = SyntheticCheckoutPeer(financial=True)
        self.addCleanup(replacement.close)
        self.api.environment["PAYMENTS_CHECKOUT_DEMO_ORIGIN"] = replacement.origin
        self.api.restart()
        replayed = self.invoke("resume")
        self.assertEqual(intent, replayed["state"]["intentId"])
        self.assertEqual(accepted["checkoutDigest"], replayed["state"]["checkoutDigest"])
        self.assertEqual(accepted["money"], replayed["state"]["money"])
        self.assertEqual("1|1|1|0", self.sql("SELECT "
            f"(SELECT count(*) FROM payment_intents WHERE run_id='{self.run_id}'),"
            f"(SELECT count(*) FROM checkout_snapshots WHERE run_id='{self.run_id}'),"
            f"(SELECT count(*) FROM external_checkout_bindings WHERE run_id='{self.run_id}'),"
            f"(SELECT count(*) FROM financial_operations WHERE run_id='{self.run_id}')"))
        self.assertEqual((1, 0), (len(self.peer.requests), len(replacement.requests)))
        registry = SqliteRegistrationStore(self.registry_path)
        try:
            self.assertEqual(start, start_from_data(json.loads(registry.get(start.reference())["start"])))
        finally:
            registry.close()
        self.registry_private()
        self.offline_replay("INCOMPLETE")  # Recovery cannot invent the lost invocation outcome.

    def test_changed_observation_and_competing_workflows_cannot_publish_two_quotes(self):
        source = self.observe()
        start = self.start_value(source)
        registry = SqliteRegistrationStore(self.registry_path)
        registry.reserve(start)
        registry.close()
        code, staged = self.api.request(source.purchase_id, "/commands", {**template("RETRIEVE", source.expected_purchase_revision), "currency": "eur"})
        self.assertEqual(200, code, staged)
        code, observed = self.api.request(source.purchase_id, "/commands/" + staged["commandId"] + "/dispatch", {})
        self.assertEqual(200, code)
        self.invoke(source=source, expected=2)
        latest = CheckoutSource(source.purchase_id, observed["revision"], observed["responseReference"])
        self.invoke(source=latest, expected=2)  # Original workflow cannot adopt a newer source.
        self.workflow_id = str(uuid4())
        winner = self.invoke(source=latest)
        self.workflow_id = str(uuid4())
        self.invoke(source=latest, expected=2)
        self.assertEqual("3|1|1|0", self.sql("SELECT "
            f"(SELECT count(*) FROM payment_intents WHERE run_id='{self.run_id}'),"
            f"(SELECT count(*) FROM checkout_snapshots WHERE run_id='{self.run_id}'),"
            f"(SELECT count(*) FROM external_checkout_bindings WHERE run_id='{self.run_id}'),"
            f"(SELECT count(*) FROM financial_operations WHERE run_id='{self.run_id}')"))
        self.assertEqual("QUOTED", winner["state"]["state"])
        self.assertEqual(2, len(self.peer.requests))

    def test_unknown_provider_outcome_retains_same_operation_after_process_loss(self):
        source = self.observe()
        quoted = self.invoke(source=source, scenario="response-loss-after-acceptance")
        intent = quoted["state"]["intentId"]
        self.approve(intent)
        self.invoke("resume", expected=73, crash="execute_existing_attempt")
        operation = self.payment("/intents/" + intent)["operationId"]
        self.api.restart()
        self.invoke("resume")
        final = self.core_until(intent, "SUCCEEDED")
        self.invoke("resume")
        self.assertEqual(operation, final["operationId"])
        self.one_effect(intent, operation, unknown=True)
        self.assertEqual(1, len(self.peer.requests))


class ExternalHandoffTemporalTests(HandoffHarness):
    def test_quote_acceptance_crash_challenge_restart_and_actual_history_replay(self):
        asyncio.run(self.challenge())

    async def challenge(self):
        source = self.observe()
        async with temporal_tests.TemporalHarness(self, self.run_id, self.api.base, self.api.environment, EXTERNAL_PROFILE) as harness:
            start = self.start_value(source, runtime="temporal", scenario="challenge-then-success")
            await harness.start_worker("quote_intent")
            ref = await harness.adapter.start(start)
            await harness.crash_witness("quote_intent")
            intent = harness.registry.recovery(ref)["state"]["intentId"]
            approved = self.approve(intent)  # Quote reply retry must not treat historical QUOTED as current truth.
            harness.stop_server()
            await harness.start_server()
            await harness.start_worker()
            waiting = await harness.until(ref, lambda v: v["state"].get("state") == "WAITING_ACTION")
            operation = waiting["state"]["operationId"]
            await harness.adapter.request_stop(ref)
            await harness.until(ref, lambda v: v["lifecycle"] == "PAUSED")
            harness.stop_worker(); harness.stop_server()
            await harness.start_server(); await harness.start_worker()
            self.complete_action(waiting["state"]["actionId"])
            await harness.adapter.resume(ref)
            final = await harness.until(ref, lambda v: v["lifecycle"] == "COMPLETED")
            self.assertEqual((approved["attemptId"], operation), (final["state"]["attemptId"], final["state"]["operationId"]))
            self.assertEqual({"currency": "EUR", "minorUnits": "1750"}, final["state"]["money"])
            self.one_effect(intent, operation)
            await harness.assert_private_history(ref, (self.peer.responses[-1]["body"], *self.peer.sessions.keys()))
            self.offline_replay("INCOMPLETE", registry=harness.path / "registrations.sqlite", runtime="temporal")
            with self.assertRaises(WorkflowError):
                await harness.adapter.start(replace(start, checkout_source=replace(source, response_reference=str(uuid4()))))
        self.assertEqual(1, len(self.peer.requests))

    def test_unknown_and_terminated_history_never_start_replacement_financial_work(self):
        asyncio.run(self.unknown())

    async def unknown(self):
        source = self.observe("demo-item", "usd")
        async with temporal_tests.TemporalHarness(self, self.run_id, self.api.base, self.api.environment, EXTERNAL_PROFILE) as harness:
            start = self.start_value(source, runtime="temporal", scenario="response-loss-after-acceptance")
            await harness.start_worker("execute_existing_attempt")
            ref = await harness.adapter.start(start)
            quoted = await harness.until(ref, lambda v: v["state"].get("state") == "QUOTED")
            intent = quoted["state"]["intentId"]
            self.approve(intent)
            await harness.crash_witness("execute_existing_attempt")
            operation = self.payment("/intents/" + intent)["operationId"]
            harness.stop_server(); await harness.start_server(); await harness.start_worker()
            final = await harness.until(ref, lambda v: v["lifecycle"] == "COMPLETED")
            self.assertEqual(operation, final["state"]["operationId"])
            self.one_effect(intent, operation, unknown=True)
            await harness.assert_private_history(ref, (self.peer.responses[-1]["body"], *self.peer.sessions.keys()))
            other_source = self.observe()
            other = self.start_value(other_source, runtime="temporal", workflow_id=str(uuid4()))
            other_ref = await harness.adapter.start(other)
            other_quoted = await harness.until(other_ref, lambda v: v["state"].get("state") == "QUOTED")
            await harness.client.get_workflow_handle(other_ref.runtime_id).terminate("synthetic-debug-recovery")
            recovery = await harness.adapter.inspect(other_ref)
            self.assertEqual("RECOVERY_REQUIRED", recovery["lifecycle"])
            self.assertEqual(other_quoted["state"]["intentId"], recovery["state"]["intentId"])
            self.assertEqual(other_ref, await harness.adapter.start(other))
            with self.assertRaises(WorkflowError):
                await harness.adapter.resume(other_ref)
            self.assertEqual(other_source, start_from_data(json.loads(harness.registry.get(other_ref)["start"])).checkout_source)
            self.assertEqual("2", self.sql(f"SELECT count(*) FROM payment_intents WHERE run_id='{self.run_id}'"))


if __name__ == "__main__":
    unittest.main()
