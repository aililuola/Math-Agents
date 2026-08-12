[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$TargetRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$ExtendedRoot = '\\?\' + $TargetRoot
$Python = Join-Path $TargetRoot '.venv-baseline\Scripts\python.exe'
$SourceRoot = Join-Path $TargetRoot '.work\source'

$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONPYCACHEPREFIX = $ExtendedRoot + '\.cache\pycache'
$env:PYTHONPATH = Join-Path $SourceRoot 'src'
$env:HOME = $ExtendedRoot + '\.work\home'
$env:USERPROFILE = $env:HOME
$env:APPDATA = $ExtendedRoot + '\.work\appdata'
$env:LOCALAPPDATA = $env:APPDATA
$env:TEMP = $ExtendedRoot + '\t'
$env:TMP = $env:TEMP
$env:MPM_ALLOW_LIVE_PROVIDER_CALLS = 'false'
Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:OPENAI_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:ANTHROPIC_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:GEMINI_API_KEY -ErrorAction SilentlyContinue

Push-Location $SourceRoot
try {
    & $Python -m pytest -q `
        -o "cache_dir=$ExtendedRoot\.cache\pytest" `
        "--basetemp=$ExtendedRoot\q"
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
