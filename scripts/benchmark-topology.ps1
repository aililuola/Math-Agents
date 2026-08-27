[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $root ".tools\jdk-25"
$env:MAVEN_USER_HOME = Join-Path $root ".cache\wrapper-home-link"
$env:TEMP = Join-Path $root ".cache\tmp"
$env:TMP = $env:TEMP

& (Join-Path $root "mvnw.cmd") -B -ntp `
  -pl :mathproofmesh-compatibility -am `
  -Dtest=TopologyBenchmarkTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

Get-Content -Raw (Join-Path $root "target\benchmark-reports\topology-java.json")
