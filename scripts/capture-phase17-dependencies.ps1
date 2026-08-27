[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$destination =
    Join-Path $root "migration/reports/phase-17-dependency-tree.txt"
$lines = @(
    "command=scripts/run-maven.ps1 -Arguments '-o dependency:tree'"
    "captured_at_utc=$([DateTime]::UtcNow.ToString('o'))"
)
try {
    $output =
        & (Join-Path $root "scripts/run-maven.ps1") `
            -Arguments "-o dependency:tree" 2>&1 |
        ForEach-Object { $_.ToString() }
    $lines += $output
    $lines += "result=PASS"
}
catch {
    $lines += "result=FAIL"
    $lines += "error=$($_.Exception.Message)"
    $lines | Set-Content -LiteralPath $destination -Encoding utf8
    throw
}
$lines | Set-Content -LiteralPath $destination -Encoding utf8
Write-Output "PHASE 17 DEPENDENCY TREE: PASS"
