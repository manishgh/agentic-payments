# Get started: complete your first purchase

[Documentation](../README.md)

This Windows walkthrough starts the platform, creates a synthetic checkout, obtains approval and completes a customer challenge.

Run commands from the repository root. No provider account or real payment instrument is needed. Keep the platform terminal open while using a second terminal for the workflow.

## 1. Install prerequisites

Requirements: PowerShell 7, Python 3.12, Node.js 24/npm and Git. Bootstrap installs verified Java 21, Maven and PostgreSQL inside ignored `.tools/`, without a global service installation.

```powershell
./scripts/Bootstrap-Toolchain.ps1
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r workflows/requirements-lock.txt
.venv/Scripts/python.exe -m pip install -r integrations/offline_lab/requirements-crypto.lock.txt
.venv/Scripts/python.exe scripts/bootstrap_temporal.py
.venv/Scripts/python.exe integrations/upstream/probe_contracts.py --fetch --include-offline-lab --include-ap2-vectors
```

## 2. Start the platform

```powershell
./scripts/Start-Platform.ps1
```

Open `http://127.0.0.1:8080`. The launcher creates private `.tools/local-sessions.json` with separate graph/UI tokens and a run ID. Use its `uiToken` in the trusted page. Tokens are not printed or stored in browser storage.

## 3. Create a purchase

In a second terminal, load the local graph session and start a workflow:

```powershell
$session = Get-Content .tools/local-sessions.json -Raw | ConvertFrom-Json
$env:PAYMENTS_RUN_ID = $session.runId
$env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
$workflowId = [guid]::NewGuid().ToString()
.venv/Scripts/python.exe -m workflows.payment_workflow start --workflow-id $workflowId --scenario challenge-then-success --profile local-ap2-acp-single-tender-0.1
```

## 4. Approve and authenticate

Enter the returned intent UUID in the UI and approve its checkout. Resume using the same workflow UUID, complete the synthetic challenge in the UI, then resume again:

```powershell
.venv/Scripts/python.exe -m workflows.payment_workflow resume --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
.venv/Scripts/python.exe -m workflows.payment_workflow status --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
```

The virtual clock is fixed unless the private runner advances it; wall-clock waiting does not expire this demo. Restart preserves budgets, operation identities and checkpoints. The legacy fixture profile remains available explicitly as `local-fake-single-tender-0.1`. See [workflow details](../how-to/langgraph-workflow.md), [Temporal setup](../advanced/durable-runtime.md) and [toolchain configuration](../reference/toolchain.md).

## 5. Check your result

Use the status command above to read the authoritative payment state. After approval, the completed synthetic challenge and workflow resume, expect `SUCCEEDED`. If it is still waiting, complete the indicated UI step and resume the **same** workflow UUID. Do not start a new purchase to recover an uncertain result.

**Next:** [Understand the purchase journey](../basics/README.md).
