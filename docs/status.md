# Current status and handoff

Updated: 2026-11-06, Europe/Berlin.

## Current milestone

The local synthetic reference implementation is runnable: versioned contracts, Java SDK/core, normalized PostgreSQL migrations and durable simulator, loopback HTTP API, trusted TypeScript UI, LangGraph/SQLite workflow and repeatable evidence runner. Implementation branch: `codex/reference-platform` in [manishgh/agentic-payments](https://github.com/manishgh/agentic-payments).

The product direction remains an AP2-based intent enclosing embedded ACP checkout and provider authentication. This milestone uses explicit synthetic fixtures; no real AP2 signature verifier, ACP wire checkout or external payment provider is implemented.

## Validation and review

The full local suite passed against unchanged source: 47 Java tests (22 real-PostgreSQL core, 23 SDK/checkout and two operation-binding tests), seven HTTP tests, three UI regressions and 12 LangGraph tests, including fresh Python CLI processes. Schema validation covered 20 schemas, 16 positive fixtures, 24 schema-negative fixtures, six binding-negative fixtures and one raw-parser negative. Desktop/mobile UI rendering was inspected in headless Edge.

Separate Astra [core review](reviews/0006-reference-code-review.md) and Sol 6.1 High [client/tooling review](reviews/0007-client-tooling-review.md) accepted the bounded source after identified defects were corrected. [Gate records](reviews/gates.md) distinguish the original reservation design from the later execution/client scope.

The aggregate evidence runner returns `NOT_CERTIFIED`, even when preview checks pass. Full seeded multi-intent replay, exhaustive adversarial/privacy evidence and certification policy are unfinished. Exact run/source evidence is recorded in [implementation evidence](implementation-evidence.md).

## Publication

Earlier milestones: documentation `15f0776a2fa1628ac8e77999885cd86811380204` on main, reviewed design `65c39dd0d8fe80a2a8d72fc5680471d414288448` and handoff `a82973df705df4d7f937f92a07e3d18b4579b3f4` on `codex/simulator-design`. Their documentation CI passed.

The reference milestone is being published on its separate branch; the completion record identifies the verified remote commit and hosted CI result. The GitHub connector previously denied PR creation with HTTP 403 `Resource not accessible by integration`; authenticated native pushes work. No merge, package release or deployment was performed.

Repository-local Git author remains `Agentic Payments Automation <automation@agentic-payments.invalid>` because no author was configured initially. Global settings were not changed. Binaries, database data, credentials, checkpoints, screenshots and generated reports remain ignored.

## Remaining work

1. Complete seeded multi-intent recorded-schedule replay and the remaining adversarial/privacy matrix, concurrent clock orders and real JVM crash windows.
2. Extract finer application/domain transaction coordination from the JDBC implementation; current claim fences are not a full timed lease scheduler.
3. Generalize beyond the deliberately closed synthetic SDK profile with versioned provider/method/action profiles and reusable third-party adapter certification fixtures.
4. Pin upstream AP2/ACP artifacts and implement reviewed signed-byte, trust, continuation and execution-owner mappings without inventing upstream extension fields.
5. Implement provider-specific sandbox connectors with authorized accounts and verified idempotency/event/recovery behavior.
6. Add separately designed split tender, refunds/compensation and managed identity, visibility, operations and support.

External dependencies are provider/partner access, public licensing/contribution terms and later regional/live operating decisions. These do not block further local development. No scheduled unattended task is established by this file.
