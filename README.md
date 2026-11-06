# agentic-payments

An extensible purchasing platform designed to embed OpenAI ACP checkout inside an AP2-based platform payment-intent workflow, including provider authentication.

**Runnable milestone: local synthetic reference implementation.** Java/PostgreSQL owns payment state; Python LangGraph coordinates work; a TypeScript UI handles trusted approval and synthetic challenges. Real AP2/ACP bridges and external providers are not connected yet.

## Implemented

- Versioned extension requirements, 21 JSON Schema contracts and canonical positive/negative fixtures.
- Java SDK with local execution ports, extensible provider/method/action descriptors and a reusable adapter TCK.
- Atomic reservations, scoped idempotency, one execution owner and normalized PostgreSQL persistence.
- Durable simulator journal, trusted challenges, revocation, unknown-outcome recovery and conflicting-evidence handling.
- Recorded multi-intent replay with seeded choices, crash/recovery tests and normalized evidence digests.
- Isolated offline lab for exact signed checkout bytes, pinned schema checks and durable synthetic sidecar bindings.
- Loopback HTTP API, trusted checkout UI and real LangGraph workflow with SQLite restart/resume.
- Automated database, HTTP, UI, schema and workflow checks with version-bound evidence and CI.

Execution currently accepts only the synthetic profile. Generic descriptors can negotiate additional methods and actions without enabling a provider. Stripe, Adyen, wallets, bank methods, vouchers/split tender, real 3DS, refunds and managed-service operation remain on the [backlog](docs/backlog.md). New providers need explicit profiles and verified semantics; naming a method does not implement it.

## Run locally on Windows

Requirements: PowerShell 7, Python 3.12, Node.js 24/npm and Git. Bootstrap installs verified Java 21, Maven and PostgreSQL inside ignored `.tools/`, without a global service installation.

```powershell
./scripts/Bootstrap-Toolchain.ps1
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r workflows/requirements-lock.txt
.venv/Scripts/python.exe -m pip install -r integrations/offline_lab/requirements-crypto.lock.txt
.venv/Scripts/python.exe integrations/upstream/probe_contracts.py --fetch --include-offline-lab
./scripts/Start-Platform.ps1
```

Open `http://127.0.0.1:8080`. The launcher creates private `.tools/local-sessions.json` with separate graph/UI tokens and a run ID. Use its `uiToken` in the trusted page. Tokens are not printed or stored in browser storage. Create a purchase from another terminal:

```powershell
$session = Get-Content .tools/local-sessions.json -Raw | ConvertFrom-Json
$env:PAYMENTS_RUN_ID = $session.runId
$env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
$workflowId = [guid]::NewGuid().ToString()
.venv/Scripts/python.exe -m workflows.payment_workflow start --workflow-id $workflowId --scenario challenge-then-success
```

Enter the returned intent UUID in the UI and approve its checkout. Resume using the same workflow UUID, complete the synthetic challenge in the UI, then resume again:

```powershell
.venv/Scripts/python.exe -m workflows.payment_workflow resume --workflow-id $workflowId
.venv/Scripts/python.exe -m workflows.payment_workflow status --workflow-id $workflowId
```

The virtual clock is fixed unless the private runner advances it; wall-clock waiting does not expire this demo. Restart preserves budgets, operation identities and checkpoints. See [workflow details](docs/langgraph-workflow.md) and [toolchain configuration](docs/toolchain.md).

## Build and verify

```powershell
. ./scripts/Use-LocalToolchain.ps1
. ./scripts/Start-LocalPostgres.ps1
npm.cmd ci --ignore-scripts --prefix ui
.venv/Scripts/python.exe scripts/conformance.py
pwsh -NoProfile -File scripts/Check-Docs.ps1
git diff --check
```

The evidence runner uses real PostgreSQL, isolated HTTP services and fresh Python processes. Results go to ignored `target/conformance/`. Missing database configuration, failed checks or source mutation during validation fail the run.

The report explicitly returns `NOT_CERTIFIED`: parts of the full adversarial/privacy matrix and certification policy remain incomplete. `--require-certification` fails until those obligations are satisfied. Project evidence grants no provider, AP2/ACP, PCI, regulatory or network certification.

## Documentation

- [Status and remaining work](docs/status.md), [roadmap](docs/roadmap.md), [backlog](docs/backlog.md)
- [Architecture](docs/architecture.md), [API](docs/api-contracts.md), [data model](docs/data-model.md)
- [Core implementation](docs/core-implementation.md), [contracts/SDK](docs/contracts-implementation.md), [LangGraph](docs/langgraph-workflow.md)
- [Generic extensions and adapter TCK](docs/extensions-and-tck.md), [pinned upstream bridge design](docs/ap2-acp-bridge-design.md)
- [Recorded simulator replay](docs/replay.md), [offline artifact lab](docs/offline-artifact-lab.md)
- [Independent core review](docs/reviews/0006-reference-code-review.md), [client/tooling review](docs/reviews/0007-client-tooling-review.md), [gate decisions](docs/reviews/gates.md)

Public licensing and package distribution remain explicit release decisions; no open-source license has been selected. Reviewed source is pushed to milestone branches. No automatic merge, package release, deployment or live-money execution is performed.
