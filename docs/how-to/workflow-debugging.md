# Workflow debugging and recorded-step replay

[Documentation](../README.md) / [How to](README.md)
The modern SQLite and Temporal payment runtimes retain a bounded durable diagnostic journal for new workflows. It records the safe input of each logical activity, individual invocation attempts, validated results and fixed failure classifications. This works independently of optional logs and metrics. Use it to locate a failed or interrupted step and inspect what the workflow observed.

The journal is orchestration evidence. It does not replace the payment ledger, establish financial success, or prove which late retry result a scheduler consumed. Temporal's native history replay remains the separate check of scheduler behavior. Modern-runtime registrations created before journaling report unavailable historical coverage. The older standalone payment and commerce checkpoint files are outside this reader's schema.

## Inspect and export

Keep the original registry, run UUID and workflow UUID. The following commands open that registry read-only. They require no graph bearer token, running Java service or Temporal connection:

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli debug --registry .tools/workflow-registry.sqlite --run-id $runId --workflow-id $workflowId
.venv/Scripts/python.exe -m workflows.runtime_cli debug --registry .tools/workflow-registry.sqlite --run-id $runId --workflow-id $workflowId --export .tools/workflow-trace.json
```

For a Temporal workflow, also pass `--runtime temporal`. The reader checks the retained scope, principal and runtime identity. Export creates a new local file and refuses to overwrite an existing one. Keep exports private: their safe allowlist includes payment identifiers, money and external checkout source references. They exclude credentials, raw checkout bytes, merchant session IDs, signed artifacts, keys, arbitrary exception text and stack traces.

The diagnostic output separates logical steps from invocation attempts. An abrupt process loss may leave an invocation without a terminal record; that means its outcome was not recorded, not that its financial command failed. A later attempt can recover the original result through the same payment idempotency key. A known failure records its safe primary code, phase and retryability; an unconfirmed diagnostic write can be reported separately.

`debug` validates record structure and reports `decisionsReplayed: false`. Its `VERIFIED_*` verdicts describe those structural checks only. Use `replay` to reproduce successful decisions; its `decisionsReplayed: true` flag confirms that decision verification ran. A mismatch report identifies the offending activity and invocation.

## Replay offline

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli replay --trace .tools/workflow-trace.json
```

Replay validates the exported snapshot and reruns successful pure decision steps using their retained version and input. It plays back recorded effect results and failures without calling an activity implementation, payment API, merchant, provider or scheduler. A recorded failure is displayed; replay does not reproduce that external exception by executing a payment again.

| Verdict | Meaning |
| --- | --- |
| `VERIFIED_RECORDED_STEPS` | Available invocation coverage is complete; with `decisionsReplayed: true`, successful decisions also match their recorded results. |
| `VERIFIED_WITH_RECORDED_FAILURE` | The same checks pass, and the snapshot includes a known safe failure. |
| `INCOMPLETE` | Historical coverage, declared records or invocation outcomes are missing. Available diagnostics remain useful. |
| `MISMATCH` | A decision differs, a record violates its contract, or durable evidence conflicts. |
| `UNSUPPORTED_VERSION` | The recorded journal or decision semantics are unavailable; no current-default fallback runs. |

Both verified verdicts return exit code 0. Incomplete, mismatched and unsupported replay returns exit code 2. A verified snapshot does not imply that the workflow has completed or that a payment settled. Consistency digests detect mismatched records; they do not authenticate an export against someone who can rewrite the entire file and its digests.

## Resume live work separately

`resume` connects to the configured core/runtime and continues the original workflow under its existing authority and source registration:

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli resume --runtime sqlite --registry .tools/workflow-registry.sqlite --run-id $runId --workflow-id $workflowId
```

For Temporal, retain the original server, namespace, queue, registry and runtime arguments. Read current payment status with the authorized core API when resolving uncertainty. Never reset a registry, change a checkout source or create a new workflow/payment identity to repair a failed replay. Approval and customer authentication remain trusted interactions outside the graph.

## Extension and operational limits

The `RegistrationStore` writer contract owns begin-before-effect and atomic publication of a terminal outcome with the recovery projection. A replacement writer must declare and implement the bounded trace guarantees before its runtime advertises `bounded-step-replay-1`. The separate `WorkflowTraceReader` port supplies consistent read-only snapshots. Implementations can adapt another backend without changing workflow callers; forwarding events to a best-effort logger does not satisfy the journal contract.

The reference bounds each workflow to 512 logical activities and 4096 invocations. It reserves terminal and conflict-record capacity before starting an invocation. Limits do not silently evict records. Capacity exhaustion stops new activity calls while leaving debug/export and authorized core inspection available. It does not cancel a payment or release unresolved exposure.

Each invocation keeps its own outcome, including retries whose read-only observations advance or arrive late. Contradictory terminal writes retain a bounded conflict marker and cannot overwrite the earlier result. Losing the acknowledgement of a committed journal result does not create a fictitious failure record. An open invocation makes replay incomplete even if the core later confirms a successful payment.

This is a local synthetic debugging facility. It does not provide distributed tracing, debugger locals/breakpoints, arbitrary historical code loading, full cross-runtime scheduler-consumption replay, automatic retention rollover or production forensic assurance. See [ADR 0017](../adr/0017-workflow-debugging-replay.md), [durable runtimes](../advanced/durable-runtime.md) and [external handoff](../advanced/external-checkout-authority.md#durable-workflow-handoff).
