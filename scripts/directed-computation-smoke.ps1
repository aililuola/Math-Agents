[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $root ".tools\jdk-25"
$env:MAVEN_USER_HOME = Join-Path $root ".cache\wrapper-home-link"
$env:TEMP = Join-Path $root ".cache\tmp"
$env:TMP = $env:TEMP

Push-Location $root
try {
    & ".\mvnw.cmd" -o -pl ":mathproofmesh-core" `
        "-Dtest=DirectedComputationSmokeParityTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Directed computation smoke failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
