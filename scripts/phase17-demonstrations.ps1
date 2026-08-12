[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path

& (Join-Path $root "scripts/run-maven.ps1") `
    -Arguments "-o -pl :mathproofmesh-compatibility -am -Dtest=Phase17LegacyResumeDemoTest -Dsurefire.failIfNoSpecifiedTests=false test"
if ($LASTEXITCODE -ne 0) {
    throw "Old-run resume demonstration failed."
}

$legacyPath =
    Join-Path $root "target/benchmark-reports/phase17-legacy-resume-demo.json"
if (-not (Test-Path -LiteralPath $legacyPath -PathType Leaf)) {
    throw "Old-run resume demonstration evidence is missing."
}
$legacy = Get-Content -LiteralPath $legacyPath -Raw | ConvertFrom-Json
if ($legacy.result -ne "PASS") {
    throw "Old-run resume demonstration did not pass."
}

$drive = "U"
$existing = (& subst.exe) | Where-Object { $_ -like "${drive}:\:*" }
if ($existing) {
    throw "Transient demonstration drive ${drive}: is already in use."
}
subst.exe "${drive}:" $root
if ($LASTEXITCODE -ne 0) {
    throw "Could not map the demonstration drive."
}
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = "${drive}:\.tools\jdk-25"
    $launcher =
        "${drive}:\target\release\JavaMathProofMesh-0.8.0\bin\mathproofmesh.cmd"
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw "Packaged CLI launcher is missing."
    }
    $output = & $launcher demo --run-id phase17-release-demo
    if ($LASTEXITCODE -ne 0) {
        throw "Packaged Mock solve demonstration failed."
    }
    $mock = $output | Select-Object -Last 1 | ConvertFrom-Json
    if (
        $mock.status -ne "completed" -or
        $mock.run_id -ne "phase17-release-demo" -or
        $mock.completed_route_ids.Count -ne 1
    ) {
        throw "Packaged Mock solve returned an unexpected result."
    }
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    subst.exe "${drive}:" /D | Out-Null
}

$report = [ordered]@{
    schema_version = "1.0"
    phase = "17"
    generated_at_utc = [DateTime]::UtcNow.ToString("o")
    result = "PASS"
    packaged_mock_solve = [ordered]@{
        launcher = "target/release/JavaMathProofMesh-0.8.0/bin/mathproofmesh.cmd"
        run_id = $mock.run_id
        status = $mock.status
        latest_event_id = $mock.latest_event_id
        completed_route_ids = @($mock.completed_route_ids)
        verified_local_claim_ids = @($mock.verified_local_claim_ids)
        provider = "Mock"
        live_provider_calls = 0
        result = "PASS"
    }
    old_run_resume = $legacy
}
$destination =
    Join-Path $root "migration/reports/phase-17-demonstrations.json"
$report | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $destination -Encoding utf8
Write-Output "PHASE 17 DEMONSTRATIONS: PASS"
