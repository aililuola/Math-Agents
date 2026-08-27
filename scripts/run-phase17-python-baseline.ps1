[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$logPath =
    Join-Path $root "migration/reports/phase-17-python-baseline.log"
$jsonPath =
    Join-Path $root "migration/reports/phase-17-python-baseline.json"

function Invoke-CapturedPowerShell {
    param([string]$Script)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = Join-Path $PSHOME "powershell.exe"
    $start.Arguments =
        "-NoProfile -ExecutionPolicy Bypass -File `"$Script`""
    $start.WorkingDirectory = $root
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    if (-not $process.Start()) {
        throw "Could not start $Script"
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    return [ordered]@{
        command = "$($start.FileName) $($start.Arguments)"
        exit_code = $process.ExitCode
        stdout = $stdoutTask.Result
        stderr = $stderrTask.Result
    }
}

$startedAt = [DateTime]::UtcNow.ToString("o")
$baseline =
    Invoke-CapturedPowerShell (
        Join-Path $root "scripts/baseline-python-tests.ps1"
    )
$immutability =
    Invoke-CapturedPowerShell (
        Join-Path $root "scripts/check-original-immutable.ps1"
    )
$completedAt = [DateTime]::UtcNow.ToString("o")

$baselinePassed =
    $baseline.exit_code -eq 0 -and
    $baseline.stdout -match "(?m)759 passed(?:,| in)"
$immutablePassed =
    $immutability.exit_code -eq 0 -and
    $immutability.stdout -match "SOURCE IMMUTABILITY: PASS" -and
    $immutability.stdout -match "files=401" -and
    $immutability.stdout -match (
        "manifest_sha256=" +
        "9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770"
    )
$result = if ($baselinePassed -and $immutablePassed) { "PASS" } else { "FAIL" }

$log = @(
    "started_at_utc=$startedAt"
    "baseline_command=$($baseline.command)"
    "baseline_exit_code=$($baseline.exit_code)"
    "[baseline_stdout]"
    $baseline.stdout
    "[baseline_stderr]"
    $baseline.stderr
    "immutability_command=$($immutability.command)"
    "immutability_exit_code=$($immutability.exit_code)"
    "[immutability_stdout]"
    $immutability.stdout
    "[immutability_stderr]"
    $immutability.stderr
    "completed_at_utc=$completedAt"
    "result=$result"
) -join "`n"
[System.IO.File]::WriteAllText(
    $logPath,
    $log,
    [System.Text.UTF8Encoding]::new($false)
)

$report = [ordered]@{
    schema_version = "1.0"
    phase = "17"
    started_at_utc = $startedAt
    completed_at_utc = $completedAt
    result = $result
    python = [ordered]@{
        expected_tests = 759
        exit_code = $baseline.exit_code
        result = if ($baselinePassed) { "PASS" } else { "FAIL" }
    }
    source_immutability = [ordered]@{
        expected_files = 401
        expected_manifest_sha256 =
            "9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770"
        exit_code = $immutability.exit_code
        result = if ($immutablePassed) { "PASS" } else { "FAIL" }
    }
    log = "migration/reports/phase-17-python-baseline.log"
}
$report | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath $jsonPath -Encoding utf8
if ($result -ne "PASS") {
    throw "Final Python baseline or source immutability failed."
}
Write-Output "PHASE 17 PYTHON BASELINE: PASS (759 tests; 401 immutable files)"
