param(
    [ValidateRange(1, 9)]
    [int]$Samples = 3
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
    throw 'BF-744 BLOCKED: LocalApplicationData is unavailable.'
}

$configPath = Join-Path (Join-Path $localAppData 'Butler') 'app-league.txt'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'BF-744 BLOCKED: Butler app league configuration is unavailable.'
}

$leagueId = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
$parsedLeagueId = [Guid]::Empty
if ([string]::IsNullOrWhiteSpace($leagueId) -or -not [Guid]::TryParse($leagueId, [ref]$parsedLeagueId)) {
    throw 'BF-744 BLOCKED: Butler app league configuration is invalid.'
}
$leagueId = $parsedLeagueId.ToString('D').ToLowerInvariant()

if (-not (Test-Path -LiteralPath $betCliDir -PathType Container)) {
    throw "BF-744 BLOCKED: bet-cli working directory not found at $betCliDir"
}
if (-not (Test-Path -LiteralPath $runtimeLibDir -PathType Container)) {
    throw "BF-744 BLOCKED: prepared Butler runtime library directory not found at $runtimeLibDir"
}

$jars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop | Sort-Object FullName)
if ($jars.Count -eq 0) {
    throw "BF-744 BLOCKED: prepared Butler runtime contains no jars at $runtimeLibDir"
}
$classpath = ($jars | ForEach-Object { $_.FullName }) -join ';'

$javaCommand = Get-Command java.exe -ErrorAction Stop
$java = $javaCommand.Source
if ([string]::IsNullOrWhiteSpace($java)) {
    throw 'BF-744 BLOCKED: java.exe could not be resolved.'
}

$previousPreference = $ErrorActionPreference
$lines = $null
$exitCode = $null
Push-Location $betCliDir
try {
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classpath `
            'io.butler.bet.sleeper.SleeperPersonalizedTargetStageDiagnostic' $leagueId $Samples 2>&1
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
    throw "BF-744 BLOCKED: target-stage read-only timing diagnostic exited with code $exitCode; output=$tail"
}

$timingLines = @($textLines | Where-Object { $_ -match '^===BUTLER_TARGET_STAGE_TIMING:.*===$' })
if ($timingLines.Count -ne $Samples) {
    throw "BF-744 BLOCKED: expected $Samples target-stage timing markers but found $($timingLines.Count)."
}

$pattern = '^===BUTLER_TARGET_STAGE_TIMING:sample=(?<sample>\d+);user_ms=(?<user>\d+);user_leagues_ms=(?<userLeagues>\d+);league_ms=(?<league>\d+);rosters_ms=(?<rosters>\d+);users_ms=(?<users>\d+);provider_sum_ms=(?<providerSum>\d+);verify_wall_ms=(?<verifyWall>\d+);residual_ms=(?<residual>\d+)===$'
$rows = @()
for ($index = 0; $index -lt $timingLines.Count; $index++) {
    $match = [regex]::Match($timingLines[$index], $pattern)
    if (-not $match.Success) {
        throw "BF-744 BLOCKED: timing marker $($index + 1) does not match the expected contract."
    }
    $sample = [int]$match.Groups['sample'].Value
    if ($sample -ne ($index + 1)) {
        throw "BF-744 BLOCKED: expected timing sample $($index + 1) but observed $sample."
    }
    $rows += [pscustomobject]@{
        Sample = $sample
        User = [long]$match.Groups['user'].Value
        UserLeagues = [long]$match.Groups['userLeagues'].Value
        League = [long]$match.Groups['league'].Value
        Rosters = [long]$match.Groups['rosters'].Value
        Users = [long]$match.Groups['users'].Value
        ProviderSum = [long]$match.Groups['providerSum'].Value
        VerifyWall = [long]$match.Groups['verifyWall'].Value
        Residual = [long]$match.Groups['residual'].Value
    }
}

function Get-Bf744Median {
    param([Parameter(Mandatory = $true)][long[]]$Values)
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { throw 'BF-744 BLOCKED: cannot compute a median from zero values.' }
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
}

Write-Host ''
Write-Host "BF-744 serial BF-623 provider-stage timing ($Samples samples):"
foreach ($row in $rows) {
    Write-Host ("  sample={0}; user_ms={1}; user_leagues_ms={2}; league_ms={3}; rosters_ms={4}; users_ms={5}; provider_sum_ms={6}; verify_wall_ms={7}; residual_ms={8}" -f `
        $row.Sample, $row.User, $row.UserLeagues, $row.League, $row.Rosters, $row.Users, $row.ProviderSum, $row.VerifyWall, $row.Residual)
}

$userMedian = Get-Bf744Median -Values @($rows | ForEach-Object { $_.User })
$userLeaguesMedian = Get-Bf744Median -Values @($rows | ForEach-Object { $_.UserLeagues })
$leagueMedian = Get-Bf744Median -Values @($rows | ForEach-Object { $_.League })
$rostersMedian = Get-Bf744Median -Values @($rows | ForEach-Object { $_.Rosters })
$usersMedian = Get-Bf744Median -Values @($rows | ForEach-Object { $_.Users })
$providerSumMedian = Get-Bf744Median -Values @($rows | ForEach-Object { $_.ProviderSum })
$verifyWallMedian = Get-Bf744Median -Values @($rows | ForEach-Object { $_.VerifyWall })
$residualMedian = Get-Bf744Median -Values @($rows | ForEach-Object { $_.Residual })

Write-Host ("BF-744 medians: user_ms={0:N1}; user_leagues_ms={1:N1}; league_ms={2:N1}; rosters_ms={3:N1}; users_ms={4:N1}; provider_sum_ms={5:N1}; verify_wall_ms={6:N1}; residual_ms={7:N1}" -f `
    $userMedian, $userLeaguesMedian, $leagueMedian, $rostersMedian, $usersMedian, $providerSumMedian, $verifyWallMedian, $residualMedian)
Write-Host 'BF-744 diagnostic boundary: unchanged serial BF-623 verification only; no caching, no parallel provider calls, /refresh excluded, and no Butler or Sleeper write path is invoked.'
