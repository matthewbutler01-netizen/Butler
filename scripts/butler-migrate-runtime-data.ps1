param(
    [string]$DataDir,
    [string]$SourceDatabase
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
    throw 'BF-770 BLOCKED: LocalApplicationData is unavailable.'
}

$configDir = Join-Path $localAppData 'Butler'
$resolvedDataDir = if ([string]::IsNullOrWhiteSpace($DataDir)) {
    Join-Path $configDir 'data'
}
else {
    if (-not [IO.Path]::IsPathRooted($DataDir)) {
        throw 'BF-770 BLOCKED: -DataDir must be an absolute path.'
    }
    [IO.Path]::GetFullPath($DataDir)
}
$resolvedDataDir = [IO.Path]::GetFullPath($resolvedDataDir)
$sourceRoot = [IO.Path]::GetFullPath($repoRoot).TrimEnd('\')
$sourcePrefix = $sourceRoot + '\'
if ($resolvedDataDir.Equals($sourceRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    $resolvedDataDir.StartsWith($sourcePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'BF-770 BLOCKED: runtime data directory must be outside the source/package tree.'
}

$sourcePath = if ([string]::IsNullOrWhiteSpace($SourceDatabase)) {
    Join-Path $repoRoot 'bet\bet-cli\butler.db'
}
else {
    if (-not [IO.Path]::IsPathRooted($SourceDatabase)) {
        throw 'BF-770 BLOCKED: -SourceDatabase must be an absolute path.'
    }
    [IO.Path]::GetFullPath($SourceDatabase)
}
$sourcePath = [IO.Path]::GetFullPath($sourcePath)
$targetPath = Join-Path $resolvedDataDir 'butler.db'

if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    throw "BF-770 BLOCKED: legacy Butler database not found at $sourcePath"
}
if (Test-Path -LiteralPath $targetPath) {
    throw "BF-770 BLOCKED: governed Butler database already exists at $targetPath; migration will not overwrite it."
}

if (Test-Path -LiteralPath $configDir -PathType Container) {
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
                throw "BF-770 BLOCKED: Butler is running (PID $markerPid). Stop Butler before migrating its database."
            }
        }
        catch {
            if ($_.Exception.Message -like 'BF-770 BLOCKED:*') { throw }
        }
    }
}

foreach ($suffix in @('-wal', '-shm', '-journal')) {
    $sidecar = $sourcePath + $suffix
    if (Test-Path -LiteralPath $sidecar) {
        throw "BF-770 BLOCKED: SQLite sidecar exists at $sidecar. Stop all database users and checkpoint/close SQLite before migration."
    }
}

$sourceInfo = Get-Item -LiteralPath $sourcePath -ErrorAction Stop
if ($sourceInfo.Length -lt 16) {
    throw 'BF-770 BLOCKED: legacy Butler database is too small to be a SQLite database.'
}
$header = New-Object byte[] 16
$stream = [IO.File]::Open($sourcePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
try {
    $read = $stream.Read($header, 0, $header.Length)
}
finally {
    $stream.Dispose()
}
if ($read -ne 16 -or [Text.Encoding]::ASCII.GetString($header) -cne "SQLite format 3`0") {
    throw 'BF-770 BLOCKED: legacy Butler database does not have the expected SQLite header.'
}

[IO.Directory]::CreateDirectory($resolvedDataDir) | Out-Null
$tempPath = Join-Path $resolvedDataDir ("butler.db.bf770-{0}.tmp" -f [Guid]::NewGuid().ToString('N'))
try {
    $sourceLengthBefore = (Get-Item -LiteralPath $sourcePath).Length
    $sourceHashBefore = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()

    Copy-Item -LiteralPath $sourcePath -Destination $tempPath -ErrorAction Stop

    $sourceLengthAfter = (Get-Item -LiteralPath $sourcePath).Length
    $sourceHashAfter = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $copyLength = (Get-Item -LiteralPath $tempPath).Length
    $copyHash = (Get-FileHash -LiteralPath $tempPath -Algorithm SHA256).Hash.ToLowerInvariant()

    if ($sourceLengthBefore -ne $sourceLengthAfter -or $sourceLengthBefore -ne $copyLength) {
        throw 'BF-770 BLOCKED: legacy database length changed during migration or did not copy exactly.'
    }
    if ($sourceHashBefore -cne $sourceHashAfter -or $sourceHashBefore -cne $copyHash) {
        throw 'BF-770 BLOCKED: legacy database changed during migration or copy hash verification failed.'
    }
    if (Test-Path -LiteralPath $targetPath) {
        throw "BF-770 BLOCKED: governed Butler database appeared during migration at $targetPath; refusing overwrite."
    }

    Move-Item -LiteralPath $tempPath -Destination $targetPath -ErrorAction Stop
    $targetHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($targetHash -cne $sourceHashBefore) {
        throw 'BF-770 BLOCKED: final governed database hash does not match the legacy source.'
    }

    Write-Host 'Butler runtime-data migration (BF-770)'
    Write-Host "Source: $sourcePath"
    Write-Host "Target: $targetPath"
    Write-Host "SHA-256: $targetHash"
    Write-Host 'Legacy source retained unchanged for rollback.'
    Write-Host 'BF-770 RUNTIME DATA MIGRATION: PASS'
}
finally {
    if (Test-Path -LiteralPath $tempPath) {
        Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
    }
}
