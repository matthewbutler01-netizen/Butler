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
    throw 'BF-745 BLOCKED: LocalApplicationData is unavailable.'
}

$configPath = Join-Path (Join-Path $localAppData 'Butler') 'app-league.txt'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-745 BLOCKED: Butler app league configuration is unavailable.'
}

$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
$parsedLeagueId = [Guid]::Empty
if ([string]::IsNullOrWhiteSpace($leagueId) -or -not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId)) {
    throw 'BF-745 BLOCKED: Butler app league configuration is invalid.'
}
$leagueId = $parsedLeagueId.ToString('D').ToLowerInvariant()

if (-not (Test-Path -LiteralPath $betCliDir -PathType Container)) {
    throw "BF-745 BLOCKED: bet-cli working directory not found at $betCliDir"
}
if (-not (Test-Path -LiteralPath $runtimeLibDir -PathType Container)) {
    throw "BF-745 BLOCKED: prepared Butler runtime library directory not found at $runtimeLibDir"
}

$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop | Sort-Object FullName)
if ($jars.Count -eq 0) {
    throw "BF-745 BLOCKED: prepared Butler runtime contains no jars at $runtimeLibDir"
}
$classpath = ($jars | ForEach-Object { $_.FullName }) -join ';'

$javaCommand = Get-Command java.exe -ErrorAction Stop
$java = $javaCommand.Source
if ([string]::IsNullOrWhiteSpace($java)) {
    throw 'BF-745 BLOCKED: java.exe could not be resolved.'
}

$previousPreference = $ErrorActionPreference
$lines = $null
$exitCode = $null
Push-Location $betCliDir
try {
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classpath `
            'io.butler.bet.sleeper.SleeperTransportPrewarmDiagnostic' $leagueId 2>&1
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
    throw "BF-745 BLOCKED: transport-prewarm read-only timing diagnostic exited with code $exitCode; output=$tail"
}

$timingLines = @($textLines | Where-Object { $_ -match '^===BUTLER_TRANSPORT_PREWARM_TIMING:.*===$' })
if ($timingLines.Count -ne 1) {
    throw "BF-745 BLOCKED: expected exactly one transport-prewarm timing marker but found $($timingLines.Count)."
}

$pattern = '^===BUTLER_TRANSPORT_PREWARM_TIMING:warmup_ms=(?<warmup>\d+);user_ms=(?<user>\d+);user_leagues_ms=(?<userLeagues>\d+);league_ms=(?<league>\d+);rosters_ms=(?<rosters>\d+);users_ms=(?<users>\d+);provider_sum_ms=(?<providerSum>\d+);verify_wall_ms=(?<verifyWall>\d+);residual_ms=(?<residual>\d+)===$'
$match = [regex]::Match($timingLines[0], $pattern)
if (-not $match.Success) {
    throw 'BF-745 BLOCKED: transport-prewarm timing marker does not match the expected contract.'
}

Write-Host ''
Write-Host 'BF-745 fresh-JVM Sleeper transport prewarm timing:'
Write-Host ("  warmup_ms={0}; user_ms={1}; user_leagues_ms={2}; league_ms={3}; rosters_ms={4}; users_ms={5}; provider_sum_ms={6}; verify_wall_ms={7}; residual_ms={8}" -f `
    $match.Groups['warmup'].Value,
    $match.Groups['user'].Value,
    $match.Groups['userLeagues'].Value,
    $match.Groups['league'].Value,
    $match.Groups['rosters'].Value,
    $match.Groups['users'].Value,
    $match.Groups['providerSum'].Value,
    $match.Groups['verifyWall'].Value,
    $match.Groups['residual'].Value)
Write-Host 'BF-745 diagnostic boundary: exactly one discarded read-only state/nfl prewarm precedes unchanged serial BF-623 verification; no cached provider data, no concurrency, /refresh excluded, and no Butler or Sleeper write path is invoked.'
