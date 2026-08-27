[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$LogPath,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$resolvedLog = [System.IO.Path]::GetFullPath((Join-Path $root $LogPath))
$reportsRoot = [System.IO.Path]::GetFullPath((Join-Path $root 'migration\reports'))
if (-not $resolvedLog.StartsWith(
        $reportsRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Verification log must stay under migration/reports: $resolvedLog"
}

$verifyScript = Join-Path $PSScriptRoot 'verify-all.ps1'
$arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$verifyScript`""
if ($Offline) {
    $arguments += ' -Offline'
}

$start = New-Object System.Diagnostics.ProcessStartInfo
$start.FileName = Join-Path $PSHOME 'powershell.exe'
$start.Arguments = $arguments
$start.WorkingDirectory = $root
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true

$process = New-Object System.Diagnostics.Process
$process.StartInfo = $start
$startedAt = (Get-Date).ToUniversalTime().ToString('o')
if (-not $process.Start()) {
    throw 'Could not start the verification process'
}
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$process.WaitForExit()
$stdout = $stdoutTask.Result
$stderr = $stderrTask.Result

$header = @(
    "command=$($start.FileName) $arguments"
    "started_at_utc=$startedAt"
)
$footer = @(
    "exit_code=$($process.ExitCode)"
    "completed_at_utc=$((Get-Date).ToUniversalTime().ToString('o'))"
)
$content = ($header -join "`n") + "`n" + $stdout
if ($stderr) {
    $content += "`n[stderr]`n" + $stderr
}
$content += "`n" + ($footer -join "`n") + "`n"
[System.IO.File]::WriteAllText(
    $resolvedLog,
    $content,
    (New-Object System.Text.UTF8Encoding($false))
)

if ($stdout) {
    Write-Output $stdout
}
if ($stderr) {
    Write-Warning $stderr
}
if ($process.ExitCode -ne 0) {
    throw "Full verification failed with exit code $($process.ExitCode)"
}
