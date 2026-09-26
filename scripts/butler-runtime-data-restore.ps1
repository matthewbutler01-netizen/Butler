param(
    [Parameter(Mandatory = $true)]
    [string]$BackupZip,

    [string]$DataDir,

    [switch]$ValidateLeague,

    [string]$LeagueId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$localAppData = [string]$env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-897 BLOCKED: LocalApplicationData is unavailable.'
}

$configDir = Join-Path $localAppData 'Butler'
if (-not $ValidateLeague -and -not [string]::IsNullOrWhiteSpace($LeagueId)) {
    throw 'BF-897 BLOCKED: -LeagueId requires -ValidateLeague.'
}

function Resolve-ExternalPath {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if (-not [IO.Path]::IsPathRooted($Value)) {
        throw "BF-897 BLOCKED: $Label must be an absolute path."
    }
    $resolved = [IO.Path]::GetFullPath($Value)
    $sourceRoot = [IO.Path]::GetFullPath($repoRoot).TrimEnd('\')
    $sourcePrefix = $sourceRoot + '\'
    if ($resolved.Equals($sourceRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $resolved.StartsWith($sourcePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "BF-897 BLOCKED: $Label must remain outside the source/package tree."
    }
    return $resolved
}

function Assert-ButlerStopped {
    if (-not (Test-Path -LiteralPath $configDir -PathType Container)) { return }

    foreach ($marker in @(Get-ChildItem -LiteralPath $configDir -Filter 'running-port-*.txt' -File -ErrorAction SilentlyContinue)) {
        try {
            $raw = [IO.File]::ReadAllText($marker.FullName, [Text.Encoding]::ASCII).Trim()
            $parts = $raw.Split('|')
            if ($parts.Length -ne 3) { continue }

            $markerPid = 0
            $markerStartTicks = 0L
            if (-not [int]::TryParse($parts[0], [ref]$markerPid)) { continue }
            if (-not [long]::TryParse($parts[1], [ref]$markerStartTicks)) { continue }

            $process = Get-Process -Id $markerPid -ErrorAction SilentlyContinue
            if ($null -eq $process) { continue }
            if ($process.StartTime.ToUniversalTime().Ticks -eq $markerStartTicks) {
                throw "BF-897 BLOCKED: Butler is running (PID $markerPid). Stop Butler before restoring runtime data."
            }
        }
        catch {
            if ($_.Exception.Message -like 'BF-897 BLOCKED:*') { throw }
        }
    }
}

function Assert-SqliteHeader {
    param([Parameter(Mandatory = $true)][string]$DatabasePath)

    $info = Get-Item -LiteralPath $DatabasePath -ErrorAction Stop
    if ($info.Length -lt 16) {
        throw 'BF-897 BLOCKED: backup database is too small to be a SQLite database.'
    }

    $header = New-Object byte[] 16
    $stream = [IO.File]::Open($DatabasePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        $read = $stream.Read($header, 0, $header.Length)
    }
    finally {
        $stream.Dispose()
    }
    if ($read -ne 16 -or [Text.Encoding]::ASCII.GetString($header) -cne "SQLite format 3`0") {
        throw 'BF-897 BLOCKED: backup database does not have the expected SQLite header.'
    }
}

$resolvedBackupZip = Resolve-ExternalPath -Value $BackupZip -Label '-BackupZip'
if (-not (Test-Path -LiteralPath $resolvedBackupZip -PathType Leaf)) {
    throw "BF-897 BLOCKED: backup archive not found at $resolvedBackupZip"
}
$backupChecksumPath = $resolvedBackupZip + '.sha256'
if (-not (Test-Path -LiteralPath $backupChecksumPath -PathType Leaf)) {
    throw "BF-897 BLOCKED: backup checksum sidecar not found at $backupChecksumPath"
}

$checksumText = [IO.File]::ReadAllText($backupChecksumPath, [Text.Encoding]::ASCII).Trim()
if ($checksumText -notmatch '^([0-9a-fA-F]{64})\s{2}(.+)$') {
    throw 'BF-897 BLOCKED: backup checksum sidecar format is invalid.'
}
$expectedArchiveHash = $Matches[1].ToLowerInvariant()
$expectedArchiveName = $Matches[2]
if ($expectedArchiveName -cne [IO.Path]::GetFileName($resolvedBackupZip)) {
    throw 'BF-897 BLOCKED: backup checksum sidecar names a different archive.'
}
$actualArchiveHash = (Get-FileHash -LiteralPath $resolvedBackupZip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualArchiveHash -cne $expectedArchiveHash) {
    throw 'BF-897 BLOCKED: backup archive SHA-256 does not match its checksum sidecar.'
}

$resolvedDataDir = if ([string]::IsNullOrWhiteSpace($DataDir)) {
    Resolve-ExternalPath -Value (Join-Path $configDir 'data') -Label 'default data directory'
}
else {
    Resolve-ExternalPath -Value $DataDir -Label '-DataDir'
}
$targetDatabase = Join-Path $resolvedDataDir 'butler.db'
$leagueConfigPath = Join-Path $configDir 'app-league.txt'

Assert-ButlerStopped
if (Test-Path -LiteralPath $targetDatabase) {
    throw "BF-897 BLOCKED: governed Butler database already exists at $targetDatabase; fresh-host restore will not overwrite it."
}
foreach ($suffix in @('-wal', '-shm', '-journal')) {
    $sidecar = $targetDatabase + $suffix
    if (Test-Path -LiteralPath $sidecar) {
        throw "BF-897 BLOCKED: SQLite sidecar exists at $sidecar; restore will not proceed."
    }
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf897-restore-{0}" -f [Guid]::NewGuid().ToString('N'))
$tempTarget = $null
try {
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    Expand-Archive -LiteralPath $resolvedBackupZip -DestinationPath $tempRoot -Force

    $files = @(Get-ChildItem -LiteralPath $tempRoot -File -ErrorAction Stop)
    $names = @($files.Name)
    foreach ($required in @('butler.db', 'butler.db.sha256', 'backup.manifest.txt')) {
        if ($names -cnotcontains $required) {
            throw "BF-897 BLOCKED: backup archive is missing required entry $required"
        }
    }
    foreach ($name in $names) {
        if (@('butler.db', 'butler.db.sha256', 'backup.manifest.txt', 'app-league.txt') -cnotcontains $name) {
            throw "BF-897 BLOCKED: backup archive contains unexpected entry $name"
        }
    }

    $manifestPath = Join-Path $tempRoot 'backup.manifest.txt'
    $manifestLines = @(Get-Content -LiteralPath $manifestPath -Encoding ASCII)
    if ($manifestLines.Count -ne 6 -or $manifestLines[0] -cne 'BUTLER_RUNTIME_DATA_BACKUP_V1') {
        throw 'BF-897 BLOCKED: backup manifest schema/version is invalid.'
    }
    $manifest = @{}
    for ($i = 1; $i -lt $manifestLines.Count; $i++) {
        if ($manifestLines[$i] -notmatch '^([a-z0-9_]+)=(.+)$') {
            throw "BF-897 BLOCKED: malformed backup manifest line: $($manifestLines[$i])"
        }
        if ($manifest.ContainsKey($Matches[1])) {
            throw "BF-897 BLOCKED: duplicate backup manifest key: $($Matches[1])"
        }
        $manifest[$Matches[1]] = $Matches[2]
    }
    foreach ($key in @('created_utc', 'database', 'database_sha256', 'league_selection', 'boundary')) {
        if (-not $manifest.ContainsKey($key)) {
            throw "BF-897 BLOCKED: backup manifest is missing required key $key"
        }
    }
    if ([string]$manifest['database'] -cne 'butler.db') {
        throw 'BF-897 BLOCKED: backup manifest database name is invalid.'
    }
    if ([string]$manifest['database_sha256'] -notmatch '^[0-9a-f]{64}$') {
        throw 'BF-897 BLOCKED: backup manifest database SHA-256 is invalid.'
    }
    if (@('PRESENT', 'ABSENT') -cnotcontains [string]$manifest['league_selection']) {
        throw 'BF-897 BLOCKED: backup manifest league-selection state is invalid.'
    }
    if ([string]$manifest['boundary'] -cne 'PRIVATE_RUNTIME_DATA_BACKUP_CONTAINS_USER_DATA') {
        throw 'BF-897 BLOCKED: backup manifest private-data boundary is invalid.'
    }

    $stagedDatabase = Join-Path $tempRoot 'butler.db'
    $databaseChecksumText = [IO.File]::ReadAllText((Join-Path $tempRoot 'butler.db.sha256'), [Text.Encoding]::ASCII).Trim()
    if ($databaseChecksumText -notmatch '^([0-9a-fA-F]{64})\s{2}butler\.db$') {
        throw 'BF-897 BLOCKED: database checksum file format is invalid.'
    }
    $databaseChecksum = $Matches[1].ToLowerInvariant()
    $actualDatabaseHash = (Get-FileHash -LiteralPath $stagedDatabase -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($databaseChecksum -cne $actualDatabaseHash -or [string]$manifest['database_sha256'] -cne $actualDatabaseHash) {
        throw 'BF-897 BLOCKED: backup database hash does not match its checksum and manifest.'
    }
    Assert-SqliteHeader -DatabasePath $stagedDatabase

    $leagueState = [string]$manifest['league_selection']
    $stagedLeaguePath = Join-Path $tempRoot 'app-league.txt'
    $restoredLeagueId = $null
    if ($leagueState -ceq 'PRESENT') {
        if (-not (Test-Path -LiteralPath $stagedLeaguePath -PathType Leaf)) {
            throw 'BF-897 BLOCKED: backup manifest requires a saved league selection but app-league.txt is missing.'
        }
        $rawLeague = [IO.File]::ReadAllText($stagedLeaguePath, [Text.Encoding]::ASCII).Trim()
        $parsedLeague = [Guid]::Empty
        if ([string]::IsNullOrWhiteSpace($rawLeague) -or -not [Guid]::TryParse($rawLeague, [ref]$parsedLeague)) {
            throw 'BF-897 BLOCKED: backup saved league selection is invalid.'
        }
        $restoredLeagueId = $parsedLeague.ToString('D').ToLowerInvariant()

        if (Test-Path -LiteralPath $leagueConfigPath -PathType Leaf) {
            $existingLeague = [IO.File]::ReadAllText($leagueConfigPath, [Text.Encoding]::ASCII).Trim().ToLowerInvariant()
            if ($existingLeague -cne $restoredLeagueId) {
                throw 'BF-897 BLOCKED: target machine already has a different Butler league selection; restore will not overwrite it.'
            }
        }
    }
    elseif (Test-Path -LiteralPath $stagedLeaguePath) {
        throw 'BF-897 BLOCKED: backup contains app-league.txt while the manifest declares league selection absent.'
    }

    if ($ValidateLeague) {
        if (-not [string]::IsNullOrWhiteSpace($LeagueId)) {
            $requested = [Guid]::Empty
            if (-not [Guid]::TryParse($LeagueId, [ref]$requested)) { throw 'BF-897 BLOCKED: -LeagueId must be a UUID.' }
            $requestedId = $requested.ToString('D').ToLowerInvariant()
            if ($null -ne $restoredLeagueId -and $restoredLeagueId -cne $requestedId) { throw 'BF-897 BLOCKED: -LeagueId conflicts with the backup selection; choose the matching backup.' }
            $restoredLeagueId = $requestedId
        }
        if (Test-Path -LiteralPath $leagueConfigPath) {
            $existing = [IO.File]::ReadAllText($leagueConfigPath).Trim().ToLowerInvariant()
            $parsedExisting = [Guid]::Empty
            if (-not [Guid]::TryParse($existing, [ref]$parsedExisting)) { throw 'BF-897 BLOCKED: existing league selection is invalid; preserve and review it before retrying.' }
            $existing = $parsedExisting.ToString('D').ToLowerInvariant()
            if ($null -ne $restoredLeagueId -and $existing -cne $restoredLeagueId) { throw 'BF-897 BLOCKED: target machine already has a different Butler league selection; restore will not overwrite it.' }
            $restoredLeagueId = $existing
        }
        if ($null -eq $restoredLeagueId) { throw 'BF-897 BLOCKED: backup has no saved league selection. Supply -LeagueId with the Butler UUID from the source installation.' }
        $java = & (Join-Path $scriptDir 'butler-java-preflight.ps1') -PassThru
        $lib = Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli\lib'
        if (-not (Test-Path -LiteralPath $lib -PathType Container)) { throw 'BF-897 BLOCKED: prebuilt runtime is missing. Use the extracted Butler runtime package.' }
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $probe = @(& $java.Executable '-cp' (Join-Path $lib '*') 'io.butler.bet.cli.ButlerSetupLeagueCheckCli' $stagedDatabase $restoredLeagueId 2>&1)
            $probeExit = $LASTEXITCODE
        }
        finally { $ErrorActionPreference = $oldPreference }
        if ($probeExit -ne 0 -or ($probe -join "`n") -notmatch 'BUTLER SETUP LEAGUE: VERIFIED') {
            throw ('BF-897 BLOCKED: league validation failed before restore. ' + (($probe | Select-Object -First 8) -join ' '))
        }
        Write-Host 'BUTLER SETUP LEAGUE: VERIFIED'
    }

    [IO.Directory]::CreateDirectory($resolvedDataDir) | Out-Null
    $tempTarget = Join-Path $resolvedDataDir ("butler.db.bf897-{0}.tmp" -f [Guid]::NewGuid().ToString('N'))
    Copy-Item -LiteralPath $stagedDatabase -Destination $tempTarget -ErrorAction Stop
    $targetTempHash = (Get-FileHash -LiteralPath $tempTarget -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($targetTempHash -cne $actualDatabaseHash) {
        throw 'BF-897 BLOCKED: restored temporary database copy failed hash verification.'
    }
    if (Test-Path -LiteralPath $targetDatabase) {
        throw "BF-897 BLOCKED: governed Butler database appeared during restore at $targetDatabase; refusing overwrite."
    }

    Assert-ButlerStopped
    Move-Item -LiteralPath $tempTarget -Destination $targetDatabase -ErrorAction Stop
    $tempTarget = $null
    $finalHash = (Get-FileHash -LiteralPath $targetDatabase -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($finalHash -cne $actualDatabaseHash) {
        throw 'BF-897 BLOCKED: final restored database hash does not match the verified backup.'
    }

    if ($null -ne $restoredLeagueId -and -not (Test-Path -LiteralPath $leagueConfigPath -PathType Leaf)) {
        [IO.Directory]::CreateDirectory($configDir) | Out-Null
        $leagueTemp = $leagueConfigPath + '.bf897.tmp'
        try {
            [IO.File]::WriteAllText($leagueTemp, ($restoredLeagueId + "`r`n"), [Text.Encoding]::ASCII)
            Move-Item -LiteralPath $leagueTemp -Destination $leagueConfigPath -ErrorAction Stop
        }
        finally {
            if (Test-Path -LiteralPath $leagueTemp) {
                Remove-Item -LiteralPath $leagueTemp -Force -ErrorAction SilentlyContinue
            }
        }
    }

    Write-Host 'Butler fresh-host runtime-data restore (BF-897)'
    Write-Host "Backup: $resolvedBackupZip"
    Write-Host "Backup SHA-256: $actualArchiveHash"
    Write-Host "Database: $targetDatabase"
    Write-Host "Database SHA-256: $finalHash"
    Write-Host "League selection: $leagueState"
    if ($ValidateLeague) { Write-Host "Verified selected league: $restoredLeagueId" }
    Write-Host 'Boundary: FRESH_HOST_ONLY; VERIFIED_PRIVATE_RUNTIME_DATA; NEVER_OVERWRITE_EXISTING_GOVERNED_DATABASE.'
    Write-Host 'BF-897 RUNTIME DATA RESTORE: PASS'
}
finally {
    if ($null -ne $tempTarget -and (Test-Path -LiteralPath $tempTarget)) {
        Remove-Item -LiteralPath $tempTarget -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
