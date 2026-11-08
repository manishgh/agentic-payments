# Internal simulator API design

Status: implemented for bounded synthetic profiles; reviewed execution clarifications are in ADR 0002, ADR 0006 and gate records. Revision: `simulator-api-0.1-draft`. Earlier review hashes identify their reviewed source; native profile selection is an additive draft revision.
Targets [EXT-001..012](../specification/0.1.0-draft/README.md) and [CT cases](simulator-and-conformance.md).
These are project-internal endpoints, not AP2/ACP wire schemas. The native profile embeds signed AP2/ACP artifacts internally; external merchant/provider transport remains disabled.

## Scope and transport

- Base path `/internal/v0.1`; JSON UTF-8; reject duplicate JSON keys, unknown material fields, invalid enums and oversized bodies.
- Profile is explicitly `local-fake-single-tender-0.1` or `local-ap2-acp-single-tender-0.1`; protocol version exactly `0.1.0-draft`; no implicit downgrade (EXT-001/CT-001). Native approval reports authority profile `ap2-v0.2-direct-tap-synthetic-0.1`; see [composition details](durable-composition.md).
- Only provider `simulator`, method `synthetic-card`, credential type `synthetic-reference`; action `synthetic-challenge` or none.
- One positive amount, one currency and exactly one tender allocation equal to the full quoted total. Split tender/vouchers reject (EXT-002/008).
- Fixtures permit EUR and USD with exponent 2; amounts are decimal strings of integer minor units, `1..999999999999`.
- IDs are opaque server-generated UUIDs; example identifiers below are shortened for readability, not literal valid IDs.
- JSON money is `{"currency":"EUR","minorUnits":"1250"}`; never JSON floating point, negative, fractional or exponent notation.
- Dates use RFC3339 UTC; simulator deadlines use the injected run clock, never caller-controlled timestamps.
- All responses include top-level `contractVersion`, `correlationId`; successful resource responses include that resource's integer `version`.
- No live hostname, real provider name, real credential, arbitrary callback URL or raw AP2/ACP payload is accepted.

## Caller identity and authorization

An infrastructure authentication port resolves a bearer session to tenant, principal, roles and allowed merchants/grants.
The simulator supplies opaque local sessions through an isolated fixture store; these are not production identity or AP2 signatures.
Tenant and run are derived from authentication, never a client body/header override. Every lookup includes authenticated `(tenant,run)`.
Cross-tenant, same-tenant cross-run or unauthorized identifiers return `404 RESOURCE_NOT_FOUND`; missing/invalid authentication returns 401.
Roles: `intent:write`, `intent:read`, `approval:write`, `execution:write`, `authority:revoke`, `action:complete`.
Roles alone do not grant spend authority: merchant allowlist, grant subject, amount, scope, expiry and revocation are checked server-side.
Graph sessions may create/quote/execute/read authorized intents, but cannot approve, revoke or complete a trusted action.
The trusted UI session must match the grant subject/action subject; administrative revocation requires an explicit fixture role.
An API bearer session, authority reference and customer authentication are distinct checks (EXT-005/007/010).

## Shared command and replay rules

Every POST requires `Idempotency-Key` (16..128 printable ASCII characters) and a JSON body, including empty commands.
Scope: `(tenant, run, principal, HTTP method, route template, target resource ID, key)`; create has target `new`.
Store a canonical validated request digest including `expectedVersion` and selected profile; authorize before returning stored results.
The same key/digest returns the original accepted command response, with `Idempotency-Replayed: true`; read status for current state.
The same key with different inputs returns 409 `IDEMPOTENCY_CONFLICT`; do not send another operation.
Changed authorization/revoked API session can deny replay access; successful replay never overrides current access rules.
Nonaccepted validation/authentication requests create no financial operation; accepted command outcome and key mapping commit atomically.
Concurrent same-key requests wait on the unique command identity; infrastructure failure before commit may return 503 and the same key is safe.
Retain command and simulator-operation identities for the run lifetime; never recycle them after expiry or local restart (CT-003/004).

## Endpoint table

| Endpoint | Required role | Body/result and permitted state |
| --- | --- | --- |
| `POST /intents` | intent:write | Create DRAFT; tenant merchant and synthetic scenario must be allowed. |
| `POST /intents/{id}/quote` | intent:write | DRAFT/QUOTED only; resolve trusted fixture cart, create immutable checkout revision/digest. |
| `POST /intents/{id}/approve` | approval:write | QUOTED only; bind the explicitly selected authority profile, create sole attempt/owner, reserve full total atomically. Native authority additionally consumes verified replay identities in that transaction. |
| `POST /intents/{id}/execute` | execution:write | APPROVED only; queue effect-free preparation; repeated accepted key returns original response. |
| `GET /intents/{id}` | intent:read | Return authoritative state, selected money/profile, attempt/action references and safe evidence summary. |
| `GET /intents/{id}/checkout` | approval:write | QUOTED/APPROVED only; trusted grant subject reads core-owned merchant, money, version and checkout binding for approval. |
| `GET /actions/{id}` | action:complete | Trusted subject only; retrieve synthetic challenge descriptor; no graph access. |
| `POST /actions/{id}/complete` | action:complete | Verify current synthetic trusted action; READY or pre-admission failure; never proves payment success. |
| `POST /authorities/{id}/revoke` | authority:revoke | Atomically revoke fixture grant; report already-admitted attempts separately from blocked future admission. |

No update-total, arbitrary route switch, client success assertion, refund, capture, cancel-provider or split-tender endpoint exists in v0.1.
Status GET supports polling; no network webhook endpoint is exposed. Verified simulator events enter through an internal runner port.
Runner clock/fault scheduling is a separate test interface, unavailable to graph/UI sessions and absent in managed profiles.

## Closed request schemas

All listed properties are required, non-null and exclusive; bodies are objects; strings are case-sensitive; arrays disallow duplicate entries.
`expectedVersion` is an integer `1..2147483647`; checkoutRevision has the same bounds; API-generated resource IDs are UUID strings.
Fixture IDs match `[a-z0-9][a-z0-9-]{0,63}`; digest is `sha256:` plus exactly 64 lowercase hex characters; body limit is 16 KiB.
| Command | Exact properties and validation |
| --- | --- |
| Create | `merchantId`, `scenarioId`: allowed fixture IDs; `profile`: exact selected profile; `requiredCapabilities`: 0..16 distinct catalog identifiers. |
| Quote | `expectedVersion`; `cartFixtureId`: allowed fixture ID; `tenders`: exactly one object with `provider`, `method`, `credentialReference`, all allowed fixture identifiers. |
| Approve | `expectedVersion`, `checkoutRevision`, `checkoutDigest`, `authorityReference`: fixture grant ID; authenticated trusted subject must match. |
| Execute | `expectedVersion`, `attemptId`: UUID; equal to the sole stored attempt. |
| Complete action | `expectedVersion`, `decision`: exactly `approve` or `deny`. |
| Revoke | `reason`: exactly `user-request`, `policy-revoked` or `test-revocation`; monotonic revoke has no expectedVersion precondition. |
Supported capability catalog is `single-tender`, `exact-money`, `customer-action`, `idempotent-execution`, `reconciliation`; unknown required identifiers return 422.
Only server-configured scenarios are accepted; scenarios cannot name callbacks, run-clock values or provider endpoints.
Examples use shorthand resource IDs and placeholder digest text; production-shaped fixture tests must substitute UUIDs and the exact digest below.

Successful responses are closed objects: common fields above plus exactly these endpoint fields; response IDs follow the request ID rules.
| Endpoint | Required additional fields |
| --- | --- |
| Create | `intentId,state,version`; state DRAFT. |
| Quote | `intentId,state,version,checkoutRevision,checkoutDigest,money,quoteExpiresAt`; state QUOTED. |
| Approve | `intentId,state,version,attemptId,executionOwner,authorityProfile,authorityVersion,reserved`; state APPROVED. |
| Execute | `intentId,state,version,attemptId,operationId`; state PREPARING in the accepted command response. |
| Get action | `actionId,version,type,attemptId,state,expiresAt,choices`; choices approve/deny when OPEN, otherwise empty. |
| Complete action | `actionId,version,state,intentState`; action state VERIFIED or DENIED; version is the updated action version. |
| Revoke | `authorityId,state,version,futureAdmissionBlocked,alreadyAdmittedAttemptIds`; state REVOKED; IDs sorted lexically. |
| Get intent | `intentId,state,version,profile,runId,attemptId,operationId,money,exposureState,reconciliationRequired,actionId,outcomeEvidenceId`. |
| Get checkout | `intentId,state,version,merchantId,checkoutRevision,checkoutDigest,money,quoteExpiresAt`; state QUOTED/APPROVED; trusted subject only, no credential reference. |
Get-intent IDs/money not yet created are null; exposureState is NONE/HELD/CONSUMED/RELEASED; reconciliationRequired is boolean.
Money/reserved use the exact money shape; timestamps are RFC3339 UTC; other state enums follow the tables below; unknown response fields require a new contract revision.

## Create and quote examples

`POST /intents` (create key), response 201:
```json
{"merchantId":"merchant-demo","scenarioId":"challenge-then-success","profile":"local-fake-single-tender-0.1","requiredCapabilities":["single-tender","exact-money","customer-action"]}
```
```json
{"contractVersion":"simulator-api-0.1-draft","intentId":"intent-1","state":"DRAFT","version":1,"correlationId":"corr-1"}
```
`POST /intents/intent-1/quote` (new quote key), response 200:
```json
{"expectedVersion":1,"cartFixtureId":"cart-1250-eur","tenders":[{"provider":"simulator","method":"synthetic-card","credentialReference":"fixture-credential-1"}]}
```
```json
{"contractVersion":"simulator-api-0.1-draft","intentId":"intent-1","state":"QUOTED","version":2,"checkoutRevision":1,"checkoutDigest":"sha256:fixture-digest","money":{"currency":"EUR","minorUnits":"1250"},"quoteExpiresAt":"2026-11-06T10:15:00Z","correlationId":"corr-2"}
```
The cart fixture is server-owned and determines merchant, items, delivery description and total; caller-supplied prices reject.
The fixture reference must belong to the authenticated tenant and merchant. Requote invalidates the previous digest before approval.
Fixture digest uses the versioned canonical project checkout encoding, not AP2 signed bytes or ACP canonicalization.
Canonical encoding: UTF-8 RFC8259 JSON with fixed schema field order, no insignificant whitespace, NFC fixture strings and integer strings.
Snapshot field order is `schema,merchantId,checkoutRevision,catalogVersion,cartFixtureId,items,fulfillmentFixtureId,money,tender`.
`items` preserve catalog order; each uses `sku,quantity,unitMinorUnits`; money uses `currency,minorUnits`; tender uses `provider,method,credentialReference`.
`schema` is `local-checkout-0.1`; quantity/revision are positive JSON integers; all other numeric money is integer strings; fixture strings prohibit control characters.
Hash is SHA-256 over those exact bytes, rendered as lowercase hex after `sha256:`; no optional/null fields, maps or caller ordering affect bytes.
Catalog version is immutable and included in binding; no cross-protocol canonicalization claim is made.
Golden snapshot is exactly the following 402 UTF-8 bytes, excluding the code-fence newline; all fixture strings use ASCII and need no escaping:
```json
{"schema":"local-checkout-0.1","merchantId":"merchant-demo","checkoutRevision":1,"catalogVersion":"fixtures-0.1","cartFixtureId":"cart-1250-eur","items":[{"sku":"sku-demo","quantity":1,"unitMinorUnits":"1250"}],"fulfillmentFixtureId":"delivery-demo","money":{"currency":"EUR","minorUnits":"1250"},"tender":{"provider":"simulator","method":"synthetic-card","credentialReference":"fixture-credential-1"}}
```
Digest: `sha256:c1f63c9e22cb8953af008e171b42d17fa188e027651ff07fc8620fceb6c238ad` (computed with .NET SHA-256, not application runtime validation).
The first catalog restricts all fixture values to printable ASCII excluding quotes/backslashes; quantities are 1..100; no alternate escaping/NFC ambiguity is allowed.
Canonical field order and values are reconstructed from validated typed catalog data; never hash arbitrary client JSON or stringify an unordered map.

## Approval and execution examples

`POST /intents/intent-1/approve` (approval key), response 200:
```json
{"expectedVersion":2,"checkoutRevision":1,"checkoutDigest":"sha256:fixture-digest","authorityReference":"fixture-grant-1"}
```
```json
{"contractVersion":"simulator-api-0.1-draft","intentId":"intent-1","state":"APPROVED","version":3,"attemptId":"attempt-1","executionOwner":"simulator-core","authorityProfile":"local-fake","authorityVersion":1,"reserved":{"currency":"EUR","minorUnits":"1250"},"correlationId":"corr-3"}
```
Approval checks merchant/payee, checkout revision/digest, full money, method/credential, subject, validity and immutable execution identity.
The same transaction locks run/grant/budget/intent, snapshots the run clock, checks capacity/revocation, creates reservation and sole attempt, then commits.
Insufficient capacity returns 409 `BUDGET_EXCEEDED` with no attempt/reservation; changed checkout returns 409 `CHECKOUT_CHANGED`.
`POST /intents/intent-1/execute` (execution key), response 202:
```json
{"expectedVersion":3,"attemptId":"attempt-1"}
```
```json
{"contractVersion":"simulator-api-0.1-draft","intentId":"intent-1","state":"PREPARING","version":4,"attemptId":"attempt-1","operationId":"operation-1","correlationId":"corr-4"}
```
The core persists operation/outbox identities before any adapter call. Preparation is guaranteed to have no financial effect in this profile.
If a challenge is needed, state becomes WAITING_ACTION; otherwise READY. A worker admits READY separately under current authority.
Execution owner is `simulator-core` forever for this attempt; LangGraph/merchant/browser cannot also execute it (EXT-003).

## Trusted action and revocation examples

`GET /actions/action-1`, response 200 to the trusted subject only:
```json
{"contractVersion":"simulator-api-0.1-draft","actionId":"action-1","version":1,"type":"synthetic-challenge","attemptId":"attempt-1","state":"OPEN","expiresAt":"2026-11-06T10:10:00Z","choices":["approve","deny"],"correlationId":"corr-5"}
```
The trusted UI uses an application-owned same-origin route; there is no arbitrary redirect or reusable action secret in the graph.
`POST /actions/action-1/complete` (action key), response 200:
```json
{"expectedVersion":1,"decision":"approve"}
```
```json
{"contractVersion":"simulator-api-0.1-draft","actionId":"action-1","version":2,"state":"VERIFIED","intentState":"READY","correlationId":"corr-6"}
```
Completion locks run then authority/intent/attempt/operation/action in global order, checks tenant/run/subject/binding and frozen clock deadline, then consumes once.
An expired or denied action cannot become VERIFIED; new-key duplicate completion returns 409 `ACTION_ALREADY_CONSUMED`.
Recheck grant validity at dispatch admission even after VERIFIED; synthetic action approval is not AP2 mandate verification.
`POST /authorities/fixture-grant-1/revoke` (revocation key), response 200:
```json
{"reason":"user-request"}
```
```json
{"contractVersion":"simulator-api-0.1-draft","authorityId":"fixture-grant-1","state":"REVOKED","version":2,"futureAdmissionBlocked":true,"alreadyAdmittedAttemptIds":["attempt-1"],"correlationId":"corr-7"}
```
Revocation locks run then authority; ACTIVE -> REVOKED increments its epoch, blocks admission and cancels/releases pre-admission attempts atomically.
Already-REVOKED with a new command key returns its current revoked metadata without another epoch/release; callers never need to discover a current grant version.
Admission is the durable dispatch ordering point, immediately before the worker's effectful send; a committed admission can outlive revocation.
Already admitted is not a claim that the provider accepted: preserve exposure and reconcile; never label it safely cancelled from revoke alone.

## State and outcome contract

| From | Allowed next state / cause |
| --- | --- |
| DRAFT | QUOTED via quote; EXPIRED via fixture deadline. |
| QUOTED | QUOTED via replacement quote; APPROVED via approval; EXPIRED via quote deadline. |
| APPROVED | PREPARING via execute; CANCELLED/EXPIRED if authority revoked/expires before admission. |
| PREPARING | WAITING_ACTION or READY from effect-free prepare; FAILED for definitive validation failure; CANCELLED/EXPIRED pre-admission. |
| WAITING_ACTION | READY from verified action; FAILED from denial/action expiry; CANCELLED/EXPIRED pre-admission authority loss. |
| READY | ADMITTED from dispatch transaction; CANCELLED/EXPIRED on pre-admission authority loss. |
| ADMITTED | PENDING, UNKNOWN, SUCCEEDED or FAILED from authenticated adapter facts. |
| PENDING | UNKNOWN on lost certainty; SUCCEEDED/FAILED from authoritative final evidence. |
| UNKNOWN | PENDING, SUCCEEDED or FAILED from authoritative reconciliation of the same operation only. |
| SUCCEEDED / FAILED / CANCELLED / EXPIRED | No normal financial transition; duplicate/stale facts ignored, contradictory facts quarantined and escalated. |

SUCCEEDED means the simulator journal confirms the full synthetic charge, not authorization/capture/settlement/fulfilment in a real system.
FAILED after admission requires authoritative sealed no-effect evidence. NOT_FOUND, timeout, expired lease or browser return is insufficient.
Full exposure remains held in ADMITTED/PENDING/UNKNOWN; success consumes it, definitive no-effect releases it (EXT-004/009).
The status response includes `outcomeEvidenceId`, `reconciliationRequired`, `exposureState`, action reference and run/profile identifiers.
It excludes bearer sessions, credential references, action descriptors, raw provider bodies and grant payloads from graph-safe status.
Late matching success resolves UNKNOWN; late stale pending cannot overwrite success; conflicts preserve facts and freeze new admissions.
`GET /intents/intent-1` example after a lost acceptance response:
```json
{"contractVersion":"simulator-api-0.1-draft","intentId":"intent-1","state":"UNKNOWN","version":8,"profile":"local-fake-single-tender-0.1","runId":"run-1","attemptId":"attempt-1","operationId":"operation-1","money":{"currency":"EUR","minorUnits":"1250"},"exposureState":"HELD","reconciliationRequired":true,"actionId":null,"outcomeEvidenceId":null,"correlationId":"corr-8"}
```

## Error envelope and HTTP mapping

The additive commerce routes use a separate `checkout-lifecycle-0.1` success DTO and the existing error envelope. See [durable external checkout HTTP surface](durable-external-checkout.md#http-surface) for stage/status/dispatch/recover-claim, exact inputs and safe output. No financial completion or raw-document endpoint is added.

```json
{"contractVersion":"simulator-api-0.1-draft","correlationId":"corr-8","error":{"code":"OUTCOME_UNRESOLVED","message":"The existing operation requires reconciliation.","retry":"READ_STATUS"}}
```
400: `INVALID_REQUEST`, `INVALID_MONEY`, `UNKNOWN_FIELD`; 401: `UNAUTHENTICATED`; 403: `FORBIDDEN` for non-object role denial.
404: `RESOURCE_NOT_FOUND`; 409: `VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `INVALID_STATE`, `CHECKOUT_CHANGED`, `BUDGET_EXCEEDED`.
409 also covers `AUTHORITY_REVOKED`, `AUTHORITY_REPLAY`, `ACTION_ALREADY_CONSUMED`, `OUTCOME_UNRESOLVED`; 410: `AUTHORITY_EXPIRED`, `ACTION_EXPIRED`.

`AUTHORITY_REPLAY` has retry advice `NEVER`: a consumed issuer/mandate identity cannot authorize a different admission, even in another run. Read the original payment's status; do not create a replacement payment to recover an uncertain result. An identical previously accepted command still returns its saved response through scoped idempotency.
422: `UNSUPPORTED_PROFILE`, `UNSUPPORTED_CAPABILITY`, `UNSUPPORTED_TENDER`, `AUTHORITY_BINDING_INVALID`; 503: `TEMPORARILY_UNAVAILABLE`.
`retry` is `NEVER`, `SAME_COMMAND`, or `READ_STATUS`; no error recommends a new attempt/provider when an operation might have executed.
Error responses have exactly common top-level fields plus `error`, containing exactly `code,message,retry`; no resource version is present.
Errors contain safe fixed messages and correlation references, never stack traces, session strings or provider payloads (CT-010).
Executable schema/error/golden-byte fixtures are in the versioned specification. The TypeScript review surface renders money and approves the digest/version from the same trusted checkout response. The local catalog also supplies `fixture-grant-usd` for its separate USD budget; `fixture-grant-1` authorizes EUR. Neither fixture is a real AP2 mandate.
