# Review 0007: Reference clients, HTTP boundary and tooling

Reviewed: 2026-11-06. Role: independent senior code reviewer, GPT-6.1 Sol at high reasoning. The reviewer did not author the reviewed implementation. Ownership in this assignment was limited to this review record; no application source was edited. This is separate from earlier plan review 0002 and the independent core/concurrency review 0006.

## Scope and verdict

Reviewed Java `LocalSessions`, `Main`, `PaymentHttpServer` and SDK/contracts/tests; TypeScript review UI and tests; Python LangGraph client/checkpoint workflow and tests; HTTP test harness; conformance runner; PowerShell bootstrap/environment/database/platform scripts; dependency/distribution locks; and the reference CI workflow. Read AGENTS, workspace standards, the API contract, ADR 0002 and gate records. PostgreSQL/core execution correctness is primarily assessed in [review 0006](0006-reference-code-review.md), not independently re-approved here.

**Verdict: recommend accepting the bounded local-fake client/HTTP/tooling implementation at the identities below. No remaining blocking finding in this reviewed scope.** The integration owner must require a final unchanged-source aggregate run and record its actual evidence. This does not pass full project conformance, real protocol/provider integration, production security, public licensing, deployment or live-payment gates.

## Findings and resolution

| ID | Severity | Finding | Disposition |
| --- | --- | --- | --- |
| CLIENT-001 | Medium | HTTP specified UTF-8 but passed raw bytes to Jackson, whose encoding detection accepted UTF-16. An independent in-process parser probe reproduced `UTF16_ACCEPTED=1`. | Closed. Owner added strict UTF-8 decoding with malformed/unmappable input set to REPORT before JSON parsing. Added authenticated HTTP rejection tests for UTF-16 BOM/non-BOM, UTF-32 and malformed UTF-8. Reviewer independently reran all seven HTTP tests successfully after the change. |
| CLIENT-002 | Medium | Evidence computed source identity only after tests, permitting results from earlier source to be associated with a later working tree if files changed during checks. | Closed for the local evidence runner. Owner records manifests before and after checks and makes previewChecks FAIL when they differ. Reviewer inspected both comparison and fail-closed verdict logic. The reviewed CI workflow is separately hash-bound below; the runner's source manifest is not a universal environment/provenance proof. |
| CLIENT-003 | Gate record | Written G2-S record still excluded dispatch/actions/journal/LangGraph/UI while the implementation included them. | Written record corrected to identify the integration owner's earlier scoped extension acceptance and the later recording time, ADR/review hashes, bootstrap and client boundaries. This reviewer does not infer that the original limited gate covered later work or independently approve authored ADR decisions through code review. |

## Positive observations

- HTTP binds `127.0.0.1`, checks Host and any supplied Origin, sends no permissive CORS response, and exposes no public event/runner ingress. API tenant/run/roles come from the resolved local bearer session. Duplicate authorization/key headers reject; tokens are not echoed in fixed transport errors.
- POST bodies are bounded to 16 KiB. Duplicate JSON names, trailing documents, null/missing primitive creator properties, unknown properties and scalar coercion are rejected. Canonical UUIDs, typed versions and fixture identifiers are checked before core commands. Static files use a fixed allowlist, with no caller-controlled filesystem traversal.
- Trusted approval displays merchant/money/revision/expiry from the authenticated checkout response used for its digest/version and selected fixture grant. Changing token or typed intent invalidates review. DOM text uses textContent, and the UI keeps a stable command key after transport uncertainty. Only the trusted role can approve or complete an action; UI completion is not payment evidence.
- Within the supported bound of at most 999999999999 minor units and exponent two, UI numeric formatting does not lose integer-unit precision. This observation is limited to the current EUR/USD local profile and is not approval of arbitrary currencies/amounts.
- Graph commands are limited to create/quote/status/execute. Authority/action methods are absent, bearer credentials stay on the client object outside checkpoint channels/config, and arbitrary resume payloads are discarded before entering LangGraph. Checkpoints contain selected safe references; status is read from the core after historic command replays. Existing operation/attempt identities and nonregressing versions are verified.
- Graph HTTP accepts only an explicit HTTP 127.0.0.1 address/port, disables ambient proxies and redirect forwarding, bounds responses, and reports fixed safe errors. Stable run/workflow-derived keys survive restart and do not create a new charge merely because an execution response was lost.
- SDK values are immutable and separate provider/method/credential/action dimensions. Exact money and versioned capability negotiation reject unsupported combinations. NOT_FOUND/UNAVAILABLE cannot be final no-effect facts; atomic sealing is explicitly a local fake guarantee.
- Bootstrap checks reviewed archive hashes and publisher checksum contents before extraction into ignored project-local tools. Local database startup uses generated credentials, SCRAM and loopback; the platform does not print tokens. CI has read-only contents permissions, pinned action commits, bounded runtime and no deployment/publishing step.
- Evidence preserves failures/exclusions and always reports NOT_CERTIFIED. CT-011 recorded-schedule replay and complete CT-006/CT-010 coverage remain explicit gaps. Optional split/voucher support and real AP2/ACP/provider evidence are not claimed.

## Checks independently executed

| Command/check | Observed result |
| --- | --- |
| Local-toolchain Maven offline `-Dtest=SdkContractTest,GoldenContractTest test` | 23 passed, zero failures/errors/skips. |
| `npm.cmd test` from ui | TypeScript build and three UI tests passed. |
| `.venv/Scripts/python.exe -m unittest workflows.test_payment_workflow -v` | Ten tests passed, including restart, lost responses, spoofed resume, changed identity, scope and checkpoint canaries. |
| `.venv/Scripts/python.exe scripts/test_http.py` with isolated run and configured local PostgreSQL | Seven tests passed, including new encoding regression; host socket access used approved escalation. No credentials were printed. |
| Parser probe before CLIENT-001 fix | UTF-16 accepted by raw-byte Jackson path; used only synthetic JSON. |

The reviewer did not independently rerun the full database core suite, the two graph-to-Java process tests, or hosted CI in this assignment. Owner aggregate-run evidence must identify their actual results separately. Early JShell probes failed because sandbox registry/socket facilities were unavailable; the successful reproduction instead used an in-process Java source-file probe without database access. Those failed probe attempts are not counted as application test failures or passes.

## Reviewed source identities

Working-tree base commit: `a82973df705df4d7f937f92a07e3d18b4579b3f4`; new implementation files were not yet committed. Hashes bind actual reviewed bytes rather than treating that base commit as containing them.

Each group hash is SHA-256 of UTF-8 JSON encoding of the lexically sorted list of `(repository-relative POSIX path, SHA-256 of file bytes)` pairs, using compact separators and the default JSON ASCII escaping. Include regular files in the named scope and exclude any path segment `node_modules`, `dist` or `__pycache__`.

| Scope | Files | Group SHA-256 |
| --- | --- | --- |
| src/main/java/io/agenticpayments/http/*.java | 3 | `8efc40e836fb3d204ff2d27feeaab59838a93d5a616b146241e00e20ebc6a70c` |
| src/main/java/io/agenticpayments/sdk/*.java plus src/test/java/io/agenticpayments/sdk/*.java | 17 | `02cd2c2ae1a4afc34c6c28b1c4ad2e5e3c3075bcec415e877a7639793ce7c9c3` |
| ui/** | 7 | `2cb90702899585c191dbbc9e25faacd0aac1428eae0736fcb57afcc607abd1fa` |
| workflows/** | 7 | `a68a6a718fbcf5abf80f9307382500c46d7fdcc678a805ed95031222edf1925b` |
| scripts/* | 10 | `0530467991f08742a25d20d38158feb1addbdfe27343380eda6a8947db31293d` |
| .github/workflows/reference.yml and pom.xml | 2 | `83753dbce50e6ae628528d9c95f5a61dd88074b0a4405a31edb37b01c06ea424` |

Critical individual file SHA-256 values:

- PaymentHttpServer.java: `f8409abbc2a21176efcddd6298dc3093faf0ab50fe904119471ddfe73f4dffa7`.
- scripts/test_http.py: `611b6fe5472015eabaee88a5bea675e62b7911105a63c33b5d319fb9611767a5`.
- scripts/conformance.py: `7232d999c89c88ecaee17e267f8af8eca9858d1c7bb674b7e7a41d3334c8a9fe`.
- workflows/payment_workflow.py: `2603f948aa1914a4557639f6d972feb8a34ccf6e0f27f36e594f6db950e276b4`.
- .github/workflows/reference.yml: `f8192264c142a386274679e2140d8ae8570c50b583e80f70387364cff417b777`.

Publication normalization: `workflows/dependency-inventory.json`, `workflows/requirements-lock.txt`, and this review were normalized to LF to match repository attributes. The reviewer verified that reconstructing CRLF for the two workflow files reproduces the earlier workflow group hash; no semantic content changed. The workflow and aggregate hashes below reflect normalized bytes; the integration owner reruns the final suite against those bytes. Other scoped identities are unchanged.

After normalization, `scripts.conformance.source_identity()` returned `8e9ea08dbb169b39e7aece5ba3eb5f58d2ce7078eea3a6cc5051a061b3717746`. This auxiliary snapshot also covers implementation files outside this review's primary scope; use the scoped hashes above to determine whether this review needs refresh. Final aggregate evidence must bind its own unchanged before/after source identity.

## Remaining limits

Local static bearer sessions, same-account files, plain loopback HTTP, eight worker threads and body limits are development controls, not a hardened multi-user identity or denial-of-service design. The local checkpoint store is explicitly a single-writer orchestration store, not payment truth or an authorized shared-tenant interface. Checkout UI is a synthetic fixture review, not an approved real AP2 Trusted Surface. No real provider credentials, live money, external protocol verification or licensing decision is supplied here.
