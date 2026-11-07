"""Deterministic native Temporal orchestration; graph and HTTP run only as activities."""
from __future__ import annotations

from datetime import timedelta
from temporalio import workflow
from temporalio.common import RetryPolicy

with workflow.unsafe.imports_passed_through():
    from workflows.runtime_contract import activity_envelope, safe_state, start_from_data, WakeNotice, MAX_POLL_SECONDS


@workflow.defn(name="payment-orchestration-1")
class PaymentOrchestration:
    def __init__(self):
        self._pause = False
        self._lifecycle = "RUNNING"
        self._state = {}
        self._sequence = 0
        self._pending = None
        self._wake_generation = 0
        self._consumed_generation = 0
        self._wake_ids = set()
        self._fingerprint = None
        self._poll_seconds = 1

    @workflow.signal
    def wake(self, notice: dict):
        if set(notice) != {"event_id", "reason"}:
            return
        WakeNotice(**notice).checked()
        # Bounded dedupe affects wake efficiency only; all payment reads are idempotent.
        if notice["event_id"] not in self._wake_ids:
            self._wake_generation += 1
            self._poll_seconds = 1
            if len(self._wake_ids) < 2048:
                self._wake_ids.add(notice["event_id"])

    @workflow.signal
    def request_stop(self):
        self._pause = True

    @workflow.signal
    def resume(self):
        self._pause = False
        self._wake_generation += 1
        self._poll_seconds = 1

    @workflow.query
    def observation(self) -> dict:
        return {"lifecycle": self._lifecycle, "state": self._state, "sequence": self._sequence,
                "outstandingActivityId": self._pending,
                "source": "RUNTIME_CACHE", "authoritativePaymentState": False}

    @workflow.run
    async def run(self, request: dict) -> dict:
        start = start_from_data(request)
        self._fingerprint = start.fingerprint()
        next_kind = "decide"
        while True:
            if self._sequence >= start.activity_limit:
                self._lifecycle = "HISTORY_LIMIT"
                # No new activities/timers and no signal can reset this budget.
                await workflow.wait_condition(lambda: False)
            if self._pause:
                self._lifecycle = "PAUSED"
                await workflow.wait_condition(lambda: not self._pause)
                next_kind = "read_status" if self._state.get("intentId") else "decide"
            envelope = activity_envelope(start, self._sequence, next_kind, self._state)
            self._pending = envelope["activityId"]
            self._lifecycle = "RUNNING"
            read_generation = self._wake_generation
            output = await workflow.execute_activity(
                "payment-step-1", envelope, activity_id=self._pending,
                start_to_close_timeout=timedelta(seconds=15),
                schedule_to_close_timeout=timedelta(seconds=60),
                retry_policy=RetryPolicy(initial_interval=timedelta(milliseconds=200),
                    maximum_interval=timedelta(seconds=2), maximum_attempts=4,
                    non_retryable_error_types=["PAYMENT_ACTIVITY_REJECTED"]),
            )
            self._pending = None
            self._sequence += 1
            if next_kind == "decide":
                decision = output["decision"]
                if decision == "finish":
                    self._lifecycle = "COMPLETED"
                    return self.observation()
                if decision == "wait":
                    if self._sequence >= start.activity_limit:
                        continue
                    self._lifecycle = "WAITING"
                    if not self._pause:
                        try:
                            await workflow.wait_condition(
                                lambda: self._pause or self._wake_generation > self._consumed_generation,
                                timeout=timedelta(seconds=self._poll_seconds))
                            self._poll_seconds = 1
                        except TimeoutError:
                            self._poll_seconds = min(MAX_POLL_SECONDS, self._poll_seconds * 2)
                    next_kind = "read_status" if self._state.get("intentId") else "decide"
                else:
                    next_kind = decision
            else:
                previous_version = self._state.get("version")
                self._state = safe_state({**self._state, **output})
                if next_kind == "read_status":
                    self._consumed_generation = read_generation
                    if self._state.get("version") != previous_version:
                        self._poll_seconds = 1
                next_kind = "decide" if next_kind in ("create_intent", "read_status") else "read_status"
