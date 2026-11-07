# Current status and handoff

Updated: 2026-11-07, Europe/Berlin.

## Current milestone

The local synthetic reference implementation is runnable: versioned contracts, extensible Java adapter SDK and reusable TCK, Java payment core, normalized PostgreSQL migrations and durable simulator, loopback HTTP API, trusted TypeScript UI, LangGraph/SQLite workflow and repeatable evidence runner. Implementation branch: `codex/reference-platform` in [manishgh/agentic-payments](https://github.com/manishgh/agentic-payments).

The product direction remains an AP2-based intent enclosing embedded ACP checkout and provider authentication. This milestone uses explicit synthetic fixtures; no real AP2 signature verifier, ACP wire checkout or external payment provider is implemented.

## Validation and review

The published SDK/TCK checkpoint `0f60d93` passed an isolated unchanged-source aggregate: 67 Java tests, eight HTTP tests, three UI regressions, 12 LangGraph tests, four upstream integrity tests and 12 structural probes. Schema validation covered 21 schemas, 17 positive fixtures, 27 schema-negative fixtures, six binding-negative fixtures and one raw-parser negative. SDK bytecode inspection found only `java.base`. Desktop/mobile UI rendering and the trusted approval flow were inspected in headless Edge.

Separate Astra [core review](reviews/0006-reference-code-review.md) and Sol 6.1 High [client/tooling review](reviews/0007-client-tooling-review.md) accepted the bounded source after identified defects were corrected. [Gate records](reviews/gates.md) distinguish the original reservation design from the later execution/client scope.

The integrated replay/lab checkpoint passed all 11 stages against a fresh database: 99 Java, eight HTTP, three UI, 12 LangGraph, 26 lab and five upstream-integrity tests, plus structural/schema fixtures and CLI checks. Source stayed unchanged across the run. Independent reviews 0010, 0011 and 0012 are accepted; exact identities and evidence are recorded in [implementation evidence](implementation-evidence.md).

The requirement-evidence follow-on passed a fresh twelve-stage aggregate with 204 test methods, zero failures/errors/skips and unchanged source. All 48 selected named observations qualify PASS. Reviews 0013..0015 accept the bounded design, event/privacy tests and evidence pipeline after corrections. The report recomputes coverage from pinned definitions and current-run artifacts; eleven CT rows still retain explicit incomplete gaps, and CT-008's optional exclusion requires its baseline refusal evidence. See [requirement evidence](requirement-evidence.md) for verification and publication rules.

The aggregate evidence runner returns `NOT_CERTIFIED`, even when preview checks pass. Exhaustive adversarial/privacy evidence, broader conflict-normalization replay and certification policy are unfinished. The offline lab verifies merchant signatures/content only; native AP2 authority and dispatch remain disabled.

## Publication

Earlier milestones: documentation `15f0776a2fa1628ac8e77999885cd86811380204` on main, reviewed design `65c39dd0d8fe80a2a8d72fc5680471d414288448` and handoff `a82973df705df4d7f937f92a07e3d18b4579b3f4` on `codex/simulator-design`. Their documentation CI passed.

Reference milestone `314bd2f` and SDK/TCK checkpoint `0f60d93` are published on the implementation branch. Hosted [reference CI](https://github.com/manishgh/agentic-payments/actions/runs/42307004050) and [documentation CI](https://github.com/manishgh/agentic-payments/actions/runs/42307004092) passed for `0f60d93`. The GitHub connector previously denied PR creation with HTTP 403 `Resource not accessible by integration`; authenticated native pushes work. No merge, package release or deployment was performed.

Replay/artifact checkpoint `9fd7dbbc5cef135007b7f2253aba24c825386954` is also published. Its [Linux reference CI](https://github.com/manishgh/agentic-payments/actions/runs/42309571643) and [documentation CI](https://github.com/manishgh/agentic-payments/actions/runs/42309571661) passed. The requirement-evidence follow-on has its own source review and local aggregate record above; earlier hosted runs do not validate later source. It does not enable providers or grant certification.

Repository-local Git author remains `Agentic Payments Automation <automation@agentic-payments.invalid>` because no author was configured initially. Global settings were not changed. Binaries, database data, credentials, checkpoints, screenshots and generated reports remain ignored.

## Remaining work

1. Complete conflict-normalization replay and the remaining adversarial/privacy and process-crash matrix, then review the certification policy.
2. Extract finer application/domain transaction coordination from the JDBC implementation; current claim fences are not a full timed lease scheduler.
3. Extend the generic metadata/TCK with reviewed executable profiles and broader third-party adapter evidence. Split the SDK publication POM from reference dependencies before package release.
4. Resolve native AP2 authority/delegation and ACP continuation mappings against the pinned sources and real peers. The offline merchant-signature/content lab cannot authorize execution or establish wire interoperability.
5. Implement provider-specific sandbox connectors with authorized accounts and verified idempotency/event/recovery behavior.
6. Add separately designed split tender, refunds/compensation and managed identity, visibility, operations and support.

External dependencies are provider/partner access, public licensing/contribution terms and later regional/live operating decisions. These do not block further local development. No scheduled unattended task is established by this file.
