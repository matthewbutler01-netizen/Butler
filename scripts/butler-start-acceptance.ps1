Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-start-fixture-' + [Guid]::NewGuid().ToString('N'))
$originalLocalData = [string]$env:LOCALAPPDATA
$originalDataDir = [string]$env:BUTLER_APP_DATA_DIR
$shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
try {
    $scripts = Join-Path $root 'package\scripts'
    [IO.Directory]::CreateDirectory($scripts) | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'butler-start.ps1') -Destination $scripts
    [IO.File]::WriteAllText((Join-Path $scripts 'butler-onboard.ps1'), 'param([string]$RuntimeZip,[switch]$NoBrowser,[switch]$VerifyOnly); if (-not $NoBrowser -or -not $VerifyOnly) { exit 12 }; Write-Output "ROUTE FRESH"; exit 0')
    [IO.File]::WriteAllText((Join-Path $scripts 'butler-setup-launch.ps1'), 'param([string]$RuntimeZip,[switch]$VerifyOnly); if (-not $VerifyOnly) { exit 13 }; Write-Output "ROUTE EXISTING"; exit 0')
    $zip = Join-Path $root 'Butler-runtime-fixture.zip'
    [IO.File]::WriteAllText($zip, 'fixture only')
    [IO.File]::WriteAllText(($zip + '.sha256'), 'fixture only')
    $env:LOCALAPPDATA = Join-Path $root 'profile'
    $env:BUTLER_APP_DATA_DIR = ''
    [IO.Directory]::CreateDirectory($env:LOCALAPPDATA) | Out-Null
    $entry = Join-Path $scripts 'butler-start.ps1'
    $fresh = @(& $shell -NoLogo -NoProfile -ExecutionPolicy Bypass -File $entry -RuntimeZip $zip -NoBrowser -VerifyOnly)
    if ($LASTEXITCODE -ne 0 -or $fresh -cnotcontains 'ROUTE FRESH') { throw "Fresh profile did not reach onboarding: $($fresh -join ' | ')" }
    $savedLeague = Join-Path $env:LOCALAPPDATA 'Butler\app-league.txt'
    [IO.Directory]::CreateDirectory((Split-Path -Parent $savedLeague)) | Out-Null
    [IO.File]::WriteAllText($savedLeague, 'fixture')
    $existing = @(& $shell -NoLogo -NoProfile -ExecutionPolicy Bypass -File $entry -RuntimeZip $zip -NoBrowser -VerifyOnly)
    if ($LASTEXITCODE -ne 0 -or $existing -cnotcontains 'ROUTE EXISTING') { throw "Existing profile did not reach verified launch: $($existing -join ' | ')" }
    if ([IO.File]::ReadAllText($savedLeague) -cne 'fixture') { throw 'Launcher changed the saved league selection.' }
    Write-Host 'BUTLER ONE-CLICK START ACCEPTANCE: PASS'
}
finally {
    $env:LOCALAPPDATA = $originalLocalData
    $env:BUTLER_APP_DATA_DIR = $originalDataDir
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
