from __future__ import annotations

import asyncio
from dataclasses import replace
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from uuid import uuid4

from workflows.payment_workflow import WorkflowError, command_key
from workflows.runtime_contract import StartWorkflow, WakeNotice, activity_envelope, RuntimeCatalog, RuntimeDescriptor, RUNTIME_CATALOG, start_from_data, validate_activity_result
from workflows.runtime_logic import PaymentActivities, decide
from workflows.runtime_registry import SqliteRegistrationStore
from workflows.sqlite_runtime import SqliteWorkflowRuntime
from workflows.test_payment_workflow import FakeCore


class RuntimeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        test_root = Path(__file__).resolve().parents[1] / ".tools/runtime-tests"
        test_root.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=test_root)
        self.addCleanup(self.directory.cleanup)
        self.filename = Path(self.directory.name) / "registry.sqlite"
        self.start = StartWorkflow("tenant-local", "agent-demo", str(uuid4()), str(uuid4()))
        self.core = FakeCore(self.start.run_id)
        self.core.scope_ref, self.core.principal_id = "tenant-local", "agent-demo"
        self.registry = SqliteRegistrationStore(self.filename)
        self.addCleanup(lambda: self.registry.close())
        self.activities = PaymentActivities(self.core, self.registry)
        self.runtime = SqliteWorkflowRuntime(self.registry, self.activities)

    async def test_same_graph_decisions_and_legacy_command_identity(self):
        ref = await self.runtime.start(self.start)
        self.assertEqual(ref, await self.runtime.start(self.start))
        self.assertEqual("QUOTED", (await self.runtime.inspect(ref))["state"]["state"])
        self.assertEqual("wait", decide((await self.runtime.inspect(ref))["state"]))
        self.assertEqual(command_key({"runId": self.start.run_id, "workflowId": self.start.workflow_id}, "create"), self.core.calls[0][1])
        self.assertEqual(1, self.core.create_count)
        for request in (replace(self.start, scenario="delayed-success"), replace(self.start, principal_id="other")):
            with self.assertRaises(WorkflowError):
                await self.runtime.start(request)
        with self.assertRaises(WorkflowError):
            await self.runtime.inspect(replace(ref, run_id=str(uuid4())))

    async def test_capabilities_versions_input_and_principal_fail_before_core(self):
        for request in (replace(self.start, required_capabilities=("durable-timer",)),
                        replace(self.start, decision_version="unknown"), replace(self.start, scope_ref="RAW_SECRET_CANARY!")):
            with self.assertRaises(WorkflowError):
                await self.runtime.start(request)
        self.assertEqual([], self.core.calls)
        ref = await self.runtime.start(self.start)
        self.core.principal_id = "changed"
        with self.assertRaises(WorkflowError):
            await self.runtime.resume(ref)
        self.assertEqual([], self.core.executions)

    async def test_lost_quote_and_execute_replay_exact_pending_envelope(self):
        actual = self.core.quote
        def lost(*args):
            self.core.quote = actual
            actual(*args)
            raise RuntimeError("SIGNED_ARTIFACT_TOKEN_EXCEPTION_CANARY")
        self.core.quote = lost
        with self.assertRaisesRegex(WorkflowError, "PAYMENT_ACTIVITY_UNAVAILABLE"):
            await self.runtime.start(self.start)
        row = self.registry.get(self.start.reference())
        first_pending = json.loads(row["progress"])["pending"]
        self.assertEqual("quote_intent", first_pending["kind"])
        self.core.approve_as_trusted_subject()
        self.core.lose_execute_response = True
        with self.assertRaises(WorkflowError):
            await self.runtime.resume(self.start.reference())
        await self.runtime.resume(self.start.reference())
        self.assertEqual(1, len(self.core.executions))
        self.assertEqual(1, self.core.create_count)
        self.core.state, self.core.version = "UNKNOWN", 8
        self.assertEqual("HELD", (await self.runtime.resume(self.start.reference()))["state"]["exposureState"])
        self.assertEqual(self.core.operation_id, (await self.runtime.inspect(self.start.reference()))["state"]["operationId"])
        raw = b"\n".join(str(tuple(row)).encode() for row in self.registry._db.execute("SELECT * FROM workflow_registrations"))
        for secret in (self.core.canary, "SIGNED_ARTIFACT_TOKEN_EXCEPTION_CANARY", "fixture-credential-1"):
            self.assertNotIn(secret.encode(), raw)

    async def test_pause_wake_restart_and_same_ref_resume(self):
        ref = await self.runtime.start(self.start)
        self.assertTrue((await self.runtime.request_stop(ref))["applied"])
        notice = WakeNotice(str(uuid4()))
        await self.runtime.wake(ref, notice); await self.runtime.wake(ref, notice)
        self.assertEqual("PAUSED", (await self.runtime.inspect(ref))["lifecycle"])
        self.core.approve_as_trusted_subject()
        self.registry.close()
        self.registry = SqliteRegistrationStore(self.filename)
        self.runtime = SqliteWorkflowRuntime(self.registry, PaymentActivities(self.core, self.registry))
        self.assertEqual("PAUSED", (await self.runtime.inspect(ref))["lifecycle"])
        self.assertEqual("PREPARING", (await self.runtime.resume(ref))["state"]["state"])
        self.assertEqual(1, len(self.core.executions))
        with self.assertRaises(WorkflowError):
            await self.runtime.wake(ref, WakeNotice(str(uuid4()), "APPROVED_RAW_SECRET_CANARY"))

    async def test_attempted_but_unconfirmed_never_drives_or_reuses(self):
        self.registry.reserve(self.start)
        self.registry.claim_attempt(self.start.reference())
        with self.assertRaisesRegex(WorkflowError, "RECOVERY_REQUIRED"):
            await self.runtime.start(self.start)
        self.assertEqual([], self.core.calls)

    async def test_applied_pause_wins_before_activity_staging(self):
        ref = await self.runtime.start(self.start)
        self.core.approve_as_trusted_subject()
        actual_stage = self.registry.stage_activity
        receipts = []
        def pause_at_dispatch(reference, progress, envelope):
            if envelope["kind"] == "execute_existing_attempt" and not receipts:
                receipts.append(self.runtime._driver.request_stop(ref))
            return actual_stage(reference, progress, envelope)
        self.registry.stage_activity = pause_at_dispatch
        result = await self.runtime.resume(ref)
        self.assertTrue(receipts[0]["applied"])
        self.assertEqual("PAUSED", result["lifecycle"])
        self.assertEqual([], self.core.executions)
        await self.runtime.resume(ref)
        self.assertEqual(1, len(self.core.executions))

    async def test_decorated_registration_forwards_atomic_staging(self):
        calls = []
        class Forwarder:
            def __init__(self, target):
                self.target = target
            def __getattr__(self, name):
                return getattr(self.target, name)
            def stage_activity(self, *args):
                calls.append("stage")
                return self.target.stage_activity(*args)
        store = Forwarder(self.registry)
        runtime = SqliteWorkflowRuntime(store, PaymentActivities(self.core, store))
        ref = await runtime.start(self.start)
        self.assertTrue(calls)
        await runtime.request_stop(ref)
        count = len(calls)
        await runtime.wake(ref, WakeNotice(str(uuid4())))
        self.assertEqual("PAUSED", (await runtime.inspect(ref))["lifecycle"])
        self.assertEqual(count, len(calls))
        self.core.approve_as_trusted_subject()
        await runtime.resume(ref)
        self.assertEqual(1, len(self.core.executions))

    async def test_decorated_activity_results_reject_before_sqlite_persistence(self):
        canary = "RAW_SIGNED_ARTIFACT_KEY_EXCEPTION_CANARY"
        actual_run = self.activities.run
        for kind in ("extra-token", "unknown-decision", "wrong-intent"):
            request = replace(self.start, workflow_id=str(uuid4()))
            def decorated(envelope, fault=kind):
                output = actual_run(envelope)
                if fault == "extra-token":
                    return {**output, "token": canary}
                if fault == "unknown-decision" and envelope["kind"] == "decide":
                    return {"decision": canary}
                if fault == "wrong-intent" and envelope["kind"] == "read_status":
                    return {**output, "intentId": str(uuid4())}
                return output
            self.activities.run = decorated
            with self.assertRaises(WorkflowError):
                await self.runtime.start(request)
            raw = b"\n".join(str(tuple(row)).encode() for table in ("workflow_registrations", "workflow_recovery_observations") for row in self.registry._db.execute("SELECT * FROM " + table))
            self.assertNotIn(canary.encode(), raw)
        self.activities.run = actual_run

    async def test_recovery_projection_is_monotonic_and_read_retry_can_advance(self):
        ref = await self.runtime.start(self.start)
        state = (await self.runtime.inspect(ref))["state"]
        self.core.approve_as_trusted_subject()
        envelope = activity_envelope(self.start, 20, "read_status", state)
        approved = self.activities.run(envelope)
        first_read = self.registry.begin_activity(envelope)
        late_read = self.registry.begin_activity(envelope)
        self.registry.record_activity(envelope, approved, first_read)
        self.core.state, self.core.version = "UNKNOWN", 8
        unknown = self.activities.run(envelope)
        self.registry.record_activity(envelope, unknown, self.registry.begin_activity(envelope))
        self.registry.record_activity(envelope, approved, late_read)  # Late timed-out thread.
        older = activity_envelope(self.start, 19, "read_status", state)
        self.registry.record_activity(older, approved, self.registry.begin_activity(older))
        retained = self.registry.recovery(ref)
        self.assertEqual(8, retained["state"]["version"])
        self.assertEqual(self.core.operation_id, retained["state"]["operationId"])
        self.assertEqual(20, retained["sequence"])
        decision = activity_envelope(self.start, 21, "decide", {**state, **approved})
        execution = activity_envelope(self.start, 22, "execute_existing_attempt", {**state, **approved})
        self.registry.record_activity(decision, {"decision": "execute_existing_attempt"}, self.registry.begin_activity(decision))
        self.registry.record_activity(execution, {}, self.registry.begin_activity(execution))
        retained = self.registry.recovery(ref)
        self.assertEqual(22, retained["sequence"])
        self.assertEqual(8, retained["state"]["version"])
        with self.assertRaises(WorkflowError):
            self.registry.record_activity(envelope, {**unknown, "operationId": str(uuid4())}, self.registry.begin_activity(envelope))

    async def test_history_limit_is_retained_and_resume_cannot_reset_budget(self):
        request = replace(self.start, activity_limit=8)
        ref = await self.runtime.start(request)
        await self.runtime.resume(ref)
        value = await self.runtime.inspect(ref)
        self.assertEqual("HISTORY_LIMIT", value["lifecycle"])
        self.assertEqual(self.core.intent_id, value["state"]["intentId"])
        for operation in (lambda: self.runtime.resume(ref), lambda: self.runtime.wake(ref, WakeNotice(str(uuid4()))), lambda: self.runtime.request_stop(ref)):
            with self.assertRaisesRegex(WorkflowError, "RECOVERY_REQUIRED"):
                await operation()
        self.assertEqual(8, (await self.runtime.inspect(ref))["sequence"])
        self.assertEqual(512, self.runtime.describe().max_completed_activities)
        self.assertEqual(16_384, self.runtime.describe().max_payload_bytes)
        self.assertEqual([], self.core.executions)

    async def test_conservative_decision_can_wait_before_intent_without_http(self):
        actual_run = self.activities.run
        def conservative(envelope):
            if envelope["kind"] == "decide":
                return {"decision": "wait"}
            return actual_run(envelope)
        self.activities.run = conservative
        ref = await self.runtime.start(self.start)
        self.assertEqual("WAITING", (await self.runtime.inspect(ref))["lifecycle"])
        self.assertEqual({}, (await self.runtime.resume(ref))["state"])
        self.assertEqual([], self.core.calls)

    async def test_template_and_exception_canaries_never_enter_envelope(self):
        self.registry.reserve(self.start); self.registry.claim_attempt(self.start.reference())
        env = activity_envelope(self.start, 0, "create_intent", {})
        env["state"]["credentialReference"] = self.core.canary
        with self.assertRaises(WorkflowError) as failure:
            self.activities.run(env)
        self.assertNotIn(self.core.canary, str(failure.exception))
        with self.assertRaises(WorkflowError):
            self.activities.run({**env, "start": {**self.start.data(), "template_version": "unknown"}})
        self.assertEqual([], self.core.calls)

    async def test_external_runtime_registration_needs_no_contract_change(self):
        adapter_id = "extension-" + uuid4().hex[:12]
        descriptor = RuntimeDescriptor(adapter_id, "1", frozenset(("restart-resume",)))
        RUNTIME_CATALOG.register(descriptor)
        external = replace(self.start, runtime=adapter_id, required_capabilities=("restart-resume",))
        self.assertEqual(external, start_from_data(external.data()))
        with self.assertRaises(WorkflowError):
            start_from_data(replace(external, required_capabilities=("durable-timer",)).data())
        # A transparent wrapper preserves the same async port and descriptor.
        class Wrapper:
            def __init__(self, implementation):
                self.implementation = implementation
            def __getattr__(self, name):
                return getattr(self.implementation, name)
        wrapped = Wrapper(self.runtime)
        self.assertEqual(self.runtime.describe(), wrapped.describe())
        self.assertEqual(await wrapped.start(self.start), self.start.reference())


class TemporalRegistrationTests(unittest.IsolatedAsyncioTestCase):
    async def test_recovery_output_from_decorated_store_is_validated(self):
        from types import SimpleNamespace
        from temporalio.client import WorkflowExecutionStatus
        from workflows.temporal_runtime import TemporalWorkflowRuntime
        root = Path(__file__).resolve().parents[1] / ".tools/runtime-tests"
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as directory:
            registry = SqliteRegistrationStore(Path(directory) / "registry.sqlite")
            try:
                start = StartWorkflow("tenant-local", "agent-demo", str(uuid4()), str(uuid4()), runtime="temporal")
                registry.reserve(start)
                class Store:
                    def __getattr__(self, name):
                        return getattr(registry, name)
                    def recovery(self, ref):
                        return {**registry.recovery(ref), "token": "RAW_TOKEN_CANARY"}
                class Client:
                    namespace = "default"
                    service_client = SimpleNamespace(config=SimpleNamespace(target_host="127.0.0.1:7233"))
                    def get_workflow_handle(self, ref):
                        return self
                    async def describe(self, **kwargs):
                        return SimpleNamespace(status=WorkflowExecutionStatus.TERMINATED)
                adapter = TemporalWorkflowRuntime(Client(), Store(), start.scope_ref, start.principal_id, start.run_id, "test-q")
                registry.bind(start.reference(), adapter._binding_id)
                registry.claim_attempt(start.reference())
                with self.assertRaisesRegex(WorkflowError, "INVALID_RUNTIME_OBSERVATION"):
                    await adapter.inspect(start.reference())
            finally:
                registry.close()

    async def test_sdk_activity_output_rejects_before_serialization_and_projection(self):
        from temporalio.exceptions import ApplicationError
        from workflows.temporal_runtime import registered_activity
        root = Path(__file__).resolve().parents[1] / ".tools/runtime-tests"
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as directory:
            registry = SqliteRegistrationStore(Path(directory) / "registry.sqlite")
            try:
                start = StartWorkflow("tenant-local", "agent-demo", str(uuid4()), str(uuid4()), runtime="temporal")
                registry.reserve(start); registry.claim_attempt(start.reference())
                class Decorated:
                    registration = registry
                    def run(self, envelope):
                        return {"decision": "create_intent", "token": "RAW_TOKEN_CANARY"}
                step = registered_activity(Decorated())
                with self.assertRaises(ApplicationError) as failure:
                    await step(activity_envelope(start, 0, "decide", {}))
                self.assertNotIn("CANARY", str(failure.exception))
                self.assertEqual(0, registry._db.execute("SELECT count(*) FROM workflow_recovery_observations").fetchone()[0])
            finally:
                registry.close()

    async def test_uncertain_start_then_history_absent_never_restarts(self):
        from workflows.temporal_runtime import TemporalWorkflowRuntime
        test_root = Path(__file__).resolve().parents[1] / ".tools/runtime-tests"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as directory:
            registry = SqliteRegistrationStore(Path(directory) / "registry.sqlite")
            start = StartWorkflow("tenant-local", "agent-demo", str(uuid4()), str(uuid4()), runtime="temporal")
            class Client:
                attempts = 0
                namespace = "default"
                from types import SimpleNamespace
                service_client = SimpleNamespace(config=SimpleNamespace(target_host="127.0.0.1:7233"))
                async def start_workflow(self, *args, **kwargs):
                    self.attempts += 1
                    raise OSError("RAW_TOKEN_EXCEPTION_CANARY")
                def get_workflow_handle(self, identity):
                    return self
                async def fetch_history(self, **kwargs):
                    raise OSError("NOT_FOUND_RAW_TOKEN_CANARY")
            client = Client()
            adapter = TemporalWorkflowRuntime(client, registry, start.scope_ref, start.principal_id, start.run_id, "test-q")
            for _ in range(2):
                with self.assertRaises(WorkflowError) as failure:
                    await adapter.start(start)
                self.assertNotIn("CANARY", str(failure.exception))
            self.assertEqual(1, client.attempts)
            self.assertTrue(registry.get(start.reference())["attempted"])
            registry.close()


if __name__ == "__main__":
    unittest.main()
