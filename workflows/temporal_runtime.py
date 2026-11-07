"""Temporal adapter to the runtime responsibility port, with independent registration."""
from __future__ import annotations

from datetime import timedelta
import hashlib
import json

from temporalio import activity
from temporalio.client import WorkflowExecutionStatus
from temporalio.common import WorkflowIDConflictPolicy, WorkflowIDReusePolicy
from temporalio.exceptions import ApplicationError

from workflows.payment_workflow import WorkflowError
from workflows.runtime_contract import (
    TEMPORAL_CAPABILITIES, RuntimeDescriptor, encoded, fail, safe_state, start_from_data, validate_activity_result, validate_runtime_observation,
)
from workflows.temporal_workflow import PaymentOrchestration


def registered_activity(activities):
    @activity.defn(name="payment-step-1")
    async def payment_step(envelope: dict) -> dict:
        # Threaded HTTP avoids blocking the worker's event loop. The envelope
        # and sanitized result are the only values sent through Temporal.
        import asyncio
        failure = None
        try:
            output = validate_activity_result(envelope, await asyncio.to_thread(activities.run, envelope))
            await asyncio.to_thread(activities.registration.record_activity, envelope, output)
            return output
        except WorkflowError as error:
            failure = str(error)
        except Exception:
            failure = "PAYMENT_ACTIVITY_UNAVAILABLE"
        retryable = failure == "PAYMENT_ACTIVITY_UNAVAILABLE"
        raise ApplicationError("Payment activity is unavailable." if retryable else "Payment activity was rejected.",
                               type="PAYMENT_ACTIVITY_UNAVAILABLE" if retryable else "PAYMENT_ACTIVITY_REJECTED",
                               non_retryable=not retryable) from None
    return payment_step


class TemporalWorkflowRuntime:
    def __init__(self, client, registration, scope_ref, principal_id, run_id, task_queue):
        from workflows.runtime_contract import label
        from workflows.payment_workflow import canonical_uuid
        label(scope_ref); label(principal_id); label(task_queue); canonical_uuid(run_id)
        self.client, self.registration = client, registration
        self.scope_ref, self.principal_id, self.run_id, self.task_queue = scope_ref, principal_id, run_id, task_queue
        configuration = client.service_client.config
        self._binding_id = hashlib.sha256(encoded(["temporal", "payment-orchestration-1", configuration.target_host,
                                                   client.namespace, task_queue]).encode("ascii")).hexdigest()

    def describe(self):
        return RuntimeDescriptor("temporal", "1.35.0", TEMPORAL_CAPABILITIES)

    def _access(self, ref):
        if (ref.runtime != "temporal" or ref.scope_ref != self.scope_ref
                or ref.principal_id != self.principal_id or ref.run_id != self.run_id):
            fail("WORKFLOW_SCOPE_DENIED")
        row = self.registration.get(ref)
        self.registration.bind(ref, self._binding_id)
        return row

    async def _confirm_history(self, ref, fingerprint):
        history = await self.client.get_workflow_handle(ref.runtime_id).fetch_history(rpc_timeout=timedelta(seconds=5))
        if not history.events:
            fail("WORKFLOW_RECOVERY_REQUIRED")
        payload = history.events[0].workflow_execution_started_event_attributes.input.payloads
        values = await self.client.data_converter.decode(payload, [dict])
        if len(values) != 1 or start_from_data(values[0]).fingerprint() != fingerprint:
            fail("WORKFLOW_REGISTRATION_CONFLICT")
        self.registration.confirm(ref)

    async def _start_backend(self, start):
        return await self.client.start_workflow(PaymentOrchestration.run, start.data(),
            id=start.reference().runtime_id, task_queue=self.task_queue,
            id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
            id_conflict_policy=WorkflowIDConflictPolicy.FAIL, rpc_timeout=timedelta(seconds=10))

    async def start(self, request):
        request.checked(self.describe().capabilities)
        ref = request.reference()
        if (ref.runtime != "temporal" or ref.scope_ref != self.scope_ref
                or ref.principal_id != self.principal_id or ref.run_id != self.run_id):
            fail("WORKFLOW_SCOPE_DENIED")
        row = self.registration.reserve(request)
        self.registration.bind(ref, self._binding_id)
        first = self.registration.claim_attempt(ref)
        failed = False
        try:
            if first:
                await self._start_backend(request)
            await self._confirm_history(ref, row["fingerprint"])
        except WorkflowError:
            raise
        except Exception:
            failed = True
        if failed:
            fail("WORKFLOW_START_UNCONFIRMED" if first else "WORKFLOW_RECOVERY_REQUIRED")
        return ref

    async def _open(self, ref, allow_completed=False, read_only=False):
        row = self._access(ref)
        if not row["attempted"]:
            fail("WORKFLOW_RECOVERY_REQUIRED")
        failed = False
        try:
            handle = self.client.get_workflow_handle(ref.runtime_id)
            description = await handle.describe(rpc_timeout=timedelta(seconds=5))
        except Exception:
            failed = True
        if failed:
            fail("WORKFLOW_RECOVERY_REQUIRED")
        allowed = {WorkflowExecutionStatus.RUNNING}
        if allow_completed:
            allowed.add(WorkflowExecutionStatus.COMPLETED)
        if read_only:
            allowed |= {WorkflowExecutionStatus.FAILED, WorkflowExecutionStatus.CANCELED,
                        WorkflowExecutionStatus.TERMINATED, WorkflowExecutionStatus.TIMED_OUT}
        if description.status not in allowed:
            fail("WORKFLOW_RECOVERY_REQUIRED")
        return handle, description.status

    async def inspect(self, ref):
        self._access(ref)
        try:
            handle, status = await self._open(ref, True, read_only=True)
        except WorkflowError as error:
            if str(error) == "WORKFLOW_RECOVERY_REQUIRED":
                return self._recovery(ref)
            raise
        if status not in (WorkflowExecutionStatus.RUNNING, WorkflowExecutionStatus.COMPLETED):
            return self._recovery(ref)
        failed = False
        try:
            value = await handle.query(PaymentOrchestration.observation, rpc_timeout=timedelta(seconds=5))
            value = validate_runtime_observation(ref, value)
            self.registration.save(ref, value)
            return value
        except WorkflowError:
            raise
        except Exception:
            failed = True
        if failed:
            fail("WORKFLOW_RUNTIME_UNAVAILABLE")

    async def _signal(self, ref, method, argument=None):
        handle, _ = await self._open(ref)
        row = self._access(ref)
        limit = start_from_data(json.loads(row["start"])).activity_limit
        # This check must not need a worker query: wakes remain deliverable
        # while all workers are down. The projection commits before completion.
        if self._recovery(ref)["sequence"] >= limit - 1:
            fail("WORKFLOW_RECOVERY_REQUIRED")
        failed = False
        try:
            if argument is None:
                await handle.signal(method, rpc_timeout=timedelta(seconds=5))
            else:
                await handle.signal(method, argument, rpc_timeout=timedelta(seconds=5))
        except Exception:
            failed = True
        if failed:
            fail("WORKFLOW_RUNTIME_UNAVAILABLE")

    def _recovery(self, ref):
        return validate_runtime_observation(ref, self.registration.recovery(ref), recovery=True)

    async def wake(self, ref, notice):
        body = notice.checked()  # Before SDK serialization.
        await self._signal(ref, PaymentOrchestration.wake, body)
        return {"requested": True}

    async def request_stop(self, ref):
        await self._signal(ref, PaymentOrchestration.request_stop)
        return {"requested": True, "applied": False, "terminal": False}

    async def resume(self, ref):
        await self._signal(ref, PaymentOrchestration.resume)
        return {"requested": True, "applied": False}
