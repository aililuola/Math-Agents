[CmdletBinding()]
param(
    [ValidateSet('Up', 'Health', 'Down', 'Reset')]
    [string]$Command = 'Up',
    [switch]$ConfirmReset
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$compose = Join-Path $root 'compose\temporal-dev.yaml'
$lock = Join-Path $root 'migration\image-lock.env'
$common = @('compose', '--env-file', $lock, '-f', $compose)
$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
if ($null -ne $dockerCommand) {
    $docker = $dockerCommand.Source
}
else {
    $docker = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
}
if (-not (Test-Path -LiteralPath $docker -PathType Leaf)) {
    throw 'Docker CLI was not found'
}

switch ($Command) {
    'Up' {
        & $docker @common up -d --wait
    }
    'Health' {
        & $docker @common exec -T temporal-dev temporal `
            --disable-config-file --disable-config-env `
            --address 127.0.0.1:7233 operator cluster health
    }
    'Down' {
        & $docker @common down
    }
    'Reset' {
        if (-not $ConfirmReset) {
            throw 'Reset deletes the Temporal development volume; pass -ConfirmReset explicitly'
        }
        & $docker @common down -v
    }
}

if ($LASTEXITCODE -ne 0) {
    throw "Temporal development command failed with exit code $LASTEXITCODE"
}
