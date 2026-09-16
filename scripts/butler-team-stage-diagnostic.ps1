Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$betCliDir = Join-Path $repoRoot 'bet\bet-cli'
$runtimeLibDir = Join-Path $betCliDir 'build\install\bet-cli\lib'
$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-722 BLOCKED: LocalApplicationData is unavailable.'
}

$configDir = Join-Path $localAppData 'Butler'
$configPath = Join-Path $configDir 'app-league.txt'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-722 BLOCKED: Butler app league configuration is unavailable.'
}

$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
$parsedLeagueId = [Guid]::Empty
if ([string]::IsNullOrWhiteSpace($leagueId) -or -not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId)) {
    throw 'BF-722 BLOCKED: Butler app league configuration is invalid.'
}
$leagueId = $parsedLeagueId.ToString('D').ToLowerInvariant()

$configuredDataDir = [string]$env:BUTLER_APP_DATA_DIR
if ([string]::IsNullOrWhiteSpace($configuredDataDir)) {
    $dataDir = Join-Path $configDir 'data'
}
else {
    if (-not [IO.Path]::IsPathRooted($configuredDataDir)) {
        throw 'BF-722 BLOCKED: BUTLER_APP_DATA_DIR must be an absolute path.'
    }
    $dataDir = $configuredDataDir
}

$dataDir = [IO.Path]::GetFullPath($dataDir)
$sourceRoot = [IO.Path]::GetFullPath($repoRoot).TrimEnd('\')
$sourcePrefix = $sourceRoot + '\'
if ($dataDir.Equals($sourceRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    $dataDir.StartsWith($sourcePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'BF-722 BLOCKED: Butler timing diagnostic data directory must be outside the source/package tree.'
}

$databasePath = Join-Path $dataDir 'butler.db'
if (-not (Test-Path -LiteralPath $databasePath -PathType Leaf)) {
    throw "BF-722 BLOCKED: governed Butler runtime database is missing at $databasePath"
}

if (-not (Test-Path -LiteralPath $betCliDir -PathType Container)) {
    throw "BF-722 BLOCKED: bet-cli source directory not found at $betCliDir"
}
if (-not (Test-Path -LiteralPath $runtimeLibDir -PathType Container)) {
    throw "BF-722 BLOCKED: prepared Butler runtime library directory not found at $runtimeLibDir"
}

$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop | Sort-Object FullName)
if ($jars.Count -eq 0) {
    throw "BF-722 BLOCKED: prepared Butler runtime contains no jars at $runtimeLibDir"
}
$classpath = ($jars | ForEach-Object { $_.FullName }) -join ';'

$javaCommand = Get-Command java.exe -ErrorAction Stop
$java = $javaCommand.Source
if ([string]::IsNullOrWhiteSpace($java)) {
    throw 'BF-722 BLOCKED: java.exe could not be resolved.'
}

$previousPreference = $ErrorActionPreference
$lines = $null
$exitCode = $null
Push-Location $dataDir
try {
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classpath 'io.butler.bet.cli.ButlerMyTeamEvidenceBundleCli' $leagueId 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}
finally {
    Pop-Location
}

$textLines = @($lines | ForEach-Object { "$_" })
if ($exitCode -ne 0) {
    $tail = ($textLines -join ' ')
    $tail = [regex]::Replace($tail, '\s+', ' ').Trim()
    if ($tail.Length -gt 1200) {
        $tail = '...' + $tail.Substring($tail.Length - 1200)
    }
    throw "BF-722 BLOCKED: My Team read-only timing diagnostic exited with code $exitCode; output=$tail"
}

$timingLines = @($textLines | Where-Object { $_ -match '^===BUTLER_TEAM_TIMING:.*===$' })
if ($timingLines.Count -ne 1) {
    throw "BF-722 BLOCKED: expected exactly one My Team timing marker but found $($timingLines.Count)."
}

$match = [regex]::Match($timingLines[0], '^===BUTLER_TEAM_TIMING:(?<payload>database_ms=\d+;target_ms=\d+;analysis_wall_ms=\d+;roster_context_ms=\d+;team_context_ms=\d+;roster_strength_ms=\d+;positional_pressure_ms=\d+;team_posture_ms=\d+;future_capital_ms=\d+;render_ms=\d+;total_ms=\d+)===$')
if (-not $match.Success) {
    throw 'BF-722 BLOCKED: My Team timing marker does not match the expected contract.'
}

$payload = $match.Groups['payload'].Value.Replace(';', '; ')
Write-Host ''
Write-Host ('My Team stage timing (diagnostic, outside BF-688): ' + $payload)
Write-Host ('BF-722 data: ' + $dataDir)
Write-Host 'BF-722 diagnostic boundary: read-only My Team evidence only; no /refresh and no Butler or Sleeper write path is invoked.'
