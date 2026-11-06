[CmdletBinding()]
param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
Push-Location $repositoryRoot
try {
    . ./scripts/Use-LocalToolchain.ps1
    . ./scripts/Start-LocalPostgres.ps1
    $sessionFile = Join-Path $repositoryRoot '.tools/local-sessions.json'
    if (-not (Test-Path -LiteralPath $sessionFile)) {
        $session = [ordered]@{
            runId = [guid]::NewGuid().ToString()
            graphToken = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
            uiToken = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
        }
        $session | ConvertTo-Json | Set-Content -LiteralPath $sessionFile -Encoding utf8NoBOM
    }
    $session = Get-Content -LiteralPath $sessionFile -Raw | ConvertFrom-Json
    $env:PAYMENTS_RUN_ID = $session.runId
    $env:PAYMENTS_GRAPH_TOKEN = $session.graphToken
    $env:PAYMENTS_UI_TOKEN = $session.uiToken
    $env:PAYMENTS_PORT = "$Port"
    & mvn.cmd -B -ntp -DskipTests package dependency:build-classpath '-Dmdep.outputFile=target/classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    Push-Location ui
    try {
        & npm.cmd ci --ignore-scripts
        if ($LASTEXITCODE -ne 0) { throw 'UI dependency installation failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'UI build failed.' }
    } finally { Pop-Location }
    Write-Host 'Local session configuration is in .tools/local-sessions.json. Keep it private.'
    $classPath = "target/classes$([IO.Path]::PathSeparator)$((Get-Content target/classpath.txt -Raw).Trim())"
    & java -cp $classPath io.agenticpayments.http.Main
    if ($LASTEXITCODE -ne 0) { throw 'Platform process stopped with an error.' }
} finally { Pop-Location }
