# Review 0028: Execution-command transaction boundary source

Status: independent source review accepted, 2026-11-08. Both review findings are resolved; no blocking source finding remains. Acceptance covers the exact bounded source identities below. The integration owner still owns the final unchanged-source aggregate validation and publication gate.

Reviewer is independent of the implementation author. Baseline is `7b5d695eb83646a8bef540f2d0ba551001e1fd50`; the governing design is [ADR 0010](../adr/0010-payment-transaction-boundary.md) at SHA-256 `8e107aa727a7403156c3eb31c36cbc025510d923bc2f3744223137c6e313a975`, accepted by [review 0026](0026-transaction-boundary-design-review.md). Reviewer owns this report only, makes no production/test edits and runs no Maven concurrently with integration validation.

## Boundary assessment

The application now owns actual execute-command policy: role/version/capability/key validation, current merchant authorization before accepted replay, digest conflict/replay decisions, intent/grant/attempt eligibility, native verification ordering, exact operation binding and preparation/result sequencing. The JDBC compatibility entry point delegates to this code. The new responsibility contract carries typed immutable records and one transaction owner rather than independently committing repositories or table CRUD.

Source comparison confirms the normal-path order and `authority`/`eligible` predicates remain aligned with the baseline. The native bridge passes the original private rows to the unchanged verifier while retaining existing resolver snapshots, exact artifact checks and replay/challenge association checks. The typed values contain no SQL maps or infrastructure types. Operation/outbox/state/audit and accepted response writes use the original run-locked transaction helper, including existing rollback, three-attempt class-40 retry and exception mapping. There is no provider call or new external networking.

Transaction handles check active lifetime and owning thread for every operation; they are invalidated when callback work returns or throws, before commit/rollback. Same-factory nested execution is rejected. A retried callback gets a new scope and only the committed operation identity survives. These are trusted in-process seams, with no public HTTP proof or arbitrary preparation endpoint.

## Findings resolved

1. **Actual database contention.** The initial `ordered` helper observed a latch immediately before JDBC execution, which could pass while the second thread was descheduled before sending SQL. The corrected barrier records each PostgreSQL backend PID. Before releasing the winner, the test requires `pg_blocking_pids` to show that the first backend blocks the second. Same-key, different-key and both revocation orderings now establish actual database contention rather than infer it from thread timing.
2. **Replay conflict before response decoding.** The initial `accepted` implementation decoded historical response JSON before the application's digest comparison, changing baseline failure precedence for conflicting input plus a corrupt stored response. `accepted` now returns typed digest metadata only. After comparing it, the application invokes a separate transaction `replay` phase bound by object identity to the retained private row. No raw JSON or arbitrary lazy callback crosses the seam. The new regression confirms conflicting input returns `IDEMPOTENCY_CONFLICT` before decoding, while identical input retains the existing decoding failure. Scope tests reject fabricated metadata and escaped replay handles. This small explicit phase split is consistent with ADR 0010's typed seam and exact-order requirements.

## Validation and limits

Application tests exercise policy independently of JDBC/JSON/protocol implementations, and PostgreSQL tests observe provisional operation/outbox/state/audit/sequence writes before command-persistence faults. They cover same-key recovery, class-40 retry with discarded provisional identity, historical JSON, current authorization, replay ownership, native artifact/crypto outage and scope lifetime/thread confinement/nested-run rejection.

The integration owner ran the corrected focused Maven selection against real PostgreSQL: `ExecutePaymentTest` 6, `ExecutionCommandIntegrationTest` 13, `NativeCoordinationIntegrationTest` 3 and `CryptoAvailabilityIntegrationTest` 3; all 25 methods passed with zero failures/errors/skips. The reviewer independently read those current Surefire XML counts after the run completed. An earlier 24-method run preceded the fixes and is not the final source evidence. The owner also selected a nonexistent `CoreIntegrationTest` name; it contributed no methods and is not claimed as coverage.

The reviewer ran no Maven or application tests. Existing UNKNOWN/reconciliation, process-crash and worker admission guarantees remain outside the extraction and require retained full-run regression evidence. This review does not claim a complete `PaymentStore` decomposition, external transaction-backend equivalence, timed leases, provider integration or certification.

## Accepted source identity

| File | SHA-256 |
| --- | --- |
| `src/main/java/io/agenticpayments/application/ExecutePayment.java` | `5ab3c00c1ef8d5f8a9f9e408ff889d483f76c23721d1e507dcdc0403e8d26088` |
| `src/main/java/io/agenticpayments/application/PaymentCommandTransactions.java` | `56f205fafc5546f6c54a8981e0ec80d0b97b15a2890c7d43d8a5895eb1e57c23` |
| `src/main/java/io/agenticpayments/infrastructure/JdbcPaymentStore.java` | `ee4802081a6574ee471aca49f713846d5d2bbea39cd4bc7d58e0217eace21f02` |
| `src/test/java/io/agenticpayments/application/ExecutePaymentTest.java` | `cc1613c1362745c6216ad592168e4e7224ab0da7ac91c4d7fc8654915457f654` |
| `src/test/java/io/agenticpayments/core/ExecutionCommandIntegrationTest.java` | `f9c9ef20ee9cc7148ea29f04870260c18f5e6458104a15c4eb0e50afddbd8a64` |
| `src/test/java/io/agenticpayments/core/ExecutionCommandFaultDataSource.java` | `50ba8d9ee43251f97afd1eeaed4bf76f8f094ebeab092793085256a003e78b02` |
| `src/test/java/io/agenticpayments/core/RunLockBarrierDataSource.java` | `c78e74e4a34fa77ae8abaec1c7ed035d209f2c2dbde06bb67128648d81947726` |

All seven hashes were independently computed after the fixes. The changed shared barrier helper adds only test backend-PID observation; it adds no production hook. Preserve these identities through the integration gate or request a review of further changes.
