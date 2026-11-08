"""Local runtime controls and separate read-only debug/recorded-step replay commands."""
from __future__ import annotations

import argparse
import asyncio
from dataclasses import asdict
import json
import os
from pathlib import Path
import re
from uuid import uuid4

from workflows.payment_workflow import (
    LocalPaymentClient, PROFILE, EXTERNAL_PROFILE, SCENARIOS, WorkflowError,
    canonical_uuid, positive_version,
)
from workflows.runtime_contract import (
    CheckoutSource, StartWorkflow, WakeNotice, label, RUNTIME_CATALOG, RUNTIME_PROFILES,
    EXTERNAL_TEMPLATE_VERSION, EXTERNAL_ACTIVITY_VERSION, EXTERNAL_HANDOFF_CAPABILITY,
    start_from_data,
)
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
    activities = None
    if args.command == "worker":
        core = LocalPaymentClient(args.api_url, os.getenv("PAYMENTS_GRAPH_TOKEN", ""), args.run_id, args.profile)
        activities = PaymentActivities(core, registry)
    client = await Client.connect(loopback_target(args.server))
    adapter = TemporalWorkflowRuntime(client, registry, "tenant-local", "agent-demo", args.run_id, args.queue)
    if args.command == "worker":
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


def prepare_start(args):
    """Validate trusted CLI selection before opening a registry or contacting a scheduler."""
    label(args.queue)
    canonical_uuid(args.run_id)
    descriptor = RUNTIME_CATALOG.resolve(args.runtime)
    if args.runtime == "temporal":
        loopback_target(args.server)
    supplied = (args.purchase_id, args.purchase_revision, args.response_reference)
    if args.command != "start" and any(value is not None for value in supplied):
        raise WorkflowError("CHECKOUT_SOURCE_IS_START_ONLY")
    if args.command == "worker":
        if args.runtime != "temporal":
            raise WorkflowError("UNSUPPORTED_RUNTIME_CAPABILITY")
        args.profile = args.profile or PROFILE
        if args.profile not in RUNTIME_PROFILES:
            raise WorkflowError("INVALID_RUNTIME_INPUT")
        if args.profile == EXTERNAL_PROFILE and EXTERNAL_HANDOFF_CAPABILITY not in descriptor.capabilities:
            raise WorkflowError("UNSUPPORTED_RUNTIME_CAPABILITY")
        if args.workflow_id is not None:
            canonical_uuid(args.workflow_id)
        return None
    if args.workflow_id is None:
        raise WorkflowError("WORKFLOW_ID_REQUIRED")
    canonical_uuid(args.workflow_id)
    if args.command != "start":
        return None
    args.profile = args.profile or PROFILE
    options = {}
    if args.profile == EXTERNAL_PROFILE:
        revision = args.purchase_revision
        if (any(value is None for value in supplied) or not isinstance(revision, str)
                or re.fullmatch(r"[1-9][0-9]{0,9}", revision) is None):
            raise WorkflowError("INVALID_CHECKOUT_SOURCE")
        options = {
            "checkout_source": CheckoutSource(canonical_uuid(args.purchase_id), positive_version(int(revision)),
                                               canonical_uuid(args.response_reference)).checked(),
            "template_version": EXTERNAL_TEMPLATE_VERSION,
            "activity_version": EXTERNAL_ACTIVITY_VERSION,
        }
    elif any(value is not None for value in supplied):
        raise WorkflowError("INVALID_CHECKOUT_SOURCE")
    return StartWorkflow("tenant-local", "agent-demo", args.run_id, args.workflow_id,
                         runtime=args.runtime, scenario=args.scenario, profile=args.profile,
                         **options).checked(descriptor.capabilities)


def retained_start(args, registry):
    # This historical value derives only the existing scoped reference. Controls
    # never invent an external start or reselect source, profile or template.
    ref = StartWorkflow("tenant-local", "agent-demo", args.run_id, args.workflow_id,
                        runtime=args.runtime).reference()
    start = start_from_data(json.loads(registry.get(ref)["start"]))
    if args.profile is not None and args.profile != start.profile:
        raise WorkflowError("WORKFLOW_REGISTRATION_CONFLICT")
    args.profile = start.profile
    return start


def diagnostics(args):
    """No writer, payment client, activity implementation or scheduler is constructed here."""
    from workflows.runtime_replay import (
        MAX_EXPORT_BYTES, SqliteWorkflowTraceReader, debug_trace, load_trace, replay_trace,
    )
    if any(value is not None for value in (args.purchase_id, args.purchase_revision, args.response_reference)):
        raise WorkflowError("CHECKOUT_SOURCE_IS_START_ONLY")
    if args.command == "replay":
        if args.trace is None:
            raise WorkflowError("WORKFLOW_TRACE_REQUIRED")
        report = replay_trace(load_trace(args.trace))
        return report, 0 if report["verdict"] in ("VERIFIED_RECORDED_STEPS", "VERIFIED_WITH_RECORDED_FAILURE") else 2
    canonical_uuid(args.run_id)
    if args.workflow_id is None:
        raise WorkflowError("WORKFLOW_ID_REQUIRED")
    canonical_uuid(args.workflow_id)
    ref = StartWorkflow("tenant-local", "agent-demo", args.run_id, args.workflow_id,
                        runtime=args.runtime).reference()
    with SqliteWorkflowTraceReader(args.registry) as reader:
        snapshot = reader.read_trace(ref)
    report = debug_trace(snapshot)
    accepted = report["verdict"] not in ("MISMATCH", "UNSUPPORTED_VERSION")
    if args.export is not None and accepted:
        # The validated snapshot contains only the closed safe journal schema.
        # Exclusive creation preserves existing files; no parent directory is created.
        data = json.dumps(snapshot, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")
        if len(data) > MAX_EXPORT_BYTES:
            raise WorkflowError("WORKFLOW_TRACE_LIMIT")
        try:
            with Path(args.export).open("xb") as output:
                output.write(data)
        except FileExistsError:
            raise WorkflowError("WORKFLOW_TRACE_EXPORT_EXISTS") from None
        except (OSError, ValueError):
            raise WorkflowError("WORKFLOW_TRACE_EXPORT_UNAVAILABLE") from None
        report = {**report, "exported": True}
    return report, 0 if accepted else 2


def diagnostic_failure(failure):
    # Existing reader/validator boundaries use fixed WorkflowError values. Only
    # exact declared codes may cross this CLI boundary; arbitrary text is discarded.
    declared = {
        "WORKFLOW_TRACE_INCOMPLETE", "WORKFLOW_TRACE_UNAVAILABLE", "WORKFLOW_TRACE_LIMIT",
        "WORKFLOW_TRACE_EXPORT_EXISTS", "WORKFLOW_TRACE_EXPORT_UNAVAILABLE", "WORKFLOW_TRACE_REQUIRED",
        "WORKFLOW_NOT_REGISTERED", "WORKFLOW_SCOPE_DENIED", "WORKFLOW_ID_REQUIRED",
        "CHECKOUT_SOURCE_IS_START_ONLY", "TRACE_EXPORT_IS_DEBUG_ONLY", "TRACE_INPUT_IS_REPLAY_ONLY",
        "WORKFLOW_TRACE_UNSUPPORTED_VERSION", "WORKFLOW_TRACE_INVALID",
    }
    code = failure.args[0] if type(failure) is WorkflowError and len(failure.args) == 1 else None
    code = code if type(code) is str and code in declared else "WORKFLOW_TRACE_UNAVAILABLE"
    verdict = {"WORKFLOW_TRACE_UNSUPPORTED_VERSION": "UNSUPPORTED_VERSION", "WORKFLOW_TRACE_INVALID": "MISMATCH"}.get(code, "INCOMPLETE")
    return {"verdict": verdict, "authoritativePaymentState": False, "schedulerConsumptionVerified": False,
            "decisionsReplayed": False,
            "failure": {"code": code, "phase": "PREPARATION", "retryable": False}}


def live_failure(failure):
    """Preserve the journal wrapper's closed primary and secondary classifications."""
    from workflows.runtime_trace import TraceActivityError, safe_failure
    if type(failure) is not TraceActivityError:
        return None
    try:
        return failure.diagnostic()
    except Exception:
        # A malformed replacement error cannot inject exception text into CLI output.
        return {"failure": safe_failure("UNEXPECTED_ACTIVITY_FAILURE", "JOURNAL")}


def main():
    parser = argparse.ArgumentParser(description=(
        "Synthetic durable payment workflow runtime. debug/export and replay are offline diagnostics; "
        "resume performs live same-identity recovery with current financial authorization."))
    parser.add_argument("command", choices=("start", "inspect", "wake", "stop", "resume", "worker", "debug", "replay"))
    parser.add_argument("--runtime", choices=("sqlite", "temporal"), default="sqlite")
    parser.add_argument("--workflow-id")
    parser.add_argument("--run-id", default=os.getenv("PAYMENTS_RUN_ID"))
    parser.add_argument("--profile", choices=RUNTIME_PROFILES)
    parser.add_argument("--scenario", choices=SCENARIOS, default="success")
    parser.add_argument("--registry", default=str(Path(__file__).resolve().parents[1] / ".tools/workflow-registry.sqlite"))
    parser.add_argument("--api-url", default="http://127.0.0.1:8080")
    parser.add_argument("--server", default="127.0.0.1:7233")
    parser.add_argument("--queue", default="payments-local-v1")
    parser.add_argument("--event-id", default=str(uuid4()))
    parser.add_argument("--purchase-id", help="Start only: canonical retained commerce purchase UUID.")
    parser.add_argument("--purchase-revision", help="Start only: selected positive observed purchase revision.")
    parser.add_argument("--response-reference", help="Start only: canonical retained response UUID.")
    parser.add_argument("--export", help="Debug only: exclusively create a bounded safe local snapshot file.")
    parser.add_argument("--trace", help="Replay only: read an exported snapshot without core or scheduler access.")
    args = parser.parse_args()
    registry = None
    try:
        if args.export is not None and args.command != "debug":
            raise WorkflowError("TRACE_EXPORT_IS_DEBUG_ONLY")
        if args.trace is not None and args.command != "replay":
            raise WorkflowError("TRACE_INPUT_IS_REPLAY_ONLY")
        if args.command in ("debug", "replay"):
            result, status = diagnostics(args)
            print(json.dumps(result, sort_keys=True))
            return status
        start = prepare_start(args)
        path = Path(args.registry)
        path.parent.mkdir(parents=True, exist_ok=True)
        registry = SqliteRegistrationStore(path)
        if args.command not in ("start", "worker"):
            start = retained_start(args, registry)
        if args.runtime == "temporal":
            result = asyncio.run(temporal(args, registry, start))
        else:
            result = asyncio.run(local(args, registry, start))
        print(json.dumps(result, sort_keys=True))
        return 0
    except WorkflowError as failure:
        if args.command in ("debug", "replay"):
            print(json.dumps(diagnostic_failure(failure), sort_keys=True))
        else:
            report = live_failure(failure)
            print(json.dumps(report, sort_keys=True) if report is not None else str(failure))
        return 2
    except Exception:
        if args.command in ("debug", "replay"):
            print(json.dumps(diagnostic_failure(None), sort_keys=True))
        else:
            print("WORKFLOW_RUNTIME_UNAVAILABLE")
        return 2
    finally:
        if registry is not None:
            registry.close()


if __name__ == "__main__":
    raise SystemExit(main())
