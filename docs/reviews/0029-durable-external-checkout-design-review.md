# Review 0029: Durable external checkout lifecycle design

Status: independent design review completed, 2026-11-08. Recommend integration-owner acceptance of the exact ADR identity below. No blocking design finding remains. Implementation, independent source review and integrated executable evidence are separate gates.

Reviewer: independent architecture/protocol reviewer, distinct from the author. Baseline: `b3544493cd6dd81ce861e73dc38a82054747618a`. The assessment covered repository/workspace instructions, README/status/architecture/data ownership, ADR 0009/0010, the external-checkout guide, current `CheckoutSessions`/ACP transport, HTTP authorization and the payment-specific Python runtime/checkpoint contracts. The reviewer owns this report and review 0030 only; no production source or tests were changed. No Maven, HTTP or conformance tests were run during design assessment.

## Scope and architecture decision

The accepted bounded profile is a usable durable commerce lifecycle: authenticated application commands, PostgreSQL staging and outcome retention, external create/retrieve/update/cancel outside database locks, a loopback HTTP API and a separate local-restart LangGraph journey. An explicit synthetic peer launcher makes the path runnable without changing default payment startup or implying merchant/provider enrollment.

A separate purchase aggregate is appropriate. It cannot supply native `FrozenCheckout`, merchant signatures, AP2 authority, reservation or financial admission. The existing native synthetic journey remains independent. A lifecycle document or `completed` status supplies no payment evidence. Binding real merchant-signed revisions to approval and financial admission remains a later reviewed profile; this slice avoids creating fictitious authority that would then require invalidation.

The application facade, atomic transaction owner, immutable command transport and trusted configuration resolver are responsibility-based seams. Direct implementations, external adapters and decorators can implement them without introducing vendor/backend interfaces. Replacement transaction adapters must preserve the indivisible staging/replay/claim/outcome guarantees, not merely present compatible CRUD. The application service must retain policy independently of JDBC.

## Findings resolved before acceptance

| Concern | Accepted disposition |
| --- | --- |
| Accepted replay could depend on currently available credentials or current checkout revision. | Current role/scope/merchant authorization precedes lookup; the caller-command fingerprint and historical safe result replay precede configuration resolution and current revision policy. Rotation or retirement cannot manufacture a replacement command. |
| A claim-token fence could be mistaken for a fence against actual network sends. | Claim recovery revokes projection authority only. A delayed old worker may still send. No replacement mutation is claimed; late outcomes remain protected history and cannot clear the separate unresolved-mutation barrier. |
| Successful GET could erase uncertain UPDATE/CANCEL ownership. | Retrieve observes only the original known session. It never clears the mutation barrier, even after a terminal-looking response or 404. Unknown create has no invented lookup endpoint or new-key recovery. |
| Configuration names and logical request digests alone cannot preserve the original request. | Immutable configuration/credential/profile/encoding versions and non-secret configuration fingerprint are retained with exact protected method/path/body, absent-body distinction and original keys. The transport validates the prepared representation before send. |
| Concurrent staging and replacement transports could bypass assumed concrete-adapter guarantees. | One STAGED/CLAIMED command per purchase, explicit revision changes, separate mutation uncertainty, and service-level response binding/coherence checks apply to all implementations. |
| Payment-specific runtime reuse would misrepresent commerce support. | A separate commerce LangGraph/SqliteSaver profile uses safe references and stable registration/template identity. Existing payment-runtime validation is preserved; no Temporal commerce adapter is claimed. |
| CLAIMED status originally reported NOT_SENT. | CLAIMED now reports UNKNOWN with no fabricated peer result, because the send may already have happened. Only initial/staged status can report NOT_SENT. |

## Required transaction and transport invariants

The first stage atomically retains purchase/command identity, caller-key replay data, immutable prepared request and staged revision before any send. Current authorization applies to every stage, status, dispatch, recover-claim and local replay. Resource UUIDs alone grant no access. Stage replay must compare the original caller request rather than a newly generated merchant key or freshly resolved configuration.

Exactly one committed claim may dispatch a command. Claim, actual send and outcome recording are separate phases. No external HTTP runs inside the transaction callback, run lock, native freeze/validation, artifact lookup or repository helper. The send uses only its original configuration/credential/profile/request identity. Re-resolution after send is not permission to discard an observed result when configuration is unavailable: the correlated result must be retained while promotion fails closed.

Current projection updates require the original command/digest/token, current revision and current immutable configuration. Response binding, session, currency, lifecycle/version and OK/OBSERVED/document coherence are checked at the application seam. Invalid replacement responses become fixed INVALID_RESPONSE/UNKNOWN. A stale possibly sent mutation remains blocked even if its late response says OK. Exact successful bytes remain protected evidence behind an opaque reference; safe DTOs never expose items, session IDs, origin/settings, keys, raw documents or peer error text.

The separate mutation barrier survives later retrieve staging, successful retrieval, service reconstruction and workflow restart. There is no timed claim takeover, automatic POST retry or assumed universal idempotency retention. Unknown-create recovery deliberately sacrifices liveness until a separately reviewed trusted resolution mechanism exists. A new purchase identity must not be presented as recovery of the uncertain purchase. The accepted profile neither prevents users from making unrelated purchases nor proves all remote mutations globally absent.

## HTTP and LangGraph integration

The exact facade/DTOs and stage/status/dispatch/recover-claim routes in ADR 0011 are accepted seams for root-owned integration. Existing strict local authentication, duplicate/unknown-field rejection, body bounds and safe errors must apply. Disabled registration rejects explicitly. No API accepts transport bytes, destination, credentials, remote session identity or merchant idempotency keys.

Each workflow identity binds one operation to its authenticated scope/principal, stable purchase, contract/template identity and original local stage key before the first effect. The separate `CheckoutWorkflowBindings` responsibility atomically reserves this immutable association before lifecycle port calls; a checkpointer alone is insufficient to arbitrate concurrent first starts. Its initial SQLite adapter is independently injectable, retains a command ID once known and offers no reset/rebind operation. The protected template and client credentials remain outside checkpoints. Checkpoint values are untrusted orchestration cache: restart validates identity/template before port calls and reads authoritative status before dispatch. It cannot adopt another command ID, dispatch CLAIMED/UNKNOWN work, regenerate keys after response loss or turn recovery into another send. Process restart and a lost stage/dispatch HTTP response must preserve the same identities.

The documented launcher must exercise real Java API and loopback merchant traffic with the separate LangGraph workflow. Direct, wrapped and alternate transaction implementations need policy evidence through the same service. Injecting a compatible checkpoint saver is an extension seam; only the actually tested SQLite local-restart profile is claimed here.

## Required evidence and decision

Source acceptance requires actual PostgreSQL rollback/serialization and exclusive-claim tests, same-key races, current-authorization replay refusal, credential/configuration changes before and during blocked sends, service reconstruction, accepted response loss, stale worker outcomes and retained request/response evidence. Controlled barriers must show another transaction can progress while the peer stalls and that recovery never permits a replacement mutation. Tests must inspect original wire identities and peer request counts rather than infer sends from local status.

HTTP and real LangGraph restart/privacy checks must cover the full local commerce path, checkpoint/template tampering, unexpected command identity and unknown-create pause. Protected data canaries must remain absent from public DTOs, errors, representations, graph checkpoints and safe evidence. Existing native authority/payment/runtime regressions remain required. Root owns the focused/full isolated-PostgreSQL checks and exact evidence record; this design review supplies no executable pass claim.

| Reviewed artifact | SHA-256 |
| --- | --- |
| [ADR 0011](../adr/0011-durable-external-checkout.md) | `379015fa5c42a7e8f7437d07df83b024dffbe830a1d5f275fd5c65cfc4b09ed3` |

Accept this exact bounded design for implementation and subsequent independent source review. Changes to the design require review. External AP2/ACP financial composition, merchant completion, provider execution, real-peer interoperability and certification remain outside this acceptance.

## Formatting-only identity amendment, 2026-11-08

Before publication, the reviewer independently verified that the staged ADR 0011 bytes equal the previously reviewed file after replacing its single CRLF line ending with LF. There are no other text changes. The working file was normalized to those exact staged bytes. The historical reviewed SHA-256 `379015fa5c42a7e8f7437d07df83b024dffbe830a1d5f275fd5c65cfc4b09ed3` is preserved above; the accepted LF-only publication SHA-256 is `fb864f2d0e730851d70ba4dce422c4e79869bc8074db546556838387ba66bbed`. Design scope, findings and acceptance are unchanged.
