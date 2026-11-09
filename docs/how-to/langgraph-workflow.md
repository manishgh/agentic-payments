# Run and resume a LangGraph purchase

[Documentation](../README.md) / [How to](README.md)

Use this lesson to pause a synthetic purchase for approval, resume it after customer authentication, and recover after restarting the workflow process. It uses the standalone LangGraph checkpoint CLI. For shared SQLite/Temporal runtime controls and diagnostic journals, use [durable runtimes](../advanced/durable-runtime.md) and [workflow debugging](workflow-debugging.md).

## Reproduce and run

### 1. Prepare the platform and session

Complete the [local setup](../getting-started/README.md#1-install-prerequisites), then run this from the repository root in a terminal that stays open:

```powershell
./scripts/Start-Platform.ps1
```

In a second terminal, load the generated session without printing its tokens:

```powershell
$session = Get-Content .tools/local-sessions.json -Raw | ConvertFrom-Json
$env:PAYMENTS_RUN_ID = $session.runId
$env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
$workflowId = [guid]::NewGuid().ToString()
```

**Expected result:** the platform serves the trusted UI at `http://127.0.0.1:8080`. Keep the run UUID and `$workflowId`; together they identify this workflow's checkpoints in `.tools/workflow-checkpoints.sqlite`.

### 2. Start a purchase that requires a challenge

```powershell
.venv/Scripts/python.exe -m workflows.payment_workflow start --workflow-id $workflowId --scenario challenge-then-success --profile local-ap2-acp-single-tender-0.1
```

**Expected result:** JSON includes an `intentId`, `state: "QUOTED"` and `paused: true`. The graph has created and quoted the fixed server-owned EUR cart, then stopped for trusted approval. Copy the returned intent UUID into the UI and review its checkout using the private session's `uiToken`.

The example explicitly selects the native AP2/ACP profile. The CLI's default remains the legacy synthetic `local-fake-single-tender-0.1`; use the same explicit profile on every command in this lesson.

### 3. Approve, authenticate and resume

Approve the checkout in the trusted UI, then run:

```powershell
.venv/Scripts/python.exe -m workflows.payment_workflow resume --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
.venv/Scripts/python.exe -m workflows.payment_workflow status --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
```

**Expected result:** once execution reaches `WAITING_ACTION`, the output includes an `actionId`. Complete that synthetic challenge in the UI, then run the same `resume` and `status` commands again. The final authoritative `state` is `SUCCEEDED`. If the provider runner is still progressing, resume later with the same identity.

`resume` carries no approval or challenge payload. It requests another core status read; only trusted UI interactions grant approval or complete authentication. `status` reads the core over authenticated HTTP, so it requires the platform and graph session even when a checkpoint exists.

### 4. Recover after closing the workflow terminal

Keep the platform session file, PostgreSQL data and checkpoint file. In a new terminal, reload the session and set `$workflowId` to the UUID retained in lesson 1:

```powershell
$session = Get-Content .tools/local-sessions.json -Raw | ConvertFrom-Json
$env:PAYMENTS_RUN_ID = $session.runId
$env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
$workflowId = Read-Host 'Original workflow UUID'
.venv/Scripts/python.exe -m workflows.payment_workflow resume --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
.venv/Scripts/python.exe -m workflows.payment_workflow status --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
```

**Expected result:** the original intent, attempt and operation remain in use. Starting again with the same run/workflow UUID is refused with `Workflow already exists. Resume its original identity.` A new workflow UUID creates a separate purchase; never use one to recover an uncertain payment.

For a platform on another local port, pass the same `--api-url http://127.0.0.1:<port>` on each command. The client accepts explicit loopback HTTP ports only and disables proxies and redirects.

## Workflow and trust boundaries

| Observed state | What to do |
| --- | --- |
| `QUOTED` | Review and approve the immutable checkout in the trusted UI, then resume. |
| `APPROVED` | Resume; the graph rechecks current authority before executing the existing attempt. |
| `WAITING_ACTION` | Complete the indicated challenge in the trusted UI, then resume. |
| `PREPARING`, `READY`, `ADMITTED`, `PENDING` | Resume later to read current status. |
| `UNKNOWN` | Preserve the same intent/attempt/operation and let reconciliation resolve it. Do not switch provider, release exposure or create a retry payment. |
| Terminal state | Confirm the outcome with `status`; a checkpoint or historic command receipt is not current payment truth. |

PostgreSQL owns payment truth; SQLite stores orchestration progress. Create/quote/execute command keys are deterministic for the run/workflow UUID pair, so retries preserve command identity. Only one local workflow driver should write this checkpoint file at a time.

The [implementation](../../workflows/payment_workflow.py) validates an allowlist of checkpoint fields. Tokens remain in HTTP clients; credentials, signed artifacts and arbitrary resume data do not enter checkpoints. Tracing is disabled for this reference. These lessons use local synthetic services with no LLM call or live payment provider.

## Validation evidence

To exercise checkpoint recovery and trust separation without PostgreSQL:

```powershell
.venv/Scripts/python.exe -m unittest workflows.test_payment_workflow -v
```

**Expected result:** the suite passes. For Java/PostgreSQL process integration and the full source-bound evidence, follow [repository validation](verify.md). Dependency versions and notices belong in the [toolchain reference](../reference/toolchain.md) and [workflow inventory](../../workflows/dependency-inventory.json).
