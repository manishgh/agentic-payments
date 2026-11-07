"""Test-only abrupt loss after an HTTP acceptance, before activity completion."""
from __future__ import annotations

import argparse
import asyncio
import os
from pathlib import Path

from temporalio.client import Client
from temporalio.worker import Worker

from workflows.payment_workflow import LocalPaymentClient, PROFILE
from workflows.runtime_cli import loopback_target
from workflows.runtime_logic import PaymentActivities
from workflows.runtime_registry import SqliteRegistrationStore
from workflows.temporal_runtime import registered_activity
from workflows.temporal_workflow import PaymentOrchestration


class CrashAfterAcceptanceClient(LocalPaymentClient):
    def __init__(self, args):
        super().__init__(args.api_url, os.environ["PAYMENTS_GRAPH_TOKEN"], args.run_id, args.profile)
        self._marker, self._step = Path(args.marker), args.crash_step

    def _request(self, method, path, key=None, body=None):
        result = super()._request(method, path, key, body)
        step = "create_intent" if path == "/intents" else "quote_intent" if path.endswith("/quote") else "execute_existing_attempt" if path.endswith("/execute") else None
        if method == "POST" and step == self._step and not self._marker.exists():
            # Safe marker is the deterministic acceptance barrier; no payload/token.
            self._marker.write_text(step, encoding="ascii")
            os._exit(73)
        return result


class FaultingActivities(PaymentActivities):
    """Trusted test decorator intentionally violating the result contract."""
    def __init__(self, client, registration, fault):
        super().__init__(client, registration)
        self.fault = fault

    def run(self, envelope):
        result = super().run(envelope)
        if self.fault == "extra-token":
            return {**result, "token": "RAW_SIGNED_ARTIFACT_KEY_EXCEPTION_CANARY"}
        if self.fault == "unknown-decision" and envelope["kind"] == "decide":
            return {"decision": "RAW_SIGNED_ARTIFACT_KEY_EXCEPTION_CANARY"}
        if self.fault == "wrong-intent" and envelope["kind"] == "read_status":
            from uuid import uuid4
            return {**result, "intentId": str(uuid4())}
        return result


async def run(args):
    client = await Client.connect(loopback_target(args.server))
    registry = SqliteRegistrationStore(args.registry)
    try:
        core = CrashAfterAcceptanceClient(args)
        activities = FaultingActivities(core, registry, args.fault_result) if args.fault_result else PaymentActivities(core, registry)
        async with Worker(client, task_queue=args.queue, workflows=[PaymentOrchestration], activities=[registered_activity(activities)]):
            print("Local Temporal worker ready.", flush=True)
            await asyncio.Event().wait()
    finally:
        registry.close()


def main():
    parser = argparse.ArgumentParser()
    for name in ("server", "registry", "queue", "run-id", "api-url", "marker"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--profile", default=PROFILE)
    faults = parser.add_mutually_exclusive_group(required=True)
    faults.add_argument("--crash-step", choices=("create_intent", "quote_intent", "execute_existing_attempt"))
    faults.add_argument("--fault-result", choices=("extra-token", "unknown-decision", "wrong-intent"))
    args = parser.parse_args()
    try:
        asyncio.run(run(args))
    except Exception:
        print("TEST_WORKER_UNAVAILABLE", flush=True)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
