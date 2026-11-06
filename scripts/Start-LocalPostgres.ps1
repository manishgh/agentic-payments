[CmdletBinding()]
param([ValidateRange(1024, 65535)][int]$Port = 55432)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = Join-Path $repositoryRoot '.tools'
$postgresBin = Join-Path $toolsRoot 'postgresql-17.11-5/pgsql/bin'
$dataRoot = Join-Path $toolsRoot 'postgres-data'
$configFile = Join-Path $toolsRoot 'local-postgres.json'
if (-not (Test-Path (Join-Path $postgresBin 'initdb.exe'))) {
    throw 'Run scripts/Bootstrap-Toolchain.ps1 first.'
}
if (-not (Test-Path $configFile)) {
    $random = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $config = [ordered]@{
        port = $Port
        database = 'agentic_payments'
        username = 'agentic_local'
        password = [Convert]::ToHexString($random)
    }
    $config | ConvertTo-Json | Set-Content -LiteralPath $configFile -Encoding utf8
}
$config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json
if ($config.port -ne $Port) {
    throw 'Existing cluster uses a different port; pass its recorded port. No data was changed.'
}
if (-not (Test-Path (Join-Path $dataRoot 'PG_VERSION'))) {
    $passwordFile = Join-Path $toolsRoot 'initdb-password.tmp'
    try {
        Set-Content -LiteralPath $passwordFile -Value $config.password -Encoding utf8NoBOM
        & (Join-Path $postgresBin 'initdb.exe') -D $dataRoot -U $config.username --auth=scram-sha-256 --encoding=UTF8 --locale=C "--pwfile=$passwordFile"
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL initialization failed.' }
        Add-Content -LiteralPath (Join-Path $dataRoot 'postgresql.conf') -Value "`nlisten_addresses = '127.0.0.1'`nport = $Port`n"
    }
    finally {
        if (Test-Path $passwordFile) { Remove-Item -LiteralPath $passwordFile }
    }
}
& (Join-Path $postgresBin 'pg_ctl.exe') -D $dataRoot status *> $null
if ($LASTEXITCODE -ne 0) {
    # pg_ctl launches postgres as a child; hiding its window keeps startup unobtrusive.
    $arguments = @('-D', "`"$dataRoot`"", '-l', "`"$(Join-Path $toolsRoot 'postgres.log')`"", '-w', 'start')
    $startup = Start-Process -FilePath (Join-Path $postgresBin 'pg_ctl.exe') -ArgumentList $arguments -WindowStyle Hidden -PassThru
    # Start-Process -Wait waits for descendants too on Windows, including the
    # long-lived server. Wait only for pg_ctl's own readiness result instead.
    if (-not $startup.WaitForExit(60000)) { throw 'PostgreSQL startup timed out. Inspect .tools/postgres.log.' }
    if ($startup.ExitCode -ne 0) { throw 'PostgreSQL startup failed. Inspect .tools/postgres.log.' }
}
$previousPgPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $config.password
    $exists = & (Join-Path $postgresBin 'psql.exe') -h 127.0.0.1 -p $Port -U $config.username -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'agentic_payments'"
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL readiness query failed.' }
    if ($exists -notcontains '1') {
        & (Join-Path $postgresBin 'createdb.exe') -h 127.0.0.1 -p $Port -U $config.username $config.database
        if ($LASTEXITCODE -ne 0) { throw 'Local database creation failed.' }
    }
}
finally { $env:PGPASSWORD = $previousPgPassword }
$env:PAYMENTS_DB_URL = "jdbc:postgresql://127.0.0.1:$Port/$($config.database)"
$env:PAYMENTS_DB_USER = $config.username
$env:PAYMENTS_DB_PASSWORD = $config.password
Write-Host "PostgreSQL ready at 127.0.0.1:$Port; local process environment configured."
