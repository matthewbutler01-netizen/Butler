param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$parsedLeague = [Guid]::Empty
if (-not [Guid]::TryParse($LeagueId, [ref]$parsedLeague)) {
    throw 'BF-675 BLOCKED: Butler league id must be an exact UUID.'
}
$LeagueId = $parsedLeague.ToString('D').ToLowerInvariant()

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$gradle = Join-Path $repoRoot 'gradlew.bat'

if (-not (Test-Path -LiteralPath $gradle)) {
    throw "BF-675 BLOCKED: Gradle wrapper not found at $gradle"
}

function Invoke-Bf675GradleStep {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Task,
        [switch]$CaptureOutput
    )

    Write-Host ("BF-675 START: {0}" -f $Label)
    $gradleArgs = @($Task, "--args=$LeagueId")
    $previousErrorActionPreference = $ErrorActionPreference
    $exitCode = -1
    $lines = @()
    try {
        # Preserve BF-642's Windows PowerShell 5.1 native-stderr rule: Gradle/JDK
        # warnings may use stderr on a successful run, so LASTEXITCODE is the gate.
        $ErrorActionPreference = 'Continue'
        if ($CaptureOutput) {
            $lines = @(& $gradle @gradleArgs 2>&1)
            $exitCode = $LASTEXITCODE
        }
        else {
            & $gradle @gradleArgs
            $exitCode = $LASTEXITCODE
        }
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        throw "BF-675 STOPPED: $Label failed with Gradle exit code $exitCode. No later stage was executed."
    }

    Write-Host ("BF-675 PASS: {0}" -f $Label)
    if ($CaptureOutput) {
        return $lines
    }
}

Push-Location $repoRoot
try {
    Write-Host 'BF-675 manual governed no-transaction refresh'
    Write-Host ("Butler league: {0}" -f $LeagueId)
    Write-Host 'Boundary: explicit Butler evidence/recommendation refresh only. This runner never submits, cancels, or replaces a Sleeper transaction and never sets FAAB.'

    $preflightLines = @(Invoke-Bf675GradleStep `
        -Label 'PRECHECK - current governed no-transaction state' `
        -Task ':bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary' `
        -CaptureOutput)
    $preflight = $preflightLines -join "`n"

    $requiredPreflight = @(
        'Decision status: NO_TRANSACTION_TO_ACT_ON',
        'BF-629 live actionability: NO_TRANSACTION_TO_REVALIDATE',
        'BF-631 evidence lineage: LATEST_EVIDENCE_LINEAGE_VERIFIED'
    )
    foreach ($required in $requiredPreflight) {
        if (-not $preflight.Contains($required)) {
            throw "BF-675 BLOCKED: preflight did not prove '$required'. No BF-602/BF-603/etc. write stage was executed."
        }
    }

    Write-Host 'BF-675 PREFLIGHT VERIFIED: current immutable decision is an exact governed no-transaction with latest evidence lineage.'

    $steps = @(
        @{ Order = 1; Bf = 'BF-602'; Task = ':bet:bet-cli:sleeperLiveWaiverSnapshotSync' },
        @{ Order = 2; Bf = 'BF-603'; Task = ':bet:bet-cli:sleeperLiveWaiverMarketAttentionSync' },
        @{ Order = 3; Bf = 'BF-605'; Task = ':bet:bet-cli:sleeperLiveWaiverProductionHydration' },
        @{ Order = 4; Bf = 'BF-606'; Task = ':bet:bet-cli:sleeperLiveWaiverAvailabilitySync' },
        @{ Order = 5; Bf = 'BF-607'; Task = ':bet:bet-cli:sleeperLiveWaiverCurrentWeekStatSync' },
        @{ Order = 6; Bf = 'BF-612'; Task = ':bet:bet-cli:sleeperLiveWaiverTargetRosterProductionHydration' },
        @{ Order = 7; Bf = 'BF-618/BF-620'; Task = ':bet:bet-cli:sleeperLiveWaiverFinalRecommendationBundle' },
        @{ Order = 8; Bf = 'BF-627'; Task = ':bet:bet-cli:sleeperLiveWaiverRecommendationAuditCapture' }
    )

    foreach ($step in $steps) {
        Invoke-Bf675GradleStep `
            -Label ("STEP {0}/9 - {1}" -f $step.Order, $step.Bf) `
            -Task $step.Task
    }

    $finalLines = @(Invoke-Bf675GradleStep `
        -Label 'STEP 9/9 - BF-629/BF-631/BF-633/BF-635/BF-638/BF-639' `
        -Task ':bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary' `
        -CaptureOutput)
    $finalText = $finalLines -join "`n"

    Write-Output 'BF-675 REFRESH COMPLETE'
    Write-Output 'All nine governed stages completed successfully.'
    Write-Output 'BF-675 did not submit a Sleeper transaction.'
    Write-Output '--- FINAL GOVERNED SUMMARY ---'
    Write-Output $finalText
}
finally {
    Pop-Location
}
