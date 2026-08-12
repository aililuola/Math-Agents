[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$SkipTests,
    [switch]$SkipWindowSmoke,
    [switch]$SkipInstaller
)

$ErrorActionPreference = "Stop"
$scriptRoot = $PSScriptRoot
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptRoot "..\.."))
$python = Join-Path $projectRoot ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Virtual environment Python was not found: $python"
}

function Remove-ProjectBuildPath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $target = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $RelativePath))
    $prefix = $projectRoot.TrimEnd('\') + '\'
    if (-not $target.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove a path outside the project: $target"
    }
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
}

Push-Location $projectRoot
try {
    if ($Clean) {
        Remove-ProjectBuildPath "build\MathProofMesh"
        Remove-ProjectBuildPath "dist\MathProofMesh"
        Remove-ProjectBuildPath "packaging\windows\output"
    }

    & $python "packaging\windows\make_icon.py" `
        --source "packaging\windows\assets\mathproofmesh.png" `
        --ico "packaging\windows\assets\mathproofmesh.ico" `
        --web "src\mathproofmesh\desktop\web\assets\app-icon.png"
    if ($LASTEXITCODE -ne 0) {
        throw "Icon generation failed with exit code $LASTEXITCODE"
    }

    if (-not $SkipTests) {
        # Keep this path short enough for Windows installations without long-path support.
        Remove-ProjectBuildPath "build\pytest"
        $pytestTemp = Join-Path $projectRoot "build\pytest"
        & $python -m pytest -q `
            --basetemp $pytestTemp `
            -p no:cacheprovider `
            "tests\test_desktop.py" `
            "tests\test_server.py" `
            "tests\test_end_to_end.py" `
            "tests\test_activity.py"
        if ($LASTEXITCODE -ne 0) {
            throw "Desktop regression tests failed with exit code $LASTEXITCODE"
        }
    }

    & $python -m PyInstaller --noconfirm --clean `
        "packaging\windows\MathProofMesh.spec"
    if ($LASTEXITCODE -ne 0) {
        throw "PyInstaller failed with exit code $LASTEXITCODE"
    }

    $desktopExe = Join-Path $projectRoot "dist\MathProofMesh\MathProofMesh.exe"
    if (-not (Test-Path -LiteralPath $desktopExe -PathType Leaf)) {
        throw "Desktop executable was not produced: $desktopExe"
    }

    Remove-ProjectBuildPath "build\desktop-health"
    Remove-ProjectBuildPath "build\desktop-window-smoke"
    $healthHome = Join-Path $projectRoot "build\desktop-health"
    $oldDesktopHome = $env:MATHPROOFMESH_DESKTOP_HOME
    try {
        $env:MATHPROOFMESH_DESKTOP_HOME = $healthHome
        $health = Start-Process `
            -FilePath $desktopExe `
            -ArgumentList "--health-check" `
            -WindowStyle Hidden `
            -Wait `
            -PassThru
        if ($health.ExitCode -ne 0) {
            throw "Packaged health check failed with exit code $($health.ExitCode)"
        }

        if (-not $SkipWindowSmoke) {
            $env:MATHPROOFMESH_DESKTOP_HOME = Join-Path $projectRoot "build\desktop-window-smoke"
            $windowSmoke = Start-Process `
                -FilePath $desktopExe `
                -ArgumentList "--window-smoke-test" `
                -WindowStyle Hidden `
                -PassThru
            if (-not $windowSmoke.WaitForExit(45000)) {
                Stop-Process -Id $windowSmoke.Id -Force
                throw "Packaged window smoke test did not exit within 45 seconds"
            }
            if ($windowSmoke.ExitCode -ne 0) {
                throw "Packaged window smoke test failed with exit code $($windowSmoke.ExitCode)"
            }
        }
    } finally {
        $env:MATHPROOFMESH_DESKTOP_HOME = $oldDesktopHome
    }

    Write-Host "Portable desktop build: $desktopExe"

    if (-not $SkipInstaller) {
        $isccCandidates = @(
            (Join-Path $projectRoot "build\tools\InnoSetup\ISCC.exe"),
            (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"),
            (Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe")
        )
        $iscc = $isccCandidates |
            Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
            Select-Object -First 1
        if ($iscc) {
            & $iscc "packaging\windows\MathProofMesh.iss"
            if ($LASTEXITCODE -ne 0) {
                throw "Inno Setup failed with exit code $LASTEXITCODE"
            }
            Write-Host "Installer output: $(Join-Path $scriptRoot 'output')"
        } else {
            Write-Warning "Inno Setup 6 is not installed; portable EXE build is complete."
        }
    }
} finally {
    Pop-Location
}
