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
    throw 'BF-733 BLOCKED: LocalApplicationData is unavailable.'
}

$configPath = Join-Path (Join-Path $localAppData 'Butler') 'app-league.txt'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-733 BLOCKED: Butler app league configuration is unavailable.'
}

$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
$parsedLeagueId = [Guid]::Empty
if ([string]::IsNullOrWhiteSpace($leagueId) -or -not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId)) {
    throw 'BF-733 BLOCKED: Butler app league configuration is invalid.'
}
$leagueId = $parsedLeagueId.ToString('D').ToLowerInvariant()

if (-not (Test-Path -LiteralPath $betCliDir -PathType Container)) {
    throw "BF-733 BLOCKED: bet-cli working directory not found at $betCliDir"
}
if (-not (Test-Path -LiteralPath $runtimeLibDir -PathType Container)) {
    throw "BF-733 BLOCKED: prepared Butler runtime library directory not found at $runtimeLibDir"
}

$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop | Sort-Object FullName)
if ($jars.Count -eq 0) {
    throw "BF-733 BLOCKED: prepared Butler runtime contains no jars at $runtimeLibDir"
}
$classpath = ($jars | ForEach-Object { $_.FullName }) -join ';'

$javaCommand = Get-Command java.exe -ErrorAction Stop
$java = $javaCommand.Source
if ([string]::IsNullOrWhiteSpace($java)) {
    throw 'BF-733 BLOCKED: java.exe could not be resolved.'
}

$previousPreference = $ErrorActionPreference
$lines = $null
$exitCode = $null
Push-Location $betCliDir
try {
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classpath 'io.butler.bet.cli.ButlerWarmedSlowRouteStageDiagnosticCli' $leagueId 2>&1
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
    throw "BF-760 BLOCKED: warmed slow-route read-only timing diagnostic exited with code $exitCode; output=$tail"
}

$prewarmLines = @($textLines | Where-Object { $_ -match '^===BUTLER_SLOW_ROUTE_TRANSPORT_PREWARM:.*===$' })
if ($prewarmLines.Count -ne 1) {
    throw "BF-760 BLOCKED: expected exactly one Sleeper transport prewarm marker but found $($prewarmLines.Count)."
}
$prewarmPattern = '^===BUTLER_SLOW_ROUTE_TRANSPORT_PREWARM:state=SUCCESS;elapsed_ms=(?<elapsed>\d+)===$'
$prewarmMatch = [regex]::Match($prewarmLines[0], $prewarmPattern)
if (-not $prewarmMatch.Success) {
    throw 'BF-760 BLOCKED: Sleeper transport prewarm marker does not match the expected success contract.'
}
$prewarmElapsedMs = [long]$prewarmMatch.Groups['elapsed'].Value

$timingLines = @($textLines | Where-Object { $_ -match '^===BUTLER_SLOW_ROUTE_TIMING:.*===$' })
if ($timingLines.Count -ne 1) {
    throw "BF-733 BLOCKED: expected exactly one slow-route timing marker but found $($timingLines.Count)."
}

$pattern = '^===BUTLER_SLOW_ROUTE_TIMING:(?<payload>database_ms=\d+;target_ms=\d+;summary_ms=\d+;comparison_ms=\d+;comparison_methodology_ms=\d+;comparison_candidate_frame_ms=\d+;comparison_roster_frame_ms=\d+;comparison_production_load_ms=\d+;comparison_residual_ms=\d+;roster_context_ms=\d+;waiver_wall_ms=\d+;home_evidence_ms=\d+;waiver_evidence_ms=\d+;league_action_plan_ms=\d+;league_rankings_ms=\d+;league_movement_ms=\d+;league_evidence_ms=\d+;total_ms=\d+)===$'
$match = [regex]::Match($timingLines[0], $pattern)
if (-not $match.Success) {
    throw 'BF-736 BLOCKED: slow-route timing marker does not match the expected comparison-substage contract.'
}

$payload = $match.Groups['payload'].Value.Replace(';', '; ')
Write-Host ''
Write-Host ("BF-760 Sleeper transport prewarm (same JVM, outside target_ms): state=SUCCESS; elapsed_ms={0}" -f $prewarmElapsedMs)
Write-Host ('Slow-route stage timing (warmed-transport diagnostic, outside BF-688): ' + $payload)
Write-Host 'BF-760 diagnostic boundary: BF-748-equivalent shared Sleeper transport is prewarmed once in the same JVM before unchanged BF-623 verification; response discarded; no provider payload caching or concurrency; /refresh excluded; no Butler or Sleeper write path is invoked.'
Write-Host 'BF-736 comparison diagnostic boundary: read-only existing evidence only; BF-615 source order preserved; /refresh excluded; no Butler or Sleeper write path is invoked.'
