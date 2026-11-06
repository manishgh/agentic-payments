# Review 0003: Independent synthetic-preview design review

Status: completed re-review, 2026-11-06. Recommendation: accept the bounded first-slice design described below; broader execution and real-protocol gates remain open. Reviewer role: independent senior protocol/concurrency design reviewer; distinct from the design author and plan reviewer. This is document review, not code review or executed conformance evidence.

## Scope

Reviewed design: [internal API](../api-contracts.md), [transaction model](../data-model.md), and [ADR 0001](../adr/0001-simulator-preview.md), against [requirements](../../specification/0.1.0-draft/README.md), [simulator cases](../simulator-and-conformance.md), and [G2-S](../roadmap.md). The target is `local-fake-single-tender-0.1`, not a real AP2/ACP/provider integration.

The single-tender restriction is appropriate. CT-008 is not a passed optional voucher/split-tender profile: this preview must reject those requests before reservation. Synthetic challenge checks can exercise the local CT-005 invariants without establishing real 3DS, OTP, wallet, or bank authentication behavior.

## Initial findings

| ID | Severity / gate impact | Finding and required resolution |
| --- | --- | --- |
| DR-001 | Blocking G2-S contract acceptance | Exact request/response schemas and checkout canonical field order/golden bytes are explicitly required but deferred. The approval hash binding cannot be implemented consistently from unspecified bytes. Freeze the selected slice's schemas, canonical serialization and positive/negative fixture values, or keep the corresponding implementation gate open. |
| DR-002 | Blocking local expiry invariant | The injected run clock determines authority/action expiry, but clock advance has no specified ordering against transaction checks and commit. Define clock snapshot/advance serialization and include it in the lock order; test an advance racing approval, action continuation and dispatch admission. |
| DR-003 | Blocking run isolation | Sessions are bound to a run, while lookup and foreign-key prose is only tenant-qualified. Another run in the same tenant must not supply grants, fixtures, intents, command keys or events. Specify tenant-plus-run scoping and same-tenant cross-run rejection tests. |
| DR-004 | Blocking the stated pre-scaffold gate | ADR runtime versions and dependency/license inventory remain unselected although its G2-S criteria require verified toolchain baselines. Record them before scaffolding, or explicitly narrow acceptance and retain that preimplementation dependency. Do not report full G2-S as passed with its own criterion missing. |
| DR-005 | Cross-document consistency | EXT-009's unqualified “before dispatch” differs from a committed admission followed by a later physical send. Name dispatch admission as the local linearization point and preserve the already-admitted physical-send gap. |
| DR-006 | Contract completeness | Action GET must expose the version needed by action-complete compare-and-set; grant revocation needs a defined way to obtain its current version. Include these in the exact schema contract. |

Findings were sent to the design author and integration owner, corrected, and independently re-reviewed. The initial findings above are retained as review history; their final disposition follows.

## Re-review and disposition

| ID | Disposition and evidence |
| --- | --- |
| DR-001 | Closed for the bounded first slice. API contracts now specify closed request/success/error fields, exact money/identifier bounds, canonical checkout field order and a 402-byte golden snapshot. The reviewer independently computed its byte count and SHA-256 using PowerShell/.NET and matched the documented `c1f63c9e22cb8953af008e171b42d17fa188e027651ff07fc8620fceb6c238ad`. Executable schemas and negative fixtures remain implementation acceptance evidence. |
| DR-002 | Closed in design. Every mutating core transaction first locks its run row and snapshots virtual time; clock advance uses that same lock. The documented total order prevents an advance between eligibility check and commit. Tests must still prove the competing interleavings. |
| DR-003 | Closed in design. Authenticated tenant and run scope lookups, references, command identities, journal/events and worker work; grants/budgets are isolated by run. Same-tenant cross-run refusal is an explicit required test. |
| DR-004 | Resolved by an explicit scope change, not by claiming a working runtime. Core targets are selected; exact distribution/checksum pins, dependency/license inventory and actual tool availability are mandatory IMP-001 bootstrap work before any application source. Runtime installation, reproducible build and database tests have not passed. Python/LangGraph and UI toolchain decisions remain outside this first slice. |
| DR-005 | Closed. EXT-009 now names committed dispatch admission and explicitly preserves the physical-send gap. Synthetic CT-005 scope and unsupported CT-008 scope are distinguished in the conformance brief. |
| DR-006 | Closed. Action reads/results expose versions; revocation is a monotonic idempotent command without an inaccessible version prerequisite. Error responses also now include the stated common contract/version fields. |

No unresolved critical design finding remains for create/quote/approve/reserve/read under the scope below. Serializing all core mutations in one run is acceptable for deterministic simulation; it is not a throughput design for the future managed service. The independent adapter journal must retain a separate commit boundary when that later slice is reviewed and implemented.

## Invariants already addressed by the proposed design

- An immutable sole attempt and execution owner prevent a second graph/browser/merchant charge path. Financial-operation identity survives delivery retries and restart.
- Command idempotency binds validated inputs; the independent simulator journal deduplicates operation keys. Both identity lifetimes and request-conflict behavior are explicit.
- ADMITTED, PENDING and UNKNOWN retain full exposure. NOT_FOUND is not no-effect evidence. Atomic simulator sealing fences a delayed send before releasing a reservation; this is clearly a synthetic adapter assumption.
- Grant locking orders admission versus revocation. Approval reserves budget atomically; events consume or release it with state transitions. The physical send cannot be undone by local revocation.
- Trusted action completion has separate subject/session authorization and one-use binding. It never establishes payment success; graph checkpoints contain safe references rather than payment authority.
- Late matching evidence can resolve unknown outcomes; contradictory final facts preserve evidence, freeze new admissions, and fail the scenario rather than manufacturing a clean financial result.

These are satisfactory proposed rules, subject to implementing and testing them. They are not evidence that any running component enforces them.

## Verdict, next slice and limits

Recommend **G2-S limited acceptance** for the design of [implementation slice 001](../implementation-slice-001.md): local-fake create, quote, trusted approval, exact atomic reservation and authoritative read/restart persistence. The integration owner records actual gate acceptance in [gate decisions](gates.md). This recommendation permits proceeding to mandatory bootstrap and then that bounded implementation; it does not claim a scaffold or runtime already works.

Before any application source, bootstrap must verify and pin the selected distributions/checksums, required dependency/license inventory and tool availability. Build/reproducibility evidence, PostgreSQL migration/integration tests, strict schemas, canonical and altered-binding fixtures, budget contention, idempotent replay, expiry/revocation ordering, isolation, restart and privacy checks must pass before IMP-001 is complete. A separate senior code reviewer must review the implementation.

This acceptance does **not** approve implementation of financial dispatch, trusted challenge execution, adapter journal/sealing, event ingestion/reconciliation, LangGraph or UI clients. Their proposed rules received concept scrutiny here but require later bounded design/code review and executable recovery evidence. It is not full simulator conformance or G3 acceptance.

G2-P remains open independently: immutable upstream pins, real trust/credentials/signatures/receipts, permitted AP2/ACP mapping and external customer-action transport require their own review and evidence. No AP2/ACP/provider compatibility or certification is established.

## Reviewed artifact identity

These SHA-256 values identify the exact file bytes read for final review, before any later acceptance annotations. Substantive changes require renewed review; the integration owner must associate its milestone commit with this record.

| Artifact | SHA-256 |
| --- | --- |
| `docs/api-contracts.md` | `1a125e83dac31bfeef8408a516cfca9dab1d68cce2d6149feab0b3a9d92b65bb` |
| `docs/data-model.md` | `05aab175de753df19fae166e3a3b0a4809afe5d9ac73080fb990d5e800582639` |
| `docs/adr/0001-simulator-preview.md` | `b5bd4bad4836394495a17cc76b48f326f3b69ea8cd57690f82f54631fd1933de` |
| `docs/implementation-slice-001.md` | `7e37b936696437a399a31fccef6b211609b5f85225d6932245bf12a67e91bb37` |

No application tests, payment operations, migrations, provider calls or cryptographic interoperability tests were executed for this document review. Later code review and executable failure tests remain necessary even after the design gate passes.
