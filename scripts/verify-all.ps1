[CmdletBinding()]
param(
    [switch]$Offline,
    [string]$LogPath
)

$ErrorActionPreference = 'Stop'
$TargetRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ($LogPath) {
    if (-not [System.IO.Path]::IsPathRooted($LogPath)) {
        $LogPath = Join-Path $TargetRoot $LogPath
    }
    $logDirectory = Split-Path -Parent $LogPath
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    Start-Transcript -LiteralPath $LogPath -Force | Out-Null
}

function Assert-LastExitCode {
    param([string]$Label)
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

$properties = ConvertFrom-StringData (
    Get-Content -LiteralPath (
        Join-Path $TargetRoot '.mvn\wrapper\maven-wrapper.properties'
    ) -Raw
)
if (
    $properties.wrapperVersion -ne '3.3.4' -or
    $properties.distributionType -ne 'only-script' -or
    $properties.distributionSha256Sum -ne
        '5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce'
) {
    throw 'Maven Wrapper no longer matches the phase-00 lock'
}

$drive = 'P'
$existingSubst = (& subst.exe) | Where-Object { $_ -like "${drive}:\:*" }
if ($existingSubst) {
    throw "Transient drive ${drive}: is already in use"
}

subst.exe "${drive}:" $TargetRoot
Assert-LastExitCode 'subst target mapping'
try {
    $shortRoot = "${drive}:\"
    $wrapperHome = Join-Path $shortRoot '.cache\maven-wrapper-home'
    $wrapperLink = Join-Path $shortRoot '.cache\wrapper-home-link'
    New-Item -ItemType Directory -Path $wrapperHome -Force | Out-Null
    if (-not (Test-Path -LiteralPath $wrapperLink)) {
        New-Item -ItemType Junction -Path $wrapperLink -Target $wrapperHome | Out-Null
    }

    $env:JAVA_HOME = Join-Path $shortRoot '.tools\jdk-25'
    $env:MAVEN_USER_HOME = $wrapperLink
    $env:TEMP = Join-Path $shortRoot '.cache\tmp'
    $env:TMP = $env:TEMP
    New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

    Push-Location $shortRoot
    try {
        $common = @('-B', '-ntp')
        if ($Offline) {
            $common += '-o'
        }

        & '.\mvnw.cmd' @common clean verify
        Assert-LastExitCode 'Maven clean verify'

        if ($Offline) {
            $sbom = Join-Path $TargetRoot 'migration\reports\phase-17-sbom.json'
            if (-not (Test-Path -LiteralPath $sbom -PathType Leaf)) {
                throw 'Offline verification requires the phase-17 SBOM'
            }
            $securityReport = Join-Path $TargetRoot (
                'migration\reports\dependency-check\dependency-check-report.json'
            )
            if (-not (Test-Path -LiteralPath $securityReport -PathType Leaf)) {
                throw 'Offline verification requires the previously generated security report'
            }
        }
        else {
            & '.\mvnw.cmd' @common `
                'org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom'
            Assert-LastExitCode 'CycloneDX aggregate SBOM'

            & '.\mvnw.cmd' '-B' '-ntp' `
                'org.owasp:dependency-check-maven:12.2.2:aggregate'
            Assert-LastExitCode 'OWASP Dependency-Check'
        }

        $python = Get-Command python -ErrorAction Stop
        & $python.Source (
            Join-Path $TargetRoot 'scripts\sanitize-dependency-check-report.py'
        )
        Assert-LastExitCode 'Dependency-Check report path normalization'

        & $python.Source (Join-Path $TargetRoot 'scripts\verify-coverage.py')
        Assert-LastExitCode 'Phase-17 coverage gates'

        & $python.Source (Join-Path $TargetRoot 'scripts\verify-security.py')
        Assert-LastExitCode 'Phase-17 security and license gates'

        if ($Offline) {
            foreach ($required in @(
                    'migration\baseline\phase-17-performance-reference.json',
                    'migration\reports\phase-17-performance.json'
                )) {
                if (-not (Test-Path -LiteralPath (
                            Join-Path $TargetRoot $required
                        ) -PathType Leaf)) {
                    throw "Offline verification requires $required"
                }
            }
        }
        else {
            & $python.Source (Join-Path $TargetRoot 'scripts\phase17-performance.py')
            Assert-LastExitCode 'Phase-17 performance gates'
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    subst.exe "${drive}:" /D | Out-Null
}

& $PSHOME\powershell.exe -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $TargetRoot 'scripts\check-original-immutable.ps1')
Assert-LastExitCode 'Original source immutability'

Write-Output 'FULL VERIFICATION: PASS'
if ($LogPath) {
    Stop-Transcript | Out-Null
}
