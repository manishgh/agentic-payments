# Durable local LangGraph reference workflow

[Documentation](../README.md) / [How to](README.md)
The Python reference calls the Java simulator through authenticated internal HTTP. It implements a real `StateGraph` with SQLite checkpoints and separate trusted approval/action handling. PostgreSQL remains the sole payment truth; SQLite stores orchestration progress only. The default profile is `local-fake-single-tender-0.1`; `--profile local-ap2-acp-single-tender-0.1` selects the [native AP2/ACP composition](../concepts/durable-composition.md). Protocol verification remains in the core, outside graph state. No LLM or external provider is used.

The original checkpoint CLI below remains supported. A separate [runtime guide](../advanced/durable-runtime.md) covers the shared runtime interface and Temporal adapter, where LangGraph decisions execute as bounded activities and the runtime owns timers, retries and history replay. The original dependency inventory remains historical; [Temporal dependencies](../../workflows/temporal-dependency-inventory.json) record the four additions to the environment lock.

## Workflow and trust boundaries

[Implementation](../../workflows/payment_workflow.py) creates a synthetic intent, quotes the fixed server-owned EUR cart, reads authoritative status, and interrupts for trusted checkout approval. The trusted UI independently reviews/approves the immutable checkout. Resuming the graph only requests another status read: a resume payload cannot assert approval, modify state or complete a challenge. `PaymentWorkflow.resume(...)` discards caller data before passing a fixed boolean to LangGraph, because even unused raw resume values would otherwise enter durable checkpoint writes.

Only authoritative `APPROVED` status permits an execute command for the existing attempt. The workflow reads status again immediately before execution, then reads again after the accepted response. Replayed command receipts may describe an earlier state and are never treated as current payment truth. The core independently enforces authority, versions and immutable operation binding.

`WAITING_ACTION` interrupts with an action ID; the graph has no action lookup/completion or approval/revocation client methods. Only the trusted UI session retrieves/completes a challenge. `PREPARING`, `READY`, `ADMITTED`, `PENDING` and `UNKNOWN` pause for a later status read. In `UNKNOWN`, the graph preserves the same attempt/operation and held exposure; it cannot change provider, release budget or create a retry payment. Terminal outcomes come from authoritative status and evidence.

Each workflow has a caller-retained UUID and explicit simulator run UUID. The LangGraph checkpoint thread is scoped by both. Create/quote/execute keys are deterministic SHA-256 identifiers derived from that pair and command name; they contain no session information and remain stable after process restart. A lost response retries the same command identity or reads the already-progressed resource. Starting again with the same checkpoint identity is refused; use resume. A new workflow UUID intentionally creates a new intent and must never be used as a recovery shortcut for an uncertain payment.

Checkpoints contain only validated UUIDs/fixture scenario IDs, safe state/version/profile fields, money, checkout digest/revision, exposure and reconciliation metadata. The fixture credential reference stays inside HTTP command construction and is never copied into graph state. Session tokens live only in the client instance and HTTP headers, outside LangGraph inputs/configuration/checkpoints. Status projection copies an explicit allowlist and validates values; extra upstream payload fields cannot become channels. LangSmith/LangChain tracing is disabled for this local implementation. There is no remote tracing/exporter or model API call.

The client accepts only `http://127.0.0.1:<explicit-port>` without user information, query, fragment or extra path. It disables ambient HTTP proxies and redirects so a bearer token cannot follow an arbitrary endpoint. HTTP failures expose fixed safe messages, preserving the workflow identity for recovery. Invalid or inaccessible core responses fail closed. The Java core still authenticates tenant/run and role access; knowledge of a checkpoint UUID grants no payment authority.

## Reproduce and run

The tested interpreter is CPython 3.12.14. [Direct requirements](../../workflows/requirements.txt) pin LangGraph `1.2.20` and SQLite checkpoint integration `3.1.3`, both MIT. [Environment lock](../../workflows/requirements-lock.txt) records the full 50-package installed dependency set, including the shared JSON Schema test tooling. It is a version lock from `pip freeze`, not a claim that all cross-platform wheel hashes are locked. Install from official PyPI into a local virtual environment:

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r workflows/requirements-lock.txt
.venv/Scripts/python.exe -m pip check
```

The machine-readable [dependency inventory](../../workflows/dependency-inventory.json) records declared licenses, exact versions, official PyPI metadata URLs and publisher source-distribution SHA-256 values. These source hashes identify published sdists; they do not claim that this Windows environment installed sdists. Installed license files were inspected where metadata was incomplete (aiosqlite is MIT). Most packages are MIT/BSD/Apache/PSF; certifi includes MPL-2.0 and orjson declares MPL-2.0 plus Apache-2.0/MIT alternatives. Preserve distribution notices if redistributing dependencies. This inventory does not select the repository's public license or authorize a package release.

Start the Java reference with the repository's platform script in a separate terminal. In the workflow terminal, load its locally generated graph session without printing it:

```powershell
$session = Get-Content .tools/local-sessions.json -Raw | ConvertFrom-Json
$env:PAYMENTS_RUN_ID = $session.runId
$env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
$workflowId = [guid]::NewGuid().ToString()
.venv/Scripts/python.exe -m workflows.payment_workflow start --workflow-id $workflowId --scenario challenge-then-success
```

The CLI writes checkpoints to ignored `.tools/workflow-checkpoints.sqlite`. Retain `$workflowId` and the original run ID across terminal/process restarts. Use the trusted local UI to approve the returned intent, then resume:

```powershell
.venv/Scripts/python.exe -m workflows.payment_workflow resume --workflow-id $workflowId
.venv/Scripts/python.exe -m workflows.payment_workflow status --workflow-id $workflowId
```

`status` performs an authenticated authoritative read; the persisted orchestration snapshot alone is never a payment result. If paused for a synthetic challenge, complete it in the trusted UI and resume again. The CLI does not accept approval/action payloads. `--api-url` selects another explicit loopback port, and `--run-id` can supply the run UUID instead of the environment. `--scenario` applies to new workflows only. A synchronous SQLite saver is intended for one local workflow driver at a time; distributed orchestration and concurrent checkpoint writers require a separate design.

## Validation evidence

Run isolated graph/checkpoint tests without a database service:

```powershell
.venv/Scripts/python.exe -m unittest workflows.test_payment_workflow -v
```

Ten tests passed: SQLite close/reopen, spoofed resume refusal, arbitrary canary exclusion from state/checkpoint writes, UNKNOWN with no new attempt/execute/provider, refusal of changed execution identity while uncertain, lost execute response, failed-before-acceptance stable command key, lost quote response replay followed by authoritative current approval, trusted action separation, run isolation, loopback URL/header validation and cross-run status rejection.

Run actual Java/PostgreSQL/LangGraph process integration after building the Java service and `target/classpath.txt`:

```powershell
. ./scripts/Use-LocalToolchain.ps1
. ./scripts/Start-LocalPostgres.ps1
.venv/Scripts/python.exe -m unittest workflows.test_live -v
```

Both live tests passed on 2026-11-06. The harness starts an isolated Java run, generates separate in-memory graph/trusted sessions, and starts a fresh Python CLI process for every start/resume. It proves durable SQLite recovery with no in-memory state transfer, trusted external checkout approval, graph action lookup/completion returning 403, challenge pause and trusted completion, stable operation identity through success, and no graph/UI token or credential reference in checkpoint rows. The harness shuts down its Java subprocess; the local PostgreSQL cluster remains available. These are internal synthetic reference tests, not provider, network, regulatory or PCI certification.

Official API references inspected for the implementation: [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence), [interrupt/resume semantics](https://docs.langchain.com/oss/python/langgraph/interrupts), and the installed `langgraph-checkpoint-sqlite` saver source. Interrupt nodes restart on resume, so side effects are isolated in idempotent command nodes and resume values are never treated as authorization.
