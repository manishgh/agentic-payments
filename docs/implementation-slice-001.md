# First implementation slice: persisted synthetic approval

Status: implemented for the bounded local profile; retained as the original acceptance task. See [implementation evidence](implementation-evidence.md) and [gate decisions](reviews/gates.md) for the later execution scope and actual validation.

## Outcome

A developer can create a local-fake intent, quote a trusted fixture, approve the immutable checkout and observe an atomic budget reservation after restarting the Java service. No financial dispatch, real provider, live ACP checkout, AP2 signature verification or customer authentication is claimed by this slice.

## Read first

- [Agent instructions](../AGENTS.md)
- [Internal API](api-contracts.md)
- [Transaction model](data-model.md)
- [Preview ADR](adr/0001-simulator-preview.md)
- [Independent design review](reviews/0003-design-review.md)

## Sequence

1. Close any remaining scoped design blockers. Record the accepted requirements and exact design revision.
2. Bootstrap the chosen Java/Maven/PostgreSQL toolchain in the workspace or isolated CI; verify release artifacts/checksums and required dependency licenses. Do not use production services or install global tools silently.
3. Add the smallest build and immutable types for Money, run/tenant scope, checkout snapshot, grant, intent, reservation and accepted command identity.
4. Materialize the reviewed canonical checkout bytes and digest as a fixture; add negative altered-amount/merchant/revision cases. Materialize strict command schemas for the implemented endpoints.
5. Implement create -> quote -> approve -> status through domain/application ports with PostgreSQL-backed atomic persistence. Keep HTTP mapping and any chosen JSON library out of the domain.
6. Add a reproducible local fixture seed/reset path isolated by tenant/run. Restart preserves approvals, reservations and idempotency identities.
7. Implement and run the tests below; have a separate Sol 6.1 high senior engineer review code. Escalate concurrency/security uncertainty to Astra.
8. Document actual build/run/test commands, update backlog/status and push a focused implementation branch after checks. No public package release or live deployment.

## Acceptance cases

| Case | Required observation |
| --- | --- |
| Exact money | Reject zero, negative, fractional, exponent, unsupported currency and overflow; reserve exact minor units. |
| Checkout binding | Canonical golden bytes/digest match; altered approved fields cannot be accepted. |
| Atomic approval | Attempt, intent version, reservation, budget counters and idempotency result commit together or not at all. |
| Budget contention | Concurrent approvals cannot reserve more than the fixture budget; one rejected command has no partial effects. |
| Command replay | Same identity/digest returns stored result; changed input with same key conflicts; distinct key cannot approve an already-approved intent again. |
| Isolation | Different tenant or same-tenant/different-run session cannot access or mutate an object, fixture or authority. |
| Expiry/revocation | Run-clock and grant locks give deterministic ordering; expired/revoked grant cannot approve. |
| Restart | Stored status, reservation and command identity survive a process restart. |
| Privacy | Synthetic sensitive canaries do not appear in graph-safe output or logs. |
| Capability refusal | Unsupported real providers, split tender and unknown required capability fail before reservation. |

## Dependencies and boundaries

Runtime targets are design decisions in ADR 0001. Exact distribution locks, test-library/JDBC/JSON choices and reproducible build evidence belong to bootstrap and must be recorded before a tested build is claimed. Local inspection on 2026-11-06 found no runnable Java/Maven on PATH and the `python` Windows alias was unusable. PowerShell documentation checks work. This is a toolchain preparation task, not a reason to alter the agreed architecture.

Dispatch, trusted challenges, simulator journal, reconciliation, LangGraph, UI and external adapters are later reviewed vertical slices. This slice proves persisted authority and reservation behavior only. Estimated effort is 1-3 focused working days after bootstrap/design prerequisites; it is not an unattended schedule.
