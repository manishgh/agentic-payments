# Review 0031: Durable external checkout source

Status: independent source review accepted for the bounded commerce lifecycle, 2026-11-08. All findings below are resolved. The integration owner must still complete the unchanged-source isolated-PostgreSQL aggregate and record its exact evidence before milestone delivery. This report does not grant external financial integration or certification.

Reviewer: independent architecture/protocol reviewer, distinct from the Java and HTTP/Python authors. Design baseline: `103d4e3a1431743d10bab446684f9a6fb1467baf`; accepted design is ADR 0011 at its LF publication identity recorded in review 0029. Review covered the 35 source/document files identified below, the existing transport/native/payment boundaries and the associated focused test reports. Only review documents were edited by this reviewer during source review. No Maven, application or conformance execution was performed by the reviewer.

## Result and scope

The implementation provides authenticated, runnable create/retrieve/update/cancel application operations with durable exact requests, exclusive claims, protected outcomes and a separate LangGraph/SQLite workflow. PostgreSQL owns commerce truth; graph checkpoints hold safe orchestration references. The explicit synthetic loopback registration is disabled by default. Existing native AP2 authority, financial admission, reservations and payment dispatch remain outside this new aggregate.

The service owns authorization, caller-command replay, preparation, configuration resolution, conservative dispatch policy and replacement-transport response validation. A typed atomic transaction port owns the indivisible configuration/stage/claim/outcome writes and current/stale fences. Its PostgreSQL implementation is independently complemented by a non-JDBC transaction double and a non-ACP transport. The latter exercise the same application service rather than wrapping JDBC under a different name. The scoped outcome-classification responsibility remains in the atomic adapter; this is not a claim that the complete financial store has been extracted.

## Findings resolved

| ID | Finding | Verified correction |
| --- | --- | --- |
| DC-1 | The prepared-request adapter caught runtime failures across actual HTTP dispatch and returned NOT_SENT, which could remove the mutation barrier after acceptance. | NOT_SENT handling is confined to pure preflight. Unexpected post-invocation runtime failures remain UNKNOWN and retain observed HTTP status. The service also rejects nonzero HTTP status combined with NOT_SENT. A real accepted HTTP response followed by a canceled future reproduces the original failure; replacement-transport exceptions and contradictory results are also tested. |
| DC-2 | Injected lifecycle and registration exceptions could enter LangGraph task/checkpoint errors with sensitive messages or causes. | Both responsibility calls are sanitized before the graph sees an exception; fixed errors are raised outside the exception handler. Raw and decoded checkpoint tests cover lifecycle and reserve/command/remember backend canaries, including absence of retained exception context. |
| DC-3 | Client identity was bound only at workflow construction and could change before later effects. | Scope/run/principal are compared before each lifecycle call. Mutated live clients reject without further calls; restart bindings continue to reject changed principal, purchase or template. |
| DC-4 | Initial replacement-storage evidence merely decorated JDBC, and stage/outcome rollback was not fault-injected. | An independently implemented in-memory typed transaction adapter exercises service policy. Scoped PostgreSQL AFTER triggers fail after provisional stage and outcome writes; tests verify complete rollback, original-key staging recovery, retained CLAIMED uncertainty and original-result retention without another HTTP send. |
| DC-5 | Sequential registration tests did not establish the newly required concurrent first-start arbitration. | Separate bindings/connections and workflow clients compete behind a barrier using different templates. One registration wins; the loser performs no lifecycle calls. |

The PostgreSQL trigger test's final PL/pgSQL `END;` syntax correction was inspected before a fresh 16-method durable-class rerun and the final source identity. It changes test syntax only. The earlier pre-correction manifest is not the accepted source manifest.

## Safety and replacement assessment

Current authorization precedes local replay. Replay retains its original command identity and remains available when the resolver is retired. A new command is staged with original configuration/credential/profile/encoding identity and reconstructible protected bytes; a second short transaction rechecks current state before publication. A claim commits before transport invocation. No merchant HTTP call runs inside the transaction callback or native financial path.

Current response promotion requires matching immutable request, command, claim, revision and configuration, plus valid binding/currency/session/lifecycle/version evidence from the transport. Configuration changes or resolver outages after a send retain the correlated protected outcome while refusing promotion. Prepared ACP commands are revalidated against pinned encoding; arbitrary paths, altered bytes and substituted profiles do not become an escape to completion.

Claim recovery revokes projection authority, not a delayed worker's physical send. The independent persistent mutation barrier survives a late original response, subsequent successful GET, missing/expired session, service restart and graph restart. Unknown creation does not acquire an invented lookup, replacement key or automatic replay. This deliberate liveness limitation is documented. Definite preflight NOT_SENT cannot establish that all other invocations had no effect, and no GET result supplies a no-future-send guarantee.

Scope is included in storage queries and relational references; one active staged/claimed command is enforced by both application checks and a partial unique index. V008 is additive to populated V007 state and preserves historic migration checksums. Protected requests/responses are kept apart from signed authority artifacts and exposed only as opaque response references. No raw-response API, merchant session identifier, bearer credential, item template or transport key enters safe status.

The HTTP facade preserves strict parsing and local authentication. The separate workflow atomically binds immutable scope/principal/purchase/template identity before the first lifecycle call, retains its stage key and command ID, rejects changed identity, rereads authoritative status before dispatch and pauses on uncertainty. It does not relabel the payment-specific SQLite/Temporal runtime. Only the SQLite local-restart profile and compatible injected seams are claimed; Temporal/Cadence/Azure commerce implementations remain outstanding. The runnable guide preserves these boundaries and the synthetic merchant's memory-only limitation.

## Observed evidence and remaining gate

The reviewer read the integration owner's focused Surefire reports: 16 durable lifecycle methods, three independent application-policy methods, one populated V007 migration method, one retained native migration method and 13 existing ACP transport methods passed with zero failures, errors or skips. The same invocation included 17 provider-contract methods, for 51 total. After the final trigger-test syntax correction, the integration owner reran all 16 durable lifecycle methods successfully on the accepted bytes; the earlier 51-method run is historical evidence for that prior test spelling. The integration owner separately reports 12 checkout workflow unit methods and 51 tooling methods passing, plus earlier five actual HTTP and two separate-process live checkout methods. The final aggregate must rerun the integrated paths on the accepted unchanged source; earlier focused execution is not substituted for that gate.

Source inspection confirms actual child-JVM halt boundaries after stage/claim commit, real peer request counts and immutable wire comparisons, delayed-worker fencing, independent storage/transport policy tests, PostgreSQL rollback, API restart, separate Python processes and privacy checks. `git diff --check` passed during review. No live merchant/provider, vendor sandbox, external AP2 financial composition, production retention/security rollout or complete certification claim is accepted.

## Accepted source identity

The 35-file manifest below is independently recomputed from the working bytes. SHA-256 is `1853c901a8e2bd23ad068793ca00d7c49d37fa210ddfe5c9e45554776f9aaccb`. Sort repository-relative paths in ordinal order; encode each row as lowercase file SHA-256, two spaces, path, and LF, including the final LF. All reviewed files use LF. Review documents are excluded so later evidence recording cannot self-reference this hash. Changed source requires re-review and revalidation.

```text
7d10ac7656e3602bc84e61cb51646fad5d7d613b5cfd9d79119f9e9c7db91833  README.md
2a35b41e4d87f12b07860fae928c5f283ae999a2d1d16e073cf7ec23f86796a4  docs/api-contracts.md
015ffbc3707741b4390833c5c84d090374100426419ff4d10aa94b413441f365  docs/architecture.md
cb3d0325c58aed80c2d6bdc12671f3886a6b477e53eea1c6a1dc364b4beaa547  docs/backlog.md
e01ebde48630079a0553eef031e70f7abf54223f6c7bb6ef8e38c543f4fcc0fc  docs/data-model.md
870991f1b97e46cbbe03f10aa4e0432494eb98e6d829ea9bb48dca364f0c2811  docs/durable-external-checkout.md
a07dd83a37ace4a6abe6b614168e9a67f14e601127a7d74d39f3f6a623a8e665  docs/external-checkout.md
650c28e734be5f8cb204e4f8d87d39e48d154f9c034e2fcdd83c1f488c52db04  docs/provider-adapter-evolution.md
95a7a9b9318124d10d0be49df5afc6e6e0a79f86c2d140cdcb8929f0b97e2525  integrations/checkout_demo.py
01010fd5d892f7547f5da71300575535d59e0e3c214acd890eba60634844af27  scripts/checkout_test_support.py
3b268ff919b5ebcd5f6673098805f34c6ad57b9a2b71f6677be4c899e48ecd87  scripts/conformance.py
0713585e1cf28f81138ed816d2c4811fef0e5df874aab450da597f8616e77583  scripts/requirement_evidence.py
ada22016d130b1ca8aaed3a29a6f58599365c5f5044f744c410404c24afbb81f  scripts/test_checkout_http.py
ae31ad8debdab1301131dfb9dd48b381cbf3430005cfbeda939829d648a934ab  src/main/java/io/agenticpayments/application/CheckoutConfigurationResolver.java
2b80d6163d5859b11e3197c8f0034df9ff61cb228919ad68c812935bdd766e25  src/main/java/io/agenticpayments/application/CheckoutLifecycle.java
7d8d4ccdf728721805e42a76ac27d907f0cfda8b6857262d9c1c5a62a19d2df8  src/main/java/io/agenticpayments/application/CheckoutLifecycleService.java
e60d89c8fa43a86eaa5ab66f53a9920cf6dd133113832d825ecff45a9932d990  src/main/java/io/agenticpayments/application/CheckoutLifecycleTransactions.java
8cb696f3d0a8d426a290a4a8e747fbdfd5d7f8d75a89f9bd62a3f2b49dfd0031  src/main/java/io/agenticpayments/checkout/CheckoutCommandTransport.java
751b5698858c92ca2706e8ae9269333bf4500a5b2d6f88ad0be8e377fc0b5832  src/main/java/io/agenticpayments/http/Main.java
809906024517cd15a5c76b7b0ffc888b57b4d48af2b91c621e484f8e2a0e339c  src/main/java/io/agenticpayments/http/PaymentHttpServer.java
5fdd14a87a51e5fc52047e0ae269e1c110ed29b27b3e94480d43867244ccd546  src/main/java/io/agenticpayments/infrastructure/AcpHttpCheckoutSessions.java
2aaca15afa12909a50c206dfe2873aafef35010d3e3715a80fefff5cc5619a7f  src/main/java/io/agenticpayments/infrastructure/DatabaseMigrator.java
c679d60887b6f51e191e42c12f4b4aa8c1bb64f368c931e0003de4f9ab711a15  src/main/java/io/agenticpayments/infrastructure/JdbcCheckoutLifecycleTransactions.java
82b91fcb4c8f721897c7069198d8cb5934ba8838a60bcefc4d402c32183b9060  src/main/java/io/agenticpayments/infrastructure/LocalCheckoutRegistration.java
c2a02f64a4b233bb002c8f8569579544b11ced4aa697ee01414851483fbb0bad  src/main/resources/db/V008__durable_checkout_lifecycle.sql
3a5dfb5515ab2b3f79cccdd843bd79932678fe96225c6d881dacf42d8947383a  src/test/java/io/agenticpayments/checkout/CheckoutLifecycleCrashProbe.java
3ba9eaa94f1deb884e0f6aa6438dd00ed58885e5b663abf8abd4d8a10afff381  src/test/java/io/agenticpayments/checkout/CheckoutLifecyclePolicyTest.java
67f1ef35874c6553696cfc46b79e0dd115821dfe9cdc4059940a5a5f65e0d6f4  src/test/java/io/agenticpayments/checkout/DurableCheckoutLifecycleTest.java
481874889cfaa99e1aa59ebabe95697684f86f67c23bd4e03b91f02422ae1157  src/test/java/io/agenticpayments/checkout/MemoryCheckoutTransactions.java
6afd9289feea95b2f6036aad91485ad914780b5dabeb1867605276a271269390  src/test/java/io/agenticpayments/core/CheckoutMigrationUpgradeTest.java
2320ee83dc3239dfdf7434851c5859defeb301c7b14cb61aed14d9cd2b9ab18d  src/test/java/io/agenticpayments/core/NativeMigrationUpgradeTest.java
e72bf5a768ae4a0e482ee698907eee133333487f941b95bf695b836982de2ca3  workflows/checkout_bindings.py
ad29e883281b8e1939826fcfeb866bedfe3313e3782dcb5dffd4daf698f3ae9e  workflows/checkout_workflow.py
80d327e52f1afd0420018f439828cbbcdf86f9e1c3b9df1f3fe837d7ab9041c8  workflows/test_checkout_live.py
ab53dae0ab4e846a813135d809840574d0e3702bdbc9f21c2b301cfc05888aab  workflows/test_checkout_workflow.py
```
