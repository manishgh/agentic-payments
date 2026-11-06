# Review 0008: Extension descriptors and adapter TCK

Reviewed: 2026-11-06. Role: independent senior source reviewer, GPT-6.1 Sol at high reasoning. The reviewer did not author the implementation or ADR 0003. This assignment changed only this review document; no source edits or commits were made.

## Scope and verdict

Reviewed the new SDK descriptor and TCK packages, JDBC TCK factory and CLI, descriptor/TCK tests, descriptor JSON schema/fixtures, adapter-sdk JAR classifier configuration, live-response schema validation and USD revocation addition in the HTTP tests, and specification introductions. Also reviewed the upstream source-lock/probe tooling, aggregate evidence/CI changes, and independently exercised the offline checks. Protocol mapping, cryptographic trust and replay/core concurrency are separate Astra review scopes.

Read AGENTS, workspace engineering standards, current internal contracts and ADR 0003. The integration owner records that the authored design was accepted before implementation; exact gate/source acceptance remains the owner's responsibility.

**Verdict: recommend accepting this bounded metadata and adapter-only TCK implementation at the source identities below. No remaining blocking finding in the reviewed scope.** Whole-platform integration must still compile and pass the final unchanged-source suite. A selected adapter-case pass does not certify every descriptor capability, the local platform profile, any real provider, AP2/ACP interoperability or production readiness.

## Findings and resolution

| ID | Severity | Finding | Disposition |
| --- | --- | --- | --- |
| EXTCR-001 | Medium | The local-profile special case required four cases and sealing but did not require the descriptor's mandatory CapabilityOffer.CATALOG. A caller-selected smaller capability set could bypass that profile precondition. | Closed. The runner requires the full local catalog independently of the caller plan. A reduced local descriptor now yields INCOMPLETE; independent regression passed. |
| EXTCR-002 | Medium | Features advertised outside the selected cases disappeared from the report, despite ADR 0003 requiring explicit unevaluated declarations. | Closed. Sorted exclusions identify declared capabilities not evaluated by passing selected adapter cases. The omitted-advertised-seal regression passed. |
| EXTCR-003 | Medium | Capability evidence was recorded before fixture close. A close failure replaced PASS with INCOMPLETE but left that capability classified as evaluated. | Closed. Evaluated capabilities are derived from final PASS results after fixture lifecycles. The close-failure regression checks one INCOMPLETE result, unevaluated exclusion and no exception-canary leak. |
| EXTCR-004 | Medium | A reusable serialized report omitted required versus optional case/capability selections and seed/clock, so its requiredCasesPassed value could not be interpreted independently of an unstored caller plan. | Closed. Immutable TckPlanEvidence retains the selected/required cases, required capabilities, seed, UTC initial clock and versioned canonical plan digest. Every result retains its safe fixture namespace, including failures. Provenance regression and JSON CLI execution passed. |
| EXTCR-005 | Medium | The new descriptor schema's dollar end anchors accepted a final LF in identifiers that Java validation rejected. A synthetic modified valid fixture reproduced schema acceptance. | Closed. Strict absolute-end negative lookaheads now reject trailing characters; a trailing-LF fixture was added. Reviewer independently verified LF and other invalid suffix rejection and reran the full contract validator. |

## Metadata and execution boundaries

Descriptor values are bounded, immutable and delimiter-safe. Their digest sorts unordered declarations, while exact revision registration rejects conflicting replacement. Negotiation checks explicit version/profile membership, required global and tuple capabilities, method/credential/action/operation combinations, exact decimal currency bounds, tenant/merchant/provider/revision configuration binding and half-open availability intervals. Multiple matching tuples reject rather than select by list order.

Generic amount metadata uses bounded BigInteger values and can describe currencies/exponents outside the current Money class. That description does not enable execution: the separate local policy requires the simulator profile, synthetic method/credential/action/operation and existing EUR/USD execution bounds. Registration creates no connector, credential, network URL, spend authority or payment operation. Current spend authorization and runtime registration remain separate trusted boundaries.

Atomic absence sealing is an optional generic test extension. A nonadvertising independent adapter can pass its selected baseline with an explicit unsupported seal case; required unsupported behavior cannot pass. Advertised but missing test implementation and unknown executable case IDs are INCOMPLETE. Duplicate economic effects fail using independent observations rather than a success response alone. Adapter exception messages are replaced with predefined safe reasons.

The JDBC fixture uses isolated per-case scope, retains durable journal evidence, and inspects it directly in a consistent transaction. The CLI selects only this local fixture from environment-backed database configuration, emits NOT_CERTIFIED with the scoped adapter report, and uses fixed safe failure text. It has no real-provider activation path.

The four TCK cases exercise a synthetic success bridge with fixed fixture money. They do not enumerate every advertised tuple/currency/authentication flow or establish core authority, reservations, process recovery, adversarial event coverage or full-profile conformance. The report's requiredCasesPassed field applies only to its retained selected plan. Its exclusions must remain attached when presenting results.

## Packaging, HTTP tests and upstream probes

The adapter-sdk classifier includes only io/agenticpayments/sdk/** class files. Those classes use JDK/SDK types, with no JUnit dependency in the reusable runner. This is a classifier of the reference project, not an independently published artifact: consuming its shared Maven POM still inherits parent runtime dependency metadata. Final package contents/build remain an integrated-build check, and public licensing/release remain unresolved.

The descriptor JSON schema closes every object, bounds distinct collections, requires an explicit action set, and validates generic identifiers, revision syntax, positive decimal strings and exponent bounds. Its positive multimethod fixture and negative implicit-action, network-endpoint and trailing-LF fixtures exercise the structural boundary. Minimum-versus-maximum comparison, unique currency semantics, capability-subset validity, tuple ambiguity and current availability remain SDK semantic checks; a schema-valid descriptor does not bypass those checks or grant execution.

The HTTP test addition resolves schemas through a local registry with network retrieval disabled and validates actual success/error response bodies against the selected closed endpoint schema. The USD grant revocation case checks pre-admission cancellation and released exposure without revoking the EUR grant used by other cases. The owner reported eight passing HTTP tests; this reviewer inspected that source but did not independently rerun the whole application transport against the concurrently unfinished core build.

Upstream probes default to offline operation, verify exact length/SHA-256/Git blob identity before parsing, constrain cache paths, and disable remote schema-reference retrieval. The explicit fetch mode obtains bounded public pinned schema artifacts and verifies them before caching; no payment API or credential is involved. The report preserves structural failures and sets signaturesVerified and interoperabilityEvidence to false. This review does not independently approve the AP2/ACP field mapping or validate all 53 researched artifacts; the executed probes checked six cached schema files.

The aggregate runner now includes upstream integrity/structural checks and the local TCK CLI, hashes integration and CI sources, and runs Maven clean verify to avoid carrying old test reports into a successful build. Each evidence run writes open logs in a unique ignored tools directory so Maven clean cannot delete or lock them; logs/report are copied to target after the checks while the unique archive remains. A failed Java/database build makes dependent transport/TCK/graph stages INCOMPLETE, not passing stale bytecode. Unchanged before/after source manifests remain required, and the verdict remains NOT_CERTIFIED. CI explicitly hydrates only immutable public schema probes before its offline aggregate check. CI additionally archives only target/conformance/report.json for 30 days, on success or failure, using the pinned upload-artifact commit ea165f8d65b6e75b540449e92b4886f43607fa02; missing evidence warns rather than fabricating a report. This adds no payment/provider or package-publication action. These changes received source/syntax review; their final whole-run execution remains the integration owner's evidence task.

## Independently executed checks

| Check | Observed result |
| --- | --- |
| Initial root Maven selected-test command | Could not compile because ReplayRunner/ReplayNormalizer were still being authored concurrently. This was recorded as an integration-in-progress limitation, not an SDK failure or a skipped passing build. |
| Isolated SDK-only temporary POM | Initial 9 descriptor and 6 TCK tests passed; subsequent regression additions were rerun. |
| Final isolated JDBC-inclusive temporary POM, Maven offline test | 20 passed: 9 AdapterRegistryTest, 10 AdapterTckTest, 1 JdbcAdapterTckIntegrationTest; zero failures/errors/skips. |
| TckMain against configured local PostgreSQL | Emitted JSON with adapter subset pass, NOT_CERTIFIED and retained plan digest. |
| integrations/upstream/test_probes.py | Four integrity/offline/disabled-claim tests passed. |
| integrations/upstream/probe_contracts.py, default offline mode | Six cached source schemas verified; 12 structural probes matched their pinned expected outcomes, including expected failures. |
| scripts/validate_contracts.py after descriptor correction | 21 schemas, 17 positive, 27 schema-negative, 6 binding-negative and 1 parser-negative passed; canonical checkout remains 402 bytes. |
| Descriptor suffix probes | Trailing LF, punctuation, slash, underscore and space reject in providerId; no source mutation was needed for these probes. |
| Python compile check for updated scripts/conformance.py | Passed. Full aggregate execution deferred until unrelated replay/core build was stable. |

Temporary POMs were placed in ignored target output and compiled actual SDK sources. The JDBC-inclusive variant additionally compiled the real adapter/factory/CLI, SQL helper, PaymentRules and migrator, used current migration resources and actual local PostgreSQL, and excluded unrelated unfinished replay/core sources. It did not replace PostgreSQL with an embedded or memory implementation. Native loopback test access used approved escalation; credentials were loaded without printing. These are focused review results, not a claim that the normal root build or final aggregate suite passed.

## Reviewed source identity

Working-tree base: 314bd2fe2303892857816944e97a990195581dce. New files were not yet committed at capture. Group hashes bind actual file bytes, using SHA-256 of compact UTF-8 JSON containing the lexically sorted list of repository-relative POSIX path/file-SHA-256 pairs. Python default JSON ASCII escaping is used; exclude __pycache__ files.

| Scope | Files | SHA-256 |
| --- | --- | --- |
| src/main/java/io/agenticpayments/sdk/descriptor/*.java | 10 | 4b062cdfb3f616a1d6e6eef362c9f9ac2e8e33a0ee4285cf8e354341e1c96f4c |
| src/main/java/io/agenticpayments/sdk/tck/*.java | 12 | d1ff4c3cf417765d4a1ab3591d6b86f6c500aef3f103f526393c86a6eb070759 |
| src/main/java/io/agenticpayments/infrastructure/tck/*.java | 2 | 512366cd552e1e63491e727388a2cf682d2f09fac7eeb726bf72abef80d84e01 |
| src/test/java/io/agenticpayments/sdk/descriptor/*.java and sdk/tck/*.java | 3 | 531383257bf57636d0a0014fc6618f687af7ed04f0ed688074f1aacda56ff123 |
| scripts/test_http.py and pom.xml | 2 | 3c134cefa490fa652cde3662bfa69638834eb3fb2d9366de2287efaf504ee211 |
| integrations/upstream/**, excluding __pycache__ | 7 | 4c2d754a71f47995fb50b176b0a88e906b08ba1db52bc0712565c478bcb8ced6 |
| specification/README.md and specification/0.1.0-draft/README.md | 2 | 5a7d7d22c07e96e0684b240343d26058f132d2e8a9de19489aaab12687f34c1d |
| Descriptor schema, fixtures/manifest.json, valid/adapter-descriptor.json and invalid/descriptor-*.json | 6 | 1ac3afc55b5418f0ab07105d9430354ad99a401195575df77fefb0a9e8af5675 |
| scripts/conformance.py and .github/workflows/reference.yml | 2 | f34c62660189ff9fc039ac1205034f7892fc7e8bd80be603e48fe0036d6b3d03 |

Critical individual files:

- AdapterTck.java: 0f0a833bd32effb03957473097dd209cdff598864660a2f6364a910dc0ffa685.
- TckPlan.java: 2b0ac74482612dc0720def592e6e0180faab8ad65cd2b1109057be661cfca5a0.
- AdapterRegistry.java: 10ccd200237a923782be6f98612de890a6232c84d574f13b14bafc4c0a8bdb92.
- TckMain.java: eee2f3e29d1f7a6fff42ff2e83f49a5a7b3934254c5b374c2a29b5e8975cf108.

Publication normalization: three descriptor fixture files (valid/adapter-descriptor.json, invalid/descriptor-implicit-action.json and invalid/descriptor-network-endpoint.json) were converted from CRLF to LF. The reviewer reconstructed the previous group digest by reversing only those newline conversions, confirming no semantic content changed. The schema group recorded here now binds LF bytes; the evidence/CI group includes the inspected report-archive step. The integration owner also reported newline normalization of the versioned specification introduction. The reviewer reread both current introductions and their staged status-wording changes and refreshed their group identity to the current bytes. Uniform CRLF reconstruction did not recover the earlier raw-byte group digest, so this record does not assert an independently reproduced newline-only delta for that group. All nine recorded groups and four critical individual hashes were rechecked against current bytes; all match. Other reviewed source groups are unchanged.

Refresh affected identities if source changes. Final integration evidence must record its own unchanged before/after source manifest, normal root build/tests, and actual package inspection. No project certification or external interoperability claim follows from this review.
