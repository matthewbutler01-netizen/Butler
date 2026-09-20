Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$backupScript = Join-Path $scriptDir 'butler-runtime-data-backup.ps1'
$restoreScript = Join-Path $scriptDir 'butler-runtime-data-restore.ps1'
if (-not (Test-Path -LiteralPath $backupScript -PathType Leaf) -or
    -not (Test-Path -LiteralPath $restoreScript -PathType Leaf)) {
    throw 'BF-897 BLOCKED: runtime-data backup/restore scripts are missing.'
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf897-acceptance-{0}" -f [Guid]::NewGuid().ToString('N'))
$sourceLocal = Join-Path $tempRoot 'source-local'
$restoreLocal = Join-Path $tempRoot 'restore-local'
$sourceData = Join-Path $tempRoot 'source-data'
$restoreData = Join-Path $tempRoot 'restore-data'
$backupOutput = Join-Path $tempRoot 'backups'
$originalLocalAppData = [string]$env:LOCALAPPDATA
$leagueId = '11111111-2222-3333-4444-555555555555'

try {
    [IO.Directory]::CreateDirectory($sourceLocal) | Out-Null
    [IO.Directory]::CreateDirectory($sourceData) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $sourceLocal 'Butler')) | Out-Null

    $databasePath = Join-Path $sourceData 'butler.db'
    $bytes = New-Object byte[] 4096
    $header = [Text.Encoding]::ASCII.GetBytes("SQLite format 3`0")
    [Array]::Copy($header, 0, $bytes, 0, $header.Length)
    for ($i = 16; $i -lt $bytes.Length; $i++) {
        $bytes[$i] = [byte]($i % 251)
    }
    [IO.File]::WriteAllBytes($databasePath, $bytes)
    [IO.File]::WriteAllText(
        (Join-Path $sourceLocal 'Butler\app-league.txt'),
        ($leagueId + "`r`n"),
        [Text.Encoding]::ASCII
    )

    $env:LOCALAPPDATA = $sourceLocal
    & $backupScript -DataDir $sourceData -OutputDir $backupOutput

    $archives = @(Get-ChildItem -LiteralPath $backupOutput -Filter 'Butler-runtime-data-*.zip' -File)
    if ($archives.Count -ne 1) {
        throw "BF-897 BLOCKED: synthetic backup produced $($archives.Count) archives instead of exactly one."
    }
    $archive = $archives[0]
    if (-not (Test-Path -LiteralPath ($archive.FullName + '.sha256') -PathType Leaf)) {
        throw 'BF-897 BLOCKED: synthetic backup did not create its archive checksum sidecar.'
    }

    $sourceHash = (Get-FileHash -LiteralPath $databasePath -Algorithm SHA256).Hash.ToLowerInvariant()

    [IO.Directory]::CreateDirectory($restoreLocal) | Out-Null
    $env:LOCALAPPDATA = $restoreLocal
    & $restoreScript -BackupZip $archive.FullName -DataDir $restoreData

    $restoredDatabase = Join-Path $restoreData 'butler.db'
    if (-not (Test-Path -LiteralPath $restoredDatabase -PathType Leaf)) {
        throw 'BF-897 BLOCKED: synthetic restore did not create the governed database.'
    }
    $restoredHash = (Get-FileHash -LiteralPath $restoredDatabase -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($restoredHash -cne $sourceHash) {
        throw 'BF-897 BLOCKED: synthetic restore database hash differs from the source hash.'
    }

    $restoredLeaguePath = Join-Path $restoreLocal 'Butler\app-league.txt'
    if (-not (Test-Path -LiteralPath $restoredLeaguePath -PathType Leaf)) {
        throw 'BF-897 BLOCKED: synthetic restore did not restore the saved league selection.'
    }
    $restoredLeague = [IO.File]::ReadAllText($restoredLeaguePath, [Text.Encoding]::ASCII).Trim()
    if ($restoredLeague -cne $leagueId) {
        throw 'BF-897 BLOCKED: synthetic restore changed the saved league selection.'
    }

    $overwriteBlocked = $false
    try {
        & $restoreScript -BackupZip $archive.FullName -DataDir $restoreData
    }
    catch {
        if ($_.Exception.Message -like 'BF-897 BLOCKED: governed Butler database already exists*') {
            $overwriteBlocked = $true
        }
        else {
            throw
        }
    }
    if (-not $overwriteBlocked) {
        throw 'BF-897 BLOCKED: fresh-host restore did not refuse to overwrite an existing governed database.'
    }

    $env:LOCALAPPDATA = $sourceLocal
    [IO.File]::WriteAllText(($databasePath + '-wal'), 'synthetic-sidecar', [Text.Encoding]::ASCII)
    $sidecarBlocked = $false
    try {
        & $backupScript -DataDir $sourceData -OutputDir (Join-Path $tempRoot 'blocked-backups')
    }
    catch {
        if ($_.Exception.Message -like 'BF-897 BLOCKED: SQLite sidecar exists*') {
            $sidecarBlocked = $true
        }
        else {
            throw
        }
    }
    if (-not $sidecarBlocked) {
        throw 'BF-897 BLOCKED: backup did not refuse a source database with a SQLite sidecar.'
    }

    Write-Host 'Butler portable runtime-data round-trip acceptance (BF-897)'
    Write-Host 'Boundary: SYNTHETIC_PRIVATE_DATA_ONLY; NO_PROVIDER_CALL; NO_BUTLER_OR_SLEEPER_TRANSACTION_WRITE'
    Write-Host 'BF-897 RUNTIME DATA BACKUP/RESTORE ACCEPTANCE: PASS'
}
finally {
    if ([string]::IsNullOrWhiteSpace($originalLocalAppData)) {
        Remove-Item Env:LOCALAPPDATA -ErrorAction SilentlyContinue
    }
    else {
        $env:LOCALAPPDATA = $originalLocalAppData
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
