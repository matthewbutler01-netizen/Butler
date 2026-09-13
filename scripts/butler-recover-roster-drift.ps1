Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$gradle = Join-Path $repoRoot 'gradlew.bat'
$acceptancePreflight = Join-Path $scriptDir 'butler-acceptance-preflight.ps1'
$acceptanceCmd = Join-Path $scriptDir 'butler-acceptance.cmd'

foreach ($required in @($gradle, $acceptancePreflight, $acceptanceCmd)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "BF-723 BLOCKED: required recovery component not found at $required"
    }
}

function Get-ConfiguredLeagueId {
    $localAppData = $env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($localAppData)) {
        $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    }
    if ([string]::IsNullOrWhiteSpace($localAppData)) {
        throw 'BF-723 BLOCKED: LocalApplicationData is unavailable.'
    }

    $configPath = Join-Path (Join-Path $localAppData 'Butler') 'app-league.txt'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw 'BF-723 BLOCKED: Butler app league is not configured.'
    }

    $raw = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
    $parsed = [Guid]::Empty
    if ([string]::IsNullOrWhiteSpace($raw) -or -not [Guid]::TryParse($raw, [ref]$parsed)) {
        throw 'BF-723 BLOCKED: configured Butler app league id is invalid.'
    }
    return $parsed.ToString('D').ToLowerInvariant()
}

function Get-BoundedTail {
    param([AllowNull()][string]$Text, [int]$Limit = 2200)

    if ([string]::IsNullOrWhiteSpace($Text)) { return 'no task output' }
    $normalized = [regex]::Replace($Text, '\s+', ' ').Trim()
    if ($normalized.Length -le $Limit) { return $normalized }
    return '...' + $normalized.Substring($normalized.Length - $Limit)
}

function Invoke-ButlerGradleTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [Parameter(Mandatory = $true)][string]$LeagueId
    )

    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    Push-Location $repoRoot
    try {
        try {
            $ErrorActionPreference = 'Continue'
            $lines = & $gradle '--no-daemon' $Task "--args=$LeagueId" 2>&1
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    finally {
        Pop-Location
    }

    return [pscustomobject]@{
        ExitCode = [int]$exitCode
        Text = (($lines | ForEach-Object { "$_" }) -join "`n")
    }
}

function Invoke-RequiredSuccess {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$LeagueId
    )

    Write-Host ("BF-723: running {0}..." -f $Label)
    $result = Invoke-ButlerGradleTask -Task $Task -LeagueId $LeagueId
    if ($result.ExitCode -ne 0) {
        $tail = Get-BoundedTail -Text $result.Text
        throw "BF-723 BLOCKED: $Label failed with Gradle exit code $($result.ExitCode); output=$tail"
    }
    Write-Host ("BF-723: {0} complete." -f $Label)
    return $result
}

$leagueId = Get-ConfiguredLeagueId
$driftPrefix = 'BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame; added='
$driftSuffix = '; refresh BF-602/BF-603 and downstream live evidence before target-roster review'
$bf610Task = ':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit'
$comparisonTask = ':bet:bet-cli:sleeperLiveWaiverComparisonBundle'

Write-Host 'Butler governed roster-drift evidence recovery (BF-723)'
Write-Host "League: $leagueId"
Write-Host 'Boundary: this command may write fresh Butler evidence for BF-602/BF-603/BF-605/BF-606/BF-607/BF-612.'
Write-Host 'Boundary: it does not submit, cancel, or replace a Sleeper transaction; it does not mutate a Sleeper roster, FAAB, or trade.'

& $acceptancePreflight
if ($LASTEXITCODE -ne 0) {
    throw "BF-723 BLOCKED: acceptance preflight failed with exit code $LASTEXITCODE."
}

Write-Host 'BF-723: checking exact BF-610 roster state before any Butler evidence write...'
$preflight = Invoke-ButlerGradleTask -Task $bf610Task -LeagueId $leagueId
$recoveryNeeded = $false
if ($preflight.ExitCode -eq 0) {
    Write-Host 'BF-723: BF-610 is already current; skipping evidence writes.'
}
elseif ($preflight.Text.Contains($driftPrefix, [System.StringComparison]::Ordinal) -and
        $preflight.Text.Contains($driftSuffix, [System.StringComparison]::Ordinal)) {
    $recoveryNeeded = $true
    Write-Host 'BF-723: exact BF-610 roster drift verified; fixed governed evidence recovery is authorized.'
}
else {
    $tail = Get-BoundedTail -Text $preflight.Text
    throw "BF-723 BLOCKED: BF-610 failed for a reason other than exact roster drift. No Butler evidence write was attempted. output=$tail"
}

if ($recoveryNeeded) {
    $stages = @(
        [pscustomobject]@{ Task = ':bet:bet-cli:sleeperLiveWaiverSnapshotSync'; Label = 'BF-602 waiver snapshot sync' },
        [pscustomobject]@{ Task = ':bet:bet-cli:sleeperLiveWaiverMarketAttentionSync'; Label = 'BF-603 market-attention sync' },
        [pscustomobject]@{ Task = ':bet:bet-cli:sleeperLiveWaiverProductionHydration'; Label = 'BF-605 production hydration' },
        [pscustomobject]@{ Task = ':bet:bet-cli:sleeperLiveWaiverAvailabilitySync'; Label = 'BF-606 availability sync' },
        [pscustomobject]@{ Task = ':bet:bet-cli:sleeperLiveWaiverCurrentWeekStatSync'; Label = 'BF-607 current-week stat sync' },
        [pscustomobject]@{ Task = ':bet:bet-cli:sleeperLiveWaiverTargetRosterProductionHydration'; Label = 'BF-612 target-roster production hydration' }
    )

    foreach ($stage in $stages) {
        [void](Invoke-RequiredSuccess -Task $stage.Task -Label $stage.Label -LeagueId $leagueId)
    }
}

[void](Invoke-RequiredSuccess -Task $bf610Task -Label 'BF-610 post-recovery target-roster verification' -LeagueId $leagueId)
[void](Invoke-RequiredSuccess -Task $comparisonTask -Label 'BF-615/BF-617 post-recovery waiver comparison verification' -LeagueId $leagueId)

Write-Host 'BF-723 RECOVERY: VERIFIED'
Write-Host 'BF-723: starting unchanged BF-698 GET-only acceptance.'
& $acceptanceCmd
if ($LASTEXITCODE -ne 0) {
    throw "BF-723 FAILED: recovery verified, but BF-698 acceptance exited with code $LASTEXITCODE."
}

Write-Host 'BF-723 RESULT: COMPLETE'
