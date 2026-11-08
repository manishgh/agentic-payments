# Review 0033: Durable provider journal design

Status: independent design review completed, 2026-11-08. Recommend integration-owner acceptance of the exact ADR identity below for bounded implementation. No blocking design finding remains. This is not source acceptance or provider/financial integration approval; implementation starts only after the integration owner records its gate.

Reviewer: independent architecture/concurrency reviewer, distinct from the design author. The review covered repository/workspace instructions, ADR 0012, the accepted provider contract and source review, `CompatibilityBindingJournal`, `LocalFakeExecutionAdapter`, the durable synthetic peer and the concrete ADR 0013 plan. Repository HEAD was `103d4e3a1431743d10bab446684f9a6fb1467baf`; the preceding source milestone was frozen for its integration-owner aggregate. This reviewer changes only this report and runs no Maven or conformance tests.

## Scope and responsibility boundary

The proposed PostgreSQL implementation supplies the existing journal responsibility without introducing a backend-specific SDK port or changing its signatures. Infrastructure owns persistence, explicit serialization and trusted immutable configuration. The portable SDK remains dependent only on `java.base`. Separate short transactions publish binding, dispatch ownership and retained evidence; provider execution remains outside journal locks.

This is a standalone synthetic compatibility profile. It does not wire the provider contract into payment admission, checkout, HTTP or financial storage, and supplies no new spending authority. Existing core and provider-peer tables remain separate. A text SDK run identifier must not be coerced into the legacy run UUID or attached to a synthetic core run. V009 adds its own scoped tables and preserves historical migration checksums.

The current journal interface is sufficient for the conservative profile. A constructor-bound mapping policy reproduces and verifies the bridge's deterministic legacy mapping; persisted complete operation, submission, scenario and policy values prevent a mutable default or changed credential version from rebinding outstanding work. An unavailable historical configuration blocks recovery instead of selecting a current substitute.

## Findings resolved before acceptance

| Concern | Accepted disposition |
| --- | --- |
| UNKNOWN before the first compatibility claim could conceal prior legacy acceptance or lost history. | Any previously recorded fact, including UNKNOWN, permanently blocks first ownership. Fresh bind followed immediately by claim remains usable. A fresh journal is not proof of a fresh provider operation; the reference requires a fresh dedicated scope, exclusive legacy-key ownership and preserved histories, with no implicit adoption after partial restore. |
| A terminal-only comparison could discard conflicting same-ID content or return an old clean terminal after conflict. | Compare the complete fact, including source, exact instant, resource and action. Retain both sides through append-only evidence/receipts, commit quarantine before throwing, and reject subsequent bind replay, read, claim and record as conflicted. A rollback must not erase the decision that callers are relying on. |
| A late UNKNOWN could erase accepted processing history and allow a false winning fence. | Keep the first pending projection on later unknown/compatible pending. In this bounded profile, pending/captured versus fenced contradicts in either order. Compatible later terminal facts preserve the first terminal projection. General authorization, refusal and financial action observations are unsupported and quarantined rather than assigned invented financial semantics. |
| A local durable claim could be mistaken for a fence on a delayed sender. | The existing synthetic peer's atomic acceptance-versus-seal journal supplies the guarantee. Compatibility ownership prevents replacement dispatch but cannot itself stop an already admitted caller. Separate commit-gap tests must distinguish adapter method invocation from new financial send/acceptance. |
| Retained-memory reconstruction or a JDBC-backed fixture could be labelled process recovery without killing a process. | The existing TCK remains `IN_PROCESS_RECONSTRUCTION` when it reconstructs objects. Separate child JVMs halt and recover against both durable journals, with independent parent SQL witnesses after termination. No whole-server or storage-power-loss claim follows. |
| Alias rejection can precede creation of the requested binding, and a SQL uniqueness exception can abort receipt persistence. | Receipt UUIDs identify protected rejections; requested namespace fields have no binding foreign key, while an optional complete existing-owner association identifies the collision. Non-aborting uniqueness decisions permit the receipt to commit without inventing a binding or quarantining the legitimate operation. Resource conflicts quarantine the candidate operation while preserving original ownership and projection. |

The intentionally conservative unknown-before-claim behavior is a liveness limitation, not proof of an earlier sender. A committed claim with lost acknowledgment grants no replacement owner. Neither process death, absent lookup, finite provider-key retention nor operator retry clears ownership. A genuinely fenced original operation may resolve uncertainty; a separately admitted future attempt is outside this journal's authority.

## Concrete implementation and evidence gates

The versioned infrastructure codec must explicitly encode all fields and null optionals, retain nanosecond instants, enforce bounded strict parsing, reject duplicate/unknown fields and reconstruct validated SDK values. Redundant digest/identity columns must agree with complete decoded content. Corruption cannot default a claim to false or a missing observation to no effect. SQL/provider exception text, causes and secret-bearing configuration must not enter safe failures or child output. Database unavailability cannot promise a new durable error receipt; previously committed claims and facts must remain intact.

Independent PostgreSQL tests must cover one-owner contention, changed submissions, operation-ID and legacy aliases, direct wrong-scope access, resource ownership conflicts, immutable-column protection and unrelated operations progressing while another row is locked. Exact duplicates, contradictory nonterminal same-ID facts, pending/fence contradictions in both orders, terminal weakening, durable quarantine and write rollback require direct database observations. Changed binding proposals must not let an invalid caller poison a legitimate operation.

The child-process matrix must exercise pre-bind rollback, committed bind before claim, committed claim before I/O, provider acceptance before compatibility recording, terminal recording before response, both fence/acceptance gaps and committed conflict before lost response. Recovery uses newly constructed connections, journals and bridge instances with the original mapping/configuration/times. Independent peer rows/events distinguish one durable acceptance from a response produced by the recovery code. Clearing a test-only finite cache must leave the actual acceptance ledger intact; lookup failure still cannot trigger resend.

The implementation ownership is bounded to new infrastructure provider classes, V009 and independent provider tests. The integration owner separately registers the migration, preserves older prefix tests and owns evidence/docs linkage, all builds, SDK dependency checks and the complete unchanged-source aggregate. Independent source review must inspect the actual SQL transaction/error paths, codec, immutable constraints and process witnesses before source acceptance. Design acceptance alone is not implementation evidence, a certification grant or authorization to activate a real provider.

| Reviewed artifact | SHA-256 |
| --- | --- |
| [ADR 0013](../adr/0013-durable-provider-journal.md) | `56af386a4602652a730d17b6cd30b4dc5f7c2ec0508cb4bfa0013c3f310b749a` |

The reviewed ADR has UTF-8/LF bytes. Accept this exact bounded design for implementation followed by independent source review. Changed design identities require further review; financial-core wiring, richer provider profiles, external authenticity and live-provider activation remain separate work.

## Pre-implementation identity amendment

After the initial review, the author applied a final ownership and migration clarification before implementation authorization. The historical `56af386a4602652a730d17b6cd30b4dc5f7c2ec0508cb4bfa0013c3f310b749a` acceptance above is retained as the original review event. The current accepted design identity is the amended hash below; the integration owner must record its corrected gate against that identity.

The reviewer independently reconstructed the exact original hash by reversing only four declared text changes: selecting the already permitted restricted SQL update path, adding the simple-SQL migration constraint, and replacing the two ownership paragraphs. This proves the amendment contains no other text changes. The existing migrator splits statements on semicolons. Choosing insert-only identity fields and explicit locked, once-only submission updates avoids introducing procedural SQL or an unrelated parser rewrite. Replays still reject immutable changes, with independent tests checking unchanged stored columns; the design makes no database-administrator tamper guarantee.

Production provider infrastructure, V009 and `ProviderJournalCodecTest` belong to the implementation author. The independent test author owns the other new provider integration/process/inspection helpers and populated migration test. Root owns migrator registration, existing migration latest-count amendments and aggregate linkage. Exact construction/codec signatures are published after the gate before parallel implementation. These changes preserve the accepted responsibility, financial, conflict and recovery semantics and improve concrete ownership. No new blocking finding remains.

| Amended reviewed artifact | SHA-256 |
| --- | --- |
| [ADR 0013](../adr/0013-durable-provider-journal.md), final frozen UTF-8/LF bytes | `492fe2fc404eed26cb8aa5ae81f5e4cefc893cdab9a06c3fcc1524988ebb5739` |

Recommend integration-owner acceptance of this amended identity for bounded implementation. No source or test files were changed by this reviewer and no build was run for the amendment.
