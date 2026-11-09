# Verify your checkout of the platform

[Documentation](../README.md) / [How-to guides](README.md)

Build the reference, run its checks and inspect a source-bound evidence bundle. Complete the [installation prerequisites](../getting-started/README.md#1-install-prerequisites), including pinned schema and Temporal setup, first. Run commands from the repository root in PowerShell 7.

## 1. Prepare an isolated test database

```powershell
. ./scripts/Use-LocalToolchain.ps1
. ./scripts/Start-LocalPostgres.ps1
$testDatabase = 'verify_' + [guid]::NewGuid().ToString('N')
$localDb = Get-Content .tools/local-postgres.json -Raw | ConvertFrom-Json
$previousPgPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $localDb.password
    createdb -h 127.0.0.1 -p $localDb.port -U $localDb.username $testDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Test database creation failed.' }
} finally {
    $env:PGPASSWORD = $previousPgPassword
}
$env:PAYMENTS_DB_URL = "jdbc:postgresql://127.0.0.1:$($localDb.port)/$testDatabase"
npm.cmd ci --ignore-scripts --prefix ui
```

**Expected result:** PostgreSQL reports readiness, `createdb` succeeds and npm installs the locked UI dependencies. The startup script sets the database user/password environment variables; the URL override sends this run to a fresh database. Keep this terminal open and do not print its credentials.

## 2. Run the conformance checks

```powershell
.venv/Scripts/python.exe scripts/conformance.py
```

**Expected result:** each stage reports `PASS`, the process exits with code 0, and the final line reads:

```text
Evidence preview PASS; validity VALID; NOT_CERTIFIED
```

This takes several minutes and runs Java/PostgreSQL, HTTP, UI, schema and workflow checks, including isolated services and fresh Python processes. Keep source files unchanged while it runs. Missing configuration, failed checks or a source change fail validation.

## 3. Verify the generated evidence

```powershell
.venv/Scripts/python.exe -m scripts.requirement_evidence --bundle target/conformance
```

**Expected result:** exit code 0 with `previewChecks: PASS`, `evidenceValidity: VALID` and `certificationVerdict: NOT_CERTIFIED` in the JSON output. These are expected field values, not the entire report.

Safe reports and receipts are under `target/conformance/`. Private raw diagnostics are under `.tools/conformance/`. Inspect the failed stage there if step 2 fails; rerun after correcting the problem. An older passing bundle is not evidence for changed source.

`NOT_CERTIFIED` is intentional: the complete adversarial/privacy matrix and certification policy remain unfinished. Passing these checks grants no upstream, provider or regulatory certification. See [evidence reference](../reference/requirement-evidence.md) for exact coverage and verification limits.

## 4. Check documentation changes

```powershell
pwsh -NoProfile -File scripts/Check-Docs.ps1
git diff --check
```

**Expected result:** the documentation checker reports success and `git diff --check` prints nothing. For documentation-only edits, use these checks and verify changed heading links; the full payment suite above validates implementation changes.

The test database remains available for investigation. It is separate from the local demo database; do not point a development test run at production data.
