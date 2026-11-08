"""Single-driver local scheduler adapter. SQLite is not a distributed task service."""
from __future__ import annotations

import json
import asyncio

from workflows.runtime_contract import (
    SQLITE_CAPABILITIES, RuntimeDescriptor, activity_envelope, fail, safe_state,
    start_from_data, validate_activity_result, validate_runtime_observation,
)
from workflows.runtime_trace import invoke_recorded, require_trace_writer


class _SqliteDriver:
    def __init__(self, registration, activities):
        self.registration = registration
        self.activities = activities

    def describe(self):
        require_trace_writer(self.registration)
        return RuntimeDescriptor("sqlite", "1", SQLITE_CAPABILITIES, initial_poll_seconds=0, max_poll_seconds=0)

    def _access(self, ref):
        client = self.activities.client
        if (ref.runtime != "sqlite" or client.run_id != ref.run_id
                or client.scope_ref != ref.scope_ref or client.principal_id != ref.principal_id):
            fail("WORKFLOW_SCOPE_DENIED")
        return self.registration.get(ref)

    def start(self, request):
        request.checked(self.describe().capabilities)
        ref = request.reference()
        client = self.activities.client
        if (request.runtime != "sqlite" or client.run_id != request.run_id
                or client.scope_ref != request.scope_ref or client.principal_id != request.principal_id):
            fail("WORKFLOW_SCOPE_DENIED")
        row = self.registration.reserve(request)
        if self.registration.claim_attempt(ref):
            self.registration.save(ref, {"state": {}, "sequence": 0, "next": "decide", "pending": None, "lifecycle": "RUNNING"})
            self.registration.confirm(ref)
            self._drive(ref)
        elif not row["confirmed"]:
            fail("WORKFLOW_RECOVERY_REQUIRED")
        return ref

    def inspect(self, ref):
        row = self._access(ref)
        progress = json.loads(row["progress"])
        if not row["confirmed"] or not progress:
            fail("WORKFLOW_RECOVERY_REQUIRED")
        lifecycle = progress["lifecycle"]
        if row["pause_requested"] and lifecycle not in ("COMPLETED", "HISTORY_LIMIT"):
            lifecycle = "PAUSED" if progress["pending"] is None else "STOP_REQUESTED"
        return validate_runtime_observation(ref, {"lifecycle": lifecycle, "state": safe_state(progress["state"]), "sequence": progress["sequence"],
                "outstandingActivityId": progress["pending"]["activityId"] if progress["pending"] else None,
                "source": "RUNTIME_CACHE", "authoritativePaymentState": False})

    def wake(self, ref, notice):
        notice.checked()
        self._access(ref)
        if self.inspect(ref)["lifecycle"] == "HISTORY_LIMIT":
            fail("WORKFLOW_RECOVERY_REQUIRED")
        self.registration.wake(ref, notice)
        return {"requested": True}  # Local driver still requires an explicit resume.

    def request_stop(self, ref):
        row = self._access(ref)
        progress = json.loads(row["progress"])
        if progress["lifecycle"] == "HISTORY_LIMIT":
            fail("WORKFLOW_RECOVERY_REQUIRED")
        if progress["lifecycle"] == "COMPLETED":
            return {"requested": False, "applied": False, "terminal": True}
        row = self.registration.pause(ref, True)
        progress = json.loads(row["progress"])
        if progress["lifecycle"] == "HISTORY_LIMIT":
            fail("WORKFLOW_RECOVERY_REQUIRED")
        return {"requested": True, "applied": progress["pending"] is None, "terminal": False}

    def resume(self, ref):
        row = self._access(ref)
        progress = json.loads(row["progress"])
        if progress["lifecycle"] == "HISTORY_LIMIT":
            fail("WORKFLOW_RECOVERY_REQUIRED")
        if progress["lifecycle"] == "COMPLETED":
            return self.inspect(ref)
        self.registration.pause(ref, False)
        if progress["pending"] is None and progress["state"].get("intentId"):
            progress["next"] = "read_status"
            self.registration.save(ref, progress)
        self._drive(ref)
        return self.inspect(ref)

    def _drive(self, ref):
        for _ in range(64):
            row = self._access(ref)
            progress = json.loads(row["progress"])
            start = start_from_data(json.loads(row["start"]))
            if progress["sequence"] >= start.activity_limit:
                progress["lifecycle"] = "HISTORY_LIMIT"
                self.registration.save(ref, progress)
                return
            if row["pause_requested"]:
                progress["lifecycle"] = "PAUSED"
                self.registration.save(ref, progress)
                return
            envelope = progress["pending"] or activity_envelope(start, progress["sequence"], progress["next"], progress["state"])
            if not self.registration.stage_activity(ref, progress, envelope):
                return
            # The exact envelope and pause ordering commit BEFORE any side effect.
            output = invoke_recorded(self.activities, envelope)
            progress["pending"] = None
            progress["sequence"] += 1
            if envelope["kind"] == "decide":
                decision = output["decision"]
                if decision in ("wait", "finish"):
                    progress["lifecycle"] = ("HISTORY_LIMIT" if progress["sequence"] >= start.activity_limit else "WAITING") if decision == "wait" else "COMPLETED"
                    progress["next"] = "read_status" if progress["state"].get("intentId") else "decide"
                    self.registration.save(ref, progress)
                    return
                progress["next"] = decision
            else:
                progress["state"] = safe_state({**progress["state"], **output})
                progress["next"] = "decide" if envelope["kind"] in ("create_intent", "read_status") else "read_status"
            self.registration.save(ref, progress)
        fail("WORKFLOW_RECOVERY_REQUIRED")


class SqliteWorkflowRuntime:
    """Same asynchronous port as external runtimes, with one local driver."""
    def __init__(self, registration, activities):
        self._driver = _SqliteDriver(registration, activities)

    def describe(self):
        return self._driver.describe()

    async def start(self, request):
        return await asyncio.to_thread(self._driver.start, request)

    async def inspect(self, reference):
        return await asyncio.to_thread(self._driver.inspect, reference)

    async def wake(self, reference, notice):
        return await asyncio.to_thread(self._driver.wake, reference, notice)

    async def request_stop(self, reference):
        return await asyncio.to_thread(self._driver.request_stop, reference)

    async def resume(self, reference):
        return await asyncio.to_thread(self._driver.resume, reference)
