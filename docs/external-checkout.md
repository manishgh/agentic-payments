# External checkout lifecycle adapters

This slice introduces `CheckoutSessions`, a responsibility-based Java contract for create, retrieve, update and cancel, and `AcpHttpCheckoutSessions`, its outbound ACP implementation. Consumers can implement the same interface, adapt an external checkout service, or wrap an implementation for policy and instrumentation. The standalone client is not registered in the reference payment workflow.

The bounded profile is `acp-2026-04-17-lifecycle-envelope-0.1`, using the [locked ACP revision](../integrations/upstream/source-lock.json). It has no completion operation, financial execution owner or provider capability. The current executable payment SDK remains synthetic; [provider adapter evolution](provider-adapter-evolution.md) describes the next contract work.

## Supported contract

| Operation | ACP request | Supported input |
| --- | --- | --- |
| Create | `POST /checkout_sessions` | Item IDs, `usd` or `eur`, empty capabilities object |
| Retrieve | `GET /checkout_sessions/{id}` | Bound session; absent request body |
| Update | `POST /checkout_sessions/{id}` | Replacement item IDs |
| Cancel | `POST /checkout_sessions/{id}/cancel` | Bound session; absent request body |

Create/update use `line_items`, with item references containing `id`. The pinned request `Item` does not support `quantity`; response line items are a different schema. Checkout `totals` is an array. Optional response protocol metadata is retained as observed; its absence does not become a fabricated version assertion. Unsupported inputs fail explicitly.

A trusted configuration fixes the merchant origin, credential and tenant/principal/merchant/configuration binding. Subsequent requests retain that binding and session currency. HTTPS is required for enrolled external origins. The explicit loopback test mode accepts synthetic credentials only. Do not pass an agent-supplied URL or checkout-returned link as configuration.

The client validates bounded JSON and the supported lifecycle envelope. It does not fully validate or interpret every nested payment handler, extension, amount or action. Exact response bytes are protected evidence; only the validated lifecycle projection and fixed status/error categories are safe for ordinary callers. Neither a `completed` checkout status nor a redirect establishes payment success.

## Retry and recovery responsibilities

The adapter makes one application-level send under HTTP/1.1. The JDK may repeat connection or safe requests under the same deadline, retaining the immutable origin, credentials, method, path, body and caller idempotency key. There is no universal one-physical-request guarantee. The supported launch configuration must not enable `jdk.httpclient.enableAllMethodRetry`; checking the current property cannot inspect a value previously cached by the JDK. No process-global retry settings are changed.

The deadline bounds connection, headers and the full body. Fixed byte/structure limits and cancellation protect against oversized or stalled responses. Redirects are disabled. Returned diagnostic metadata contains validated fixed values or trusted-echo indicators, never arbitrary peer messages or credentials.

After a mutating request loses its response, receives a server error or returns invalid success data, its commerce effect is uncertain. Preserve the original command/key and reconcile that identity. An HTTP failure cannot authorize a replacement charge, another execution owner or release of financial exposure.

This client has no durable request journal. Before integrating it into LangGraph-driven purchasing, the application must persist the immutable command, dispatch outside the database transaction, then recheck current scope and checkout revision while recording the correlated result. LangGraph coordinates that workflow through the durable runtime; payment state remains authoritative in the transaction layer. Existing native freeze/validate calls run under the payment transaction, so injecting remote HTTP there is not supported. See [ADR 0009](adr/0009-external-checkout-adapters.md).

## Evidence and scope

Java tests use an independently authored local HTTP peer and record actual synthetic create/retrieve/update/cancel traffic. The independent Python verifier checks those fresh captures against hash-verified upstream schema bytes without network schema resolution. The aggregate passes the same execution identity to Java and Python, rejecting missing or stale captures. The capture excludes headers, credentials and negative-test canaries and is not published in the safe evidence bundle.

This is local wire/schema evidence, not a successful Stripe, Adyen or merchant sandbox exchange. External completion, financial delegation, real customer authentication, verified provider events and recoverable platform wiring require their own reviewed profiles and evidence. The existing certification gaps remain open.
