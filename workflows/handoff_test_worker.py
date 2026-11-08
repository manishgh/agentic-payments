"""Test-only fresh-process SQLite crash at an accepted financial command boundary."""
import argparse
import asyncio
import json

from workflows.runtime_contract import (CheckoutSource, StartWorkflow, EXTERNAL_PROFILE,
    EXTERNAL_TEMPLATE_VERSION, EXTERNAL_ACTIVITY_VERSION, start_from_data)
from workflows.runtime_logic import PaymentActivities
from workflows.runtime_registry import SqliteRegistrationStore
from workflows.sqlite_runtime import SqliteWorkflowRuntime
from workflows.temporal_test_worker import CrashAfterAcceptanceClient


async def run(args):
    registry = SqliteRegistrationStore(args.registry)
    try:
        reference = StartWorkflow("tenant-local", "agent-demo", args.run_id, args.workflow_id).reference()
        if args.command == "start":
            start = StartWorkflow("tenant-local", "agent-demo", args.run_id, args.workflow_id,
                profile=EXTERNAL_PROFILE, scenario=args.scenario,
                template_version=EXTERNAL_TEMPLATE_VERSION, activity_version=EXTERNAL_ACTIVITY_VERSION,
                checkout_source=CheckoutSource(args.purchase_id, args.purchase_revision, args.response_reference))
        else:
            start = start_from_data(json.loads(registry.get(reference)["start"]))
        runtime = SqliteWorkflowRuntime(registry, PaymentActivities(CrashAfterAcceptanceClient(args), registry))
        if args.command == "start":
            await runtime.start(start)
        else:
            await runtime.resume(start.reference())
    finally:
        registry.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("start", "resume"))
    for name in ("registry", "api-url", "run-id", "workflow-id", "marker", "crash-step"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--purchase-id")
    parser.add_argument("--purchase-revision", type=int)
    parser.add_argument("--response-reference")
    parser.add_argument("--scenario", default="success")
    args = parser.parse_args()
    args.profile = EXTERNAL_PROFILE
    try:
        asyncio.run(run(args))
    except Exception:
        print("TEST_HANDOFF_WORKER_UNAVAILABLE")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
