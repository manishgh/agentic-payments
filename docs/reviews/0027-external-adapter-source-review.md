# Review 0027: External checkout lifecycle source and wire evidence

Status: independent source review accepted, 2026-11-08. All source findings are resolved; no blocking source finding remains. Acceptance covers the exact bounded source identities below. The integration owner retains the final unchanged-source aggregate and publication gate.

Reviewer is independent of the implementation authors and owns this report only. Baseline: `7b5d695eb83646a8bef540f2d0ba551001e1fd50`. Governing design: [ADR 0009](../adr/0009-external-checkout-adapters.md), amended SHA-256 `c6dc291503bfb72ad37fef991e898add1f95a2a0a98b459758a8b7a3aa6837cc`, and [review 0025](0025-external-adapter-design-review.md). The amendment permits bounded immutable JDK retries while prohibiting application retries and universal physical-single-send claims.

## Boundary and source assessment

The responsibility interface exposes create/retrieve/update/cancel only, with immutable protected documents, exact body retention, trusted binding and safe result categories. The HTTP implementation is separate from native checkout and payment-store wiring. It fixes the origin/credential, uses HTTP/1.1 with redirects and proxies disabled, captures status before consuming a bounded body, decodes strict JSON/UTF-8 and validates the stated lifecycle envelope. Its top-level field set independently matches the pinned `CheckoutSessionBase` properties. Nested optional payment/action content remains opaque rather than authority.

The independent Python verifier reuses hash/length/Git-blob checked schemas and disabled remote resolution. It validates four current-stage captures against complete schemas and checks exact method/path/status, absent bodies, profile, session and currency. The conformance runner passes the stage execution identity into Java and the independent verifier, and verifier failure disqualifies the Java stage even if Maven succeeds. Source identity now includes both new ADRs and their design reviews. CLI failures expose fixed codes, not peer/error text. The capture producer takes request bodies from actual peer observations and response bodies from the client's exact protected bytes, rather than retyping fixtures. It writes no headers or negative-test canaries. The verifier accepts the same bounded session-ID grammar as the contract, including safe dots inside one path segment.

The runtime strict parser rejects malformed UTF-8, duplicate members, trailing content and oversized/deep structures. Projection checks preserve the exact successful status, required envelope shapes, supported lifecycle state, currency/session binding and optional observed version. Allowlisted header values are never blindly exposed: output contains fixed matched/replayed booleans and bounded numeric retry advice. Errors retain observed numeric status even if response content is unusable. POST response loss remains uncertain; neither 404, 405 nor checkout completion becomes financial no-effect or settlement evidence.

No application retry, redirect, fallback credential, returned-URL fetch or alternate financial owner exists. The selected HTTP/1.1 request is immutable across permitted JDK library retries. The current-property check refuses enabled all-method retries without modifying global settings; the documented static-cache/runtime restriction remains a limitation, not an absolute physical-single-send guarantee. The contract/decorator tests and unsupported native-profile negotiation keep the new responsibility separate from local signed checkout and synthetic payment execution.

## Findings resolved

1. **Concurrent close and request admission.** The initial implementation could read the credential array while close zeroed it, or race request registration against client shutdown. A short lifecycle lock now serializes the closed check, coherent credential snapshot and `sendAsync` registration with close/zeroing. It is not held while waiting on the peer. Tests cover zero-request refusal after close and safe uncertainty when close interrupts an already-started body.
2. **Late subscriber cancellation.** Timeout cleanup could observe no subscriber while the response handler was about to install one. A cancellation flag now covers both orderings: cleanup cancels any published subscriber, and a subsequently published subscriber observes cancellation before accepting body work. Pending futures are cancelled as well. Body byte limits and subscription cancellation remain bounded.
3. **Deadline and fixed origin details.** The total deadline is rechecked inside the lifecycle guard before registration, after success parsing and after error parsing. Configuration rejects ports above 65535. Optional absent correlation IDs are permitted; unsolicited request/idempotency IDs are omitted when none was sent. Conflicting or duplicate expected echoes reject without exposing peer text. Tests exercise actual duplicate headers using `Headers.add`, absent echoes and unsolicited canaries.
4. **Cancellation evidence accidentally observed warmup completion.** The original deadline test counted down a shared handler-completion latch during the normal warmup request, so its later await did not prove stalled-body cancellation. The corrected target latch counts only the fault-mode handler. The trickling-body test requires that target to end before releasing the server, establishing client cancellation rather than test cleanup. Header and stalled-body cases retain bounded return/status assertions.

## Validation and remaining gate

The independently authored peer asserts exact methods/paths/version/credentials, bound sessions, POST keys, accepted-response-loss replay/conflict, numeric error/status handling, strict parser rejection, optional protocol behavior, protected opaque URLs, hostile headers, redirects, lifecycle closure and full-body deadlines. POST loss checks one observed request before explicit caller replay in the supported runtime configuration. Dropped GET permits one or two immutable observations; this is bounded library behavior, not a universal physical-single-send assertion.

The owner reports 12 conformance-runner tests and six independent verifier tests passing. The first combined Python run encountered Windows temporary-directory permissions for the new verifier tests; their corrected repository-local temporary directory uses resolved containment checks before cleanup and the six-test rerun passed. Earlier Java runs preceded final edits and are not the final source evidence.

The integration owner reran the frozen source after the last pre-registration deadline guard: `AcpHttpCheckoutSessionsTest` 13 and `CheckoutSessionsContractTest` 3, all 16 passing with zero failures/errors/skips. The actual four-exchange capture then passed the independent complete pinned-schema verifier again in the same command. The reviewer independently read the final Surefire XML counts, inspected the four-operation `standalone` capture identity and reconfirmed the final transport hash. The owner's documentation check passed 76 files. Focused standalone capture evidence is separate from the later aggregate's fresh execution identity.

The reviewer ran source/hash checks only, with no concurrent Maven or aggregate tests. Root owns complete isolated-PostgreSQL conformance and publication. Runtime nested-schema validation remains deliberately limited; raw nested content is not actionable. This slice cannot claim hosted merchant/provider integration, durable application recovery, financial authority or certification.

## Accepted source identity

| File | SHA-256 |
| --- | --- |
| `src/main/java/io/agenticpayments/checkout/CheckoutSessions.java` | `018c5378a4530fcef3c0ceed7ce1bf7be80c555d034994a7ad336e7fc776b3b1` |
| `src/main/java/io/agenticpayments/infrastructure/AcpHttpCheckoutSessions.java` | `1a32ba3e4ec52b6fae2512ab5b9c56cfb3ca21a8c76c9372ebf275b840691c7a` |
| `src/test/java/io/agenticpayments/checkout/CheckoutSessionsContractTest.java` | `af39b2dcaf171af1e2e8508ebf91f4c90c5d7aa19c6c274566ebbe43bd804f3a` |
| `src/test/java/io/agenticpayments/checkout/AcpHttpCheckoutSessionsTest.java` | `b9069d773fd49620d116705e43c9b3e7052b02a4cc4dcd0e86f327b7f3c1d347` |
| `integrations/verify_external_checkout.py` | `84029b178c12b5290f944f555ea7f0d24d35de65421452b044376f14b6dd062b` |
| `integrations/test_external_checkout.py` | `324916ba0dd57c005a033671a82305ce6f88b1d638bd133df3d1eedd534ed0f3` |
| `scripts/conformance.py` | `4145072e7e928aa2e2d90898e791c6b7ce89ed177861ae75bfc2be275066ff41` |
| `scripts/test_conformance.py` | `2bf913b8a5aa6211bbf605711d7277afa5d339a0cc1c1185ab1c21a67560c71a` |
| `scripts/requirement_evidence.py` | `61459a2df0233806c49f5a4e402359ea52028452485d0f562d5d05913f638168` |
| `docs/external-checkout.md` | `ce05ebb6769d4179d9f55c84d7e31d7ff7a7220b67ecd5f820babdccb9211fda` |

All ten identities were independently computed after the source fixes. Preserve them through the gate or request review of further changes.
