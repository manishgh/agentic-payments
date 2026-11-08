# Durable external checkout

The `durable-checkout-lifecycle-0.1` profile coordinates external ACP create, retrieve, update and cancel through a separate commerce aggregate. Java/PostgreSQL retains command identity and protected outcomes; LangGraph coordinates one operation per workflow. The opt-in demonstration uses an independently authored synthetic loopback merchant. No vendor account, completion, financial submission or AP2 signing is enabled by this profile.

## Replaceable boundaries

| Responsibility | Contract | Reference implementation |
| --- | --- | --- |
| Application commands and safe status | `CheckoutLifecycle`, `checkout-lifecycle-0.1` | `CheckoutLifecycleService` |
| Atomic scoped configuration, command, claim and outcome persistence | `CheckoutLifecycleTransactions`, `checkout-lifecycle-transactions-0.1` | PostgreSQL `JdbcCheckoutLifecycleTransactions` |
| Resolve the exact enrolled configuration and credential version | `CheckoutConfigurationResolver` | Explicit synthetic startup registration |
| Pure exact-request preparation and one application send | `CheckoutCommandTransport`, `checkout-command-transport-0.1` | `AcpHttpCheckoutSessions`, also implementing standalone `CheckoutSessions` |
| Workflow calls to the application | Python `CheckoutPort` | Authenticated loopback `LocalCheckoutClient` |
| Immutable workflow/template/command registration | Python `CheckoutWorkflowBindings` | SQLite compare-and-set registration |
| Graph checkpoints | LangGraph `BaseCheckpointSaver` | `SqliteSaver`, synchronous checkpoint persistence |

An implementation may implement, adapt or wrap each responsibility. A storage product or new merchant does not introduce a new interface. Replacements must preserve the behavioral contract in [ADR 0011](adr/0011-durable-external-checkout.md), including atomicity, exact identity, scope, uncertainty and response provenance. A generic CRUD store alone cannot satisfy the transaction contract. Configuration resolution belongs in trusted infrastructure; merchant URLs and secrets are never agent-supplied command fields.

The existing payment workflow's SQLite/Temporal runtime remains separate. This commerce workflow tests local SQLite restart and explicit resume, and accepts other compatible checkpointers and registration adapters by injection. It does not claim a Temporal, Cadence or Azure Durable Task commerce adapter. LangGraph persistence is described in the [primary documentation](https://docs.langchain.com/oss/python/langgraph/persistence).

## Commit, send and observe

1. Authorize tenant/run/principal/merchant scope before reading a prior local-key result. An identical accepted command replays without depending on the current configuration resolver.
2. For a new command, resolve its exact enrolled configuration and prepare the immutable request without I/O. Recheck revision/configuration and persist the original method, path, body, merchant idempotency key and correlation identity in a short transaction.
3. Commit an exclusive claim. Only then send outside all database transactions and locks.
4. Retain the correlated protected outcome. Promote its lifecycle projection only if scope, configuration, revision, claim and response provenance still match. A changed configuration or unavailable resolver cannot erase an already observed outcome.
5. Return a bounded safe projection. Remote session IDs, item templates, credentials, merchant keys and raw request/response bytes remain outside API status and graph checkpoints.

At most one staged/claimed command exists per purchase. The mutation-uncertainty barrier is separate from the latest command and remote status. A late original worker can still reach the merchant after local recovery; revoking its projection claim does not revoke its network send. Consequently, `recover-claim` marks uncertainty and never transfers permission to another mutation. A known-session GET may observe the original session, but even a 404, expiry, cancellation or completed status cannot clear that barrier. An unknown create without a session requires a future trusted resolution profile. No automatic retry or key replacement restores liveness.

The transport's documented bounded JDK retry behavior still applies; one application send is not a universal one-physical-request guarantee. See [external transport constraints](external-checkout.md). A lifecycle `completed` status is not financial success, a signed artifact or spending authority. External checkout binding to AP2 authority and financial admission requires its own reviewed profile.

## HTTP surface

All routes are under `/internal/v0.1`, authenticated using the existing local sessions. Purchase IDs and command IDs are canonical UUIDs. Stage/dispatch/recovery require `intent:write`; status requires `intent:read`. Every operation enforces purchase principal and current merchant access.

| Method and path | Body / behavior |
| --- | --- |
| `POST /checkout-purchases/{purchaseId}/commands` | Exact `operation`, `expectedRevision`, `merchantId`, `configurationId`, `currency`, `itemIds`; local `Idempotency-Key` required. |
| `GET /checkout-purchases/{purchaseId}` | Current command and safe projection. |
| `POST /checkout-purchases/{purchaseId}/commands/{commandId}/dispatch` | Empty object; claims/sends only the original staged command. |
| `POST /checkout-purchases/{purchaseId}/commands/{commandId}/recover-claim` | Empty object; explicit conservative recovery of a claimed command. |

Both signal POSTs use the existing required idempotency-header syntax; their behavior is bound to the command UUID, not a new merchant key. Success returns `checkout-lifecycle-0.1`; errors retain the existing sanitized API envelope. Missing registration returns `422 UNSUPPORTED_CAPABILITY`. Unknown/missing fields, invalid scalar types and duplicate JSON members fail before activity. No raw-response retrieval endpoint exists.

Safe status includes run/purchase/command IDs, revision, operation, command state, fixed outcome/observation, HTTP status, remote-session-known flag, validated remote lifecycle status, opaque response reference, mutation barrier and recovery classification. States are `STAGED`, `CLAIMED`, `RECORDED`, `UNKNOWN` and `STALE`. Recovery is `DISPATCH_STAGED`, `IN_FLIGHT`, `RETRIEVE_ONLY`, `TRUSTED_RESOLUTION_REQUIRED` or `NONE`. A recorded GET can coexist with a blocked mutation; check both fields.

## Run the local demonstration

Complete the repository toolchain/bootstrap steps first. In terminal one, keep the synthetic merchant running:

```powershell
.venv/Scripts/python.exe -m integrations.checkout_demo --port 18181
```

In terminal two, opt into its trusted synthetic registration and start the platform:

```powershell
$env:PAYMENTS_CHECKOUT_DEMO_ORIGIN = 'http://127.0.0.1:18181'
./scripts/Start-Platform.ps1
```

The demo registration accepts literal loopback only, uses a public synthetic fixture credential and fixes `merchant-demo` / `checkout-demo`. Default startup with the environment variable absent leaves the feature disabled. A real deployment supplies its own registration/resolver implementation; this demo is not a general secret-management adapter.

In terminal three, retain the workflow/purchase identities and private template:

```powershell
$session = Get-Content .tools/local-sessions.json -Raw | ConvertFrom-Json
$env:PAYMENTS_RUN_ID = $session.runId
$env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
$workflowId = [guid]::NewGuid().ToString()
$purchaseId = [guid]::NewGuid().ToString()
$template = '.tools/checkout-template.json'
@{ operation='CREATE'; expectedRevision=0; merchantId='merchant-demo'; configurationId='checkout-demo'; currency='usd'; itemIds=@('demo-item') } |
    ConvertTo-Json | Set-Content -LiteralPath $template -Encoding utf8NoBOM
.venv/Scripts/python.exe -m workflows.checkout_workflow start --workflow-id $workflowId --purchase-id $purchaseId --template $template
.venv/Scripts/python.exe -m workflows.checkout_workflow resume --workflow-id $workflowId --purchase-id $purchaseId --template $template
```

Restart the Java/Python processes while keeping the peer running, then resume with those same IDs, template and `.tools/checkout-workflows.sqlite`. Resume reads recorded truth without another mutation. The synthetic merchant itself is memory-backed and loses sessions if restarted; do not treat that fixture as a durable merchant.

For the next explicit operation, read the returned revision, create a **new workflow ID for that operation**, retain the purchase ID, and write a new template. `UPDATE` uses replacement item IDs; `RETRIEVE` and `CANCEL` use an empty item list. Reusing a workflow ID with altered inputs is rejected before a lifecycle call. Never create a replacement workflow to work around an uncertain mutation; the application barrier must remain authoritative. A known-session retrieve is allowed only as observation of that same purchase.

`status` reads current truth. `recover-claim` conservatively classifies an interrupted claim, then resumes observation; it cannot clear the mutation barrier. Keep templates, session files, graph databases and database contents private. The workflow stores only safe identity/projection data and a template fingerprint.

## Storage and validation scope

V008 adds scoped immutable configuration metadata/current pointers, purchase projections, immutable commands and correlated protected outcomes, independently of financial tables and signed-artifact storage. The PostgreSQL adapter uses the existing run lock for short atomic units of work; independent reads/writes can proceed while the merchant response is blocked. Retain unresolved history; dropping these tables is not a safe rollback of unknown external effects. Protected rows require the same database-access controls as other sensitive platform data.

Tests cover actual HTTP/merchant exchanges, concurrent claims, stable-key conflicts, scope checks, configuration changes, response loss, late recovered workers, actual JVM halt after stage/claim commit, populated V007 migration, API restart and separate Python graph processes. Replacement/decorated ports and checkpoint privacy are tested. The aggregate includes these tests without changing the existing certification inventory. Evidence remains bounded to synthetic loopback peers; vendor interoperability, complete financial composition, operational retention and broader certification remain outstanding.

The optional [external authority binding profile](external-checkout-authority.md) now joins retained observations to synthetic AP2 approval and financial admission through a permanent atomic seal. It requires separate trusted opt-in, leaves the commerce-only profile unchanged, and does not enable vendor execution or merchant checkout completion.
