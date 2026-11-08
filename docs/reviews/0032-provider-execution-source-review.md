# Review 0032: Provider execution contract source

Status: independent source review accepted for the additive adapter-only SDK/TCK, 2026-11-08. All findings below are resolved. The integration owner must still complete the unchanged-source aggregate and SDK dependency check before milestone delivery. This is neither provider activation nor approval of financial wiring.

Reviewer: independent architecture/protocol reviewer, distinct from the author. Design baseline: `103d4e3a1431743d10bab446684f9a6fb1467baf`; ADR 0012 and review 0030 define the accepted scope. The assessment covered all 27 new production SDK files, five synthetic test files and the versioned normative README identified below, together with the unchanged original payment SDK. The reviewer edited review documents only and ran no Maven or application tests.

## Result and scope

The new `sdk.execution.v1` contains portable immutable values, capability selection and separate preparation, submission, reconciliation, event verification/normalization and optional no-effect-fencing interfaces. It supplies an explicit synthetic compatibility bridge with an injected atomic binding-journal responsibility and a reusable executable TCK. It does not connect these interfaces to financial admission, import a vendor implementation or widen the original synthetic SDK.

The normative `provider-execution-1` README specifies canonical fingerprints, scoped idempotency, resource association, effect certainty, evidence meanings and conditional capabilities. It preserves the distinction between in-process adapter reconstruction and real process/storage durability. The supplied retained-memory fixtures implement only the former. A durable journal backend, vendor profile, independent wire peer, authorized sandbox and financial integration remain separate work.

## Findings resolved

| ID | Finding | Verified correction |
| --- | --- | --- |
| PE-1 | The fence case checked acceptance counts but could pass a forbidden post-fence send if the peer refused it; simultaneous thread release did not prove the delayed-sender boundary. | Explicit pause/await/resume controls stop an original admitted caller before its financial send. A winning fence then permits no send or acceptance, including after reconstruction. Accepted-first and simultaneous branches inspect both counters. A deliberately broken delayed sender that reaches the peer after fencing makes PE-FENCE fail. |
| PE-2 | The TCK derived preparation/capabilities from the first offer tuple and hardcoded capture, rejecting valid non-first or AUTHORIZE-only implementations. | Requirements derive from the selected tuple and financial expectations follow purpose. The regression chooses an AUTHORIZE-only second tuple while the unrelated first tuple has a different preparation effect; a single AUTHORIZE-only offer is also exercised. |
| PE-3 | Resource-count checks after preparation response loss did not prove the recovered resource belonged to the original operation. | The fixture supplies an independently observed operation/resource association. The case compares exact operation/effect, original reference when present and stable peer mapping after reconstruction. A loss-only decorator returning another existing resource now fails PE-PREPARE. |

The author also corrected an evidence-ID collision before freeze: legacy observations with different source/time derive distinct IDs, while identical observations remain stable. A regression distinguishes pending submit evidence from reconciliation evidence without changing their financial meaning.

## Contract and implementation assessment

The operation fingerprint covers trusted scoped identity, full binding/selection digests, exact money and purpose using pinned length/count framing. Offer hashes cover complete executable declarations. Input constructors reject unsupported purpose/money and inconsistent fingerprints; immutable collections and defensive validation prevent mutable support declarations. A scoped key cannot evade conflict by changing account, region, configuration or credential version. Separately authenticated scopes may reuse key text without conflating their operations.

Submission binds its absent/present prepared-resource association separately from the stable operation fingerprint. Preparation cannot represent capture/authorization; resource-creating READY requires a reference, effect-free preparation cannot carry one, and action descriptors are bound opaque references. Action validation is a pure binding/expiry check. The suite does not claim trusted UI completion or redirect provenance.

Evidence keeps uncertainty, pending, action, authorization, capture, refusal and a genuine winning fence distinct. Source enums and constructed event references are not authentication proofs. Event normalization resolves synthetic trusted provenance separately from verification; fabricated, wrong-account, altered, conflicting duplicate and unbound references reject. Out-of-order weaker events retain their original meaning. Atomic application monotonicity/conflict handling remains explicitly outside this adapter suite.

The synthetic bridge validates its exact pinned selection/configuration and scope, atomically binds complete new and legacy identities before delegation, and obtains at most one initial dispatch claim. Subsequent uncertain calls reconcile rather than resend after simulated provider-key expiry. The journal contract, not an SDK in-memory map, owns persistence and terminal/conflict guarantees. The bridge advertises only its synthetic profile and does not manufacture event authenticity. Real implementations cannot infer equivalent atomic fencing from ordinary absence or cancellation.

Failure values use closed fixed codes and certainty with no provider payload, cause or stack trace. Reference-bearing diagnostics are redacted. NOT_STARTED describes one invocation rather than every prior/concurrent attempt. Unhandled fixture errors become fixed incomplete evidence, including close failures that revoke an otherwise passing case.

The TCK owns the case list and controls, uses independent peer/journal observations, and rejects missing advertised extensions. The map/resource/event and append-list/effect-free fixtures have different execution algorithms; they are not two decorators around one reference adapter. Compatibility and forwarding-wrapper cases exercise the same public responsibilities. Broken extra-effect, delayed-fence, resource-rebinding and exception cases demonstrate that green fixture self-reporting alone is insufficient. Evidence remains limited to the selected tuple and named assertions rather than all possible vendor behavior.

## Observed evidence and remaining gate

The reviewer inspected the final integration-owner Surefire report: all 17 `ExecutionContractTest` methods passed with zero failures, errors or skips. The encompassing focused invocation passed 51 methods including durable checkout, independent policy, migrations and retained ACP transport. This reviewer performed source/hash inspection and `git diff --check`; no test execution is attributed to the reviewer.

All production imports stay within the SDK and `java.base` on source inspection. Root must confirm the packaged SDK with bytecode dependency inspection and complete the fresh unchanged-source PostgreSQL aggregate. Passing adapter-only synthetic evidence neither authorizes spending nor grants provider/AP2/ACP/PCI/regulatory/network certification. No public package release, license choice, sandbox/live call or deployment is included.

## Accepted source identity

The independently recomputed 33-file manifest below has SHA-256 `4d80b501225b1dfc9292106ef5b6032c82dd8d145717fdc37d3e74a2efec936f`. Its ordering is case-insensitive path order, matching PowerShell `Sort-Object FullName` for these paths; encode each row as lowercase file SHA-256, two spaces, repository-relative slash path, and LF, including the final LF. The same 33 file identities sorted in ordinal path order produce `19210a3816441f29da5dddf104362d0448250ca4eeb5076cd6115e9965a9720f`; this ordering difference changes no source bytes. All reviewed files use LF. Review documents are excluded. Changed source requires re-review and revalidation.

```text
4c41d1019c9c07c167142ab5f6272fcf686a4a5d5507bc9bc603ce218438a6d8  specification/provider-execution-1/README.md
765c55f828b76032065ebc3854703ce74c1c98d3a2fc87ebbda7397e1e6bbbc5  src/main/java/io/agenticpayments/sdk/execution/v1/CapabilitySelection.java
12e6e885dc13e51614fc5862768488f7c0c5ed0f75b8a4c912ad96ff04412159  src/main/java/io/agenticpayments/sdk/execution/v1/compat/CompatibilityBindingJournal.java
89f0271a0c5f13b8c40a7f4c63566fc52e381d12bbf651d050e5603c58e9f375  src/main/java/io/agenticpayments/sdk/execution/v1/compat/LocalFakeExecutionAdapter.java
bb29e1c67d77d8e99edba90d08a518f3d97fd966c0dc5dc8ad11d99b17f3a669  src/main/java/io/agenticpayments/sdk/execution/v1/ContractValues.java
45161f8856b039cd237fcc3acb352a0c3bd0dc28028452ddcc238d8d7270a782  src/main/java/io/agenticpayments/sdk/execution/v1/EventVerificationRequest.java
d44c76aa8a08951db0d8d406b2326eb56d38d67def5a6cc867bcb9723afeeca7  src/main/java/io/agenticpayments/sdk/execution/v1/ExactMoney.java
51939116b63be9a06f9c5f2c6b0e3907ee945fe2760e2916b5852713e24110f8  src/main/java/io/agenticpayments/sdk/execution/v1/ExecutionBinding.java
a5b09362366987c9cd2f37736cee8516053819a30418b03dd1b9652a265239b5  src/main/java/io/agenticpayments/sdk/execution/v1/ExecutionEvidence.java
95c9501543b7fdf521d744ce92b6d0ba490f57d4027469347b90e122ed902a6f  src/main/java/io/agenticpayments/sdk/execution/v1/ExecutionFailure.java
698317bc15ed1a1c2493f873e7e466830805a74c2344cbcf8eae6e83abf48fbb  src/main/java/io/agenticpayments/sdk/execution/v1/ExecutionIdentity.java
738c9382f7716f645317295785a8273b7e0a9e9ad9d6c35a91a29a92f4e4c418  src/main/java/io/agenticpayments/sdk/execution/v1/ExecutionOffer.java
e3a5061b76a6c76bd7e9bccbd8d2f9cb7d0989495eb46f86a92911af9c89dfe4  src/main/java/io/agenticpayments/sdk/execution/v1/ExecutionOperation.java
c6e1ed06eaea08fc5ac1f920e6dda90b06764f18cf7cbe39a6774fbe5fa50f1c  src/main/java/io/agenticpayments/sdk/execution/v1/ExecutionPreparer.java
95bb23fee45e9177e7a39d0a753ad1abb5ed8e5089b1d239241b9f48aa3cbd14  src/main/java/io/agenticpayments/sdk/execution/v1/ExecutionReconciler.java
82bd7af45046f35ef2999bc802ef7087f8c816fbe1326e05cbff53849fce3ce3  src/main/java/io/agenticpayments/sdk/execution/v1/FinancialSubmitter.java
59772afb778d81e42bb02dbe5e16ad4be134d3daa2a19e2d8c259e710b2213fb  src/main/java/io/agenticpayments/sdk/execution/v1/NoEffectFencer.java
9c16168546d4fb8fb84f40a8700f22a70922c7e1d16306d1721775b1844df214  src/main/java/io/agenticpayments/sdk/execution/v1/OperationPurpose.java
a1b1fa40bf6600410addaa6154a9676253faae3fc56f15ce6e202444b649d03a  src/main/java/io/agenticpayments/sdk/execution/v1/PreparationResult.java
a5167022b46d7f27d71f3ea804b98dc760120a15ed898546973b4f84747ae47a  src/main/java/io/agenticpayments/sdk/execution/v1/ProviderEventNormalizer.java
ba423732f4125e3a28a2bc3b5809ca18de439f4c7e3372322f1c6a02e0067a91  src/main/java/io/agenticpayments/sdk/execution/v1/ProviderEventVerifier.java
1c38c3a147cc0d45f62f95de13a2f8085880f585f6dbcaba619cd308978fe268  src/main/java/io/agenticpayments/sdk/execution/v1/SubmissionRequest.java
f53794cdaa05332e2e499852fa4472e09e3ce65bd6dd5cfeda1f511734fb2b4c  src/main/java/io/agenticpayments/sdk/execution/v1/tck/ExecutionTck.java
c255d422724aaeff83491438001755f6c049fd08529d87907c3abc6bc3279f45  src/main/java/io/agenticpayments/sdk/execution/v1/tck/ExecutionTckFactory.java
3b15f098c1fb08c3f7d8a8d3dacbb6fbd5751d02dfaeec5db00b3b2841f8d386  src/main/java/io/agenticpayments/sdk/execution/v1/tck/ExecutionTckFixture.java
200ce3b9600cdfc5143dd738f8f134de7d91efb72388c61932d8f768fd98912a  src/main/java/io/agenticpayments/sdk/execution/v1/tck/ExecutionTckReport.java
3c505d9d51c81224a5a2eb4c6f148fa85c92f05bef3022f20e16a751fb60ffec  src/main/java/io/agenticpayments/sdk/execution/v1/TrustedActionDescriptor.java
c6a16f1d4cb45d230c8fe716e62913e72ae02a089ca2b2b0d48f4461c87d5c13  src/main/java/io/agenticpayments/sdk/execution/v1/VerifiedEventReference.java
f7b8ac7012fe096f3a167475b25ef349520eaf6a7574df8df304f47d1731c149  src/test/java/io/agenticpayments/sdk/execution/v1/CompatibilityFixture.java
cff2a248b8ff8ce447bef87234328bf847f4c04b313277c93c661add1ccfa1f3  src/test/java/io/agenticpayments/sdk/execution/v1/ExecutionContractTest.java
f8e610bb98cb47395fe29d2c73b9852e04aafc2343fa733934fa6400b02208df  src/test/java/io/agenticpayments/sdk/execution/v1/FixtureOperations.java
90506f595a649157b954f39e3bc235645ab1a87aa4ba52a4a9527bba2ad8bd92  src/test/java/io/agenticpayments/sdk/execution/v1/ListPeerFixture.java
69a0698ce9e0539de3ed55fc58b9ccca6660fc4c51b352a7b5194d458fc5b8ed  src/test/java/io/agenticpayments/sdk/execution/v1/MapPeerFixture.java
```
