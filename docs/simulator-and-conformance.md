# Deterministic simulator and project conformance brief

Status: proposed requirements and evidence plan. No simulator, runner, evidence schema, passing tests, or certified adapter exists in this milestone. The stable requirement register is [extension 0.1.0-draft](../specification/0.1.0-draft/README.md); the [SDK brief](adapter-sdk.md) describes the intended test subject.

## Simulator boundary

Build a local fake provider and trusted-surface harness before any provider sandbox integration. Simulator execution must have no live payment endpoint, production credential, or money-movement path. Sandbox integration is a distinct opt-in profile with its own configuration and evidence; passing local simulation proves no provider behavior.

Inject a virtual clock into deadlines, expiry, polling, action completion, and fault scheduling. Runs advance that clock explicitly and must not rely on wall-clock sleeps. Use a seeded random generator plus an explicit ordered event schedule, stable tie-break rules, controlled identifiers, and recorded concurrency interleavings. Pin runner/adapter/scenario versions and configuration; a seed alone is insufficient for reproducibility. Each scenario gets isolated state and reset semantics. Replaying a recorded run must reproduce normalized outcomes and invariant checks (EXT-011).

Seeded fault plans should cover response loss before/after provider acceptance, delayed/reordered/duplicate events, spoofed events, temporary lookup failure, graph restart, concurrent request replay, action expiry, voucher partial redemption, and revocation at dispatch boundaries. Scenarios must inspect financial invariants and authoritative state rather than only HTTP responses or graph completion.

## Fake mandates and real AP2 integration

Local fixtures may represent synthetic mandates and deterministic verification decisions to exercise binding, expiry, revocation, and recovery logic. Label them `local-fake` throughout fixtures, reports, and UI. They do not produce valid AP2 signatures, establish a signer trust chain, prove interoperability, or satisfy cryptographic verification.

A future real AP2 profile must pin the upstream specification/revision and mandate types, checkout binding/canonicalization, signature algorithms, key discovery/trust/revocation, role responsibilities, and receipt verification. It needs positive interoperability evidence and negative cryptographic tests using synthetic keys. Until those decisions and tests exist, report that profile as not implemented. A local fake passing CT-007 is evidence only of local binding logic.

## Proposed conformance matrix

IDs are permanent under the [version policy](../specification/README.md). These are acceptance-case definitions to implement later, not completed tests. Every row applies to the baseline unless an optional profile is explicitly named.

| Case ID | Requirement | SDK/core contract and simulator scenario | Pass evidence |
| --- | --- | --- | --- |
| CT-001 | EXT-001 | Negotiate known versions; require an unknown capability; remove a required authentication feature; attempt a lower version/security profile. | Only explicit compatible selection succeeds; rejection precedes any financial dispatch. No silent downgrade. |
| CT-002 | EXT-002 | Advertise a provider with selected methods/credentials/actions; request an unadvertised combination or unavailable merchant configuration. | Invalid cross-products reject; omission of an optional method is reported accurately. |
| CT-003 | EXT-003 | Concurrent identical commands, changed input with reused key, graph replay, and crash around dispatch. | One execution owner and stable provider operation identity; no duplicate economic effect or excess allocation. |
| CT-004 | EXT-004 | Provider accepts but response is lost; lookup temporarily fails; caller retries or requests another route. | State stays unresolved and funds remain reserved until authoritative reconciliation; no second charge/route. |
| CT-005 | EXT-005 | Card/3DS, provider-2FA, or bank-redirect profile: complete correctly, replay completion, alter subject/attempt, abandon, or expire. | Only verified current action progresses; redirect return alone never proves success. Optional profiles are assessed independently. |
| CT-006 | EXT-006 | Send spoofed, duplicate, late, and reordered webhooks plus conflicting synchronous/provider evidence. | Untrusted events reject; duplicates have no effect; settled facts persist; conflicts enter reconciliation. |
| CT-007 | EXT-007 | Alter merchant, checkout version/content, amount/currency, method/credential scope, execution identity, or expiry; substitute a fake for a real profile mandate. | Invalid bindings reject before dispatch; report distinguishes local-fake checks from future real signature/trust verification. |
| CT-008 | EXT-008 | Voucher/partial-funding profile: race redemption, fund partly then lose response, expire remaining reservation, and reconcile/compensate. | Exact allocation ledger; no reusable ambiguous redemption or double spend; remaining balance explicit; no premature full success. |
| CT-009 | EXT-009 | Revoke/expire before eligibility, race dispatch, then deliver late success. | Durable order explains each outcome; accepted pre-dispatch revocation blocks execution; in-flight uncertainty is reconciled, never relabeled as safely cancelled. |
| CT-010 | EXT-010 | Place synthetic sensitive canaries in credential/action/provider paths; inspect model inputs, logs, traces, checkpoints, and reports. | No canaries or sensitive payloads cross forbidden boundaries; only safe opaque references appear. |
| CT-011 | EXT-011 | Run and replay seeded faults with the same clock/schedule/configuration, then isolate/reset scenarios. | Matching normalized state and invariant digest; no cross-test contamination or live network/payment path. |
| CT-012 | EXT-012 | Generate a report with failures, unsupported optional profiles, and missing upstream integration evidence. | Report preserves failures/exclusions and exact scope; cannot emit an unqualified or external accreditation claim. |

An optional profile is `not applicable` only when it is not claimed; record why and its capability declaration. An advertised but untested profile is incomplete, not a pass. Required failures, missing evidence, and unexplained skips block a project-scoped certification decision.

## Evidence JSON plan

Design a machine-readable JSON report after contracts are reviewed. Proposed contents are: report-format version; extension version and profile; pinned upstream references; adapter/core/runner/scenario artifact versions and hashes; safe configuration digest; synthetic-data declaration; seed, initial virtual time and event schedule reference; requirement/case IDs; expected/observed invariant summaries; per-case pass/fail/not-applicable results and reasons; redacted trace artifact hashes; limitations; and review identity/date/decision. Preserve the original failing runs as well as reruns. Hashes support traceability but do not by themselves establish trustworthy provenance.

Do not include credentials, raw mandates/payment payloads, OTPs, private keys, customer data, or sensitive provider responses. Decide the evidence schema, retention/access policy, tamper evidence, and reviewer approval policy at the design gate; there is no executable report schema today.

“Project certification” means an internal, version/profile-scoped assessment of this project's conformance evidence. It is not AP2 or OpenAI certification, a vendor partnership, PCI compliance, regulatory approval, or network accreditation. No current certification claim is authorized by this documentation.
