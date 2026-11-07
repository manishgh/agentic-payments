# Current status and handoff

Updated: 2026-11-07, Europe/Berlin.

## Current milestone

The local synthetic reference implementation is runnable: versioned contracts, extensible Java adapter SDK and reusable TCK, Java payment core, normalized PostgreSQL migrations and durable simulator, loopback HTTP API, trusted TypeScript UI, LangGraph/SQLite workflow and repeatable evidence runner. Implementation branch: `codex/reference-platform` in [manishgh/agentic-payments](https://github.com/manishgh/agentic-payments).

The product direction remains an AP2-based intent enclosing embedded ACP checkout and provider authentication. The independently authored Java AP2 direct TAP engine now binds native synthetic authority and an exact embedded ACP checkout to durable financial admission. Checkout, authority, crypto, key and artifact implementations are injectable. LangGraph decisions run through a shared local SQLite/Temporal runtime contract. ACP merchant HTTP transport, external providers, Cadence/Azure adapters and broader AP2 profiles remain outstanding. See [durable composition](durable-composition.md) and [runtime guide](durable-runtime.md).

## Validation and review

The durable AP2/ACP and runtime slice passed fresh run `ac79d0a17b14434e8f6e23995ce00cf9`: twelve stages and 317 methods, zero failures/errors/skips, unchanged 294-file source identity, and separate semantic bundle verification. Independent Astra reviews 0019 and 0021 accept the bounded source after ten findings were resolved. Evidence covers native financial admission/recovery, populated-schema migration, replacement adapters, actual Temporal worker/server restarts, history replay, bounded history and decoded-payload privacy checks. All 48 selected requirement observations pass; certification remains incomplete. See [implementation evidence](implementation-evidence.md) for exact identities and limits.

The published SDK/TCK checkpoint `0f60d93` passed an isolated unchanged-source aggregate: 67 Java tests, eight HTTP tests, three UI regressions, 12 LangGraph tests, four upstream integrity tests and 12 structural probes. Schema validation covered 21 schemas, 17 positive fixtures, 27 schema-negative fixtures, six binding-negative fixtures and one raw-parser negative. SDK bytecode inspection found only `java.base`. Desktop/mobile UI rendering and the trusted approval flow were inspected in headless Edge.

Separate Astra [core review](reviews/0006-reference-code-review.md) and Sol 6.1 High [client/tooling review](reviews/0007-client-tooling-review.md) accepted the bounded source after identified defects were corrected. [Gate records](reviews/gates.md) distinguish the original reservation design from the later execution/client scope.

The integrated replay/lab checkpoint passed all 11 stages against a fresh database: 99 Java, eight HTTP, three UI, 12 LangGraph, 26 lab and five upstream-integrity tests, plus structural/schema fixtures and CLI checks. Source stayed unchanged across the run. Independent reviews 0010, 0011 and 0012 are accepted; exact identities and evidence are recorded in [implementation evidence](implementation-evidence.md).

The requirement-evidence follow-on passed a fresh twelve-stage aggregate with 204 test methods, zero failures/errors/skips and unchanged source. All 48 selected named observations qualify PASS. Reviews 0013..0015 accept the bounded design, event/privacy tests and evidence pipeline after corrections. The report recomputes coverage from pinned definitions and current-run artifacts; eleven CT rows still retain explicit incomplete gaps, and CT-008's optional exclusion requires its baseline refusal evidence. See [requirement evidence](requirement-evidence.md) for verification and publication rules.

The independent AP2 milestone passed a fresh twelve-stage aggregate with 249 test methods, including 37 new AP2 Java methods and four independent Python verifier methods. The Java tests evaluate 74 Python-generated vectors; Python independently verifies five Java signatures and two receipt references. Reviews 0016/0017 are accepted, all six source findings are resolved, source stayed unchanged and separate bundle verification passed. The AP2 JAR is separately packaged and hashed. Exact run/source identities and the corrected earlier report-generation failure are in [implementation evidence](implementation-evidence.md).

The aggregate evidence runner returns `NOT_CERTIFIED`, even when preview checks pass. Exhaustive adversarial/privacy evidence, broader conflict-normalization replay and certification policy are unfinished. The earlier offline lab verifies merchant signatures/content only. Native composition separately gates financial admission through durable authority/replay checks; synthetic identities and UI fixtures do not prove real-world human consent.

## Publication

Earlier milestones: documentation `15f0776a2fa1628ac8e77999885cd86811380204` on main, reviewed design `65c39dd0d8fe80a2a8d72fc5680471d414288448` and handoff `a82973df705df4d7f937f92a07e3d18b4579b3f4` on `codex/simulator-design`. Their documentation CI passed.

Reference milestone `314bd2f` and SDK/TCK checkpoint `0f60d93` are published on the implementation branch. Hosted [reference CI](https://github.com/manishgh/agentic-payments/actions/runs/42307004050) and [documentation CI](https://github.com/manishgh/agentic-payments/actions/runs/42307004092) passed for `0f60d93`. The GitHub connector previously denied PR creation with HTTP 403 `Resource not accessible by integration`; authenticated native pushes work. No merge, package release or deployment was performed.

Replay/artifact checkpoint `9fd7dbbc5cef135007b7f2253aba24c825386954` is also published. Its [Linux reference CI](https://github.com/manishgh/agentic-payments/actions/runs/42309571643) and [documentation CI](https://github.com/manishgh/agentic-payments/actions/runs/42309571661) passed. The requirement-evidence follow-on has its own source review and local aggregate record above; earlier hosted runs do not validate later source. It does not enable providers or grant certification.

Requirement-evidence checkpoint `95b5ac8276601f41c5f9d8e0380eb57c9892e9c7` is published. Its [reference CI](https://github.com/manishgh/agentic-payments/actions/runs/42333495260) and [documentation CI](https://github.com/manishgh/agentic-payments/actions/runs/42333495344) passed and the safe bundle was preserved. The independent AP2 milestone has separate design/source review and fresh validation.

Repository-local Git author remains `Agentic Payments Automation <automation@agentic-payments.invalid>` because no author was configured initially. Global settings were not changed. Binaries, database data, credentials, checkpoints, screenshots and generated reports remain ignored.

## Remaining work

1. Complete conflict-normalization replay and the remaining adversarial/privacy and process-crash matrix, then review the certification policy.
2. Extract finer application/domain transaction coordination from the JDBC implementation; current claim fences are not a full timed lease scheduler.
3. Extend the generic metadata/TCK with reviewed executable profiles and broader third-party adapter evidence. Split the SDK publication POM from reference dependencies before package release.
4. Extend native authority to delegated/User Credential profiles only after their own design and adversarial evidence. Establish wire interoperability with real peers separately from independent Python vectors. Add separately reviewed Cadence/Azure runtime adapters and distributed registration/production identity.
5. Implement provider-specific sandbox connectors with authorized accounts and verified idempotency/event/recovery behavior.
6. Add separately designed split tender, refunds/compensation and managed identity, visibility, operations and support.

External dependencies are provider/partner access, public licensing/contribution terms and later regional/live operating decisions. These do not block further local development. No scheduled unattended task is established by this file.
