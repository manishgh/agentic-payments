# Debug a workflow and replay recorded steps

[Documentation](../README.md) / [How to](README.md)

Use the modern SQLite or Temporal runtime's durable journal to inspect activity inputs, invocation attempts, validated results and safe failures. Offline replay reruns recorded successful decisions and plays back effect results; it does not send another payment command or continue the workflow.

## 1. Create a workflow with diagnostic coverage

Complete [local setup](../getting-started/README.md#1-install-prerequisites) and keep `./scripts/Start-Platform.ps1` running in another terminal. From the repository root, create a new registration with the modern SQLite runtime:

```powershell
$session = Get-Content .tools/local-sessions.json -Raw | ConvertFrom-Json
$runId = $session.runId
$env:PAYMENTS_RUN_ID = $runId
$env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
$workflowId = [guid]::NewGuid().ToString()
$registry = '.tools/workflow-registry.sqlite'
$trace = ".tools/workflow-trace-$workflowId.json"
.venv/Scripts/python.exe -m workflows.runtime_cli start --runtime sqlite --registry $registry --run-id $runId --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1 --scenario challenge-then-success
```

**Expected result:** the response includes `lifecycle: "WAITING"`, a nested `state` containing `state: "QUOTED"` and an `intentId`, plus `source: "RUNTIME_CACHE"` and `authoritativePaymentState: false`. Leave approval pending for now; a waiting workflow already has useful recorded steps.

For an existing modern workflow, use its original registry, run UUID, workflow UUID and runtime instead. For Temporal, pass `--runtime temporal` to `debug`; no server connection is needed. The standalone `payment_workflow` and commerce checkpoint files use different schemas and cannot be read by this diagnostic command. Older modern registrations may report unavailable historical coverage.

## 2. Inspect the recorded activity attempts

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli debug --runtime sqlite --registry $registry --run-id $runId --workflow-id $workflowId
```

**Expected result for the healthy workflow above:** the report contains these fields, alongside `steps` with each activity's `kind`, `sequence` and individual `invocations`:

```json
{
  "verdict": "VERIFIED_RECORDED_STEPS",
  "coverage": "FROM_REGISTRATION",
  "decisionsReplayed": false,
  "failures": 0,
  "unresolved": 0,
  "authoritativePaymentState": false,
  "schedulerConsumptionVerified": false
}
```

This is an excerpt. `debug` validates record structure; it does not rerun decisions. When investigating a failure, find the relevant activity and invocation. A recorded failure includes a fixed code, phase and retryability. An invocation without an outcome means recording is incomplete, not that its financial command failed. A retry can recover the original result using the same payment idempotency key.

The reader opens the registry read-only and needs neither a bearer token nor running Java/Temporal services. Keep the file and original scope identifiers even if live recovery is unavailable.

## 3. Export and replay offline

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli debug --runtime sqlite --registry $registry --run-id $runId --workflow-id $workflowId --export $trace
.venv/Scripts/python.exe -m workflows.runtime_cli replay --trace $trace
```

**Expected result:** export adds `exported: true`. Replay reports `VERIFIED_RECORDED_STEPS` and `decisionsReplayed: true` for this healthy snapshot. It uses the recorded decision version and input; recorded effects and failures are played back without invoking the payment API, merchant, provider or scheduler. It does not execute a failed decision again.

Export creates a new file and refuses to overwrite an existing one with `WORKFLOW_TRACE_EXPORT_EXISTS`. Keep exports private: their allowlist includes payment identifiers, money and external checkout source references. It excludes credentials, raw checkout bytes, merchant sessions, signed artifacts, keys and arbitrary exception text. Digests detect inconsistent records; they do not authenticate a file against someone able to rewrite it and its digests.

| Verdict | Interpretation and next action |
| --- | --- |
| `VERIFIED_RECORDED_STEPS` | Available coverage is complete. With `decisionsReplayed: true`, successful decisions also match. Inspect core status separately for the financial outcome. |
| `VERIFIED_WITH_RECORDED_FAILURE` | Checks pass and a known failure is retained. Inspect its activity, code and retryability before live recovery. |
| `INCOMPLETE` | Historical coverage, records or invocation outcomes are missing. Preserve the evidence and inspect the original payment in the core. |
| `MISMATCH` | A decision differs, records violate the contract or evidence conflicts. Preserve the trace and investigate the reported activity/invocation. |
| `UNSUPPORTED_VERSION` | The retained journal/decision version is unavailable. Restore compatible trusted code; no current-default fallback runs. |

Replay exits 0 for the two verified verdicts and 2 otherwise. `debug` can exit 0 for a structurally valid `INCOMPLETE` report, so inspect its verdict as well as `$LASTEXITCODE`. Neither verified verdict implies workflow completion or settlement. Temporal scheduler behavior requires its separate native history replay.

## 4. Resume live work separately

To continue the example, copy `state.intentId` from the start response into the trusted UI and approve its checkout. Then run:

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli resume --runtime sqlite --registry $registry --run-id $runId --workflow-id $workflowId
.venv/Scripts/python.exe -m workflows.runtime_cli inspect --runtime sqlite --registry $registry --run-id $runId --workflow-id $workflowId
```

**Expected result:** the original workflow advances. When it reports `WAITING_ACTION` inside `state`, complete the challenge in the trusted UI and resume again. Eventually `lifecycle` becomes `COMPLETED` with cached `state.state: "SUCCEEDED"`. Intermediate `PENDING` states require a later resume. Inspect remains a runtime cache; use the authorized core API or trusted UI to confirm current payment truth.

Unlike replay, resume requires the original live core and graph session. For Temporal also retain the original server, namespace, queue, registry and worker configuration; follow [runtime controls](../advanced/durable-runtime.md). Never reset a registry, change an external checkout source or create a new workflow/payment identity to repair a replay. Approval and authentication remain trusted interactions outside the graph.

## Operational limits

The reference retains at most 512 logical activities and 4096 invocations per workflow. Capacity exhaustion stops new activity calls without evicting evidence, cancelling payments or releasing unresolved exposure. Debug/export and authorized core inspection remain available.

Retries retain separate outcomes, including late observations. Conflicting terminal writes cannot overwrite earlier evidence; a retained conflict reports `MISMATCH`. Losing a committed journal acknowledgement does not manufacture a failure. An open invocation keeps replay incomplete even if the core confirms success.

Replacement registration writers and trace readers must preserve these bounded, atomic journal guarantees; best-effort logs do not satisfy them. This facility provides local synthetic diagnostics, not debugger breakpoints, distributed tracing, arbitrary historical code loading, cross-runtime scheduler replay or production forensic assurance. See [ADR 0017](../adr/0017-workflow-debugging-replay.md) for the contract.

## Reproduce a simulator schedule

This exercise runs a recorded synthetic schedule in a **new isolated payment run** in PostgreSQL. Unlike offline trace replay above, it executes simulator commands. It does not resume or repair the original payment.

### 1. Build the runner

In a new PowerShell terminal at the repository root:

```powershell
. ./scripts/Use-LocalToolchain.ps1
. ./scripts/Start-LocalPostgres.ps1
mvn.cmd compile dependency:build-classpath '-Dmdep.outputFile=target/classpath.txt'
$replayClasspath = "target/classes$([IO.Path]::PathSeparator)$((Get-Content target/classpath.txt -Raw).Trim())"
```

**Expected result:** `BUILD SUCCESS`. The startup script sets the local database environment without printing credentials. The CLI has no public HTTP endpoint and needs no graph/UI token.

### 2. Run the built-in schedule and inspect the report

```powershell
java -cp $replayClasspath io.agenticpayments.replay.ReplayMain --demo > .tools/replay-demo.json
if ($LASTEXITCODE -ne 0) { throw 'Inspect the replay report before continuing.' }
$report = Get-Content .tools/replay-demo.json -Raw | ConvertFrom-Json
$report | Select-Object outcome, runId, coreDigest, journalDigest, auditDigest, combinedDigest
```

**Expected result:** exit code 0, `outcome` equal to `PASS`, a new run UUID and nonempty digests. The example covers a trusted challenge and delayed success. It records worker steps, reconstruction, reconciliation and assertions without wall-clock sleeps. The runner validates identity, money, ownership and recorded facts before normalizing identifiers.

`--demo` generates and executes the plan in one process. Each invocation creates a fresh run; it never resets an uncertain original payment.

**Current saved-plan limitation:** `--example` can export a plan and `--plan` can read it, but the current execution-policy digest serializes unordered sets. Its value can change between JVM processes, causing an unchanged saved plan to return `INVALID_REPLAY_INPUT`. Use `--demo` for this lesson. Cross-process digest comparison is not a reliable acceptance check until that canonicalization issue is fixed; do not bypass identity validation. This is tracked under [CON-003](../project/backlog.md#conformance-and-preview).

| Result | Next action |
| --- | --- |
| Exit 1, `FAIL` | Inspect the safe report reason and raw invariant checks; do not present its digest as passing evidence. |
| Exit 1, `INCOMPLETE` | Preserve the run for inspection. An orphan started step or uncertain worker cannot be resumed automatically by this runner. |
| Exit 2, `INVALID_REPLAY_INPUT` | Check plan shape and implementation identity; saved plans also have the limitation above. |
| Exit 2, `REPLAY_EXECUTION_FAILED` | Check the local database environment and build. |

This bounded simulator supports sequential recorded steps and two fixed concurrency recipes; it is not exhaustive concurrency testing or exactly-once scheduling. See [ADR 0003](../adr/0003-replay-and-adapter-conformance.md) for the design.
