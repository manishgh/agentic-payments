# Review 0026: Execution-command transaction boundary design

Status: independent design review completed, 2026-11-08. Recommend integration-owner acceptance of the exact ADR identity below. No blocking design finding remains. Implementation and source acceptance are separate gates.

Reviewer: independent architecture/protocol reviewer, distinct from the author. Baseline: `7b5d695eb83646a8bef540f2d0ba551001e1fd50`. Assessment covered repository/workspace instructions, README/status/architecture, ADR 0008, durable composition, composition contracts, `PaymentService`/`PaymentStore`, and `JdbcPaymentStore` command, execute, transaction, native verification, eligibility, work and audit paths. The reviewer owns this report only and has changed no production source or tests.

## Scope and boundary decision

Extracting execute is a useful bounded next step: the application owns actual state/version, grant, attempt and replay policy plus operation binding and preparation scheduling. It is smaller than extracting approval's issuance/provisional-write sequence or the worker's external effects. It schedules PREPARE and creates one operation; it does not itself admit or charge a payment. Approval, reservation, dispatch, evidence and UNKNOWN reconciliation remain in their existing owners.

The accepted typed callback is a transaction-lifetime mechanism, not a generic business-command facade. One `PaymentCommandTransaction` owns all reads, accepted-command lookup/write, native verification and invariant-preserving preparation write. No separately committing repository/journal/verifier registration or table CRUD is introduced. Application code must contain the policy, and a deterministic non-JDBC implementation must exercise that same code. Merely relocating the old whole command behind an adapter would fail source review.

The callback remains synchronous, may be retried and cannot release its lock, independently commit, retain a usable handle or introduce external effects. Runtime handle invalidation is required. Existing immutable artifact reads and independent verification remain at their established phases; no new ACP/provider networking is authorized inside the transaction. This is a partial financial-use-case extraction, not proof that an arbitrary backend can replace atomic admission.

## Findings resolved before acceptance

| Concern | Final disposition |
| --- | --- |
| Separate repository, journal and native-verifier views could imply independently composable commit owners and unnecessary interface growth. | Final ADR flattens the operations into one atomic responsibility and explicitly prohibits independently committing registrations. |
| Draft request hashing was exposed only on a transaction-scoped journal although existing request hashing occurs before lock acquisition. | The factory exposes a pure, typed execute-request digest operation before the transaction. Canonical Jackson encoding and historic command digest stay adapter-owned. |
| A broad phrase about attempt association could accidentally strengthen existing policy during a compatibility extraction. | Final ADR spells out the current `authority` and `eligible` predicates and prohibits adding new association rules. The supplied attempt ID remains checked after native verification. |

## Required implementation invariants

Preserve role/version/capability/key/hash ordering before locking; run ownership and persisted virtual time under the lock; current object authorization before accepted-command lookup; and historical result replay before current version/state/grant/native checks. An accepted replay must preserve its stored result while remaining subject to current access control. A request conflict must not create another operation.

On a new command, retain intent, attempt, checkout and grant read order, exact grant error precedence, native verification before supplied-attempt binding, and the original `OperationBinding` fields. The adapter must retain fresh native resolver binding and existing authority/replay/challenge constraints without caching trust in typed facts. Native verification is read-only, with no authority renewal.

Operation, adapter identity, PREPARE outbox sequence, PREPARING state/version/audit and command response commit atomically. Class-40 SQL retries may replace only uncommitted UUIDs; no provider may observe them. Preserve existing unique-conflict, unavailable and composition-error mapping, and do not broaden runtime-exception translation. Return success only after commit. Lost response/uncertain commit recovery retains the same command identity.

Worker PREPARE/DISPATCH gates, revocation ordering and UNKNOWN reconciliation are untouched. The new preparation write cannot release exposure, mark admission, change arbitrary states or claim checkout completion is payment proof. External transport remains outside this transaction seam.

## Evidence required for source acceptance

Application tests must prove the moved policy independently of JDBC/JSON/protocol implementations, including rejection/error precedence, valid legacy/native preparation, accepted replay after later state/grant changes, current access loss, digest conflict, native outage and exact operation binding. Redaction and immutable values must be checked.

Real PostgreSQL evidence must establish rollback after operation/outbox/state/audit provisional writes and at command insertion, including sequence counters and HELD exposure; same-key recovery; persisted restart/replay compatibility against historical fixtures; same-key and different-key execution races; execute/revocation ordering; replay-owner rejection; and native unavailable rollback. Use controlled barriers rather than timing sleeps. Existing native snapshot, approval rollback, worker fence, response-loss and UNKNOWN tests remain required; retained tests may supply evidence when they exercise the exact obligation.

Source review must compare executable order and data predicates against the baseline rather than infer equivalence from green tests. Root owns focused and full isolated-PostgreSQL validation and documentation checks. This reviewer ran no Maven, application or conformance tests during design review.

## Reviewed identity and decision

| Artifact | SHA-256 |
| --- | --- |
| [ADR 0010](../adr/0010-payment-transaction-boundary.md) | `8e107aa727a7403156c3eb31c36cbc025510d923bc2f3744223137c6e313a975` |

Accept this bounded design for implementation and independent source review. Preserve this design identity; later changes require review. Existing synthetic-profile, external-provider and certification exclusions remain.
