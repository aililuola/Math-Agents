[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$TargetRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$WorkspaceRoot = (Resolve-Path -LiteralPath (Join-Path $TargetRoot '..')).Path
$BaselineCsv = Join-Path $TargetRoot 'migration\baseline\source-manifest.csv'
$ExpectedManifest = Join-Path $TargetRoot 'SOURCE_SNAPSHOT_SHA256SUMS.txt'
$ExpectedCombinedHash = '9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770'

if (-not (Test-Path -LiteralPath $BaselineCsv -PathType Leaf)) {
    throw "Missing frozen baseline: $BaselineCsv"
}

$baseline = @(Import-Csv -LiteralPath $BaselineCsv -Encoding UTF8)
if ($baseline.Count -ne 401) {
    throw "Frozen baseline has $($baseline.Count) rows; expected 401"
}

$current = @{}
$files = New-Object System.Collections.Generic.List[IO.FileInfo]
foreach ($file in Get-ChildItem -LiteralPath $WorkspaceRoot -Force -File) {
    $files.Add($file)
}
foreach ($directory in Get-ChildItem -LiteralPath $WorkspaceRoot -Force -Directory) {
    if (
        $directory.FullName.Equals(
            $TargetRoot,
            [StringComparison]::OrdinalIgnoreCase
        ) -or $directory.Name -eq '.git'
    ) {
        continue
    }
    foreach ($file in Get-ChildItem -LiteralPath $directory.FullName -Recurse -Force -File) {
        $files.Add($file)
    }
}
foreach ($file in $files) {
    $relative = $file.FullName.Substring($WorkspaceRoot.Length).TrimStart('\')
    $relative = $relative.Replace('\', '/')
    if ($relative -match '(^|/)\.git(/|$)') {
        continue
    }
    $current[$relative] = $file
}

$failures = New-Object System.Collections.Generic.List[string]
if ($current.Count -ne 401) {
    $failures.Add("Current source file count is $($current.Count); expected 401")
}

foreach ($row in $baseline) {
    if (-not $current.ContainsKey($row.path)) {
        $failures.Add("Missing source file: $($row.path)")
        continue
    }
    $file = $current[$row.path]
    if ([int64]$row.size_bytes -ne $file.Length) {
        $failures.Add(
            "Size mismatch: $($row.path) expected=$($row.size_bytes) actual=$($file.Length)"
        )
        continue
    }
    $actualHash = (
        Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    if ($row.sha256 -ne $actualHash) {
        $failures.Add(
            "SHA-256 mismatch: $($row.path) expected=$($row.sha256) actual=$actualHash"
        )
    }
}

foreach ($path in $current.Keys) {
    if (-not ($baseline.path -contains $path)) {
        $failures.Add("Unexpected source file outside TARGET_ROOT: $path")
    }
}

[string[]]$paths = @($current.Keys)
[Array]::Sort($paths, [StringComparer]::Ordinal)
$lines = foreach ($path in $paths) {
    $hash = (
        Get-FileHash -LiteralPath $current[$path].FullName -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    "$hash  $path"
}
$utf8 = New-Object Text.UTF8Encoding($false)
$bytes = $utf8.GetBytes(($lines -join "`n") + "`n")
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $combinedHash = (
        [BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', ''
    ).ToLowerInvariant()
}
finally {
    $sha.Dispose()
}

$expectedBytes = [IO.File]::ReadAllBytes($ExpectedManifest)
$exactManifest = $bytes.Length -eq $expectedBytes.Length
if ($exactManifest) {
    for ($index = 0; $index -lt $bytes.Length; $index++) {
        if ($bytes[$index] -ne $expectedBytes[$index]) {
            $exactManifest = $false
            break
        }
    }
}
if ($combinedHash -ne $ExpectedCombinedHash -or -not $exactManifest) {
    $failures.Add(
        "Combined manifest mismatch: expected=$ExpectedCombinedHash actual=$combinedHash"
    )
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "SOURCE IMMUTABILITY: PASS"
Write-Output "files=401"
Write-Output "manifest_sha256=$combinedHash"
Write-Output "workspace_root=$WorkspaceRoot"
Write-Output "target_excluded=$TargetRoot"
