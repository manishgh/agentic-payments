# Review 0012: Aggregate integration and offline setup tooling

Reviewed: 2026-11-06. Independent senior code reviewer: GPT-6.1 Sol at high reasoning; not the author of the reviewed integration changes. Baseline: 0f60d93de1045967a48a65c521ceae0abf7bcfe1. No application source was edited or root Maven build run in this assignment.

## Scope and verdict

Reviewed changes since the SDK checkpoint in scripts/conformance.py, reference CI, upstream probe setup/integrity tests and README. Inspected the new lab dependency manifest/crypto lock and the lab/replay CLI exit contracts needed to understand orchestration. Lab cryptography and replay/core correctness remain separate independent review scopes; running their provided tests here is not an approval of their implementations.

**Verdict: recommend accepting the integration wiring at the identities below, subject to the actual unchanged-source aggregate run and the separate lab/replay acceptance decisions. No remaining blocking finding in this scope.** This recommendation does not claim that the full current application suite, hosted CI, G3 or real interoperability has passed.

## Finding and resolution

| ID | Severity | Finding | Disposition |
| --- | --- | --- | --- |
| INTCR-001 | Medium | lab_dependencies validated each supplied manifest record but accepted an empty/reduced artifact list. The explicit include-offline-lab setup could succeed without checking every dependency of the named profile. Runtime lab loading independently failed closed, so this was a setup-contract gap rather than an execution bypass. | Closed. The setup function now requires the exact nine profile schema IDs as well as matching lock identity/role/source/path/commit/hash/length and no duplicates. Missing and empty manifest regressions were added; the reviewer reran all five integrity tests successfully. |

## Integration observations

- The aggregate runner adds lab unit tests, an offline demo and recorded replay CLI. A failed Java/database build marks TCK, recorded replay, HTTP and graph stages INCOMPLETE and does not run them against old target classes. Maven clean verify precedes these Java-dependent stages.
- Nonzero child exits, timeouts and missing local toolchain/start failures produce failing results; any failure/incomplete result or before/after source mismatch prevents previewChecks PASS. require-certification returns nonzero even if preview checks pass, and certificationVerdict remains NOT_CERTIFIED.
- Each run writes logs outside target under a unique ignored tools directory, then copies results to target after Maven clean. Original run archives are retained. The report identifies exact source bytes, including integration/CI files and dependency locks; it is traceability evidence, not independent provenance or proof of every untested behavior.
- The replay CLI maps a non-PASS report to a nonzero exit. The lab demo emits its restricted offline status and keeps native AP2 authority, provider interoperability and payment dispatch false. The integration wrapper does not convert either demo into financial success or external certification.
- Required upstream preparation is an explicit setup action. CI and README use fetch with include-offline-lab before the aggregate runner. The runner and lab runtime omit fetch; absent/changed cached artifacts fail rather than silently reaching the network. Union setup verifies 11 cached schemas, while the lab's own required set contains nine.
- The hydrator checks immutable allowlisted public source URLs, bounded download size and exact locked bytes before caching. Schema references resolve through a local registry with remote retrieval refused. No bearer/payment credential or provider endpoint is part of this setup.
- The additional dependency lock uses exact crypto-related package versions, binary-only selection and published wheel hashes. Installed local versions observed were cryptography 50.0.2, cffi 2.1.1 and pycparser 3.12. The local pip implementation enables hash checking when requirements contain hash options. This does not independently verify dependency publisher provenance or review crypto algorithms.
- CI preserves read-only contents permissions, immutable action pins and a report-only upload with bounded retention. It installs dependencies and explicitly hydrates public schema files; these setup network actions are distinct from offline verification and make no payment call. Actual execution of the changed hosted workflow remains unobserved in this review.

README now identifies implemented source features and their setup commands while retaining synthetic execution, no real provider/bridge connection, NOT_CERTIFIED and open licensing/release boundaries. Implementation-source existence is distinct from final acceptance; current roadmap/backlog preserve the pending lab/replay reviews. Historical SDK checkpoint CI wording was updated from pending to the integration owner's recorded passing reference run 42307004050 and documentation run 42307004092; those runs do not validate later source changes.

## Independently executed checks

| Check | Observation |
| --- | --- |
| upstream/test_probes.py after fix | Five tests passed, including changed/duplicate/missing/empty lab dependency manifests. |
| upstream/probe_contracts.py with include-offline-lab, without fetch | 11 cached schema sources verified; 12 structural probes matched expected positive/negative outcomes. Interoperability/signature flags remain false. |
| Provided offline lab unittest discovery | 26 tests passed. This is integration execution evidence, not this reviewer's independent cryptographic design/source approval. |
| Offline lab demo | Exit 0; safe restricted report, financial UNKNOWN, native AP2 authority/provider interoperability/dispatch false. |
| Python syntax checks | Updated runner/probe/test modules compiled successfully. |
| Four isolated orchestration probes | Failed build, source mutation, timeout and require-certification behavior produced the expected failure/incomplete outcomes described below. |

The orchestration probes imported the actual runner into isolated temporary roots, replaced subprocess execution with controlled fake child results, and provided synthetic configuration only. They verified that failed Java build prevents all four Java-dependent stages, changed source makes the preview fail, a timeout records exit 124, and require-certification exits 1 while retaining NOT_CERTIFIED even when controlled children succeed. Each probe retained its unique report archive. These probes executed no Maven, Java, provider or payment operation and are not application test passes.

The real root aggregate/replay CLI and updated hosted CI were deliberately not run while the separate replay review/author tests controlled target classes. Final integrated evidence must name actual test counts, source stability and reviewer acceptance, rather than reuse the SDK checkpoint result.

## Reviewed source identity

Group SHA-256: 5ba4c1b3046102b23fdd6878812789c9355eea52fad3c8fcfe71f181675979f8.

The group hashes compact UTF-8 JSON of the lexically sorted list of repository-relative POSIX path/file-SHA-256 pairs, with Python default JSON ASCII escaping. All seven files are included:

| File | SHA-256 |
| --- | --- |
| .github/workflows/reference.yml | ec80092b303f0c1b34d056307ddbc226b15df3537e313d096e2a8b9d91734a44 |
| README.md | a9fcef4db7f5fa7476fd58769bfa0365e07e38e1ccb6153bd984e9de08d40793 |
| integrations/offline_lab/required-source-artifacts.json | bbcc8ad7261ba231bc08b2dd76202e071550f6aeaa1f2a8eaa9001f065190e82 |
| integrations/offline_lab/requirements-crypto.lock.txt | 16b2e11a0b9b963c7238f3199bf9d2fc570954b245740c60ad1634738dabf292 |
| integrations/upstream/probe_contracts.py | b5afa2d4b1a8a7494bb7809efa732fc049270298e5d04db026829a6455df9a69 |
| integrations/upstream/test_probes.py | aa470741d850b4d688e11a392f1ec0cc6ea763175797734206698ac020bece2a |
| scripts/conformance.py | 6f693ad9f615fc0636e6feb6c6d8d008b80a554b050d1a71a3d1532089405042 |

Evidence-wording follow-up: the reviewer inspected two report-text changes after the original checks. CT-011 now states that authenticated conflicts fail closed without equality digests, and requiredBeforeCertification explicitly retains reviewed normalized conflict/disputed-exposure/frozen-budget evidence. Reversing only those two string changes reconstructs the previous runner and complete seven-file group hashes, confirming no runner logic or other reviewed file changed. The narrower wording is appropriate; no application tests were rerun for this text-only change. Hashes above bind the updated wording.

Portability follow-up, 2026-11-07: source_identity now globally sorts entries by repository-relative POSIX path string after adding every source and pom.xml, before JSON hashing. This removes dependence on per-platform Path ordering and the earlier directory-group/POM-last sequence. The reviewer independently checked that all 219 manifest keys are globally lexically sorted. Removing exactly the one new sort line reconstructs the previously reviewed runner and seven-file group hashes, confirming no other reviewed file changed. The change is appropriate for portable new evidence identities; historical recorded manifests remain unchanged. No Maven or application test was run for this isolated manifest-order correction. Hashes above bind the corrected source.

Refresh affected identities after source changes. No full-platform, provider, AP2/ACP, PCI, regulatory or payment-network certification is established by this integration review.
