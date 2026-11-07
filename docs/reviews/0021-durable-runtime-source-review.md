# Review 0021: Durable runtime source

Status: accepted for the bounded ADR 0007 implementation, 2026-11-07. All five source findings are resolved. Publication requires the integration owner's unchanged-source aggregate gate against this corrected snapshot; no pending aggregate result is claimed by this report.

Reviewer: independent protocol/security reviewer, separate from the runtime and tooling authors. Accepted design: [ADR 0007](../adr/0007-durable-workflow-runtime.md), SHA-256 `338a8a4bb256b0278028832209f9a3d397782d772d3b321f2d550f6cf00a16e2`, and [review 0020](0020-durable-runtime-design-review.md). The reviewer edits this report only and runs no Maven or runtime installation.

## Boundaries inspected

The review covers the stable runtime/registration contracts, SQLite driver, deterministic Temporal workflow, shared bounded LangGraph decision and HTTP activities, local CLI/worker, runtime tests, dependency inventory/locks, Temporal bootstrap, CI wiring and conformance evidence integration. Financial authority remains in the separately reviewed ADR 0006 implementation.

The shared graph chooses one bounded next action. Native replay code schedules named activities and durable timers/conditions; it does not invoke HTTP or graph/model decisions. Activities retain the established create/quote/execute command-key algorithm. Before execute, the activity rereads the core and rejects changed attempt/operation identity or a regressed version. Accepted command receipts are followed by current status reads. Neither runtime executes a payment provider or approves/completes customer authentication.

Registration atomically retains input fingerprint, scope/principal and attempted-start marker. Temporal binds endpoint, namespace, task queue and workflow definition. First-start uncertainty is resolved only by confirming retained history input; absent history after attempted start cannot create another execution. Explicit reject-duplicate/conflict policy supplements the independent registration tombstone. Cooperative pause keeps the same open workflow; a wake cannot resume it. Resume rereads the core. Version identifiers and credential-free selectors reconstruct the fixed request in the worker; private credentials stay in the HTTP client.

## Findings

| ID | Severity | Finding and acceptance criterion | Status |
| --- | --- | --- | --- |
| RS-001 | P1 | Temporal's activity wrapper serializes a replacement activity's result directly, without shared exact result-schema validation; SQLite persists an unchecked decision string. Add per-kind exact keys/types, decision variants and identity consistency validation before Temporal return or checkpoint mutation. Adversarial decorated outputs containing extra token fields, unknown decisions and changed identifiers must be rejected without persistence. | Resolved; exact shared result validation and adversarial SQLite/real Temporal decorators inspected and tested |
| RS-002 | P2 | SQLite calls `stage_local_activity`, absent from the advertised `RegistrationStore` port. A backend implementing the declared contract cannot substitute. Add the atomic pause-versus-stage responsibility and result semantics to the same stable port, and exercise a forwarding replacement through local scheduling and stop ordering. | Resolved; stable atomic staging port and forwarding-registration test passed |
| RS-003 | P2 | Read-only Temporal inspect rejects failed/canceled/terminated histories before returning safe recovery references; the registration cache is updated only by a prior inspect. A failure after an unpolled accepted execute can therefore hide the core references needed for recovery. Retain a validated safe recovery projection independently of polling, expose failed-runtime observations with nonauthoritative provenance, and keep closed resume/start replacement prohibited. Test failure after execution without a prior inspect. | Resolved; monotonic independent projection, closed-runtime inspection and unpolled accepted-execute termination evidence passed |
| RS-004 | P2 | `RuntimeDescriptor` omits the promised limits/history/privacy policy, and one-second polling grows history indefinitely until native limits fail a delayed approval workflow. Expose concrete configured limits and a bounded safe history/wait policy that preserves references and requires explicit recovery instead of silently creating another execution. Test the limit and document the bounded reference's behavior. | Resolved; explicit 512-activity/16-KiB limits, capped backoff and irreversible HISTORY_LIMIT tested |
| RS-005 | P2 | History privacy tests scan `WorkflowHistory.to_json()` literally, but protobuf JSON encodes payload data as base64. The reviewer independently planted a token in `Payload.data`: the literal scan missed it. Scan decoded payload values, including nested strings/bytes and failure details, as well as raw metadata/logs/registry. A planted token/artifact regression must prove the scanner detects actual payload leaks. This finding is a validation gap, not an observed implementation leak. | Resolved; recursive configured-decoder scan and planted payload/failure-detail artifact regression passed |

## Evidence inspected

The original nine contract tests cover duplicate start/input conflicts, scope/principal/capability/version refusal, lost command responses with retained envelopes, cooperative pause/wake/restart, uncertain start with absent history, atomic local pause ordering, safe input projection, runtime catalog registration and a forwarding runtime wrapper. Existing legacy workflow tests preserve command identity and trusted action separation.

Three real Temporal process tests cover accepted create/quote/execute response-loss barriers, fresh workers and persistent server restart, duplicate wakes, cooperative pause, forced-termination refusal and SDK history replay. Separate Java/PostgreSQL cases cover native authentication pause/crash/resume with the same operation and native response loss with UNKNOWN audit plus one journal effect and consumed reservation. The two native scenarios are complementary; no combined new scenario is claimed. Corrected tests traverse protobuf history fields, decode payloads with the configured converter and inspect nested strings/bytes, including escaped artifact strings and failure details. Raw errors/metadata, registry and recovery rows, and private diagnostics are also scanned. A planted token/artifact fixture proves the original base64 blind spot is closed.

The reviewer independently ran all 27 corrected focused Python methods: 16 runtime, one encoded-history privacy regression and ten existing workflow methods passed in 2.018 seconds. Windows asyncio requires a loopback socketpair; initial sandbox invocations stalled before test setup, were interrupted, and the loopback-capable invocation passed. The implementation owner reports the expanded real Temporal process test passed in 69.280 seconds and both native Java/PostgreSQL recovery cases passed in 33.934 seconds on the same frozen source. The reviewer inspected their assertions and corrected code; these live tests were executed by the owner. No independent Maven execution is claimed. The later corrected-source aggregate is the integration owner's publication gate.

Temporal bootstrap checks cached/downloaded archives against pinned hashes and a separately pinned publisher checksum manifest, selects only regular binary/license/notice files without using archive paths as extraction destinations, and confines writes to workspace `.tools`. Both Windows and Linux artifacts are pinned. The dependency inventory identifies the base SDK and new transitive versions/licenses; optional agent/cloud extras are absent. CI installs the verified local CLI and runs the aggregate suite. Conformance includes the real runtime tests and independent Java ACP verification, keeps diagnostics private, and binds input locks/source identities before and after execution. Native/runtime methods remain additional evidence; the existing extension coverage map is not silently expanded into certification.

The recovery projection is separate from scheduler progress and atomically orders logical sequence and retained core version. A late timed-out activity cannot erase newer references; a same-sequence status retry may observe newer truth. A newer logical sequence advances even when it retains a newer cached core observation. Caller-facing recovery output from a decorated registration backend undergoes exact shape/provenance validation. If execute was accepted but its response never returned, the cache may retain only intent/attempt: it explicitly directs recovery through current core status rather than inventing an operation.

The graph remains the owner of decision policy. Shared validation enforces closed variants and safe preconditions while allowing conservative wait/read decisions, including a wait before creation without an HTTP call. This preserves real composition instead of duplicating the graph's routing algorithm in the port.

The immutable start limit defaults to 512 activities and permits a smaller trusted 8..512 budget for bounded cases; it participates in the start fingerprint. Polling backs off deterministically from one to 60 seconds and resets on a wake or changed observed core version, never resetting the activity budget. At HISTORY_LIMIT the workflow retains references and schedules no new activities or timers. Controls cannot clear the limit, and tests prove they add no history events. This is a bounded demonstration on the scale of hours, not multi-day orchestration or a reviewed history-rollover protocol.

## Limits

This is a synthetic local reference with a trusted loopback host and fixed synthetic core principals. Temporal registration is a single local SQLite service, not a distributed registration or production availability guarantee. SQLite provides manual local resume without automatic timers, distributed workers or retry scheduling. Cadence, Azure Durable Task, cross-runtime migration, forced-history reset/reuse and hosted scheduler authentication are unimplemented. Runtime observations never assert financial truth or release admitted UNKNOWN exposure.

## Reviewed source identity

The following 23 files identify the reviewed runtime/tooling slice, including its capability/limit guide. Manifest SHA-256 is `f3b6720ab385f895afb3c458ce5663f775fa534adb267600e2ac206264f566f8`, computed over sorted UTF-8 lines `SHA256`, two spaces, repository-relative path and LF. Admission source remains separately frozen by review 0019; broad explanatory/status documents are outside this manifest.

| File | SHA-256 |
| --- | --- |
| `.github/workflows/reference.yml` | `7738cf1b40efaa78e48fc9e6298b26fe90abf8afc03358b2fa35285d37f07b8c` |
| `docs/durable-runtime.md` | `143ba07e6326e2d6acf716df68664a1b65908de56e3bf6e393bec6ee1710b3dd` |
| `scripts/bootstrap_temporal.py` | `87462f017328a48ba1462054ce90e49952391ec1c97242ea4fa0faed1489d070` |
| `scripts/conformance.py` | `402261622143fff0aa4908ed1330bfc5f810d92a2b1c9c6f544118c21d9551ab` |
| `scripts/requirement_evidence.py` | `4791d3dc398b48f29ae1946da8cac302bd1057539b99de950694a780a70427ca` |
| `scripts/temporal-toolchain-lock.json` | `84e717fc61992f916debc06e6cc78e5c329570bf8074ab7ffda51a76a862ef59` |
| `scripts/test_bootstrap_temporal.py` | `de4afe66053262c47f7fa3f05c1b8ad3d9ea0f32c33ba24e8834329aee4184f7` |
| `scripts/test_conformance.py` | `69145648648a4a9f2438cd8d91d4c635a9022d0e6f91c96d6818bbe95a3d5472` |
| `specification/0.1.0-draft/evidence/input-lock.json` | `59ad8531bdbb433c399008c1c3542dccfe5ac571719fc92b659bb3229d133b07` |
| `workflows/payment_workflow.py` | `66130360be1e9e2f82b9ca3d88240d036a4ef5af2a9dea83a8a2396c3b85fe98` |
| `workflows/requirements-lock.txt` | `4efccb8443f173e07b14a6b5d429fee9413716138a8ec50056d7625ba9231bd7` |
| `workflows/requirements.txt` | `213d2b5950b9b7a3b30d3b444184fabf1a26b56825213d3c35e799aad409075a` |
| `workflows/runtime_cli.py` | `a831a860f6e7376c3f5c8daee7b4faecf9594f5d6ce931a24e839890f6f618fb` |
| `workflows/runtime_contract.py` | `1613147ace69d0135804476aa5330e946e63ebd9ef3e3e706e687ce0bbf6984e` |
| `workflows/runtime_logic.py` | `6bf2ab9da1dd08aa4be401f47cde1e3a24ac1bc07bf585e41c78056c8f00e682` |
| `workflows/runtime_registry.py` | `5f387fe810c958843e8b352e939ff293bcecd1026346e4e6f07cb1c1a650887c` |
| `workflows/sqlite_runtime.py` | `966f4786604d58f4e388d0f935d56d48d2db83851dc390ecd97819993bb2d320` |
| `workflows/temporal-dependency-inventory.json` | `204f7a4b5ff3a9247b02aa57471ffff0fed4784427161e26c8a7ba598afd1d57` |
| `workflows/temporal_runtime.py` | `0e184652bb2b88b25ba47d6a96eb2ff4b3a90dcfd665c192eb6f825b9690b754` |
| `workflows/temporal_test_worker.py` | `26a5b803825ca6079290eabf9aa9dce202bacb5f522ce1bed73fee38c51902d8` |
| `workflows/temporal_workflow.py` | `f7c016b608b8115bd3c9d07b2cd32d280add780f4d38f95d1917f4df52589118` |
| `workflows/test_runtime.py` | `bad017b476ec3ab0a48082514c3da77afb797b06304e03cccc6ae267029c6f6d` |
| `workflows/test_temporal_runtime.py` | `7b851e0911ce980ef05b8d947f5511ccfc3c9f77112435c17eb9863000e8700e` |
