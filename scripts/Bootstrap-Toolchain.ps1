[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if (-not $IsWindows -or -not [Environment]::Is64BitOperatingSystem) {
    throw 'This bootstrap supports Windows x64. Use the documented versions on other platforms.'
}
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = Join-Path $repositoryRoot '.tools'
$downloads = Join-Path $toolsRoot 'downloads'
New-Item -ItemType Directory -Path $downloads -Force | Out-Null
$lock = Get-Content (Join-Path $PSScriptRoot 'toolchain-lock.json') -Raw | ConvertFrom-Json
foreach ($artifact in $lock.artifacts) {
    $archive = Join-Path $downloads $artifact.file
    if (-not (Test-Path $archive)) {
        Write-Host "Downloading $($artifact.name) $($artifact.version)"
        Invoke-WebRequest -Uri $artifact.url -OutFile $archive
    }
    $actual = (Get-FileHash -LiteralPath $archive -Algorithm $artifact.algorithm).Hash
    if ($actual -ine $artifact.hash) {
        throw "Checksum mismatch for $($artifact.file); remove that specific cached file and retry."
    }
    $publishedFile = "$archive.$($artifact.algorithm.ToLowerInvariant())"
    if (-not (Test-Path $publishedFile)) {
        Invoke-WebRequest -Uri $artifact.checksumUrl -OutFile $publishedFile
    }
    $published = (Get-Content -LiteralPath $publishedFile -Raw).Trim().Split(' ')[0]
    if ($published -ine $artifact.hash) {
        throw "Publisher checksum differs from reviewed lock for $($artifact.file)."
    }
    $executable = Join-Path $toolsRoot $artifact.executable
    if (-not (Test-Path $executable)) {
        Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $toolsRoot $artifact.destination) -Force
    }
    Write-Host "Verified $($artifact.name) $($artifact.version) $($artifact.algorithm)"
}
. (Join-Path $PSScriptRoot 'Use-LocalToolchain.ps1')
& java.exe -version
if ($LASTEXITCODE -ne 0) { throw 'Java verification failed.' }
& mvn.cmd -version
if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
& postgres.exe --version
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL verification failed.' }
