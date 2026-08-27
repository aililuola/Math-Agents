[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Arguments
)

$ErrorActionPreference = 'Stop'
$TargetRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$drive = 'P'
$existingSubst = (& subst.exe) | Where-Object { $_ -like "${drive}:\:*" }
if ($existingSubst) {
    throw "Transient drive ${drive}: is already in use"
}

subst.exe "${drive}:" $TargetRoot
if ($LASTEXITCODE -ne 0) {
    throw "Could not map ${drive}: to the target root"
}
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
    $mavenArguments = @('-B', '-ntp') + ($Arguments -split '\s+')
    Push-Location $shortRoot
    try {
        & '.\mvnw.cmd' @mavenArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Maven failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    subst.exe "${drive}:" /D | Out-Null
}
