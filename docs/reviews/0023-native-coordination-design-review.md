# Review 0023: Native application coordination design

Status: independent design review completed, 2026-11-07. Recommend integration-owner acceptance of ADR 0008 at the exact identity below. No blocking design finding remains. Implementation and source acceptance are separate gates.

Reviewer: independent architecture/protocol reviewer, distinct from the ADR author. Baseline: `5e9d6329b0d8d67d92937c00034b888934e12b07`. The reviewer owns this report only and has not changed the ADR, source or tests. Assessment included repository/workspace instructions, README, architecture and durable-composition documentation, existing admission design/reviews, the native portions and transaction/worker boundaries of `JdbcPaymentStore`, composition contracts and current admission, registration and crypto-outage tests.

## Boundary assessment

The proposed application component owns meaningful policy: exact artifact reconstruction and strict decoding, stored digest comparison, issuance and immutable staging, independent verification of reloaded mandates, and verified-role/expiry checks. These responsibilities can be exercised together against existing replacement/decorated protocol and artifact ports without JDBC. Moving only delegating wrappers would not satisfy this decision.

The typed evidence records preserve the existing serialized field names and string-form instants. JDBC retains serialization and row mapping. Application methods do not accept SQL maps, connections, sessions, transaction objects or generic callbacks. No additional backend-specific interface or unchecked financial proof is introduced. Existing ports and registration remain the replacement mechanism.

This is a deliberately partial IMP-010 extraction. The transaction owner still constructs trusted contexts and controls provisioning, enrollment/resolver policy, grant eligibility, replay/challenge ownership, reservation and financial admission. An alternate artifact store is not an alternate admission backend; this decision does not claim a complete `PaymentStore` decomposition or external ACP integration.

## Preserved security and execution order

| Review concern | Accepted disposition |
| --- | --- |
| Resolver binding can move earlier through Java argument evaluation. | Separate freeze/load/validate/verify phases permit binding at the existing points. Approval validates the old checkout using a pre-provision snapshot; mandate verification binds a new snapshot only after provisioning and exact mandate reload. Execution retains separately bound checkout and mandate verification resolvers. |
| Extracted work can accidentally bypass atomic admission or change failure precedence. | JDBC retains the run lock and all predicates/writes. Approval inserts the provisional attempt, validates checkout, provisions, issues/stages, inserts authority/challenges, reloads/verifies, inserts replay identities and effective expiry, then reserves and stores the command result in one transaction. |
| Parsed equality or issuer success can replace original signed evidence. | Stored checkout digest is compared before artifact reads; ACP payload and checkout JWT retain their read order. Mandates are reloaded from immutable storage, reference metadata/bytes and strict UTF-8 are checked, and independent verification follows. Reference-pair digest framing remains unchanged. |
| Persisted verification can lose its current authoritative associations. | Eligibility, revision, authority row associations and persisted expiry are checked before decoding/loading. After verification, effective expiry equality and every existing replay/challenge predicate remain in JDBC. Resolver output remains constrained by authoritative snapshot scope, time, exact enrollment, revocation and validity. |
| Availability failure can be interpreted as authority rejection or no effect. | Existing signing/crypto/artifact unavailability maps to 503 `TEMPORARILY_UNAVAILABLE`/`SAME_COMMAND`; native gates propagate it for transaction rollback. Non-5xx validation failure retains existing EXPIRED/CANCELLED behavior only before admission. |
| Refactoring can renew consent on retry or erase uncertain financial exposure. | Accepted command lookup remains before native effects. Reverification is read-only. PREPARE, post-preparation and DISPATCH gates stay in place; admitted/UNKNOWN reconciliation retains the original operation and does not demand new authority. |

No public HTTP response, profile, capability, dependency, migration or database schema change is accepted by this review. Existing raw-adapter/runtime/parser exception behavior must not be broadened or silently remapped during extraction.

## Required source evidence

Source review must compare the old and new call order, row predicates and persisted record shape, not infer equivalence merely because tests pass. It must confirm the coordinator actually owns the stated artifact/verification policy and has no infrastructure dependency, while JDBC continues to own every financial mutation and authoritative trust constraint.

Required evidence includes coordinator tests for artifact substitution/metadata/profile/UTF-8 rejection, early checkout digest rejection, independent staged-byte reload, invalid role/issuer/hash/expiry output, exact byte/digest preservation, redacted records and read-only reverification. Historical persisted JSON compatibility must be tested independently of the new record serializer. Real PostgreSQL coverage must establish fresh pre-/post-provision snapshots and rollback when staged mandate retrieval or verification fails after provisional rows exist; same-key recovery must succeed only once.

The review requested explicit crypto-outage coverage because artifact outage alone cannot establish its classification. Existing `CryptoAvailabilityIntegrationTest` already exercises checkout and mandate crypto outages with 503/SAME_COMMAND, READY/HELD retention, unchanged identities and evidence, no journal effect, and same-operation recovery; it also checks definitive invalid-signature cancellation. Retain those scenarios and strengthen the existing helper with an unchanged outbox-fence assertion. A duplicate test suite is unnecessary. Keep the existing admission/replay races, restart, expiry/revocation, artifact-outage, UNKNOWN and legacy regressions.

The integration owner runs the focused and complete documented validation against isolated real PostgreSQL, documentation checks and the whitespace check. No Maven or application test was run by this reviewer during design assessment. The review independently read the final ADR addition and recomputed its SHA-256; `git diff --check` passed at that point. These are design observations, not implementation or conformance evidence.

## Reviewed identity and decision

| Artifact | SHA-256 |
| --- | --- |
| [ADR 0008](../adr/0008-native-application-coordination.md) | `597d7bb960f6fcb6b1e295bf795a0834d800673ae552392fc220724999428aa3` |

Accept this design for a bounded implementation followed by independent source review. Preserve this exact design identity; future design changes require explicit review rather than rewriting the historical acceptance. Existing synthetic-profile and certification exclusions remain in force.
