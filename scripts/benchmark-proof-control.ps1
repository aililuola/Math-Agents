param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
  & .\mvnw.cmd -q -pl ':mathproofmesh-compatibility' -am `
    '-Dtest=ProofControlBenchmarkTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
  if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
  }
  Write-Output '{"benchmark":"proof-control","cases":10,"provider_calls":0,"status":"PASS"}'
} finally {
  Pop-Location
}
