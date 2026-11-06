# Recorded simulator replay

Status: bounded local implementation of [ADR 0003](adr/0003-replay-and-adapter-conformance.md), subject to independent source review.
Replay evidence concerns the local fake profile only. It does not establish AP2/ACP interoperability, provider support or certification.
Interactive HTTP behavior is unchanged; plan execution and fault controls have no public HTTP route.

## Run the example

Use the documented local toolchain and PostgreSQL environment first. Credentials stay in ignored configuration/environment variables.
The CLI requires `PAYMENTS_DB_URL`, `PAYMENTS_DB_USER` and `PAYMENTS_DB_PASSWORD`; it never accepts them as command arguments.
After compiling and generating the dependency classpath:

```powershell
& .tools/apache-maven-3.9.16/bin/mvn.cmd '-Dmaven.repo.local=.tools/m2' compile dependency:build-classpath '-Dmdep.outputFile=target/classpath.txt'
$replayClasspath = 'target/classes;' + (Get-Content target/classpath.txt -Raw).Trim()
& .tools/jdk-21.0.13+9/bin/java.exe -cp $replayClasspath io.agenticpayments.replay.ReplayMain --example > .tools/replay-plan.json
& .tools/jdk-21.0.13+9/bin/java.exe -cp $replayClasspath io.agenticpayments.replay.ReplayMain --plan .tools/replay-plan.json
```

`--example` emits an expanded, current-artifact plan without touching the database.
`--demo` generates and executes that example directly. `--plan path` accepts a strict UTF-8 JSON file up to 4 MiB.
The exit status is zero for PASS, one for FAIL/INCOMPLETE, or two for invalid input/infrastructure failure.
Errors contain fixed safe codes; no exception stack, connection string, password or bearer material is printed.
`PAYMENTS_REPLAY_TENANT` optionally sets a safe physical tenant name; every execution still receives a fresh random run UUID.

The example has two intents: a synthetic trusted challenge and a seed-chosen success/delayed-success scenario.
It records explicit preparation, reconstruction, action completion, dispatch, reconciliation, IDLE and assertions.
Seed `42`, ordinal `2`, choice name `scenario` and candidates `[success, delayed-success]` select `delayed-success`.
There are no runtime random fault draws and no wall-clock sleeps in the schedule.

## Programmatic contract

`ReplayPlan` is immutable and identifies format, extension/profile, runner, normalizer, actual artifact digests, logical run, seed, initial clock, safe configuration, expanded choices and steps.
`ReplayPlan.local(...)` is a convenience for a trusted locally authored plan; `ReplayPlanJson.parse(...)` rejects unknown/duplicate/missing/coerced fields and trailing JSON.
The seed is a canonical signed 64-bit decimal string. Each choice records `generatorVersion=plan-choice-0.1` and uses SHA-256 named selection, with unsigned first-eight-byte modulo selection.
The fully expanded schedule is authoritative; changing a seed does not secretly alter already recorded actions.

| API | Meaning |
| --- | --- |
| `ReplayRunner.start(plan,tenant)` | Validate runtime/config identities, create an isolated REPLAY-owned run and seed trusted fixtures. |
| `execute(plan,tenant)` | Start and run the whole schedule in a fresh physical run. |
| `advance(execution)` | Execute one explicit step; preserve RUNNING unless finished, failed or incomplete. |
| `continueExecution(execution)` | Continue only a clean recorded boundary; never retry an orphan STARTED step. |
| `inspect(execution)` | Collect a current validated snapshot without executing another plan action. |

The execution handle contains run/execution UUIDs, not ownership credentials.
An internal owner guard rejects ordinary graph/UI/background mutations even when callers know the run UUID and otherwise have roles.
A session advisory lease prevents two runner instances from concurrently executing or collecting a final snapshot of the same execution.
State is reloaded after taking that lease; it is explicitly unlocked before closing its connection, including when a pool retains the physical session.
Read-only authorized payment observation remains possible; payment truth is still held by the core tables.

## Schedule and receipts

The typed vocabulary is CREATE, QUOTE, APPROVE, EXECUTE, COMPLETE_ACTION, REVOKE, ADVANCE_CLOCK, RUN_NEXT, RECONCILE, RECONSTRUCT, ASSERT and INTERLEAVING.
Logical intent references must already exist. Expected versions are literal integers or explicit references to a prior same-intent command result.
Quote/cart and grant aliases resolve through the trusted fixture catalog; plans contain no raw credential references, actors' bearer sessions or arbitrary callbacks/code.
Expected errors must be explicit; an unexpected error fails the step instead of being hidden by a retry.

Before each action the runner commits STARTED with ordinal, stable label and request digest.
Afterward it atomically persists verified relationship aliases, the safe result/receipt and DONE or FAILED.
Command keys derive from plan digest, ordinal and actor, so identical command retries never manufacture another charge identity.
An interrupted accepted command or worker action without DONE makes the execution INCOMPLETE and stops automatic continuation.
Reproducing the plan creates a fresh run; it never resets an unresolved original run or releases its exposure.
RECONSTRUCT creates new service/adapter/repository objects against the same database; it is explicitly distinct from process-kill evidence.

`runNextRecorded` reports the actual selected operation, work kind/fence, committed audit extent and whether observable state progressed.
IDLE and unchanged UNKNOWN polling remain explicit. The preserved boolean `runNext` API still indicates selected work or expiry-only progress.
New work receives a per-run enqueue sequence once; retries preserve it. Priority is PREPARE, DISPATCH, RECONCILE, then enqueue sequence.
Intent creation sequence also orders audit-producing expiry/revocation scans, so random UUIDs cannot reorder those effects.

Two finite private barrier recipes are implemented:

| Recipe | Recorded boundary order |
| --- | --- |
| `ADMISSION_REVOKE_SEND_0_1` | Admission committed -> send entered -> target grant revoked -> send released -> worker finished. |
| `SEAL_SEND_0_1` | Admission committed -> send entered -> atomic no-effect seal committed -> stale send released -> worker finished. |

Recipes require the named READY intent to be next under the persisted work ordering.
They block outside core transactions and use bounded waits. A timeout is incomplete; it cannot authorize another financial action.
If a worker cannot be confirmed quiescent, the runner emits only an incomplete diagnostic and no final normalized digest.

## Artifact pins and normalized evidence

Artifact identities hash actual compiled classes, nested classes and all numbered migration resources used by this runtime.
The catalog and local execution-policy digests are separate. Runner/parser/normalizer source identities are pinned with the plan.
Plans must be regenerated/reviewed after an intended implementation change; an old plan does not silently execute against different code.
These hashes identify local artifacts. They are not signatures, trusted provenance or upstream compatibility evidence.

Raw checks run before identifiers are normalized. They validate tenant/run relationships, known semantic aliases, trusted fixture money,
checkout bytes/digests, exact reservation/budget sums, single execution ownership, physical OperationBinding hashes and stable adapter keys.
They also verify journal/core identities and amounts, durable journal events/core evidence, exact terminal phase/exposure/final-proof agreement,
all quote revisions' expiry, admission time/grant epoch, revocation boundary, every action binding and persisted order counters.
Every new clock advance appends CLOCK_ADVANCED at the new virtual time; raw validation requires the complete contiguous audit sequence.
An altered identity, amount, unknown reference, missing final proof or authenticated conflict fails the run before equality can be claimed.

Only validated UUID relationships are mapped to intent/attempt/operation/action aliases.
Journal events use operation alias plus fact sequence; audit events use their durable sequence.
Raw request digests containing physical IDs are verified, then replaced with a separate logical binding digest.
Credential binding is checked inside the trusted boundary and represented by catalog identity plus the binding-valid result.
The normalizer excludes physical tenant/run/UUID allocation, transport correlations, owner tokens, system time and local configuration secrets.
It preserves virtual timestamps, exact money, state/version, admission/audit/work/fact ordering, outcomes, held uncertainty and recorded errors/barriers.

Reports publish separate normalized core, journal and audit/schedule digests plus a combined digest and named raw-check results.
Physical run/execution UUIDs remain report-level trace references outside the cross-run normalized digest.
The exact artifact/configuration identities remain part of the canonical plan; `artifactIdentities` also survives in standalone failed/incomplete reports.
Failed raw validation emits no equality digest; diagnostic output must not be presented as a conformant replay.

## Persistence and historical data

V004 adds replay run/step/alias metadata, owner mode and monotonic intent/work sequencing without changing published V001-V003.
Old interactive rows get a one-time documented historical ordering and remain LEGACY/non-replayable; backfill does not reconstruct actual prior scheduling.
V005 records admission virtual time/grant epoch and the revocation admission boundary for independent raw authorization checks.
V006 constrains replay ownership and positive/nonnegative order values.
Replay metadata never edits payment totals, budget exposure or journal outcomes.

## Verification and remaining limits

The replay tests exercise fresh physical runs with equal logical core/journal/audit/combined digests, reconstruction with multiple pending operations,
owner denial, orphan STARTED with preserved pending exposure, recorded finite recipes and strict choice/plan validation.
Negative tests alter raw journal identity/money, expiry, request/fact bindings, audit history or final proof and require failure rather than normalization masking.
The fixed canonical plan golden is 1106 UTF-8 bytes with deliberately fixed dummy artifact hashes; executable plans require actual current artifacts.
Named-choice goldens include both signed 64-bit seed bounds and an unsigned high-bit hash case.
The independent test/source report records the exact tested revision, command and results; this document does not grant certification.
This is sequential recorded replay with two fixed recipes, not resumable exactly-once scheduling or exhaustive concurrent state exploration.
Additional clock-race evidence lives in the core boundary tests; those races are not silently claimed as replay recipe coverage.
