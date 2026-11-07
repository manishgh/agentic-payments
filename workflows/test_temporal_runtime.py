"""Actual pinned Temporal server/worker process recovery, plus Java/PostgreSQL evidence."""
from __future__ import annotations

import asyncio
from dataclasses import replace
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import json
import os
from pathlib import Path
import secrets
import socket
import shutil
import subprocess
import sys
import tempfile
from threading import Thread, RLock
import time
import unittest
from urllib.parse import urlsplit
from uuid import uuid4
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from collections.abc import Mapping, Iterable
from google.protobuf.message import Message

from temporalio.api.workflowservice.v1 import DescribeNamespaceRequest
from temporalio.client import Client
from temporalio.worker import Replayer

from workflows.payment_workflow import CONTRACT, PROFILE, NATIVE_PROFILE, WorkflowError
from workflows.runtime_contract import StartWorkflow, WakeNotice
from workflows.runtime_registry import SqliteRegistrationStore
from workflows.temporal_runtime import TemporalWorkflowRuntime
from workflows.temporal_workflow import PaymentOrchestration
from workflows import test_live
from workflows.test_payment_workflow import FakeCore

ROOT = Path(__file__).resolve().parents[1]


async def history_contains_sensitive(history, converter, secrets):
    """Scan protobuf metadata/errors AND decoded payloads, including escaped JSON strings."""
    needles = tuple(item.encode("utf-8") if isinstance(item, str) else item for item in secrets if item)

    async def visit(value):
        if isinstance(value, str):
            raw = value.encode("utf-8")
            if any(needle in raw for needle in needles):
                return True
            if value.startswith(("{", "[")):
                try:
                    parsed = json.loads(value)
                except ValueError:
                    return False
                return await visit(parsed)
            return False
        if isinstance(value, bytes):
            if any(needle in value for needle in needles):
                return True
            try:
                parsed = json.loads(value)
            except (ValueError, UnicodeError):
                return False
            return await visit(parsed)
        if isinstance(value, Message):
            if value.DESCRIPTOR.full_name == "temporal.api.common.v1.Payload":
                if await visit(value.data) or await visit(dict(value.metadata)):
                    return True
                failed = False
                try:
                    decoded = await converter.decode([value])
                except Exception:
                    failed = True
                if failed:
                    raise WorkflowError("HISTORY_PRIVACY_SCAN_UNAVAILABLE")
                return await visit(decoded)
            for descriptor, child in value.ListFields():
                if await visit(child):
                    return True
            return False
        if isinstance(value, Mapping):
            for key, child in value.items():
                if await visit(key) or await visit(child):
                    return True
            return False
        if isinstance(value, Iterable):
            for child in value:
                if await visit(child):
                    return True
        return False

    if any(needle in history.to_json().encode("utf-8") for needle in needles):
        return True
    for event in history.events:
        if await visit(event):
            return True
    return False


class HistoryPrivacyTests(unittest.IsolatedAsyncioTestCase):
    async def test_scanner_detects_encoded_payloads_and_escaped_artifact_strings(self):
        from temporalio.client import WorkflowHistory
        from temporalio.converter import DataConverter
        from temporalio.api.common.v1 import Payloads
        from temporalio.api.history.v1 import HistoryEvent, WorkflowExecutionStartedEventAttributes, ActivityTaskFailedEventAttributes
        from temporalio.api.failure.v1 import Failure, ApplicationFailureInfo
        token = "RAW_TOKEN_CANARY"
        artifact = '{"artifact":"signed.synthetic.checkout","quote":"escaped"}'
        payloads = await DataConverter.default.encode([{"token": token, "artifact": artifact}])
        history = WorkflowHistory("planted", [HistoryEvent(event_id=1,
            workflow_execution_started_event_attributes=WorkflowExecutionStartedEventAttributes(input=Payloads(payloads=payloads))),
            HistoryEvent(event_id=2, activity_task_failed_event_attributes=ActivityTaskFailedEventAttributes(
                failure=Failure(application_failure_info=ApplicationFailureInfo(details=Payloads(payloads=payloads)))))])
        self.assertNotIn(token, history.to_json())
        self.assertTrue(await history_contains_sensitive(history, DataConverter.default, (token,)))
        self.assertTrue(await history_contains_sensitive(history, DataConverter.default, (artifact,)))
        failure_only = WorkflowHistory("planted", history.events[1:])
        self.assertTrue(await history_contains_sensitive(failure_only, DataConverter.default, (artifact,)))
        self.assertFalse(await history_contains_sensitive(history, DataConverter.default, ("ABSENT_SAFE_CANARY",)))


class TemporalHarness:
    def __init__(self, case, run_id, base, environment, profile=PROFILE):
        self.case, self.run_id, self.base, self.environment, self.profile = case, run_id, base, environment, profile
        directory = ROOT / ".tools/runtime-tests"
        directory.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=directory)
        self.path = Path(self.directory.name)
        self.registry = SqliteRegistrationStore(self.path / "registrations.sqlite")
        self.queue = "payment-" + uuid4().hex
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            self.port = sock.getsockname()[1]
        self.server = self.worker = None
        self.logs = []

    async def __aenter__(self):
        await self.start_server()
        return self

    async def __aexit__(self, *args):
        self.stop_worker()
        self.stop_server()
        self.registry.close()
        for file in self.logs:
            file.close()
        if args and args[0] is not None:
            diagnostics = self.path.parent / ("diagnostic-" + uuid4().hex)
            diagnostics.mkdir()
            for path in self.path.glob("*.log"):
                shutil.copyfile(path, diagnostics / path.name)
        self.directory.cleanup()

    def start_request(self, scenario="success"):
        return StartWorkflow("tenant-local", "agent-demo", self.run_id, str(uuid4()), runtime="temporal", profile=self.profile, scenario=scenario)

    async def start_server(self):
        executable = Path(os.environ.get("PAYMENTS_TEMPORAL_CLI", ROOT / ".tools/temporal-1.10.0" / ("temporal.exe" if os.name == "nt" else "temporal")))
        if not executable.is_file():
            raise RuntimeError("Explicit verified Temporal CLI is required.")
        log = open(self.path / f"server-{len(self.logs)}.log", "wb")
        self.logs.append(log)
        self.server = subprocess.Popen([str(executable), "server", "start-dev", "--ip", "127.0.0.1", "--port", str(self.port),
            "--headless", "--db-filename", str(self.path / "temporal.sqlite")], cwd=ROOT, stdout=log, stderr=log)
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            try:
                self.client = await asyncio.wait_for(Client.connect(f"127.0.0.1:{self.port}"), timeout=2)
                await self.client.workflow_service.describe_namespace(DescribeNamespaceRequest(namespace="default"), timeout=timedelta(seconds=2))
                self.adapter = TemporalWorkflowRuntime(self.client, self.registry, "tenant-local", "agent-demo", self.run_id, self.queue)
                return
            except Exception:
                if self.server.poll() is not None:
                    raise RuntimeError("Temporal development server failed to start.") from None
                await asyncio.sleep(.1)
        raise RuntimeError("Temporal development server readiness deadline exceeded.")

    def stop_server(self):
        if self.server is not None and self.server.poll() is None:
            self.server.kill()
            self.server.wait(timeout=10)

    async def start_worker(self, crash_step=None, fault_result=None):
        self.stop_worker()
        test_worker = bool(crash_step or fault_result)
        module = "workflows.temporal_test_worker" if test_worker else "workflows.runtime_cli"
        args = [sys.executable, "-m", module]
        if not test_worker:
            args += ["worker", "--runtime", "temporal"]
        args += ["--server", f"127.0.0.1:{self.port}", "--queue", self.queue, "--registry", str(self.path / "registrations.sqlite"),
                 "--api-url", self.base, "--run-id", self.run_id, "--profile", self.profile]
        if crash_step:
            args += ["--crash-step", crash_step, "--marker", str(self.path / (crash_step + ".accepted"))]
        elif fault_result:
            args += ["--fault-result", fault_result, "--marker", str(self.path / "unused.accepted")]
        error_log = open(self.path / f"worker-{len(self.logs)}.log", "wb")
        self.logs.append(error_log)
        self.worker = subprocess.Popen(args, cwd=ROOT, env=self.environment, stdout=subprocess.PIPE, stderr=error_log, text=True)
        loop = asyncio.get_running_loop()
        try:
            ready = await asyncio.wait_for(loop.run_in_executor(None, self.worker.stdout.readline), timeout=20)
        except TimeoutError:
            self.stop_worker()
            raise RuntimeError("Temporal worker readiness deadline exceeded.") from None
        if ready.strip() != "Local Temporal worker ready.":
            self.stop_worker()
            raise RuntimeError("Temporal worker failed to start.")

    def stop_worker(self):
        if self.worker is not None:
            if self.worker.poll() is None:
                self.worker.kill()
                self.worker.wait(timeout=10)
            if self.worker.stdout:
                self.worker.stdout.close()

    async def crash_witness(self, step):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if self.worker.poll() is not None:
                self.case.assertEqual(73, self.worker.returncode)
                self.case.assertEqual(step, (self.path / (step + ".accepted")).read_text())
                return
            await asyncio.sleep(.05)
        self.case.fail("Accepted-command crash barrier was not reached.")

    async def until(self, ref, predicate):
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            try:
                value = await self.adapter.inspect(ref)
            except WorkflowError as error:
                if str(error) != "WORKFLOW_RUNTIME_UNAVAILABLE":
                    raise
                await asyncio.sleep(.1)
                continue
            if predicate(value):
                return value
            await asyncio.sleep(.05)
        self.case.fail("Temporal workflow did not reach the expected bounded state.")

    async def assert_private_history(self, ref, extra_private=()):
        history = await self.client.get_workflow_handle(ref.runtime_id).fetch_history()
        raw = history.to_json().encode()
        registry_raw = b"\n".join(str(tuple(row)).encode() for table in ("workflow_registrations", "workflow_recovery_observations") for row in self.registry._db.execute("SELECT * FROM " + table))
        logs = b"\n".join(path.read_bytes() for path in self.path.glob("*.log"))
        secrets = (self.environment.get("PAYMENTS_GRAPH_TOKEN", ""), self.environment.get("PAYMENTS_UI_TOKEN", ""),
                   "fixture-credential-1", "RAW_SIGNED_ARTIFACT_KEY_EXCEPTION_CANARY", "SESSION_CREDENTIAL_ACTION_SECRET_CANARY_9e17", *extra_private)
        if await history_contains_sensitive(history, self.client.data_converter, secrets):
            self.case.fail("Sensitive content found in decoded runtime history.")
        for value in secrets:
            if value:
                for channel in (raw, registry_raw, logs):
                    if (value.encode() if isinstance(value, str) else value) in channel:
                        self.case.fail("Sensitive content found in runtime persistence.")
        self.case.assertIn(b"payment-step-1", raw)
        self.case.assertIn(b"decide", raw)
        await Replayer(workflows=[PaymentOrchestration]).replay_workflow(history)


class TemporalRuntimeTests(unittest.TestCase):
    def test_real_process_recovery_activity_boundaries_wakes_pause_and_history(self):
        asyncio.run(self._exercise())

    async def _exercise(self):
        run_id = str(uuid4())
        core = FakeCore(run_id)
        lock = RLock()
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                with lock:
                    self.send_body(core.status(self.path.rsplit("/", 1)[1]))
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                key = self.headers["Idempotency-Key"]
                with lock:
                    if self.path.endswith("/quote"):
                        result = core.quote(core.intent_id, key, body["expectedVersion"])
                    elif self.path.endswith("/execute"):
                        result = core.execute(core.intent_id, key, body["expectedVersion"], body["attemptId"])
                    else:
                        result = core.create(key, body["scenarioId"])
                    self.send_body(result)
            def send_body(self, value):
                data = json.dumps({"contractVersion": CONTRACT, **value}).encode()
                self.send_response(200); self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
        http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=http.serve_forever, daemon=True); thread.start()
        environment = dict(os.environ, PAYMENTS_GRAPH_TOKEN=secrets.token_hex(32))
        try:
            async with TemporalHarness(self, run_id, f"http://127.0.0.1:{http.server_port}", environment) as harness:
                start = harness.start_request()
                await harness.start_worker("create_intent")
                ref = await harness.adapter.start(start)
                await harness.crash_witness("create_intent")
                await harness.start_worker("quote_intent")
                await harness.crash_witness("quote_intent")
                await harness.start_worker()
                await harness.until(ref, lambda value: value["state"].get("state") == "QUOTED")
                notice = WakeNotice(str(uuid4()))
                await harness.adapter.wake(ref, notice); await harness.adapter.wake(ref, notice)
                await harness.adapter.request_stop(ref)
                await harness.until(ref, lambda value: value["lifecycle"] == "PAUSED")
                core.approve_as_trusted_subject()
                await harness.adapter.wake(ref, WakeNotice(str(uuid4())))
                self.assertEqual("PAUSED", (await harness.adapter.inspect(ref))["lifecycle"])
                await harness.start_worker("execute_existing_attempt")
                await harness.adapter.resume(ref)
                await harness.crash_witness("execute_existing_attempt")
                self.assertEqual(1, core.create_count); self.assertEqual(1, len(core.executions))
                harness.stop_server()
                await harness.start_server()
                await harness.start_worker()
                await harness.until(ref, lambda value: value["state"].get("state") == "PREPARING")
                core.state, core.version = "UNKNOWN", 8
                unknown = await harness.until(ref, lambda value: value["state"].get("state") == "UNKNOWN")
                self.assertEqual(core.operation_id, unknown["state"]["operationId"])
                core.state, core.version = "SUCCEEDED", 9
                await harness.until(ref, lambda value: value["lifecycle"] == "COMPLETED")
                self.assertEqual(ref, await harness.adapter.start(start))
                await harness.assert_private_history(ref)
                # Forced termination cannot be recovered through start or resume.
                harness.stop_worker()
                another = harness.start_request()
                other_ref = await harness.adapter.start(another)
                await harness.client.get_workflow_handle(other_ref.runtime_id).terminate("bounded-test")
                with self.assertRaises(WorkflowError):
                    await harness.adapter.resume(other_ref)
                self.assertEqual(other_ref, await harness.adapter.start(another))
                self.assertEqual(1, core.create_count)
                # No inspect occurs before this accepted execution is terminated.
                core.intent_id, core.attempt_id, core.operation_id = str(uuid4()), str(uuid4()), str(uuid4())
                core.state, core.version = "DRAFT", 1
                (harness.path / "execute_existing_attempt.accepted").unlink()
                await harness.start_worker("execute_existing_attempt")
                unobserved = harness.start_request()
                unobserved_ref = await harness.adapter.start(unobserved)
                deadline = time.monotonic() + 20
                while core.state != "QUOTED" and time.monotonic() < deadline:
                    await asyncio.sleep(.05)
                self.assertEqual("QUOTED", core.state)
                core.approve_as_trusted_subject()
                await harness.crash_witness("execute_existing_attempt")
                await harness.client.get_workflow_handle(unobserved_ref.runtime_id).terminate("bounded-test-after-acceptance")
                recovered = await harness.adapter.inspect(unobserved_ref)
                self.assertEqual("RECOVERY_REQUIRED", recovered["lifecycle"])
                self.assertEqual("REGISTRATION_RECOVERY_CACHE", recovered["source"])
                self.assertEqual(core.intent_id, recovered["state"]["intentId"])
                self.assertEqual(core.attempt_id, recovered["state"]["attemptId"])
                self.assertIn(recovered["state"].get("operationId"), (None, core.operation_id))
                with self.assertRaises(WorkflowError):
                    await harness.adapter.resume(unobserved_ref)
                # Deliberately bad decorators never serialize their output.
                for fault in ("extra-token", "unknown-decision", "wrong-intent"):
                    core.state, core.version = "DRAFT", 1
                    await harness.start_worker(fault_result=fault)
                    bad_ref = await harness.adapter.start(harness.start_request())
                    rejected = await harness.until(bad_ref, lambda value: value["lifecycle"] == "RECOVERY_REQUIRED")
                    self.assertFalse(rejected["authoritativePaymentState"])
                    await harness.assert_private_history(bad_ref)
                core.state, core.version = "DRAFT", 1
                await harness.start_worker()
                limited = replace(harness.start_request(), activity_limit=8)
                limited_ref = await harness.adapter.start(limited)
                boundary = await harness.until(limited_ref, lambda value: value["lifecycle"] == "HISTORY_LIMIT")
                self.assertEqual(8, boundary["sequence"])
                count = len((await harness.client.get_workflow_handle(limited_ref.runtime_id).fetch_history()).events)
                for operation in (lambda: harness.adapter.resume(limited_ref), lambda: harness.adapter.wake(limited_ref, WakeNotice(str(uuid4())))):
                    with self.assertRaises(WorkflowError):
                        await operation()
                self.assertEqual(count, len((await harness.client.get_workflow_handle(limited_ref.runtime_id).fetch_history()).events))
                await harness.assert_private_history(limited_ref)
        finally:
            http.shutdown(); http.server_close(); thread.join(timeout=5)


class TemporalPaymentTests(test_live.LiveWorkflowTests):
    # The superclass supplies the real Java/PostgreSQL harness and trusted UI client.
    test_live_success_with_graph_process_restart = None
    test_live_trusted_challenge_is_separate_from_checkpoint = None

    def test_native_challenge_pause_crash_and_same_operation_settlement(self):
        asyncio.run(self._challenge())

    def test_native_response_loss_unknown_audit_same_effect(self):
        asyncio.run(self._response_loss())

    def sql(self, statement):
        database = urlsplit(os.environ["PAYMENTS_DB_URL"].removeprefix("jdbc:"))
        binary = shutil.which("psql") or str(ROOT / ".tools/postgresql-17.11-5/pgsql/bin/psql.exe")
        command = [str(binary), "-X", "-A", "-t", "-h", database.hostname, "-p", str(database.port), "-U", os.environ["PAYMENTS_DB_USER"],
                   "-d", database.path.lstrip("/"), "-v", "ON_ERROR_STOP=1", "-c", statement]
        result = subprocess.run(command, env=dict(os.environ, PGPASSWORD=os.environ["PAYMENTS_DB_PASSWORD"]), capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, "PostgreSQL witness query failed.")
        return result.stdout.strip()

    def assert_one_effect(self, intent_id, operation_id, unknown=False):
        # IDs are canonical UUIDs generated by the core, never arbitrary SQL input.
        from workflows.payment_workflow import canonical_uuid
        canonical_uuid(intent_id); canonical_uuid(operation_id); canonical_uuid(self.run_id)
        scope = f"tenant_id='tenant-local' AND run_id='{self.run_id}'"
        count = self.sql(f"SELECT (SELECT count(*) FROM payment_attempts WHERE {scope} AND intent_id='{intent_id}'),(SELECT count(*) FROM financial_operations WHERE {scope} AND operation_id='{operation_id}'),(SELECT count(*) FROM simulator_operation_journal WHERE {scope} AND adapter_key='{operation_id}'),(SELECT count(*) FROM reservations r JOIN payment_attempts a USING(tenant_id,run_id,attempt_id) WHERE r.tenant_id='tenant-local' AND r.run_id='{self.run_id}' AND a.intent_id='{intent_id}' AND r.state='CONSUMED')")
        self.assertEqual("1|1|1|1", count)
        if unknown:
            self.assertGreaterEqual(int(self.sql(f"SELECT count(*) FROM audit_events WHERE {scope} AND intent_id='{intent_id}' AND state='UNKNOWN'")), 1)

    def private_native_artifacts(self):
        values = self.sql(f"SELECT encode(content,'hex') FROM native_artifacts WHERE tenant_id='tenant-local' AND run_id='{self.run_id}'")
        return tuple(bytes.fromhex(line) for line in values.splitlines() if line)

    async def _challenge(self):
        async with TemporalHarness(self, self.run_id, self.base, self.environment, NATIVE_PROFILE) as harness:
            await harness.start_worker()
            start = harness.start_request("challenge-then-success")
            ref = await harness.adapter.start(start)
            quoted = await harness.until(ref, lambda value: value["state"].get("state") == "QUOTED")
            intent = quoted["state"]["intentId"]
            self.approve_independently(intent)
            waiting = await harness.until(ref, lambda value: value["state"].get("state") == "WAITING_ACTION")
            operation = waiting["state"]["operationId"]
            await harness.adapter.request_stop(ref)
            await harness.until(ref, lambda value: value["lifecycle"] == "PAUSED")
            harness.stop_worker(); harness.stop_server()
            await harness.start_server(); await harness.start_worker()
            self.assertEqual("PAUSED", (await harness.adapter.inspect(ref))["lifecycle"])
            action_path = "/actions/" + waiting["state"]["actionId"]
            self.assertEqual(403, self.trusted_request(action_path, graph=True)[0])
            status, action = self.trusted_request(action_path)
            self.assertEqual(200, status)
            self.assertEqual(200, self.trusted_request(action_path + "/complete", {"expectedVersion": action["version"], "decision": "approve"})[0])
            await harness.adapter.resume(ref)
            final = await harness.until(ref, lambda value: value["lifecycle"] == "COMPLETED")
            self.assertEqual(operation, final["state"]["operationId"])
            self.assertEqual("SUCCEEDED", final["state"]["state"])
            self.assert_one_effect(intent, operation)
            await harness.assert_private_history(ref, self.private_native_artifacts())

    async def _response_loss(self):
        async with TemporalHarness(self, self.run_id, self.base, self.environment, NATIVE_PROFILE) as harness:
            await harness.start_worker("execute_existing_attempt")
            start = harness.start_request("response-loss-after-acceptance")
            ref = await harness.adapter.start(start)
            quoted = await harness.until(ref, lambda value: value["state"].get("state") == "QUOTED")
            intent = quoted["state"]["intentId"]
            self.approve_independently(intent)
            await harness.crash_witness("execute_existing_attempt")
            status, accepted = self.trusted_request("/intents/" + intent)
            self.assertEqual(200, status)
            operation = accepted["operationId"]
            harness.stop_server(); await harness.start_server(); await harness.start_worker()
            final = await harness.until(ref, lambda value: value["lifecycle"] == "COMPLETED")
            self.assertEqual(operation, final["state"]["operationId"])
            self.assert_one_effect(intent, operation, unknown=True)
            await harness.assert_private_history(ref, self.private_native_artifacts())


if __name__ == "__main__":
    unittest.main()
