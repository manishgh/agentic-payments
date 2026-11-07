"""Local control/worker entry point. All runtime connections are trusted loopback."""
from __future__ import annotations

import argparse
import asyncio
from dataclasses import asdict
import json
import os
from pathlib import Path
import re
from uuid import uuid4

from workflows.payment_workflow import LocalPaymentClient, PROFILE, PROFILES, SCENARIOS, WorkflowError
from workflows.runtime_contract import StartWorkflow, WakeNotice, label, RUNTIME_CATALOG
from workflows.runtime_logic import PaymentActivities
from workflows.runtime_registry import SqliteRegistrationStore
from workflows.sqlite_runtime import SqliteWorkflowRuntime


def loopback_target(value):
    if not isinstance(value, str) or re.fullmatch(r"127\.0\.0\.1:[0-9]{4,5}", value) is None:
        raise WorkflowError("INVALID_RUNTIME_ENDPOINT")
    if not 1024 <= int(value.rsplit(":", 1)[1]) <= 65535:
        raise WorkflowError("INVALID_RUNTIME_ENDPOINT")
    return value


async def temporal(args, registry, start):
    from temporalio.client import Client
    from temporalio.worker import Worker
    from workflows.temporal_runtime import TemporalWorkflowRuntime, registered_activity
    from workflows.temporal_workflow import PaymentOrchestration
    client = await Client.connect(loopback_target(args.server))
    adapter = TemporalWorkflowRuntime(client, registry, "tenant-local", "agent-demo", args.run_id, args.queue)
    if args.command == "worker":
        core = LocalPaymentClient(args.api_url, os.getenv("PAYMENTS_GRAPH_TOKEN", ""), args.run_id, args.profile)
        activities = PaymentActivities(core, registry)
        async with Worker(client, task_queue=args.queue, workflows=[PaymentOrchestration], activities=[registered_activity(activities)]):
            print("Local Temporal worker ready.", flush=True)
            await asyncio.Event().wait()
    ref = start.reference()
    if args.command == "start":
        return {"reference": asdict(await adapter.start(start))}
    if args.command == "inspect":
        return await adapter.inspect(ref)
    if args.command == "wake":
        return await adapter.wake(ref, WakeNotice(args.event_id, "manual"))
    if args.command == "stop":
        return await adapter.request_stop(ref)
    return await adapter.resume(ref)


async def local(args, registry, start):
    if args.command == "worker":
        raise WorkflowError("UNSUPPORTED_RUNTIME_CAPABILITY")
    core = LocalPaymentClient(args.api_url, os.getenv("PAYMENTS_GRAPH_TOKEN", ""), args.run_id, args.profile)
    adapter = SqliteWorkflowRuntime(registry, PaymentActivities(core, registry))
    ref = start.reference()
    if args.command == "start":
        return {"reference": asdict(await adapter.start(start)), **await adapter.inspect(ref)}
    if args.command == "inspect":
        return await adapter.inspect(ref)
    if args.command == "wake":
        return await adapter.wake(ref, WakeNotice(args.event_id, "manual"))
    if args.command == "stop":
        return await adapter.request_stop(ref)
    return await adapter.resume(ref)


def main():
    parser = argparse.ArgumentParser(description="Synthetic durable payment workflow runtime.")
    parser.add_argument("command", choices=("start", "inspect", "wake", "stop", "resume", "worker"))
    parser.add_argument("--runtime", choices=("sqlite", "temporal"), default="sqlite")
    parser.add_argument("--workflow-id")
    parser.add_argument("--run-id", default=os.getenv("PAYMENTS_RUN_ID"))
    parser.add_argument("--profile", choices=PROFILES, default=PROFILE)
    parser.add_argument("--scenario", choices=SCENARIOS, default="success")
    parser.add_argument("--registry", default=str(Path(__file__).resolve().parents[1] / ".tools/workflow-registry.sqlite"))
    parser.add_argument("--api-url", default="http://127.0.0.1:8080")
    parser.add_argument("--server", default="127.0.0.1:7233")
    parser.add_argument("--queue", default="payments-local-v1")
    parser.add_argument("--event-id", default=str(uuid4()))
    args = parser.parse_args()
    registry = None
    try:
        label(args.queue)
        # Worker identity is trusted configuration, never supplied through a graph payload.
        start = StartWorkflow("tenant-local", "agent-demo", args.run_id, args.workflow_id or str(uuid4()),
                              runtime=args.runtime, scenario=args.scenario, profile=args.profile)
        if args.command != "worker" and args.workflow_id is None:
            raise WorkflowError("WORKFLOW_ID_REQUIRED")
        start.checked(RUNTIME_CATALOG.resolve(args.runtime).capabilities)
        path = Path(args.registry)
        path.parent.mkdir(parents=True, exist_ok=True)
        registry = SqliteRegistrationStore(path)
        if args.runtime == "temporal":
            result = asyncio.run(temporal(args, registry, start))
        else:
            result = asyncio.run(local(args, registry, start))
        print(json.dumps(result, sort_keys=True))
        return 0
    except WorkflowError as failure:
        print(str(failure))
        return 2
    except Exception:
        print("WORKFLOW_RUNTIME_UNAVAILABLE")
        return 2
    finally:
        if registry is not None:
            registry.close()


if __name__ == "__main__":
    raise SystemExit(main())
