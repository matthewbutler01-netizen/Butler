param(
    [ValidateSet('baseline', 'prewarm')]
    [string]$Mode = 'baseline'
)

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
    throw 'BF-747 BLOCKED: LocalApplicationData is unavailable.'
}

$configPath = Join-Path (Join-Path $localAppData 'Butler') 'app-league.txt'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-747 BLOCKED: Butler app league configuration is unavailable.'
}

$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
$parsedLeagueId = [Guid]::Empty
if ([string]::IsNullOrWhiteSpace($leagueId) -or -not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId)) {
    throw 'BF-747 BLOCKED: Butler app league configuration is invalid.'
}
$leagueId = $parsedLeagueId.ToString('D').ToLowerInvariant()

if (-not (Test-Path -LiteralPath $betCliDir -PathType Container)) {
    throw "BF-747 BLOCKED: bet-cli working directory not found at $betCliDir"
}
if (-not (Test-Path -LiteralPath $runtimeLibDir -PathType Container)) {
    throw "BF-747 BLOCKED: prepared Butler runtime library directory not found at $runtimeLibDir"
}

$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop | Sort-Object FullName)
if ($jars.Count -eq 0) {
    throw "BF-747 BLOCKED: prepared Butler runtime contains no jars at $runtimeLibDir"
}
$classpath = ($jars | ForEach-Object { $_.FullName }) -join ';'

$javaCommand = Get-Command java.exe -ErrorAction Stop
$java = $javaCommand.Source
if ([string]::IsNullOrWhiteSpace($java)) {
    throw 'BF-747 BLOCKED: java.exe could not be resolved.'
}

$previousPreference = $ErrorActionPreference
$lines = $null
$exitCode = $null
Push-Location $betCliDir
try {
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classpath `
            'io.butler.bet.sleeper.SleeperSameClientTransportDiagnostic' $leagueId $Mode 2>&1
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
    throw "BF-747 BLOCKED: same-client transport diagnostic exited with code $exitCode; output=$tail"
}

$timingLines = @($textLines | Where-Object { $_ -match '^===BUTLER_SAME_CLIENT_TRANSPORT_TIMING:.*===$' })
if ($timingLines.Count -ne 1) {
    throw "BF-747 BLOCKED: expected exactly one same-client transport timing marker but found $($timingLines.Count)."
}

$pattern = '^===BUTLER_SAME_CLIENT_TRANSPORT_TIMING:mode=(?<mode>baseline|prewarm);warmup_ms=(?<warmup>\d+);user_ms=(?<user>\d+);user_leagues_ms=(?<userLeagues>\d+);league_ms=(?<league>\d+);rosters_ms=(?<rosters>\d+);users_ms=(?<users>\d+);provider_sum_ms=(?<providerSum>\d+);verify_wall_ms=(?<verifyWall>\d+);residual_ms=(?<residual>\d+)===$'
$match = [regex]::Match($timingLines[0], $pattern)
if (-not $match.Success) {
    throw 'BF-747 BLOCKED: same-client transport timing marker does not match the expected contract.'
}
if ($match.Groups['mode'].Value -ne $Mode) {
    throw "BF-747 BLOCKED: diagnostic mode mismatch; requested=$Mode observed=$($match.Groups['mode'].Value)"
}

Write-Host ''
Write-Host ("BF-747 fresh-JVM same-client Sleeper transport timing ({0}):" -f $Mode)
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
Write-Host 'BF-747 diagnostic boundary: optional discarded read-only state/nfl prewarm and unchanged serial BF-623 verification share the same SleeperClient; no cached provider data, no concurrency, /refresh excluded, and no Butler or Sleeper write path is invoked.'
