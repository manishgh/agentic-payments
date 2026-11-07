# Durable workflow runtime reference

The shared LangGraph decision graph runs through two concrete runtime adapters: local SQLite and Temporal. Both coordinate the Java payment core through narrow authenticated HTTP activities. PostgreSQL owns payment authority, reservations, attempts, operations and outcomes; runtime histories and checkpoints record orchestration progress only. [ADR 0007](adr/0007-durable-workflow-runtime.md) and [review 0020](reviews/0020-durable-runtime-design-review.md) define the accepted design.

## Responsibilities and replacement

[`runtime_contract.py`](../workflows/runtime_contract.py) defines the asynchronous start, inspect, wake, stop and resume port, immutable identities and trusted runtime metadata registration. The same bounded [`runtime_logic.py`](../workflows/runtime_logic.py) LangGraph decision activity chooses the next step for both adapters. Separate activities create, quote, read status or execute the existing approved attempt. The deterministic Temporal workflow schedules activities and waits; it performs no HTTP, graph/model evaluation or payment mutation during replay.

New runtime adapters implement the same responsibility port and register a safe adapter ID, version and capabilities in the trusted runtime catalog. They do not change the contract's allowed adapter names. Reference CLI choices deliberately expose only implemented SQLite and Temporal adapters. Cadence and Azure Durable Task adapters, hosted services and cross-runtime live migration remain unimplemented. Runtime wrappers must preserve identity, capability negotiation, errors and financial ownership.

| Configured adapter | Implemented capability boundary |
| --- | --- |
| SQLite | One local driver, manual restart/resume, persisted wake notices, cooperative pause and versioned decisions. No automatic timers, distributed scheduling or automatic activity retries. |
| Temporal | Persistent local server, fresh worker recovery, durable signal/timer scheduling, bounded activity retries, history replay and cooperative pause/resume. Registration remains a local SQLite service; no distributed registration or production availability claim. |

`describe()` exposes the configured limits and fixed history/privacy policy. The reference accepts at most 16 KiB per activity input/result and defaults to 512 completed activities per workflow. A trusted caller may select a smaller immutable `activity_limit` of 8..512 at start; it becomes part of the start fingerprint, and the reference CLI has no override. Temporal polling uses deterministic backoff from 1 to 60 seconds. Wakes and changed core versions reset the delay, never the activity count. This gives a bounded demonstration on the scale of hours; waiting for days and production history rollover remain future work.

At the activity budget, both adapters report `HISTORY_LIMIT` and retain the original references. Temporal schedules no more activities or timers and remains open for inspection. Stop, wake and resume cannot reset this limit. Inspect the core directly using the original intent UUID (`GET /internal/v0.1/intents/{id}` with its authorized local session); operator reconciliation is required. No automatic Continue-As-New, reset, replacement workflow or new payment identity bypasses the boundary.

The registration-store port retains scope, original core principal, start fingerprint, decision/activity/template versions and runtime binding independently of scheduler history. The reference's SQLite store is atomically insert-if-absent and exposes no deletion/reuse operation. Temporal's actual endpoint, namespace, task queue and definition bind the registration; changing them conflicts. Registering another backend requires an implementation that preserves this contract, not a generic CRUD store that silently discards atomicity.

## Prepare the local environment

Use the [toolchain setup](toolchain.md) and install the workflow lock into the local virtual environment. Temporal Python SDK `1.35.0` and CLI `1.10.0` are pinned separately from LangGraph `1.2.20`; exact resolved dependencies and notices are in the [Temporal inventory](../workflows/temporal-dependency-inventory.json). The bootstrap checks distribution bytes against the repository lock and publisher checksum manifest. It never starts an implicit test-server download.

```powershell
.venv/Scripts/python.exe -m pip install -r workflows/requirements-lock.txt
.venv/Scripts/python.exe scripts/bootstrap_temporal.py
.venv/Scripts/python.exe -m pip check
./scripts/Start-Platform.ps1
```

The platform runs separately. Load its private local session configuration without printing tokens:

```powershell
$session = Get-Content .tools/local-sessions.json -Raw | ConvertFrom-Json
$env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
$env:PAYMENTS_RUN_ID = $session.runId
$workflowId = [guid]::NewGuid().ToString()
```

The reference binds the graph session to the platform's fixed synthetic `tenant-local` / `agent-demo` identity. This is trusted local configuration, not a production identity-discovery service. Token refresh must preserve that same principal and scope. Tokens live in injected HTTP clients and never enter workflow arguments, signals, activity envelopes, history, query results or logs.

## Run Temporal

In one terminal start the verified server, retaining its database file across restarts:

```powershell
./.tools/temporal-1.10.0/temporal.exe server start-dev --ip 127.0.0.1 --port 7233 --headless --db-filename .tools/temporal-local.sqlite
```

In another terminal load the same session variables and start the native-profile worker:

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli worker --runtime temporal --server 127.0.0.1:7233 --queue payments-native-v1 --run-id $env:PAYMENTS_RUN_ID --profile local-ap2-acp-single-tender-0.1
```

In the control terminal start the workflow using the same queue, run and profile:

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli start --runtime temporal --server 127.0.0.1:7233 --queue payments-native-v1 --run-id $env:PAYMENTS_RUN_ID --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1 --scenario challenge-then-success
.venv/Scripts/python.exe -m workflows.runtime_cli inspect --runtime temporal --server 127.0.0.1:7233 --queue payments-native-v1 --run-id $env:PAYMENTS_RUN_ID --workflow-id $workflowId
```

The inspect result is labeled `RUNTIME_CACHE` with `authoritativePaymentState=false`. Copy its safe intent UUID into the trusted checkout UI and approve there. The worker polls core status automatically; a manual wake may reduce waiting:

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli wake --runtime temporal --server 127.0.0.1:7233 --queue payments-native-v1 --run-id $env:PAYMENTS_RUN_ID --workflow-id $workflowId
```

A wake is only an authenticated scheduling hint. It carries no consent or outcome. When the core reports `WAITING_ACTION`, complete the separate synthetic challenge in the trusted UI. The graph has no action lookup/completion or approval role. After a lost provider response, the existing core runner reconciles the same operation while the workflow reads status; no workflow activity sends directly to a provider.

The CLI defaults to `.tools/workflow-registry.sqlite`; `--registry` selects another explicit local registry shared by the control process and its workers. Keep the same file, backend database, queue, run and workflow UUID when restarting. The server binds to loopback and the UI is disabled. Access to this development host/backend is trusted; the reference supplies no public scheduler authentication boundary, cloud deployment or live payment capability.

## SQLite and lifecycle controls

The SQLite adapter uses the same decision and activity implementation with a manual local driver:

```powershell
.venv/Scripts/python.exe -m workflows.runtime_cli start --runtime sqlite --run-id $env:PAYMENTS_RUN_ID --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1 --scenario challenge-then-success
.venv/Scripts/python.exe -m workflows.runtime_cli resume --runtime sqlite --run-id $env:PAYMENTS_RUN_ID --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
```

Use a new workflow UUID for a separate intentional purchase, never as recovery from uncertainty. The original [`payment_workflow` CLI](langgraph-workflow.md) and existing LangGraph checkpoint identities remain supported; its command-key algorithm is unchanged. Select `--profile local-fake-single-tender-0.1` explicitly when exercising the legacy fixture profile. A native validation failure never selects legacy approval automatically.

`stop` requests a cooperative pause; it is not payment cancellation. An already outstanding HTTP activity may commit. The receipt distinguishes pause requested from applied, and inspect shows `PAUSED` when the safe scheduling boundary is reached. Wakes cannot clear pause. `resume` continues that same open workflow and re-reads the core before scheduling another command. Change the preceding command to `stop` or `resume` while retaining its runtime/run/workflow/server/queue arguments.

An identical duplicate start returns the existing reference; changed semantic input conflicts. The attempted-start marker commits before the Temporal RPC. If an uncertain start's history cannot be found, the adapter reports `WORKFLOW_RECOVERY_REQUIRED` rather than starting again. This also covers the conservative crash window between marking attempted and sending the RPC. A force-terminated/failed execution cannot be resumed automatically. Keep its retained core references and reconcile manually; reset, identity reuse and cross-runtime handoff require a separate reviewed protocol. Workflow cancellation, failure and expiry never release admitted exposure or imply financial success/no-effect.

Validated activity results update a separate monotonic recovery projection before the runtime receives completion. Late activity threads cannot regress its versions or references; a retried status read may advance that projection. Failed, terminated or missing runtime histories remain inspectable as `RECOVERY_REQUIRED` from `REGISTRATION_RECOVERY_CACHE`, even when nobody previously called inspect. The cache retains the original intent/attempt when known. If an accepted execute never returned, its operation may still be unknown in the cache: read current core status by the retained intent rather than inventing an operation or starting again.

## Verification and limits

```powershell
. ./scripts/Use-LocalToolchain.ps1
. ./scripts/Start-LocalPostgres.ps1
$env:PAYMENTS_TEMPORAL_CLI=(Resolve-Path .tools/temporal-1.10.0/temporal.exe).Path
.venv/Scripts/python.exe -m unittest workflows.test_runtime workflows.test_payment_workflow -v
.venv/Scripts/python.exe -m unittest workflows.test_temporal_runtime -v
```

The Temporal integration module requires the compiled Java service, classpath file, real PostgreSQL environment and verified CLI. It starts an isolated persistent local Temporal server and fresh worker processes. Test-only barriers kill workers after accepted create/quote/execute HTTP requests, before reporting activity completion. A separate native challenge test restarts server/workers while paused for authentication and verifies the same operation settles. Another native case observes response loss through the durable UNKNOWN audit and verifies one simulator journal effect and reservation settlement after recovery. These are two complementary scenarios; the reference does not claim one new combined payment scenario.

Tests also replay the actual Temporal history and scan protobuf payload bytes, recursively decoded activity/signal/failure payloads, metadata, registration/recovery rows and private process diagnostics for tokens, credential canaries and exact retained native artifacts. A planted encoded-payload regression proves the scanner detects values hidden by the history export's base64 framing and JSON string escaping. Shared closed result validation runs before SDK serialization and before local/recovery persistence, including decorated activity implementations. Only safe activity selectors/versions reconstruct the credential-bearing request inside the trusted worker. Missing historical templates or changed principal/configuration fail closed. Private test artifacts stay under ignored `.tools/`.

This demonstrates local synthetic durability and identity preservation. It supplies no real provider, remote AP2 peer, ACP merchant transport, hosted runtime, production availability, distributed registration or certification claim.
