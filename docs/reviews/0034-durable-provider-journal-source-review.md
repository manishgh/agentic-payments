# Review 0034: Durable provider journal source

Status: independent source review completed, 2026-11-08. Accept the exact twenty-file implementation manifest below for integration-owner aggregate validation. All findings are resolved; no blocking source finding remains. This acceptance is bounded to the synthetic journal and does not approve financial wiring or production operation.

Reviewer: independent architecture/concurrency reviewer, distinct from the production and test authors. The assessment follows accepted [ADR 0013](../adr/0013-durable-provider-journal.md) and [review 0033](0033-durable-provider-journal-design-review.md). The reviewer owns this report only and makes no implementation/test changes or Maven/conformance runs. Root owns integration, test execution and aggregate evidence.

## Boundary and implementation assessment

The implementation uses the unchanged `CompatibilityBindingJournal` responsibility. PostgreSQL, the explicit JSON codec and configuration helpers remain infrastructure; the portable SDK and original compatibility bridge are unchanged. Provider calls occur through that bridge after short journal transactions, not inside database work. V009 adds separate tables without creating financial intent/run rows or changing historical migrations. Root's migrator and historical-test amendments register V009 and retain the preceding prefix/checksum assertions.

Trusted scope and capability validation precede database activity. The constructor-bound scenario policy produces the independently checked immutable legacy mapping. Full operation, legacy identity/scenario, submission association and policy revision comparisons reject changes under an existing namespace. Exact complete values matter in addition to their fingerprints. Operation-ID aliases and legacy-key collisions retain safe receipts without inventing a binding for the rejected namespace or poisoning the legitimate owner.

The transaction wrapper returns a decision, commits it, closes the connection and only then raises its safe conflict failure. Provider/SQL exception text and causes do not escape that boundary. A failed or lost commit acknowledgment returns uncertainty rather than a dispatch grant. Once published, ownership is never leased, reset or stolen. Any prior recorded fact blocks initial ownership, including UNKNOWN; this deliberately preserves safety at a documented liveness cost. Fresh scope/history/exclusive-key premises remain necessary: an empty journal cannot prove an old provider operation never occurred.

Evidence records compare complete same-ID facts, including source, exact instant, resource and action. Valid distinct observations are appended before projection/conflict decisions. Pending/captured versus a winning fence quarantines in both orders; later uncertainty does not erase pending/captured projections. Unsupported financial states are retained as protected violations. Resource ownership cannot transfer or silently change, and absent references cannot release it. Persisted quarantine prevents clean replay. The synthetic peer, not the compatibility claim, supplies atomic acceptance-versus-seal behavior.

## Findings addressed during review

| Finding | Correction and evidence obligation |
| --- | --- |
| PJ-1: The codec sorted the list of currency limits, changing record equality and exact selection after decoding. | Preserve list order while canonically sorting set-valued enums. Codec regressions round-trip reversed limits through operations, submissions and evidence and revalidate the original selection. |
| PJ-2: The SQL submission-presence CHECK used `= false`, allowing NULL through PostgreSQL's UNKNOWN check result. | Use `IS FALSE`; independent SQL tests reject partially populated associations rather than relying only on Java validation. |
| PJ-3: The finite-cache test originally wrote/cleared a table that did not affect execution, so it did not demonstrate cache expiration behavior. | The test cache now returns its retained fact on a hit and invokes the durable peer on a miss. Independent counters distinguish cold, hit and expired cases while retaining one actual peer acceptance. The uncertain-claim case still forbids replacement execution even with an empty cache and failed lookup. |
| PJ-4: A scenario resolver returning null caused a raw null-pointer failure outside the journal sanitizer. | Explicitly reject null using a fixed safe failure. Direct bind/read/claim/record tests cover null and throwing resolvers, no cause/payload leakage and zero database activity. |
| Root ownership-consistency finding: A stored submission with both ownership flags false could be interpreted as a first dispatch candidate. | SQL and load-time checks require every present submission to have claimed ownership or a permanent dispatch block. This detects an impossible published state without claiming resistance to arbitrary administrator tampering or complete history deletion. |

## Evidence and remaining acceptance gates

The codec explicitly represents optional fields and nanosecond instants, rejects duplicate/unknown/missing fields and invalid types, validates fingerprints through SDK construction and checks bounded payloads/collections. Read paths compare redundant stored identity, evidence source/state/time and digest columns. Corruption cannot default an existing submission to a fresh owner or turn missing evidence into no effect.

The process helpers invoke separate JVMs with fresh connections and bridge/journal objects. Halt boundaries distinguish uncommitted bind, committed mapping, committed claim, peer acceptance/seal and committed terminal/conflicting evidence. Parent SQL observations inspect both compatibility storage and the independent durable peer after child termination. A fixed child output marker keeps configuration and failures out of test reports. Existing reusable TCK fixtures retain their in-process reconstruction label; these named child-process tests supply separate process evidence.

Independent transaction tests observe PostgreSQL lock waits through a separate connection, contend eight claimants for one owner and prove unrelated rows progress. Scoped test-only triggers exercise genuine rollback after provisional binding/submission or fact/resource/projection writes. Separate commit proxies model acknowledgment loss after a real commit. A delayed original worker can invoke the legacy adapter after the peer fence, but the inspected peer journal contains no successful financial event; the test explicitly counts that Java invocation rather than claiming none occurred. Full binding rotations cannot poison the original operation, and the original pinned policy still replays after replacement configuration is rejected.

The migration test starts from exact V001..V008 SQL, populates native financial success, provider-peer history and unresolved checkout uncertainty, snapshots every historical data table, then applies V009 twice. It checks preserved rows, original checksums, the exact new migration checksum and initially empty journal tables. The existing native/checkout prefix tests remain in the focused selection.

Root ran a passing forty-method focused selection: sixteen independent integration, seven transaction-boundary, three binding-policy, eleven codec, one populated journal migration and the two existing native/checkout migration methods. A final cleanup amendment added rollback in the locked-row test's finally block so a failure releases the lock before executor closure. Because that amendment arrived during the earlier run, root separately reran the final seven transaction-boundary methods on the frozen bytes; they passed with zero failures/errors/skips. The reviewer inspected the generated Surefire summaries, including that final seven-method result, rather than rerunning builds. Root also reported documentation and whitespace checks passing.

The reviewer independently recomputed every raw file hash below, verified all twenty files use LF, and reproduced the manifest identity. The unchanged-source full aggregate, SDK dependency check and publication records remain root gates. This slice supplies no vendor sandbox evidence, external-event authenticity, financial admission wiring, database-server/power-loss guarantee or production operations approval.

## Exact reviewed source identity

Manifest SHA-256: `9456965d09125d5b64a21024307474a7ec74ba624932205b2a7e99246edc537e`. Sort repository-relative paths ordinally; each manifest line is lowercase raw-file SHA-256, two spaces, path using `/`, then LF, including a final LF. Hash the resulting UTF-8 bytes. The table below contains the complete accepted set. Later status/backlog/evidence/gate publication records are outside this source manifest and do not substitute for aggregate evidence.

| Reviewed file | SHA-256 |
| --- | --- |
| `README.md` | `1d2c2932e2b0251bff7ace3360215f868ab38cce944840403c2e7f73c45b51de` |
| `docs/architecture.md` | `c76be82af2e46e6d765b2718204466c678c373e6383633a60128d5ce6b5e4c5f` |
| `docs/data-model.md` | `e6aa797331ff1643e70f43d25490694f72ebb4f5e095c6788f9f07434e0d9411` |
| `docs/durable-provider-journal.md` | `f5463a765380ac02508469ce933e63c667af360b9ece5e09b5df2e4ce84393cf` |
| `docs/provider-adapter-evolution.md` | `c02f992f56c8a5486c079ad9c8ae816f735fee54ade5f2524419447e616f102b` |
| `scripts/requirement_evidence.py` | `ea5a83ccabba157a685ecceb3c182dfecda27d5df5d92ed80def56195bfa3d9f` |
| `src/main/java/io/agenticpayments/infrastructure/DatabaseMigrator.java` | `769aedf4acbc37b6c6c279d48852ccddcef24b66603af062b488e09893bd1324` |
| `src/main/java/io/agenticpayments/infrastructure/provider/JdbcCompatibilityBindingJournal.java` | `4b1a9c0f87fa68742eaaeec655c62bab6341491cca37055be94ab2c665490561` |
| `src/main/java/io/agenticpayments/infrastructure/provider/ProviderJournalCodec.java` | `383957b67f128c2dc8a25d66437c47943703f5294b3b760faef4f01f09be1231` |
| `src/main/java/io/agenticpayments/infrastructure/provider/ProviderJournalConfiguration.java` | `433380494b588296b5a66aa5454b37ad584e60ed5ea36d1d15ac9b7012e30c4c` |
| `src/main/resources/db/V009__provider_compatibility_journal.sql` | `b1dd519a406dcb3b97d4a65061a1603dd87ff66687a7b781796865e7f0a23d91` |
| `src/test/java/io/agenticpayments/core/CheckoutMigrationUpgradeTest.java` | `884ead87f67212578bfaf28c11fdaca2e2bd23155a14924dd756775bca98d2dd` |
| `src/test/java/io/agenticpayments/core/NativeMigrationUpgradeTest.java` | `0771d3b310406b906a526f98635e5888b6999fc6bb835b07a33511a51209aeaa` |
| `src/test/java/io/agenticpayments/provider/IndependentProviderJournalIntegrationTest.java` | `946e4af439eab058939688cc12413dff2c520db658527ab053fb065bc201cb5b` |
| `src/test/java/io/agenticpayments/provider/ProviderJournalBindingPolicyTest.java` | `bebfc752499621b32396aa87ccd5fb76845bf91076cd55cd1438cb898673da6c` |
| `src/test/java/io/agenticpayments/provider/ProviderJournalCodecTest.java` | `131e25ba38579d569040dad4f988c286d854195d809e66b8f2bc14312124910f` |
| `src/test/java/io/agenticpayments/provider/ProviderJournalCrashProcess.java` | `46bcc079d66f28d3cc74b4bda773cac388f468417541892d008c3ba055c1d54a` |
| `src/test/java/io/agenticpayments/provider/ProviderJournalMigrationUpgradeTest.java` | `ad830ff945638be113747c0c3a708da5bcbaa61490d0614233368e7a6b9f8e38` |
| `src/test/java/io/agenticpayments/provider/ProviderJournalTestSupport.java` | `359201373ff57fed5963e787f3558f7a380aa711ea4ac952cad546ebea31024e` |
| `src/test/java/io/agenticpayments/provider/ProviderJournalTransactionBoundaryTest.java` | `7e6f70aff093b772cdabd8b905b71679722c44c6e0538a690fd2e6cd27150aa9` |
