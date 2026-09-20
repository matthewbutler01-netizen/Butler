param(
    [string]$DataDir,
    [string]$OutputDir
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
                throw "BF-897 BLOCKED: Butler is running (PID $markerPid). Stop Butler before backing up runtime data."
            }
        }
        catch {
            if ($_.Exception.Message -like 'BF-897 BLOCKED:*') { throw }
        }
    }
}

function Assert-NoSqliteSidecars {
    param([Parameter(Mandatory = $true)][string]$DatabasePath)

    foreach ($suffix in @('-wal', '-shm', '-journal')) {
        $sidecar = $DatabasePath + $suffix
        if (Test-Path -LiteralPath $sidecar) {
            throw "BF-897 BLOCKED: SQLite sidecar exists at $sidecar. Stop all database users and checkpoint/close SQLite before backup."
        }
    }
}

function Assert-SqliteHeader {
    param([Parameter(Mandatory = $true)][string]$DatabasePath)

    $info = Get-Item -LiteralPath $DatabasePath -ErrorAction Stop
    if ($info.Length -lt 16) {
        throw 'BF-897 BLOCKED: governed Butler database is too small to be a SQLite database.'
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
        throw 'BF-897 BLOCKED: governed Butler database does not have the expected SQLite header.'
    }
}

$resolvedDataDir = if ([string]::IsNullOrWhiteSpace($DataDir)) {
    [IO.Path]::GetFullPath((Join-Path $configDir 'data'))
}
else {
    Resolve-ExternalPath -Value $DataDir -Label '-DataDir'
}
$resolvedOutputDir = if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    [IO.Path]::GetFullPath((Join-Path $configDir 'backups'))
}
else {
    Resolve-ExternalPath -Value $OutputDir -Label '-OutputDir'
}

$databasePath = Join-Path $resolvedDataDir 'butler.db'
$leagueConfigPath = Join-Path $configDir 'app-league.txt'

if (-not (Test-Path -LiteralPath $databasePath -PathType Leaf)) {
    throw "BF-897 BLOCKED: governed Butler database is missing at $databasePath"
}

Assert-ButlerStopped
Assert-NoSqliteSidecars -DatabasePath $databasePath
Assert-SqliteHeader -DatabasePath $databasePath

$leagueId = $null
if (Test-Path -LiteralPath $leagueConfigPath -PathType Leaf) {
    $rawLeague = [IO.File]::ReadAllText($leagueConfigPath, [Text.Encoding]::ASCII).Trim()
    $parsedLeague = [Guid]::Empty
    if ([string]::IsNullOrWhiteSpace($rawLeague) -or -not [Guid]::TryParse($rawLeague, [ref]$parsedLeague)) {
        throw 'BF-897 BLOCKED: saved Butler league selection is invalid.'
    }
    $leagueId = $parsedLeague.ToString('D').ToLowerInvariant()
}

[IO.Directory]::CreateDirectory($resolvedOutputDir) | Out-Null
$sourceHashBefore = (Get-FileHash -LiteralPath $databasePath -Algorithm SHA256).Hash.ToLowerInvariant()
$shortHash = $sourceHashBefore.Substring(0, 8)
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$archiveName = "Butler-runtime-data-$timestamp-$shortHash.zip"
$archivePath = Join-Path $resolvedOutputDir $archiveName
$archiveChecksumPath = $archivePath + '.sha256'
if ((Test-Path -LiteralPath $archivePath) -or (Test-Path -LiteralPath $archiveChecksumPath)) {
    throw "BF-897 BLOCKED: backup output already exists for this timestamp at $archivePath"
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf897-backup-{0}" -f [Guid]::NewGuid().ToString('N'))
$stageDir = Join-Path $tempRoot 'backup'
$tempArchive = Join-Path $resolvedOutputDir ("$archiveName.tmp-" + [Guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($stageDir) | Out-Null

    $stagedDatabase = Join-Path $stageDir 'butler.db'
    Copy-Item -LiteralPath $databasePath -Destination $stagedDatabase -ErrorAction Stop

    $sourceHashAfter = (Get-FileHash -LiteralPath $databasePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $stagedHash = (Get-FileHash -LiteralPath $stagedDatabase -Algorithm SHA256).Hash.ToLowerInvariant()
    $sourceLength = (Get-Item -LiteralPath $databasePath).Length
    $stagedLength = (Get-Item -LiteralPath $stagedDatabase).Length

    if ($sourceHashBefore -cne $sourceHashAfter -or $sourceHashBefore -cne $stagedHash -or $sourceLength -ne $stagedLength) {
        throw 'BF-897 BLOCKED: governed database changed during backup or the staged copy did not verify exactly.'
    }

    [IO.File]::WriteAllText(
        (Join-Path $stageDir 'butler.db.sha256'),
        ("{0}  butler.db`r`n" -f $stagedHash),
        [Text.Encoding]::ASCII
    )

    $leagueState = 'ABSENT'
    if ($null -ne $leagueId) {
        [IO.File]::WriteAllText((Join-Path $stageDir 'app-league.txt'), ($leagueId + "`r`n"), [Text.Encoding]::ASCII)
        $leagueState = 'PRESENT'
    }

    $manifestLines = @(
        'BUTLER_RUNTIME_DATA_BACKUP_V1',
        ("created_utc={0}" -f [DateTime]::UtcNow.ToString('o')),
        'database=butler.db',
        ("database_sha256={0}" -f $stagedHash),
        ("league_selection={0}" -f $leagueState),
        'boundary=PRIVATE_RUNTIME_DATA_BACKUP_CONTAINS_USER_DATA'
    )
    [IO.File]::WriteAllText(
        (Join-Path $stageDir 'backup.manifest.txt'),
        (($manifestLines -join "`r`n") + "`r`n"),
        [Text.Encoding]::ASCII
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $stageDir,
        $tempArchive,
        [System.IO.Compression.CompressionLevel]::Optimal,
        $false
    )

    $zip = [System.IO.Compression.ZipFile]::OpenRead($tempArchive)
    try {
        $entries = @($zip.Entries | Where-Object { -not [string]::IsNullOrEmpty($_.Name) } | ForEach-Object { $_.FullName.Replace('\', '/') })
        $required = @('butler.db', 'butler.db.sha256', 'backup.manifest.txt')
        foreach ($name in $required) {
            if ($entries -cnotcontains $name) {
                throw "BF-897 BLOCKED: backup archive is missing required entry $name"
            }
        }
        if ($leagueState -ceq 'PRESENT' -and $entries -cnotcontains 'app-league.txt') {
            throw 'BF-897 BLOCKED: backup archive lost the saved Butler league selection.'
        }
        $expectedCount = if ($leagueState -ceq 'PRESENT') { 4 } else { 3 }
        if ($entries.Count -ne $expectedCount) {
            throw "BF-897 BLOCKED: backup archive contains unexpected files; expected $expectedCount and found $($entries.Count)."
        }
    }
    finally {
        $zip.Dispose()
    }

    Move-Item -LiteralPath $tempArchive -Destination $archivePath -ErrorAction Stop
    $archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(
        $archiveChecksumPath,
        ("{0}  {1}`r`n" -f $archiveHash, $archiveName),
        [Text.Encoding]::ASCII
    )

    Write-Host 'Butler portable runtime-data backup (BF-897)'
    Write-Host "Database: $databasePath"
    Write-Host "Database SHA-256: $stagedHash"
    Write-Host "Backup: $archivePath"
    Write-Host "Backup SHA-256: $archiveHash"
    Write-Host "League selection: $leagueState"
    Write-Host 'Boundary: PRIVATE_RUNTIME_DATA_BACKUP_CONTAINS_USER_DATA; keep this archive private and separate from release evidence.'
    Write-Host 'BF-897 RUNTIME DATA BACKUP: PASS'
}
finally {
    if (Test-Path -LiteralPath $tempArchive) {
        Remove-Item -LiteralPath $tempArchive -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
