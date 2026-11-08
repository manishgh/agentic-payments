# agentic-payments

An extensible purchasing platform designed to embed OpenAI ACP checkout inside an AP2-based platform payment-intent workflow, including provider authentication.

**Runnable milestone: durable synthetic AP2/ACP payment composition.** Java/PostgreSQL owns payment state; Python LangGraph coordinates work; a TypeScript UI handles trusted approval and synthetic challenges. The independently implemented AP2 direct profile binds signed authority to an embedded ACP checkout and financial admission. SQLite and Temporal implement the durable workflow boundary. External providers remain outstanding.

## Implemented

- Versioned extension requirements, 21 JSON Schema contracts and canonical positive/negative fixtures.
- Java SDK with local execution ports, extensible provider/method/action descriptors and a reusable adapter TCK.
- Atomic reservations, scoped idempotency, one execution owner and normalized PostgreSQL persistence.
- Durable simulator journal, trusted challenges, revocation, unknown-outcome recovery and conflicting-evidence handling.
- Recorded multi-intent replay with seeded choices, crash/recovery tests and normalized evidence digests.
- Isolated offline lab for exact signed checkout bytes, pinned schema checks and durable synthetic sidecar bindings.
- Independently authored Java AP2 direct Trusted Agent Provider engine, native mandates/receipts, role verification, replay guards and cross-language vectors; no Google SDK dependency.
- Durable native AP2/ACP admission, immutable artifact storage, replaceable checkout/authority/crypto/key implementations and atomic replay/grant/reservation checks.
- Loopback HTTP API, trusted checkout UI and real LangGraph workflow with SQLite restart/resume.
- Shared durable-runtime contract with local SQLite and Temporal implementations, retained workflow identity, cooperative pause, and Temporal timers/activity retries.
- Standalone external ACP lifecycle contract and HTTP adapter for create/retrieve/update/cancel, with independent local-peer wire/schema checks; see [external checkout](docs/external-checkout.md) for its bounded scope.
- Application-owned execute-command policy through a typed atomic transaction contract, retaining PostgreSQL locking, command replay and operation/outbox/state atomicity.
- Automated database, HTTP, UI, schema and workflow checks with named requirement observations, recomputable evidence and CI.

Execution currently accepts only explicit synthetic profiles. Generic descriptors can negotiate additional methods and actions without enabling a provider. Stripe, Adyen, wallets, bank methods, vouchers/split tender, real 3DS, refunds and managed-service operation remain on the [backlog](docs/backlog.md). New providers need explicit profiles and verified semantics; naming a method does not implement it.

## Use cases

This project is for teams building purchasing agents, merchant checkout integrations and enterprise payment platforms. It is especially useful when a purchase spans human decisions, external authentication, delayed outcomes or service restarts.

| Use case | Example | How the platform helps |
| --- | --- | --- |
| Business procurement | An agent prepares a purchase and waits for a manager's approval. | Bind approval to the exact checkout and spending authority; recheck price, expiry and budget before execution. |
| Customer authentication | A customer must complete 3DS, wallet approval or a bank redirect. | Pause the workflow for a trusted interaction, then resume from a verified result without exposing credentials or OTPs to the agent. |
| Uncertain payment outcomes | The provider accepts a payment, but the response is lost or a worker crashes. | Preserve the original operation and idempotency identity, retain unresolved exposure and reconcile before considering any further action. |
| Scheduled purchasing | Purchase an item at an agreed time or when an authorised condition is met. | Use durable timers and recheck current availability, checkout terms and valid authority; an expired grant requires fresh authority. |
| Long-running bank payments | A payment remains pending for hours or days. | Track the original payment across restarts and handle delayed or duplicate events without treating a pending status as settlement. |
| Multi-step purchases | Coordinate a booking, payment and fulfillment. | Keep step ownership and outcomes explicit, with separately defined recovery or compensation for partial completion. |
| Enterprise integration | A business already uses its own checkout, signing, storage, grant service or workflow runtime. | Adapt those implementations to stable component interfaces rather than rewrite the payment protocols or business workflow. |

### Reference journey

The reference journey is: **agent creates checkout → customer approves → authentication pauses the workflow → service restarts → workflow resumes → the original payment completes**. The native synthetic AP2/ACP profile exercises this journey with trusted approval and a synthetic challenge. A separate response-loss scenario exercises UNKNOWN recovery and reconciliation while preserving the original operation. See [durable composition](docs/durable-composition.md) for the exact protocol profile and boundaries.

Real 3DS/wallet/bank integrations, scheduled or conditional purchasing policy, multi-step compensation and fulfillment are target use cases, not currently enabled features. LangGraph supplies bounded purchase decisions; a [durable runtime](docs/durable-runtime.md) supplies persistence and scheduling. SQLite supports local restart/manual resume; the Temporal adapter adds durable timers, activity retry and history replay. Cadence and Azure Durable Task remain future adapters to the same interface.

### Bring your own implementation

Replaceability applies to every component boundary. Intended compositions include our AP2 implementation with an external ACP implementation, or our AP2 and ACP implementations with the ACP layer wrapping/delegating to a third-party checkout implementation such as Stripe's. These examples describe extension paths, not existing vendor integrations.

The native application coordinator applies artifact and verification policy through those same ports, independently of JDBC. Transactional admission remains owned by the payment store. See [application coordination and adapter boundaries](docs/durable-composition.md#application-coordination-and-transaction-ownership) when integrating an implementation.

Each responsibility has one stable, versioned interface. For example, PostgreSQL, Azure Storage, DynamoDB or an adapter to an existing payment-grant service can implement the same signed-artifact storage contract. Another backend adds an implementation and registration, not a new vendor-specific interface. The same principle applies to protocol engines, signing/key services, admission, providers, authentication, events and durable workflow runtimes. Implementations must satisfy the contract's behavioral and security guarantees; supported algorithms and capabilities are explicitly selected, with no silent fallback. See [architecture](docs/architecture.md) and [backlog](docs/backlog.md) for implementation scope.

## Run locally on Windows

Requirements: PowerShell 7, Python 3.12, Node.js 24/npm and Git. Bootstrap installs verified Java 21, Maven and PostgreSQL inside ignored `.tools/`, without a global service installation.

```powershell
./scripts/Bootstrap-Toolchain.ps1
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r workflows/requirements-lock.txt
.venv/Scripts/python.exe -m pip install -r integrations/offline_lab/requirements-crypto.lock.txt
.venv/Scripts/python.exe scripts/bootstrap_temporal.py
.venv/Scripts/python.exe integrations/upstream/probe_contracts.py --fetch --include-offline-lab --include-ap2-vectors
./scripts/Start-Platform.ps1
```

Open `http://127.0.0.1:8080`. The launcher creates private `.tools/local-sessions.json` with separate graph/UI tokens and a run ID. Use its `uiToken` in the trusted page. Tokens are not printed or stored in browser storage. Create a purchase from another terminal:

```powershell
$session = Get-Content .tools/local-sessions.json -Raw | ConvertFrom-Json
$env:PAYMENTS_RUN_ID = $session.runId
$env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
$workflowId = [guid]::NewGuid().ToString()
.venv/Scripts/python.exe -m workflows.payment_workflow start --workflow-id $workflowId --scenario challenge-then-success --profile local-ap2-acp-single-tender-0.1
```

Enter the returned intent UUID in the UI and approve its checkout. Resume using the same workflow UUID, complete the synthetic challenge in the UI, then resume again:

```powershell
.venv/Scripts/python.exe -m workflows.payment_workflow resume --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
.venv/Scripts/python.exe -m workflows.payment_workflow status --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
```

The virtual clock is fixed unless the private runner advances it; wall-clock waiting does not expire this demo. Restart preserves budgets, operation identities and checkpoints. The legacy fixture profile remains available explicitly as `local-fake-single-tender-0.1`. See [workflow details](docs/langgraph-workflow.md), [Temporal setup](docs/durable-runtime.md) and [toolchain configuration](docs/toolchain.md).

## Build and verify

```powershell
. ./scripts/Use-LocalToolchain.ps1
. ./scripts/Start-LocalPostgres.ps1
npm.cmd ci --ignore-scripts --prefix ui
.venv/Scripts/python.exe scripts/conformance.py
pwsh -NoProfile -File scripts/Check-Docs.ps1
git diff --check
```

The evidence runner uses real PostgreSQL, isolated HTTP services and fresh Python processes. Safe reports, execution receipts and observations go to ignored `target/conformance/`; raw diagnostics stay private under `.tools/conformance/`. Missing database configuration, failed checks or source mutation during validation fail the run. Verify the bundle with `python -m scripts.requirement_evidence --bundle target/conformance`; see [requirement evidence](docs/requirement-evidence.md) for identity, coverage gaps and verification limits.

The report explicitly returns `NOT_CERTIFIED`: parts of the full adversarial/privacy matrix and certification policy remain incomplete. `--require-certification` fails until those obligations are satisfied. Project evidence grants no provider, AP2/ACP, PCI, regulatory or network certification.

## Documentation

- [Status and remaining work](docs/status.md), [roadmap](docs/roadmap.md), [backlog](docs/backlog.md)
- [Independent AP2 engine](docs/ap2-engine.md), [native requirements](docs/ap2-requirements.md), [independent vectors](integrations/ap2_vectors/README.md)
- [Architecture](docs/architecture.md), [API](docs/api-contracts.md), [data model](docs/data-model.md)
- [Durable AP2/ACP composition and replacement ports](docs/durable-composition.md), [durable runtime and Temporal setup](docs/durable-runtime.md)
- [Core implementation](docs/core-implementation.md), [contracts/SDK](docs/contracts-implementation.md), [LangGraph](docs/langgraph-workflow.md)
- [Generic extensions and adapter TCK](docs/extensions-and-tck.md), [pinned upstream bridge design](docs/ap2-acp-bridge-design.md)
- [Recorded simulator replay](docs/replay.md), [offline artifact lab](docs/offline-artifact-lab.md)
- [Independent core review](docs/reviews/0006-reference-code-review.md), [client/tooling review](docs/reviews/0007-client-tooling-review.md), [gate decisions](docs/reviews/gates.md)

Public licensing and package distribution remain explicit release decisions; no open-source license has been selected. Reviewed source is pushed to milestone branches. No automatic merge, package release, deployment or live-money execution is performed.
