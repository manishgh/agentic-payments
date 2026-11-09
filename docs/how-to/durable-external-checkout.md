# Durable external checkout

[Documentation](../README.md) / [How-to guides](README.md)

Create, update, retrieve and cancel a checkout with the synthetic loopback merchant, then resume its recorded result after a restart. This lesson uses `durable-checkout-lifecycle-0.1`; it does not complete checkout, sign AP2 authority or submit a payment.

Complete the [quickstart prerequisites](../getting-started/README.md#1-install-prerequisites). Run commands from the repository root in PowerShell 7. Keep the merchant and platform terminals open.

## Run the local demonstration

### 1. Start the synthetic merchant

In terminal one:

```powershell
.venv/Scripts/python.exe -m integrations.checkout_demo --port 18181
```

**Expected:** `Synthetic checkout peer: http://127.0.0.1:18181`. Its sessions live in memory, so keep this process running throughout the lesson.

### 2. Enable its trusted registration

In terminal two, stop any existing platform process, then start it with the demo registration:

```powershell
$env:PAYMENTS_CHECKOUT_DEMO_ORIGIN = 'http://127.0.0.1:18181'
./scripts/Start-Platform.ps1
```

**Expected:** the platform starts on port 8080 and retains the private session file at `.tools/local-sessions.json`. This opt-in accepts literal loopback only and registers `merchant-demo` / `checkout-demo` with a public synthetic credential. Without the environment variable, checkout registration is disabled.

### 3. Create one checkout

In terminal three, load the graph session and create a protected command template:

```powershell
$session = Get-Content .tools/local-sessions.json -Raw | ConvertFrom-Json
$env:PAYMENTS_RUN_ID = $session.runId
$env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
$workflowId = [guid]::NewGuid().ToString()
$purchaseId = [guid]::NewGuid().ToString()
$template = '.tools/checkout-create.json'
@{
    operation = 'CREATE'; expectedRevision = 0
    merchantId = 'merchant-demo'; configurationId = 'checkout-demo'
    currency = 'usd'; itemIds = @('demo-item')
} | ConvertTo-Json | Set-Content -LiteralPath $template -Encoding utf8NoBOM

$createResult = .venv/Scripts/python.exe -m workflows.checkout_workflow start --workflow-id $workflowId --purchase-id $purchaseId --template $template
if ($LASTEXITCODE -ne 0) { throw 'Preserve this workflow identity and inspect its status.' }
$createResult
$view = $createResult | ConvertFrom-Json
```

**Expected:** safe JSON with `commandState: "RECORDED"`, `outcomeCode: "OK"`, `remoteSessionKnown: true`, `remoteStatus: "ready_for_payment"`, `mutationBlocked: false` and `recovery: "NONE"`. Retain the returned revision rather than guessing the next one. The response excludes merchant session IDs and raw checkout documents.

### 4. Resume the same operation after restart

Restart the platform in terminal two with the same demo environment, leaving the merchant running. In terminal three:

```powershell
.venv/Scripts/python.exe -m workflows.checkout_workflow resume --workflow-id $workflowId --purchase-id $purchaseId --template $template
.venv/Scripts/python.exe -m workflows.checkout_workflow status --workflow-id $workflowId --purchase-id $purchaseId --template $template
```

**Expected:** the original command ID, revision and recorded result. Resume does not create another merchant mutation. Keep the same run/session file, workflow UUID, purchase UUID, template and default `.tools/checkout-workflows.sqlite` checkpoint file. A changed template under the same workflow UUID is rejected.

### 5. Update, retrieve and cancel explicitly

Each new operation needs a new workflow UUID and the current purchase revision. Continue in terminal three after the successful create/resume above:

```powershell
foreach ($operation in @('UPDATE', 'RETRIEVE', 'CANCEL')) {
    $nextWorkflowId = [guid]::NewGuid().ToString()
    $nextTemplate = ".tools/checkout-$($operation.ToLowerInvariant()).json"
    $items = @()
    if ($operation -eq 'UPDATE') { $items = @('demo-item-alt') }
    @{
        operation = $operation; expectedRevision = $view.revision
        merchantId = 'merchant-demo'; configurationId = 'checkout-demo'
        currency = 'usd'; itemIds = @($items)
    } | ConvertTo-Json | Set-Content -LiteralPath $nextTemplate -Encoding utf8NoBOM

    $result = .venv/Scripts/python.exe -m workflows.checkout_workflow start --workflow-id $nextWorkflowId --purchase-id $purchaseId --template $nextTemplate
    if ($LASTEXITCODE -ne 0) { throw 'Preserve this workflow identity and inspect its status.' }
    $result
    $view = $result | ConvertFrom-Json
    if ($view.commandState -ne 'RECORDED' -or $view.outcomeCode -ne 'OK' -or $view.mutationBlocked) {
        throw 'Stop here and use the recovery guidance below.'
    }
}
```

**Expected:** three distinct command IDs on the same purchase. Update and retrieve report `ready_for_payment`; cancel reports `canceled`. `UPDATE` replaces item IDs; `RETRIEVE` and `CANCEL` require an empty item list. The demo never performs financial completion.

## Commit, send and observe

### 6. Handle an interrupted claim conservatively

If a workflow stops unexpectedly, use `status` with that operation's original UUIDs and template. For an interrupted `CLAIMED` command, explicitly classify it with:

```powershell
.venv/Scripts/python.exe -m workflows.checkout_workflow recover-claim --workflow-id $workflowId --purchase-id $purchaseId --template $template
```

Substitute the retained variables for the interrupted operation; the example variables above belong to CREATE. Recovery does not resend the command. An interrupted mutation remains uncertain even if the original worker has not yet sent: it may still reach the merchant later.

| Returned recovery | Next action |
| --- | --- |
| `DISPATCH_STAGED` | Resume the same workflow; it can dispatch its original staged command. |
| `IN_FLIGHT` | Read status; use explicit claim recovery only for an interrupted worker. |
| `RETRIEVE_ONLY` | A new explicit RETRIEVE workflow may observe the known session. Mutations remain blocked. |
| `TRUSTED_RESOLUTION_REQUIRED` | Preserve the command and purchase. This profile has no trusted resolution for an unknown create. |
| `NONE` | Inspect `commandState` and `outcomeCode`; recorded does not imply success. |

**Expected after recovering an interrupted mutation:** `mutationBlocked: true`, with no replacement mutation. A GET result, 404, expiry, cancellation or `completed` lifecycle status cannot clear that barrier. Never create a new workflow or purchase to bypass uncertainty. The [transport reference](../reference/external-checkout.md#retry-and-recovery-responsibilities) explains bounded JDK retries; one application send does not guarantee one physical request.

## HTTP surface

All routes use the local bearer session under `/internal/v0.1`; IDs are canonical UUIDs. Status requires `intent:read`; the three POST routes require `intent:write`, current merchant access, a JSON body and `Idempotency-Key`.

| Method and path | Body / result |
| --- | --- |
| `POST /checkout-purchases/{purchaseId}/commands` | Exact fields from the templates above; stage an immutable command. |
| `GET /checkout-purchases/{purchaseId}` | Current safe status. |
| `POST /checkout-purchases/{purchaseId}/commands/{commandId}/dispatch` | `{}`; claim/send the original staged command. |
| `POST /checkout-purchases/{purchaseId}/commands/{commandId}/recover-claim` | `{}`; conservatively classify an interrupted claim. |

Success uses `checkout-lifecycle-0.1`; failures use the [API error envelope](../reference/api-contracts.md#error-envelope-and-http-mapping). Missing registration returns `422 UNSUPPORTED_CAPABILITY`. Unknown/missing fields, invalid types and duplicate JSON members fail before activity. Signal idempotency keys do not replace the original merchant key. See [ADR 0011](../adr/0011-durable-external-checkout.md#application-api-and-workflow-integration) for the exact safe DTO, enums and scope rules. There is no raw-document endpoint.

## Replaceable boundaries

To integrate another merchant or backend, implement, adapt or wrap the responsibility-based ports in [ADR 0011](../adr/0011-durable-external-checkout.md#stable-responsibilities). Trusted infrastructure resolves the exact enrolled configuration and credentials; agents cannot supply merchant URLs or secrets. Replacements must preserve atomic command/claim/outcome persistence, exact scope, response provenance and uncertainty barriers. Generic CRUD alone is insufficient. The commerce workflow supports injected checkpoint/registration adapters; only local SQLite restart is demonstrated, with no Temporal, Cadence or Azure Durable Task commerce adapter claim.

## Storage and validation scope

V008 stores scoped configuration metadata, purchase projections, immutable commands and protected correlated outcomes separately from financial tables and signed artifacts. Keep templates, sessions, checkpoints and database contents private. Retain unresolved history; dropping these tables cannot undo an external effect. PostgreSQL records authoritative commerce state; graph checkpoints contain safe identities/projections and a template fingerprint.

Use [verification](verify.md) for source-bound evidence covering HTTP exchanges, claims, response loss, process restart and replacement ports. That evidence uses synthetic peers and grants no vendor interoperability or certification. To bind retained observations to synthetic AP2 approval and financial admission, follow the separately enabled [external authority lesson](../advanced/external-checkout-authority.md#run-the-synthetic-http-journey).
