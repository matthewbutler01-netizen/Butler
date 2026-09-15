Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceLauncher = Join-Path $scriptDir 'butler-app.ps1'
if (-not (Test-Path -LiteralPath $sourceLauncher -PathType Leaf)) {
    throw "BF-786 BLOCKED: Butler app launcher not found at $sourceLauncher"
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf786-{0}" -f [Guid]::NewGuid().ToString('N'))
$tempPackage = Join-Path $tempRoot 'package'
$tempScripts = Join-Path $tempPackage 'scripts'
$tempLocalAppData = Join-Path $tempRoot 'localappdata'
$tempData = Join-Path $tempRoot 'data'
$probeLauncher = Join-Path $tempScripts 'butler-app.ps1'
$probeShell = Join-Path $tempScripts 'butler-app-shell.ps1'
$leagueId = '11111111-1111-1111-1111-111111111111'
$databasePath = Join-Path $tempData 'butler.db'
$configPath = Join-Path (Join-Path $tempLocalAppData 'Butler') 'app-league.txt'
$expectedFailure = "BF-785 BLOCKED: governed Butler runtime database is missing at $databasePath. The Butler runtime package is code/runtime-only; restore or migrate an existing governed Butler database before launching."

$originalLocalAppData = [string]$env:LOCALAPPDATA
$originalDataDir = [string]$env:BUTLER_APP_DATA_DIR

try {
    [IO.Directory]::CreateDirectory($tempScripts) | Out-Null
    [IO.Directory]::CreateDirectory($tempLocalAppData) | Out-Null
    [IO.Directory]::CreateDirectory($tempData) | Out-Null

    Copy-Item -LiteralPath $sourceLauncher -Destination $probeLauncher

    $sentinelShell = @'
param(
    [string]$LeagueId,
    [int]$Port,
    [switch]$NoBrowser
)
throw 'BF-786 BLOCKED: app shell was invoked during missing-runtime-database acceptance.'
'@
    [IO.File]::WriteAllText($probeShell, $sentinelShell, [Text.Encoding]::ASCII)

    $env:LOCALAPPDATA = $tempLocalAppData
    $env:BUTLER_APP_DATA_DIR = $tempData

    $observedFailure = $null
    try {
        & $probeLauncher -LeagueId $leagueId -Port 65431 -NoBrowser
        throw 'BF-786 BLOCKED: missing-runtime-database launcher probe unexpectedly succeeded.'
    }
    catch {
        $observedFailure = [string]$_.Exception.Message
    }

    if ($observedFailure -cne $expectedFailure) {
        throw "BF-786 BLOCKED: expected BF-785 missing-database failure. Observed: $observedFailure"
    }
    if (Test-Path -LiteralPath $configPath) {
        throw "BF-786 BLOCKED: launcher persisted league configuration despite missing governed database: $configPath"
    }
    if (Test-Path -LiteralPath $databasePath) {
        throw "BF-786 BLOCKED: launcher created a governed database during missing-database acceptance: $databasePath"
    }

    Write-Host 'Butler missing-runtime-database acceptance (BF-786)'
    Write-Host "Probe package: $tempPackage"
    Write-Host "Probe data: $tempData"
    Write-Host 'Boundary: TEMPORARY_FAKE_PACKAGE; NO_RUNTIME_DATABASE; NO_APP_SHELL; NO_PROVIDER_OR_TRANSACTION_WRITE'
    Write-Host 'BF-786 MISSING RUNTIME DATABASE ACCEPTANCE: PASS'
}
finally {
    if ([string]::IsNullOrWhiteSpace($originalLocalAppData)) {
        Remove-Item Env:LOCALAPPDATA -ErrorAction SilentlyContinue
    }
    else {
        $env:LOCALAPPDATA = $originalLocalAppData
    }

    if ([string]::IsNullOrWhiteSpace($originalDataDir)) {
        Remove-Item Env:BUTLER_APP_DATA_DIR -ErrorAction SilentlyContinue
    }
    else {
        $env:BUTLER_APP_DATA_DIR = $originalDataDir
    }

    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}
