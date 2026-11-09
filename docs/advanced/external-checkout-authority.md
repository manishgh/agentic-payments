# External checkout financial-authority binding

[Documentation](../README.md) / [Advanced](README.md)
The opt-in `local-external-ap2-acp-single-tender-0.1` profile binds an exact retained external checkout response to a financial quote, trusted customer approval, independently verified AP2 mandates and synthetic payment execution. It uses the existing authority, artifact, checkout and transactional admission interfaces. The reference implementation accepts a closed synthetic ACP subset; it does not enable a live merchant or payment provider.

## Trust and ownership

An HTTP response is commerce evidence. The financial path reloads its protected original bytes and validates current scope, command, response, configuration and observation time under the same transaction lock used by lifecycle changes. It derives the amount from those bytes rather than a local cart default. A trusted fixture signer attests the original bytes locally; this is explicitly **not a merchant-origin signature or production delegation**. Customer approval and AP2 verification are still required.

Quote publication permanently binds one purchase to one intent. The quote, binding, local seal and accepted-command result commit together. All new lifecycle stages, including retrieve, are rejected after sealing. Status and exact historical command replay remain available. Sealing does not make remote merchant state immutable, and it never clears a pre-existing uncertain-mutation barrier. The bounded profile assumes the controlled synthetic peer and a shared reference virtual clock.

The original checkout principal is retained in the binding. Later approval by a different customer principal and worker execution recheck that source through its immutable binding while independently authorizing the caller. They cannot choose a different owner, response, credential or configuration. Effective expiry is the earlier of the original observation time plus 300 seconds and the response expiry; replay cannot extend it. Expiry uses strict four-digit-year RFC3339 UTC `Z` syntax without leap seconds or midnight normalization. Valid timestamps must be exactly representable at microsecond precision; finer nonzero precision rejects before a quote or seal is stored, without rounding the original bytes.

Configuration, authority and freshness are checked again at approval, execute, prepare and dispatch. Once a financial operation is admitted, later expiry or configuration changes preserve its original operation and unresolved exposure for reconciliation. This profile neither sends `/complete` to the merchant nor invokes the separate provider-execution journal.

## Replaceable composition

| Responsibility | Extension |
| --- | --- |
| Checkout protocol | Existing `CheckoutProtocol` gains fail-closed `inspectObserved` and `freezeObserved` methods and explicit profile capabilities. |
| Atomic financial application | Existing `PaymentStore` gains fail-closed `quoteExternal` and an explicit atomic-binding capability. |
| Lifecycle storage | Existing `CheckoutLifecycleTransactions` declares seal capability and a shared opaque coordination-domain identity. Direct stage/claim calls enforce the seal. |
| Authority, artifacts and trust | Existing issuer, verifier, artifact, provisioning and key-resolution interfaces explicitly negotiate the additional profile. |
| Trusted registration | An immutable registration binds the exact scope/configuration and required synthetic policies; a trusted factory supplies the same database and coordination token to the reference adapters. |

There is no backend-specific observation-storage interface. A replacement backend must preserve the indivisible transaction and seal semantics, not just method signatures. The coordination token represents a trusted composition assertion; it cannot prove two arbitrarily configured services share storage. This reference factory deliberately constructs both against the same database. Under-lock callbacks are restricted to declared bounded local computation and reference database retention. Remote signing/storage services need a separately reviewed transaction strategy.

## Run the synthetic HTTP journey

Start the explicit financial-ready peer:

```powershell
.venv/Scripts/python.exe -m integrations.checkout_demo --port 18181 --financial-fixture
```

Then opt into both commerce and financial registration before starting the platform:

```powershell
$env:PAYMENTS_CHECKOUT_DEMO_ORIGIN = 'http://127.0.0.1:18181'
$env:PAYMENTS_EXTERNAL_CHECKOUT_FINANCE = 'true'
./scripts/Start-Platform.ps1
```

Create and dispatch a commerce checkout using the [existing lifecycle routes or LangGraph workflow](../how-to/durable-external-checkout.md). Use currency `usd` or `eur`, and exactly one item: `demo-item` (1250 minor units) or `demo-item-alt` (1750). The ordinary commerce demo's empty totals remain ineligible for financial binding. The financial peer uses the reference run's virtual-clock origin and a fixed synthetic expiry, not real merchant wall-clock validity.

With the graph-role token, create an intent with profile `local-external-ap2-acp-single-tender-0.1`, merchant `merchant-demo` and a supported synthetic scenario. Submit this exact shape to `POST /internal/v0.1/intents/{intentId}/quote-external`, using a retained local `Idempotency-Key`:

```json
{
  "expectedVersion": 1,
  "purchaseId": "<canonical purchase UUID>",
  "expectedPurchaseRevision": 2,
  "responseReference": "<canonical response UUID from the recorded status>",
  "tenders": [{"provider": "simulator", "method": "synthetic-card", "credentialReference": "fixture-credential-1"}]
}
```

Use the actual recorded purchase revision and response reference. The response contains the financial checkout revision/digest, amount and expiry. The trusted UI/customer can review `/intents/{intentId}/checkout` and approve that exact digest using the existing approval route. The graph role can then execute the approved attempt using the existing execute route. Customer-action and uncertain-outcome recovery continue through the existing payment paths. Retain the same keys and identities across retries and restarts.

## Durable workflow handoff

The shared SQLite/Temporal runtime accepts this profile through the explicit `external-checkout-request-1` template. After commerce has recorded an eligible observation, retain its purchase UUID, actual revision and response UUID. Start the financial workflow once under a stable workflow UUID:

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli start --runtime sqlite --workflow-id $workflowId --run-id $env:PAYMENTS_RUN_ID --profile local-external-ap2-acp-single-tender-0.1 --purchase-id $purchaseId --purchase-revision $purchaseRevision --response-reference $responseReference --scenario challenge-then-success
.venv/Scripts/python.exe -m workflows.runtime_cli inspect --runtime sqlite --workflow-id $workflowId --run-id $env:PAYMENTS_RUN_ID
.venv/Scripts/python.exe -m workflows.runtime_cli resume --runtime sqlite --workflow-id $workflowId --run-id $env:PAYMENTS_RUN_ID
```

Load the graph token using the existing local session instructions. Review and approve the returned intent in the trusted UI; the graph cannot approve or complete a customer action. Source flags are accepted only by `start`. Controls recover the immutable source and profile from registration. A changed source under the same workflow UUID conflicts; an uncertain payment must never be recovered with a new workflow UUID.

For Temporal, use the [server and worker setup](durable-runtime.md), select this external profile for the worker, and pass `--runtime temporal --queue payments-external-v1` on the worker and every start/control command, with the same server and registry. Keep this queue separate from native/fixture workers: a worker is bound to one profile and rejects another profile's activities. Existing default queue bindings are unchanged. Workers can process multiple registered purchase sources; no source is configured globally on a worker.

Registration commits the closed source tuple before creating an intent. The injected request-template policy prepares exactly those selectors; it never refreshes a checkout before replaying an accepted quote. The core reloads protected bytes and owns freshness, configuration, sealing and admission. Separate workflow identities can leave competing DRAFT intents, but the core admits only one quote/financial owner for a purchase. Registration is not spending authority or a cross-runtime purchase lock.

The older `workflows.payment_workflow` entry point remains limited to native/fixture profiles and rejects this external profile. Both old serialized start fingerprints and captured pre-change Temporal histories remain compatible. See [ADR 0016](../adr/0016-external-checkout-workflow-handoff.md) for the exact boundary and [workflow debugging](../how-to/workflow-debugging.md) for read-only failure inspection and replay.

## Persistence and limits

V010 adds a trusted observation timestamp to newly promoted outcomes and a permanent source binding. Old outcomes remain timestamp-less and ineligible; migration does not invent freshness. Existing native payment, commerce and provider-journal history remains intact. Do not drop bindings or unseal purchases to recover liveness; expiry, failed approval and payment completion do not release a seal.

The supported response is strictly closed, including nested handler configuration, exact integer money, consistent line/root totals, status and expiry. Unknown fields, additional handlers, metadata, unsupported actions, malformed UTF-8/JSON and duplicate members fail closed. Parsing never replaces the bytes that are attested and retained. Protected response bodies, merchant session identifiers, keys and credentials do not belong in public status, graph checkpoints or diagnostic logs.

See [ADR 0014](../adr/0014-external-checkout-authority-binding.md) and the [verification guide](../reference/requirement-evidence.md) for acceptance, executable evidence and remaining limits. This profile does not establish production merchant authenticity, vendor interoperability, live execution or certification.

The versioned [quote request schema](../../specification/0.1.0-draft/schemas/quote-external-request.schema.json) fixes the five accepted request members. Existing quote, approval, execution and status response contracts are reused; the shared profile enumeration explicitly includes this opt-in profile. HTTP tests validate actual responses against the local schema registry.
