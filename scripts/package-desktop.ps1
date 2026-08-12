[CmdletBinding()]
param(
    [string]$OutputDirectory = "target/desktop-dist",
    [switch]$SkipInstaller
)

$ErrorActionPreference = "Stop"

function Clear-ReadOnlyAttributes {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $items = @((Get-Item -LiteralPath $Path -Force)) +
        @(Get-ChildItem -LiteralPath $Path -Recurse -Force)
    foreach ($item in $items) {
        if (($item.Attributes -band [System.IO.FileAttributes]::ReadOnly) -ne 0) {
            $item.Attributes =
                $item.Attributes -band (-bnot [System.IO.FileAttributes]::ReadOnly)
        }
    }
}

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $root "target"))
$output = [System.IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
$comparison = [System.StringComparison]::OrdinalIgnoreCase
if (-not $output.StartsWith($targetRoot + [System.IO.Path]::DirectorySeparatorChar, $comparison)) {
    throw "Desktop package output must remain below target."
}

$jdk = Join-Path $root ".tools/jdk-25"
$jpackage = Join-Path $jdk "bin/jpackage.exe"
if (-not (Test-Path -LiteralPath $jpackage -PathType Leaf)) {
    throw "Pinned JDK 25 jpackage executable is missing."
}

$packageWork = Join-Path $root "target/desktop-package"
$cleanupPaths = @()
foreach ($candidate in @($output, $packageWork)) {
    if (Test-Path -LiteralPath $candidate) {
        $resolvedCandidate = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $candidate).Path)
        if (-not $resolvedCandidate.StartsWith(
                $targetRoot + [System.IO.Path]::DirectorySeparatorChar,
                $comparison)) {
            throw "Refusing to clean a desktop package directory outside target."
        }
        $cleanupPaths += $resolvedCandidate.Substring($root.Length).TrimStart("\", "/")
    }
}
if ($cleanupPaths) {
    $cleanupDrive = "R"
    $existingCleanupDrive = (& subst.exe) | Where-Object { $_ -like "${cleanupDrive}:\:*" }
    if ($existingCleanupDrive) {
        throw "Transient cleanup drive ${cleanupDrive}: is already in use."
    }
    subst.exe "${cleanupDrive}:" $root
    if ($LASTEXITCODE -ne 0) {
        throw "Could not map ${cleanupDrive}: to the target root."
    }
    try {
        foreach ($relativeCleanupPath in $cleanupPaths) {
            Remove-Item -LiteralPath (Join-Path "${cleanupDrive}:\" $relativeCleanupPath) `
                -Recurse -Force
        }
    } finally {
        subst.exe "${cleanupDrive}:" /D | Out-Null
    }
}
$input = Join-Path $packageWork "input"
New-Item -ItemType Directory -Path $input -Force | Out-Null
New-Item -ItemType Directory -Path $output -Force | Out-Null

$priorJavaHome = $env:JAVA_HOME
$priorMavenUserHome = $env:MAVEN_USER_HOME
$priorTemp = $env:TEMP
$priorTmp = $env:TMP
$priorPath = $env:PATH
try {
    & (Join-Path $root "scripts/run-maven.ps1") `
        -Arguments "-o -pl :mathproofmesh-desktop -am package -DskipTests dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=$input"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven desktop package assembly failed."
    }
} finally {
    $env:JAVA_HOME = $priorJavaHome
    $env:MAVEN_USER_HOME = $priorMavenUserHome
    $env:TEMP = $priorTemp
    $env:TMP = $priorTmp
    $env:PATH = $priorPath
}

$modules = @(
    "mathproofmesh-contracts",
    "mathproofmesh-core",
    "mathproofmesh-server",
    "mathproofmesh-desktop"
)
foreach ($module in $modules) {
    $jar = Join-Path $root "target/modules/$module/$module-0.8.0.jar"
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Required reactor artifact is missing: $module"
    }
    Copy-Item -LiteralPath $jar -Destination $input -Force
}

$profilesInput = Join-Path $input "profiles"
$sidecarInput = Join-Path $input "sidecar"
New-Item -ItemType Directory -Path $profilesInput -Force | Out-Null
New-Item -ItemType Directory -Path $sidecarInput -Force | Out-Null
$profileFiles = @(
    "deepseek-v4-pro-smoke.yaml",
    "deepseek-v4-pro.yaml",
    "topology-active.yaml",
    "proof-control-shadow.yaml",
    "proof-control-active.yaml"
)
foreach ($profileFile in $profileFiles) {
    $profileSource = Join-Path $root "config/$profileFile"
    if (-not (Test-Path -LiteralPath $profileSource -PathType Leaf)) {
        throw "Required desktop profile is missing: $profileFile"
    }
    Copy-Item -LiteralPath $profileSource -Destination $profilesInput -Force
}

$sidecarSources = Get-ChildItem -LiteralPath (Join-Path $root "python-compute-service") -File -Filter "*.py"
if (-not $sidecarSources) {
    throw "Python computation sidecar sources are missing."
}
foreach ($sidecarSource in $sidecarSources) {
    Copy-Item -LiteralPath $sidecarSource.FullName -Destination $sidecarInput -Force
}

$packageDrive = "Q"
$existingPackageDrive = (& subst.exe) | Where-Object { $_ -like "${packageDrive}:\:*" }
if ($existingPackageDrive) {
    throw "Transient package drive ${packageDrive}: is already in use."
}
subst.exe "${packageDrive}:" $root
if ($LASTEXITCODE -ne 0) {
    throw "Could not map ${packageDrive}: to the target root."
}
try {
$shortRoot = "${packageDrive}:\"
$shortInput = Join-Path $shortRoot "target/desktop-package/input"
$packagedSidecarRuntime = Join-Path $shortInput "sidecar-runtime"
$baselineEnvironment = Join-Path $shortRoot ".venv-baseline"
$baselineConfig = Join-Path $baselineEnvironment "pyvenv.cfg"
if (-not (Test-Path -LiteralPath $baselineConfig -PathType Leaf)) {
    throw "Locked Python baseline environment is missing."
}
$homeLine = Get-Content -LiteralPath $baselineConfig | Where-Object { $_ -match '^home\s*=' } | Select-Object -First 1
if (-not $homeLine) {
    throw "Locked Python baseline has no interpreter home."
}
$pythonHome = $homeLine.Split('=', 2)[1].Trim()
if (-not (Test-Path -LiteralPath (Join-Path $pythonHome "python.exe") -PathType Leaf)) {
    throw "Locked Python base interpreter is missing."
}
New-Item -ItemType Directory -Path $packagedSidecarRuntime -Force | Out-Null
$pythonRuntimeEntries = @(
    "python.exe",
    "python3.dll",
    "python314.dll",
    "vcruntime140.dll",
    "vcruntime140_1.dll",
    "DLLs",
    "Lib",
    "LICENSE.txt"
)
foreach ($entry in $pythonRuntimeEntries) {
    $source = Join-Path $pythonHome $entry
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Required Python runtime entry is missing: $entry"
    }
    Copy-Item -LiteralPath $source -Destination $packagedSidecarRuntime -Recurse -Force
}
$baselinePackages = Join-Path $baselineEnvironment "Lib/site-packages"
$requiredPackages = @(
    "mpmath",
    "mpmath-1.3.0.dist-info",
    "sympy",
    "sympy-1.14.0.dist-info",
    "z3",
    "z3_solver-4.16.0.0.dist-info"
)
$packagedSitePackages = Join-Path $packagedSidecarRuntime "Lib/site-packages"
New-Item -ItemType Directory -Path $packagedSitePackages -Force | Out-Null
foreach ($package in $requiredPackages) {
    $source = Join-Path $baselinePackages $package
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Required locked sidecar package is missing: $package"
    }
    Copy-Item -LiteralPath $source -Destination $packagedSitePackages -Recurse -Force
}
& (Join-Path $packagedSidecarRuntime "python.exe") -I -c `
    "import sympy,z3; assert sympy.__version__ == '1.14.0'; assert z3.get_version_string() == '4.16.0'"
if ($LASTEXITCODE -ne 0) {
    throw "Packaged Python sidecar runtime verification failed."
}

    $relativeOutput = $output.Substring($root.Length).TrimStart("\", "/")
    $shortOutput = Join-Path $shortRoot $relativeOutput
    $runtime = Join-Path $shortRoot "target/desktop-package/runtime"
    $appImageTemp = Join-Path $shortRoot "target/desktop-package/app-image-temp"
    $installerTemp = Join-Path $shortRoot "target/desktop-package/installer-temp"
    $jlink = Join-Path $shortRoot ".tools/jdk-25/bin/jlink.exe"
    $shortJpackage = Join-Path $shortRoot ".tools/jdk-25/bin/jpackage.exe"
    $mainJar = "mathproofmesh-desktop-0.8.0.jar"
    $icon =
        Join-Path $shortRoot "mathproofmesh-desktop/src/main/resources/io/github/aililuola/mathproofmesh/desktop/assets/mathproofmesh.ico"
    & $jlink `
        --strip-debug `
        --no-header-files `
        --no-man-pages `
        --compress zip-6 `
        --add-modules "java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.crypto.mscapi,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.jsobject,jdk.management,jdk.management.agent,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.httpserver" `
        --output $runtime
    if ($LASTEXITCODE -ne 0) {
        throw "Pinned JDK 25 runtime image creation failed."
    }
    $common = @(
        "--verbose",
        "--name", "MathProofMesh",
        "--app-version", "0.8.0",
        "--vendor", "MathProofMesh",
        "--description", "Local-first multi-agent mathematical proof workbench",
        "--input", $shortInput,
        "--main-jar", $mainJar,
        "--main-class", "io.github.aililuola.mathproofmesh.desktop.DesktopMain",
        "--icon", $icon,
        "--runtime-image", $runtime,
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "--enable-native-access=ALL-UNNAMED"
    )

    & $shortJpackage @common --type app-image --dest $shortOutput --temp $appImageTemp
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage portable image creation failed."
    }

    $applicationRoot = Join-Path $shortOutput "MathProofMesh"
    $application = Join-Path $applicationRoot "MathProofMesh.exe"
    if (-not (Test-Path -LiteralPath $application -PathType Leaf)) {
        throw "Portable desktop launcher is missing."
    }
    $packagedJava = Join-Path $applicationRoot "runtime/bin/java.exe"
    $packagedClasspath = Join-Path $applicationRoot "app/*"
    $healthRoot = Join-Path $shortOutput "clean-start-data"
    $healthTemp = Join-Path $healthRoot "tmp"
    New-Item -ItemType Directory -Path $healthTemp -Force | Out-Null
    $priorHome = $env:MATHPROOFMESH_DESKTOP_HOME
    try {
        $env:MATHPROOFMESH_DESKTOP_HOME = $healthRoot
        & $packagedJava `
            "-Dfile.encoding=UTF-8" `
            "-Djava.io.tmpdir=$healthTemp" `
            "--enable-native-access=ALL-UNNAMED" `
            "-cp" $packagedClasspath `
            "io.github.aililuola.mathproofmesh.desktop.DesktopMain" `
            "--health-check"
        if ($LASTEXITCODE -ne 0) {
            throw "Packaged desktop clean-start health check failed."
        }
    } finally {
        $env:MATHPROOFMESH_DESKTOP_HOME = $priorHome
    }

    $portable = Join-Path $shortOutput "MathProofMesh-0.8.0-windows-x64-portable.zip"
    Compress-Archive -LiteralPath (Join-Path $shortOutput "MathProofMesh") -DestinationPath $portable

    if (-not $SkipInstaller) {
        $wixBin = Join-Path $shortRoot ".tools/wix-5.0.2/PFiles64/WiX Toolset v5.0/bin"
        $wix = Join-Path $wixBin "wix.exe"
        if (-not (Test-Path -LiteralPath $wix -PathType Leaf)) {
            throw "Pinned WiX 5.0.2 executable is missing."
        }
        $wixExtensions = Join-Path $shortRoot ".tools/wix-5.0.2/portable-home"
        $priorInstallerPath = $env:PATH
        $priorWixExtensions = $env:WIX_EXTENSIONS
        try {
            $env:PATH = "$wixBin;$priorInstallerPath"
            $env:WIX_EXTENSIONS = $wixExtensions
            & $shortJpackage @common `
                --type exe `
                --dest $shortOutput `
                --temp $installerTemp `
                --win-dir-chooser `
                --win-menu `
                --win-menu-group "MathProofMesh" `
                --win-shortcut
            if ($LASTEXITCODE -ne 0) {
                throw "jpackage Windows installer creation failed."
            }
        } finally {
            $env:PATH = $priorInstallerPath
            $env:WIX_EXTENSIONS = $priorWixExtensions
        }
    }

    $artifacts =
        Get-ChildItem -LiteralPath $shortOutput -File |
        Where-Object { $_.Extension -in @(".zip", ".exe", ".msi") } |
        Sort-Object Name
    if (-not $artifacts) {
        throw "No distributable desktop artifacts were created."
    }
    $checksums =
        foreach ($artifact in $artifacts) {
            $hash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $($artifact.Name)"
        }
    $checksums |
        Set-Content -LiteralPath (Join-Path $shortOutput "SHA256SUMS.txt") -Encoding ascii
} finally {
    try {
        Clear-ReadOnlyAttributes (Join-Path "${packageDrive}:\" "target/desktop-dist")
        Clear-ReadOnlyAttributes (Join-Path "${packageDrive}:\" "target/desktop-package")
    } finally {
        subst.exe "${packageDrive}:" /D | Out-Null
    }
}
Write-Host "Desktop packages: $output"
