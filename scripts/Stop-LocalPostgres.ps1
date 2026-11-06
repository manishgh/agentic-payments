$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$postgresBin = Join-Path $repositoryRoot '.tools/postgresql-17.11-5/pgsql/bin'
$dataRoot = Join-Path $repositoryRoot '.tools/postgres-data'
& (Join-Path $postgresBin 'pg_ctl.exe') -D $dataRoot status *> $null
if ($LASTEXITCODE -eq 0) {
    & (Join-Path $postgresBin 'pg_ctl.exe') -D $dataRoot -m fast -w stop
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL shutdown failed.' }
}
