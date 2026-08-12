[CmdletBinding()]
param(
    [string]$OutputDirectory = "target/release",
    [switch]$SkipBuild,
    [switch]$SkipDesktop
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $root "target"))
$output = [System.IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
$comparison = [System.StringComparison]::OrdinalIgnoreCase
if (-not $output.StartsWith(
        $targetRoot + [System.IO.Path]::DirectorySeparatorChar,
        $comparison)) {
    throw "Release output must remain below target."
}

if (-not $SkipBuild) {
    & (Join-Path $root "scripts/run-maven.ps1") `
        -Arguments "-o -pl :mathproofmesh-server -am package -DskipTests"
    if ($LASTEXITCODE -ne 0) {
        throw "Release Maven package failed."
    }
}
if (-not $SkipDesktop) {
    & (Join-Path $root "scripts/package-desktop.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "Desktop release package failed."
    }
}

$longRoot = $root
$longOutput = $output
$assemblyDrive = "S"
$existingAssemblyDrive =
    (& subst.exe) | Where-Object { $_ -like "${assemblyDrive}:\:*" }
if ($existingAssemblyDrive) {
    throw "Transient release drive ${assemblyDrive}: is already in use."
}
subst.exe "${assemblyDrive}:" $longRoot
if ($LASTEXITCODE -ne 0) {
    throw "Could not map the release assembly drive."
}
try {
    $root = "${assemblyDrive}:\"
    $targetRoot = Join-Path $root "target"
    $relativeOutput =
        $longOutput.Substring($longRoot.Length).TrimStart("\", "/")
    $output = Join-Path $root $relativeOutput

if (Test-Path -LiteralPath $output) {
    $resolvedOutput = [System.IO.Path]::GetFullPath(
        (Resolve-Path -LiteralPath $output).Path
    )
    if (-not $resolvedOutput.StartsWith(
            $targetRoot + [System.IO.Path]::DirectorySeparatorChar,
            $comparison)) {
        throw "Refusing to clean a release directory outside target."
    }
    Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
}

$bundle = Join-Path $output "JavaMathProofMesh-0.8.0"
$directories = @(
    "bin",
    "lib",
    "sidecar",
    "db",
    "compose",
    "config",
    "docs",
    "reports",
    "desktop",
    "examples"
)
foreach ($directory in $directories) {
    New-Item -ItemType Directory -Path (Join-Path $bundle $directory) -Force |
        Out-Null
}

function Copy-RequiredFile {
    param(
        [string]$Source,
        [string]$Destination
    )
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "Required release input is missing: $Source"
    }
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Copy-RequiredTree {
    param(
        [string]$Source,
        [string]$Destination
    )
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        throw "Required release input directory is missing: $Source"
    }
    Get-ChildItem -LiteralPath $Source -Force |
        ForEach-Object {
            Copy-Item -LiteralPath $_.FullName `
                -Destination $Destination -Recurse -Force
        }
}

$serverJar =
    Join-Path $root "target/modules/mathproofmesh-server/mathproofmesh-server-0.8.0-exec.jar"
$cliJar =
    Join-Path $root "target/modules/mathproofmesh-server/mathproofmesh-server-0.8.0-cli.jar"
Copy-RequiredFile $serverJar (Join-Path $bundle "lib")
Copy-RequiredFile $cliJar (Join-Path $bundle "lib")

foreach ($name in @(
        "service.py",
        "handlers.py",
        "sandbox_ast.py",
        "pyproject.toml",
        "requirements.in",
        "requirements.lock",
        "build-requirements.lock",
        "README.md"
    )) {
    Copy-RequiredFile (
        Join-Path $root "python-compute-service/$name"
    ) (Join-Path $bundle "sidecar")
}
Copy-RequiredTree (
    Join-Path $root "mathproofmesh-server/src/main/resources/db/migration"
) (Join-Path $bundle "db")
Copy-RequiredFile (Join-Path $root "compose.yaml") (Join-Path $bundle "compose")
Copy-RequiredFile (
    Join-Path $root "compose/temporal-dev.yaml"
) (Join-Path $bundle "compose")
Copy-RequiredFile (
    Join-Path $root "migration/image-lock.env"
) (Join-Path $bundle "compose")
Copy-RequiredTree (Join-Path $root "config") (Join-Path $bundle "config")
Copy-RequiredTree (Join-Path $root "docs") (Join-Path $bundle "docs")
Copy-RequiredTree (
    Join-Path $root "migration/reports"
) (Join-Path $bundle "reports")
Copy-RequiredTree (Join-Path $root "examples") (Join-Path $bundle "examples")

foreach ($name in @(
        "README.md",
        "LICENSE",
        "NOTICE",
        ".env.local.example",
        "PYTHON_SOURCE_MIGRATION_MAP.csv",
        "PYTHON_TEST_MIGRATION_MAP.csv",
        "OPS_CONFIG_DOC_MIGRATION_MAP.csv",
        "SOURCE_SNAPSHOT_SHA256SUMS.txt"
    )) {
    Copy-RequiredFile (Join-Path $root $name) $bundle
}
$completionReport = Join-Path $root "MIGRATION_COMPLETION_REPORT.md"
if (Test-Path -LiteralPath $completionReport -PathType Leaf) {
    Copy-Item -LiteralPath $completionReport -Destination $bundle -Force
}

if (-not $SkipDesktop) {
    $desktopRoot = Join-Path $root "target/desktop-dist"
    $desktopArtifacts =
        Get-ChildItem -LiteralPath $desktopRoot -File |
        Where-Object {
            $_.Extension -in @(".zip", ".exe", ".msi") -or
            $_.Name -eq "SHA256SUMS.txt"
        }
    if (-not $desktopArtifacts) {
        throw "Desktop release artifacts are missing."
    }
    foreach ($artifact in $desktopArtifacts) {
        Copy-Item -LiteralPath $artifact.FullName `
            -Destination (Join-Path $bundle "desktop") -Force
    }
}
elseif (Test-Path -LiteralPath (Join-Path $root "target/desktop-dist")) {
    Get-ChildItem -LiteralPath (Join-Path $root "target/desktop-dist") -File |
        Where-Object {
            $_.Extension -in @(".zip", ".exe", ".msi") -or
            $_.Name -eq "SHA256SUMS.txt"
        } |
        ForEach-Object {
            Copy-Item -LiteralPath $_.FullName `
                -Destination (Join-Path $bundle "desktop") -Force
        }
}

$utf8 = [System.Text.UTF8Encoding]::new($false)
$cliCmd = @'
@echo off
setlocal
set "HERE=%~dp0"
if defined JAVA_HOME (
  set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA=java"
)
"%JAVA%" -jar "%HERE%..\lib\mathproofmesh-server-0.8.0-cli.jar" %*
exit /b %ERRORLEVEL%
'@
$serverCmd = @'
@echo off
setlocal
set "HERE=%~dp0"
if defined JAVA_HOME (
  set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA=java"
)
"%JAVA%" -jar "%HERE%..\lib\mathproofmesh-server-0.8.0-exec.jar" --spring.config.additional-location="optional:file:%HERE%..\config\application.yaml" %*
exit /b %ERRORLEVEL%
'@
$sidecarCmd = @'
@echo off
setlocal
set "HERE=%~dp0"
python "%HERE%..\sidecar\service.py"
exit /b %ERRORLEVEL%
'@
$cliSh = @'
#!/usr/bin/env sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$HERE/../lib/mathproofmesh-server-0.8.0-cli.jar" "$@"
'@
$serverSh = @'
#!/usr/bin/env sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$HERE/../lib/mathproofmesh-server-0.8.0-exec.jar" --spring.config.additional-location="optional:file:$HERE/../config/application.yaml" "$@"
'@
$sidecarSh = @'
#!/usr/bin/env sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$HERE/../sidecar/service.py"
'@
[System.IO.File]::WriteAllText(
    (Join-Path $bundle "bin/mathproofmesh.cmd"),
    $cliCmd.Replace("`n", "`r`n"),
    $utf8
)
[System.IO.File]::WriteAllText(
    (Join-Path $bundle "bin/mathproofmesh-server.cmd"),
    $serverCmd.Replace("`n", "`r`n"),
    $utf8
)
[System.IO.File]::WriteAllText(
    (Join-Path $bundle "bin/mathproofmesh-sidecar.cmd"),
    $sidecarCmd.Replace("`n", "`r`n"),
    $utf8
)
[System.IO.File]::WriteAllText(
    (Join-Path $bundle "bin/mathproofmesh"),
    $cliSh.Replace("`r`n", "`n"),
    $utf8
)
[System.IO.File]::WriteAllText(
    (Join-Path $bundle "bin/mathproofmesh-server"),
    $serverSh.Replace("`r`n", "`n"),
    $utf8
)
[System.IO.File]::WriteAllText(
    (Join-Path $bundle "bin/mathproofmesh-sidecar"),
    $sidecarSh.Replace("`r`n", "`n"),
    $utf8
)

$manifest = [ordered]@{
    schema_version = "1.0"
    product = "JavaMathProofMesh"
    version = "0.8.0"
    java = "25"
    server_jar = "lib/mathproofmesh-server-0.8.0-exec.jar"
    cli_jar = "lib/mathproofmesh-server-0.8.0-cli.jar"
    python_sidecar_lock = "sidecar/requirements.lock"
    database_migrations = (
        Get-ChildItem -LiteralPath (Join-Path $bundle "db") -Filter "*.sql"
    ).Count
    desktop_artifacts = @(
        Get-ChildItem -LiteralPath (Join-Path $bundle "desktop") -File |
            Where-Object { $_.Extension -in @(".zip", ".exe", ".msi") } |
            Sort-Object Name |
            ForEach-Object { "desktop/$($_.Name)" }
    )
}
[System.IO.File]::WriteAllText(
    (Join-Path $bundle "release-manifest.json"),
    ($manifest | ConvertTo-Json -Depth 5) + "`n",
    $utf8
)

$bundleChecksums =
    Get-ChildItem -LiteralPath $bundle -Recurse -File |
    Where-Object {
        $_.FullName -ne (Join-Path $bundle "SHA256SUMS.txt")
    } |
    Sort-Object FullName |
    ForEach-Object {
        $hash = (
            Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        $relative = $_.FullName.Substring($bundle.Length + 1).Replace("\", "/")
        "$hash  $relative"
    }
[System.IO.File]::WriteAllLines(
    (Join-Path $bundle "SHA256SUMS.txt"),
    $bundleChecksums,
    [System.Text.Encoding]::ASCII
)

$archive = Join-Path $output "JavaMathProofMesh-0.8.0.zip"
Compress-Archive -LiteralPath $bundle -DestinationPath $archive
$archiveHash = (
    Get-FileHash -LiteralPath $archive -Algorithm SHA256
).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText(
    (Join-Path $output "SHA256SUMS.txt"),
    "$archiveHash  JavaMathProofMesh-0.8.0.zip`r`n",
    [System.Text.Encoding]::ASCII
)
Write-Output "RELEASE PACKAGE: $(Join-Path $longOutput 'JavaMathProofMesh-0.8.0.zip')"
}
finally {
    subst.exe "${assemblyDrive}:" /D | Out-Null
}
