$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Join-Path $repositoryRoot '.tools/jdk-21.0.13+9'
$mavenBin = Join-Path $repositoryRoot '.tools/apache-maven-3.9.16/bin'
$postgresBin = Join-Path $repositoryRoot '.tools/postgresql-17.11-5/pgsql/bin'
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Run scripts/Bootstrap-Toolchain.ps1 first.'
}
$env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$mavenBin;$postgresBin;$env:PATH"
# Keep downloaded dependencies local to this repository too.
$env:MAVEN_OPTS = "$env:MAVEN_OPTS -Dmaven.repo.local=$repositoryRoot/.tools/m2".Trim()
