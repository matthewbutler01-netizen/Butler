param(
    [string]$RuntimeZip,
    [switch]$NoBrowser,
    [switch]$VerifyOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

if ([string]::IsNullOrWhiteSpace($RuntimeZip)) {
    Add-Type -AssemblyName System.Windows.Forms
    $picker = New-Object System.Windows.Forms.OpenFileDialog
    try {
        $picker.Title = 'Select the Butler runtime ZIP'
        $picker.Filter = 'Butler runtime ZIP (Butler-runtime-*.zip)|Butler-runtime-*.zip'
        $picker.InitialDirectory = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
        $picker.RestoreDirectory = $true
        if ($picker.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
            Write-Host 'Butler start canceled.'
            exit 0
        }
        $RuntimeZip = $picker.FileName
    }
    finally { $picker.Dispose() }
}

$zip = [IO.Path]::GetFullPath($RuntimeZip)
if (-not (Test-Path -LiteralPath $zip -PathType Leaf) -or -not (Test-Path -LiteralPath ($zip + '.sha256') -PathType Leaf)) {
    throw 'Select the Butler runtime ZIP with its matching .sha256 file beside it.'
}
if ($zip.Contains('"')) { throw 'Runtime ZIP path contains an unsupported quote.' }

$localData = [string]$env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localData)) { $localData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData) }
if ([string]::IsNullOrWhiteSpace($localData)) { throw 'LocalApplicationData is unavailable.' }
$savedLeague = Join-Path $localData 'Butler\app-league.txt'
$dataDir = if ([string]::IsNullOrWhiteSpace([string]$env:BUTLER_APP_DATA_DIR)) { Join-Path $localData 'Butler\data' } else { [string]$env:BUTLER_APP_DATA_DIR }
$database = Join-Path $dataDir 'butler.db'
$existing = (Test-Path -LiteralPath $savedLeague) -or (Test-Path -LiteralPath $database)

if (-not $existing) {
    Write-Host 'Butler: new profile. Opening the local Sleeper league finder.'
    $entry = Join-Path $PSScriptRoot 'butler-onboard.ps1'
    $arguments = @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $entry + '"'), '-RuntimeZip', ('"' + $zip + '"'))
    if ($NoBrowser) { $arguments += '-NoBrowser' }
    if ($VerifyOnly) { $arguments += '-VerifyOnly' }
    & $shell @arguments
    exit $LASTEXITCODE
}

Write-Host 'Butler: existing profile. Checking and opening Dashboard.'
$entry = Join-Path $PSScriptRoot 'butler-setup-launch.ps1'
$arguments = @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $entry + '"'), '-RuntimeZip', ('"' + $zip + '"'))
if ($VerifyOnly) { $arguments += '-VerifyOnly' }
& $shell @arguments | Tee-Object -Variable launchLines
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if (-not $NoBrowser -and -not $VerifyOnly) {
    $dashboard = @($launchLines | Where-Object { $_ -match '^BUTLER DASHBOARD: (http://127\.0\.0\.1:\d+/$)' })
    if ($dashboard.Count -ne 1) { throw 'Dashboard handoff URL was not found after launch.' }
    $handoff = [regex]::Match([string]$dashboard[0], '^BUTLER DASHBOARD: (http://127\.0\.0\.1:\d+/$)')
    Start-Process $handoff.Groups[1].Value
}
