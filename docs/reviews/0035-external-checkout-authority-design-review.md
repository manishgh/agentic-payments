# Review 0035: External checkout authority binding design

Status: independent design review completed, 2026-11-08. Recommend integration-owner acceptance of the exact ADR identity below. No blocking design finding remains. Implementation, independent source review and executable evidence remain separate gates.

Reviewer: independent architecture/security reviewer, distinct from the design and implementation authors. Baseline: `709d0a0901b9b0fcb0b85688d4caabd2b06b5dc5`. Assessment covered repository/workspace instructions, README, architecture, durable native composition, external commerce contracts and their JDBC/application implementations, existing lifecycle reviews and ADR 0014. Only this review report was edited. No Maven, application or conformance execution was performed.

## Scope and decision

The reviewed profile, `local-external-ap2-acp-single-tender-0.1`, binds a protected observation from the durable external commerce lifecycle to existing native AP2 authority and atomic synthetic financial admission. It remains disabled without explicit trusted registration. The controlled synthetic peer, shared virtual-clock domain, single synthetic tender and existing simulator execution owner are essential scope restrictions.

Exact remote response bytes receive an explicitly local synthetic attestation. This is not a merchant-origin signature, production merchant delegation or authority derived from an HTTP response. Trusted approval, enrolled signing identities and independent verification remain required. Remote checkout completion, vendor payment execution, production merchant authenticity and generic mutable-checkout support are excluded.

The bridge uses existing responsibility interfaces with additive fail-closed methods/capabilities. PaymentStore retains indivisible source validation, seal and quote publication. A replacement lifecycle/admission backend must preserve the same authoritative coordination domain; independent CRUD operations cannot satisfy the contract. Trusted registration also requires bounded local callbacks under the run lock. Remote artifact/signing services are not enabled merely because they implement a compatible interface.

## Concerns resolved before acceptance

| Concern | Reviewed disposition |
| --- | --- |
| Lifecycle Document proves only a bounded envelope, not complete financial terms. | The checkout boundary independently validates a strict, fully closed synthetic response subset, including all nested fields, exact integral money and line/root arithmetic. Unknown handlers, fields and actions reject. Pinned-schema validation of actual peer responses remains required evidence. |
| Regenerating a native fixture could discard actual external price or signed-byte identity. | Derive the amount from retained bytes, preserve those bytes exactly as the ACP artifact and decoded JWS payload, and independently compare normalized facts. Distinct raw-byte and lifecycle base64-byte digests are recomputed separately. |
| A response UUID or latest projection could select stale, substituted or uncorrelated evidence. | Under the common run lock, require the exact scoped purchase/current command/current response, immutable configuration/request identity, correlated non-stale OK/OBSERVED outcome, matching parsed projections and trusted observation time. |
| Claim recovery does not stop a delayed network sender. | Permanent mutation uncertainty remains ineligible even after a successful later GET. No active STAGED/CLAIMED command can coexist with a new seal. Local recovery never supplies a no-future-send guarantee. |
| Lifecycle updates could race quote, approval or dispatch. | Quote permanently seals the purchase against every new stage, including GET, atomically with quote/result publication. Direct stage and claim enforce the seal. All native-family admission gates recheck the original binding/configuration/deadline. |
| A sealed purchase could fund another intent or become mutable after failure/expiry. | Unique purchase ownership and unique intent binding persist through expiry, revocation, denial and payment outcome. No release/requote or replacement-intent recovery is implemented. Failed quote transactions publish neither seal nor quote. |
| Approval and worker callers have a different principal from the commerce creator. | Persist the original source principal in the trusted intent binding; later checks use that identity rather than the current caller or a request-supplied principal. Current caller authorization remains independently required. |
| Freshness could be fabricated from quote time or historical records. | Only promoted current OK outcomes receive locked-run observation time. Old, stale and other outcomes remain timestamp-less. The effective deadline is bounded by original observation plus 300 seconds and remote expiry; exact equality expires. |
| Key expiry wording exceeded current native behavior. | The final ADR preserves independent current key-validity/revocation checks at every applicable gate without claiming a new stored deadline capped to all key expiries. |
| Replaceable callbacks could introduce network I/O under database locks. | Trusted registration must assert bounded-local-callbacks-0.1 for every under-lock protocol, authority, artifact, provisioning, signature and key-resolution callback. Remote implementations need a separately reviewed transaction strategy. |

## Required source and executable evidence

Source review must verify external-profile routing in PaymentService, ExecutePayment and every JDBC native-family expiry, execute, prepare, post-prepare and dispatch guard. The pre-dispatch check and admission record must share the existing revocation/configuration ordering. Already admitted unknown work must keep its original operation and exposure and remain reconcilable after expiry or configuration changes.

Tests must exercise actual PostgreSQL serialization and rollback: quote versus stage in both orders, two intents competing for one purchase, configuration changes, uncertainty with a delayed original sender, quote/seal/result rollback, approval/replay/reservation rollback, and unchanged accepted-command replay. A new JVM must reconstruct exact artifacts, enrollment, binding and permanent seal. V009 upgrade evidence must preserve existing history/checksums and leave old observations null/ineligible rather than backfill freshness.

Boundary tests must reject raw/projection mismatches, malformed UTF-8/JSON, duplicate members, unsupported nested fields, invalid arithmetic, altered request/response digests, stale response selection, cross-scope access and absent capabilities/registration. Accepted whitespace/order variations must remain distinct exact byte identities. Replacement/decorated implementations must demonstrate the same behavior rather than relying on concrete reference classes.

The runnable HTTP journey must use the actual controlled merchant response, trusted UI approval and synthetic execution, including restart and challenge/recovery coverage. Independent locked-schema checks must validate the new financial-ready response captured over HTTP. No `/complete` or vendor/provider-journal financial call is authorized. Safe responses, checkpoints, logs and evidence must exclude protected bytes, merchant session identifiers, keys and credentials.

This review grants no implementation pass, production suitability, live merchant guarantee or certification. The integration owner must explicitly accept the design before implementation and later complete focused checks, the unchanged-source PostgreSQL-backed aggregate, documentation checks and whitespace checks.

## Reviewed identity and handoff

The reviewer independently recomputed SHA-256 of the frozen LF working bytes of [ADR 0014](../adr/0014-external-checkout-authority-binding.md): `0635b5dd8747dd8b32b0a93079a450eab42e0b8847c2f811a2c218d403d5938d`. This is the reviewed design identity. Semantic changes require an explicit amendment and renewed review; capability/API refinements may not weaken its guarantees.

`git diff --check` passed during design assessment. No other executable verification is claimed. This report is frozen at handoff; the integration owner records acceptance separately.
