[CmdletBinding()]
param(
    [switch]$Offline
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$arguments = @(
    "-pl :mathproofmesh-server -am",
    "verify",
    "-Dtest=Phase17MessagePerformanceBenchmarkTest,Phase17GraphPerformanceBenchmarkTest,Phase17ConcurrentMockPerformanceBenchmarkTest,Phase17PythonSidecarPerformanceBenchmarkTest,Phase17SseResumePerformanceBenchmarkTest,Phase17TemporalPerformanceBenchmarkTest",
    "-Dit.test=Phase17CheckpointOutboxPerformanceIT",
    "-Dsurefire.failIfNoSpecifiedTests=false",
    "-Dfailsafe.failIfNoSpecifiedTests=false"
)
if ($Offline) {
    $arguments = @("-o") + $arguments
}

& (Join-Path $root "scripts/run-maven.ps1") -Arguments ($arguments -join " ")
if ($LASTEXITCODE -ne 0) {
    throw "Phase-17 benchmark execution failed."
}

$python = Get-Command python -ErrorAction Stop
& $python.Source (Join-Path $root "scripts/phase17-performance.py")
if ($LASTEXITCODE -ne 0) {
    throw "Phase-17 performance budget failed."
}
Write-Output "PHASE 17 BENCHMARKS: PASS"
