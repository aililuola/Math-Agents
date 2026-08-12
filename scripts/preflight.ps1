[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$TargetRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$ExpectedManifestHash = '9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770'
$ExpectedPostgres = 'postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296'
$ExpectedTemporal = 'temporalio/temporal@sha256:59561b9ef060eaeb1f46cb6a1842d6cbdd8a393eb3b6d315ecef5fe2f0b1d7a6'

function Assert-LastExitCode {
    param([string]$Label)
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

function Read-LockValue {
    param([string]$Name)
    $line = Get-Content -LiteralPath (Join-Path $TargetRoot 'migration\image-lock.env') |
        Where-Object { $_ -like "$Name=*" } |
        Select-Object -First 1
    if (-not $line) {
        throw "Missing $Name in migration/image-lock.env"
    }
    return $line.Substring($Name.Length + 1)
}

$javaHome = Join-Path $TargetRoot '.tools\jdk-25'
$java = Join-Path $javaHome 'bin\java.exe'
$javac = Join-Path $javaHome 'bin\javac.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "Target-local JDK is missing: $java"
}
$savedErrorPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersion = (& $java -version 2>&1 | Out-String)
$javaExitCode = $LASTEXITCODE
$ErrorActionPreference = $savedErrorPreference
if ($javaExitCode -ne 0) {
    throw "java -version failed with exit code $javaExitCode"
}
if ($javaVersion -notmatch 'version "25\.') {
    throw "Expected JDK 25, got: $javaVersion"
}
& $javac -version
Assert-LastExitCode 'javac'

& git --version
Assert-LastExitCode 'git'

$python = Join-Path $TargetRoot '.venv-baseline\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Target-local Python baseline environment is missing: $python"
}
& $python -c 'import sys; assert sys.version_info >= (3, 11); print(sys.version)'
Assert-LastExitCode 'Python >= 3.11'

$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
if ($dockerCommand) {
    $docker = $dockerCommand.Source
}
else {
    $docker = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
}
if (-not (Test-Path -LiteralPath $docker -PathType Leaf)) {
    throw 'Docker CLI was not found'
}
& $docker version --format 'client={{.Client.Version}} server={{.Server.Version}}'
Assert-LastExitCode 'Docker Engine'
& $docker compose version
Assert-LastExitCode 'Docker Compose plugin'

$postgresImage = Read-LockValue 'POSTGRES_IMAGE'
$temporalImage = Read-LockValue 'TEMPORAL_DEV_IMAGE'
if ($postgresImage -ne $ExpectedPostgres -or $temporalImage -ne $ExpectedTemporal) {
    throw 'Container image lock does not match phase 00 evidence'
}
& $docker image inspect $postgresImage --format '{{.Id}} {{.Os}}/{{.Architecture}}' | Out-Null
Assert-LastExitCode 'PostgreSQL image lock'
& $docker image inspect $temporalImage --format '{{.Id}} {{.Os}}/{{.Architecture}}' | Out-Null
Assert-LastExitCode 'Temporal image lock'
$temporalVersion = (
    & $docker run --rm --network none --read-only --cap-drop ALL `
        --security-opt 'no-new-privileges:true' $temporalImage --version 2>&1 |
        Out-String
)
if (
    $temporalVersion -notmatch 'temporal version 1\.8\.1' -or
    $temporalVersion -notmatch 'Server 1\.31\.2'
) {
    throw "Unexpected Temporal version output: $temporalVersion"
}

$postgresData = Join-Path $TargetRoot '.work\container-preflight\postgres'
$temporalData = Join-Path $TargetRoot '.work\container-preflight\temporal'
New-Item -ItemType Directory -Path $postgresData,$temporalData -Force | Out-Null
$random = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($random)
}
finally {
    $rng.Dispose()
}
$env:POSTGRES_PREFLIGHT_PASSWORD = (
    [BitConverter]::ToString($random) -replace '-', ''
).ToLowerInvariant()
$env:POSTGRES_PREFLIGHT_DATA = $postgresData
$env:TEMPORAL_PREFLIGHT_DATA = $temporalData
& $docker compose --project-name mathproofmesh-phase00 `
    --env-file (Join-Path $TargetRoot 'migration\image-lock.env') `
    -f (Join-Path $TargetRoot 'migration\preflight\containers.compose.yaml') `
    config --quiet
Assert-LastExitCode 'Container Compose configuration'

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
    throw 'Maven Wrapper properties are not the phase 00 lock'
}
$forbiddenWrapperFiles = Get-ChildItem -LiteralPath (
    Join-Path $TargetRoot '.mvn\wrapper'
) -Recurse -Force -File | Where-Object {
    $_.Name -in @('maven-wrapper.jar', 'MavenWrapperDownloader.java')
}
if ($forbiddenWrapperFiles) {
    throw 'Binary/source wrapper bootstrap files are forbidden for only-script'
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

    & (Join-Path $shortRoot 'mvnw.cmd') --version
    Assert-LastExitCode 'Maven Wrapper'
    & (Join-Path $shortRoot 'mvnw.cmd') `
        "-Dmaven.repo.local=${shortRoot}.cache\maven-repository" `
        -o -f (Join-Path $shortRoot 'migration\preflight\pom.xml') `
        -B -ntp validate
    Assert-LastExitCode 'Offline Maven dependency gates'
    & (Join-Path $shortRoot 'mvnw.cmd') `
        "-Dmaven.repo.local=${shortRoot}.cache\maven-repository" `
        -o -f (Join-Path $shortRoot 'migration\preflight\pom.xml') `
        -B -ntp dependency:go-offline | Out-Null
    Assert-LastExitCode 'Offline Maven artifact resolution'
}
finally {
    subst.exe "${drive}:" /D | Out-Null
}

$baselineLog = Get-Content -LiteralPath (
    Join-Path $TargetRoot 'migration\logs\python-baseline.log'
) -Raw
if ($baselineLog -notmatch '(?m)^759 passed, [0-9]+ warnings in [0-9.]+s\r?$') {
    throw 'Python baseline log does not contain the exact 759 passed result'
}

$sourceRows = @(Import-Csv -LiteralPath (Join-Path $TargetRoot 'migration\source-state.csv'))
$testRows = @(Import-Csv -LiteralPath (Join-Path $TargetRoot 'migration\test-state.csv'))
$auxRows = @(Import-Csv -LiteralPath (Join-Path $TargetRoot 'migration\auxiliary-state.csv'))
$paths = @($sourceRows.source_file) + @($testRows.python_test_file) + @($auxRows.source_file)
if (
    $sourceRows.Count -ne 142 -or
    $testRows.Count -ne 167 -or
    $auxRows.Count -ne 92 -or
    $paths.Count -ne 401 -or
    @($paths | Sort-Object -Unique).Count -ne 401
) {
    throw 'Migration state coverage is not exactly 142 + 167 + 92 = 401'
}

& $PSHOME\powershell.exe -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $TargetRoot 'scripts\check-original-immutable.ps1')
Assert-LastExitCode 'Original source immutability'

$manifestHash = (
    Get-FileHash -LiteralPath (
        Join-Path $TargetRoot 'SOURCE_SNAPSHOT_SHA256SUMS.txt'
    ) -Algorithm SHA256
).Hash.ToLowerInvariant()
if ($manifestHash -ne $ExpectedManifestHash) {
    throw 'Frozen source manifest fingerprint changed'
}

Write-Output 'PHASE 00 PREFLIGHT: PASS'
