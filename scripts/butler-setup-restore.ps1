param([string]$BackupZip, [string]$LeagueId, [string]$DataDir)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Write-Output 'Butler guided private-backup restore'
Write-Output 'This backup contains private user data. Keep it outside public releases and repositories.'
try {
    if ([string]::IsNullOrWhiteSpace($BackupZip)) { throw 'Select a private backup explicitly with -BackupZip and keep its .sha256 file beside it. Nothing was restored.' }
    # Load Windows inbox modules explicitly when called via CMD from PowerShell 7.
    foreach ($module in @('Microsoft.PowerShell.Utility', 'Microsoft.PowerShell.Archive')) {
        Import-Module (Join-Path $PSHOME ('Modules\' + $module)) -ErrorAction Stop
    }
    if ([string]::IsNullOrWhiteSpace($DataDir)) { $DataDir = [string]$env:BUTLER_APP_DATA_DIR }
    & (Join-Path $PSScriptRoot 'butler-runtime-data-restore.ps1') -BackupZip $BackupZip -DataDir $DataDir -LeagueId $LeagueId -ValidateLeague
    Write-Output 'BUTLER SETUP RESTORE: PASS'
    Write-Output 'The selected league exists in the restored database. Manager-page readiness still requires launch verification.'
    if (-not [string]::IsNullOrWhiteSpace($DataDir)) { Write-Output "NEXT: Keep BUTLER_APP_DATA_DIR set to $DataDir when checking or launching Butler." }
    Write-Output 'NEXT: Run scripts\butler-setup-check.cmd -RuntimeZip with the downloaded runtime ZIP, then launch Butler.'
}
catch {
    Write-Output ('BUTLER SETUP RESTORE: BLOCKED - ' + $_.Exception.Message)
    Write-Output 'NEXT: Resolve the reported blocker and retry with the matching backup. Existing databases and conflicting league selections are never overwritten.'
    exit 1
}
