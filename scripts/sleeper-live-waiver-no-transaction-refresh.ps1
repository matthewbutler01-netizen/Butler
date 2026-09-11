param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$parsedLeague = [Guid]::Empty
if (-not [Guid]::TryParse($LeagueId, [ref]$parsedLeague)) {
    throw 'BF-676 BLOCKED: Butler league id must be an exact UUID.'
}
$LeagueId = $parsedLeague.ToString('D').ToLowerInvariant()

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$gradle = Join-Path $repoRoot 'gradlew.bat'

if (-not (Test-Path -LiteralPath $gradle)) {
    throw "BF-676 BLOCKED: Gradle wrapper not found at $gradle"
}

function Invoke-Bf676GradleStep {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Task,
        [switch]$CaptureOutput
    )

    Write-Host ("BF-676 START: {0}" -f $Label)
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
        throw "BF-676 STOPPED: $Label failed with Gradle exit code $exitCode. No later stage was executed."
    }

    Write-Host ("BF-676 PASS: {0}" -f $Label)
    if ($CaptureOutput) {
        return $lines
    }
}

function Get-Bf676SingleField {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $matches = [regex]::Matches($Text, $Pattern)
    if ($matches.Count -ne 1) {
        throw "BF-676 BLOCKED: preflight must contain exactly one $Label. No BF-602/BF-603/etc. write stage was executed."
    }
    return $matches[0].Groups['value'].Value.Trim()
}

function Assert-Bf676WarningRefreshPlan {
    param([Parameter(Mandatory = $true)][string]$Text)

    $refreshTrigger = Get-Bf676SingleField `
        -Text $Text `
        -Pattern '(?m)^Refresh trigger:\s+(?<value>.+?)\s*$' `
        -Label 'BF-635 refresh trigger'
    $allowedTriggers = @(
        'BF-603 market and BF-602 waiver evidence exceed 21600 seconds',
        'BF-603 market evidence exceeds 21600 seconds',
        'BF-602 waiver evidence exceeds 21600 seconds'
    )
    if ($allowedTriggers -cnotcontains $refreshTrigger) {
        throw "BF-676 BLOCKED: BF-635 refresh trigger is not an approved six-hour warning projection. No BF-602/BF-603/etc. write stage was executed."
    }

    $planPolicy = Get-Bf676SingleField `
        -Text $Text `
        -Pattern '(?m)^Plan policy:\s+(?<value>\S+)\s*$' `
        -Label 'BF-636 plan policy'
    if ($planPolicy -cne 'sleeper-live-waiver-manual-refresh-plan-v1-bf635-explicit-operator-only-no-execution') {
        throw 'BF-676 BLOCKED: BF-636 plan policy is not the governed manual refresh policy. No BF-602/BF-603/etc. write stage was executed.'
    }

    $planState = Get-Bf676SingleField `
        -Text $Text `
        -Pattern '(?m)^Plan state:\s+(?<value>\S+)\s*$' `
        -Label 'BF-636 plan state'
    if ($planState -cne 'MANUAL_REFRESH_PLAN_READY') {
        throw 'BF-676 BLOCKED: BF-636 manual refresh plan is not ready. No BF-602/BF-603/etc. write stage was executed.'
    }

    $expectedPlanSteps = @(
        @{ Order = 1; Bf = 'BF-602'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverSnapshotSync' },
        @{ Order = 2; Bf = 'BF-603'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverMarketAttentionSync' },
        @{ Order = 3; Bf = 'BF-605'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverProductionHydration' },
        @{ Order = 4; Bf = 'BF-606'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverAvailabilitySync' },
        @{ Order = 5; Bf = 'BF-607'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverCurrentWeekStatSync' },
        @{ Order = 6; Bf = 'BF-612'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverTargetRosterProductionHydration' },
        @{ Order = 7; Bf = 'BF-618/BF-620'; Mode = 'READ_ONLY'; Task = 'sleeperLiveWaiverFinalRecommendationBundle' },
        @{ Order = 8; Bf = 'BF-627'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverRecommendationAuditCapture' },
        @{ Order = 9; Bf = 'BF-629/BF-631/BF-633/BF-635'; Mode = 'READ_ONLY'; Task = 'sleeperLiveWaiverLatestGovernedDecisionSummary' }
    )

    $planStepCount = [regex]::Matches($Text, '(?m)^  \d+\. BF-[^\r\n]+$').Count
    if ($planStepCount -ne 9) {
        throw 'BF-676 BLOCKED: BF-636 manual refresh plan must contain exactly nine governed step lines. No BF-602/BF-603/etc. write stage was executed.'
    }

    $searchFrom = 0
    foreach ($step in $expectedPlanSteps) {
        $line = ("  {0}. {1} | {2} | {3}" -f $step.Order, $step.Bf, $step.Mode, $step.Task)
        if ([regex]::Matches($Text, [regex]::Escape($line)).Count -ne 1) {
            throw "BF-676 BLOCKED: BF-636 plan step does not reconcile exactly: $line. No BF-602/BF-603/etc. write stage was executed."
        }
        $lineIndex = $Text.IndexOf($line, $searchFrom, [System.StringComparison]::Ordinal)
        if ($lineIndex -lt $searchFrom) {
            throw "BF-676 BLOCKED: BF-636 plan order drifted at $line. No BF-602/BF-603/etc. write stage was executed."
        }

        $command = ".\gradlew.bat :bet:bet-cli:$($step.Task) --args=`"$LeagueId`""
        if ([regex]::Matches($Text, [regex]::Escape($command)).Count -ne 1) {
            throw "BF-676 BLOCKED: BF-636 plan command does not exactly bind the Butler league for $($step.Task). No BF-602/BF-603/etc. write stage was executed."
        }
        $commandIndex = $Text.IndexOf($command, $lineIndex, [System.StringComparison]::Ordinal)
        if ($commandIndex -le $lineIndex) {
            throw "BF-676 BLOCKED: BF-636 plan command order drifted at $($step.Task). No BF-602/BF-603/etc. write stage was executed."
        }
        $searchFrom = $commandIndex + $command.Length
    }
}

Push-Location $repoRoot
try {
    Write-Host 'BF-676 manual governed waiver refresh'
    Write-Host ("Butler league: {0}" -f $LeagueId)
    Write-Host 'Boundary: explicit Butler evidence/recommendation refresh only. This runner never submits, cancels, or replaces a Sleeper transaction and never sets FAAB.'

    $preflightLines = @(Invoke-Bf676GradleStep `
        -Label 'PRECHECK - governed refresh eligibility' `
        -Task ':bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary' `
        -CaptureOutput)
    $preflight = $preflightLines -join "`n"

    $decisionState = Get-Bf676SingleField `
        -Text $preflight `
        -Pattern '(?m)^Decision status:\s+(?<value>\S+)\s*$' `
        -Label 'decision status'
    $bf629State = Get-Bf676SingleField `
        -Text $preflight `
        -Pattern '(?m)^BF-629 live actionability:\s+(?<value>\S+)\s*$' `
        -Label 'BF-629 live actionability'
    $bf631State = Get-Bf676SingleField `
        -Text $preflight `
        -Pattern '(?m)^BF-631 evidence lineage:\s+(?<value>\S+)\s*$' `
        -Label 'BF-631 evidence lineage'

    if ($decisionState -ceq 'NO_TRANSACTION_TO_ACT_ON') {
        if ($bf629State -cne 'NO_TRANSACTION_TO_REVALIDATE' -or $bf631State -cne 'LATEST_EVIDENCE_LINEAGE_VERIFIED') {
            throw 'BF-676 BLOCKED: no-transaction preflight did not retain the exact BF-675 BF-629/BF-631 gates. No BF-602/BF-603/etc. write stage was executed.'
        }
        Write-Host 'BF-676 PREFLIGHT VERIFIED: exact governed no-transaction state remains eligible for manual refresh.'
    }
    elseif ($decisionState -ceq 'CURRENT_REFRESH_RECOMMENDED') {
        if ($bf629State -cne 'LIVE_ACTIONABLE_VERIFIED' -or $bf631State -cne 'LATEST_EVIDENCE_LINEAGE_VERIFIED') {
            throw 'BF-676 BLOCKED: warning-state refresh requires BF-629 live actionability and BF-631 latest evidence lineage. No BF-602/BF-603/etc. write stage was executed.'
        }
        Assert-Bf676WarningRefreshPlan -Text $preflight
        Write-Host 'BF-676 PREFLIGHT VERIFIED: current recommendation has the governed BF-635 six-hour refresh warning and exact BF-636 nine-step plan.'
    }
    else {
        throw "BF-676 BLOCKED: decision state '$decisionState' is not authorized for browser refresh. No BF-602/BF-603/etc. write stage was executed."
    }

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
        Invoke-Bf676GradleStep `
            -Label ("STEP {0}/9 - {1}" -f $step.Order, $step.Bf) `
            -Task $step.Task
    }

    $finalLines = @(Invoke-Bf676GradleStep `
        -Label 'STEP 9/9 - BF-629/BF-631/BF-633/BF-635/BF-638/BF-639' `
        -Task ':bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary' `
        -CaptureOutput)
    $finalText = $finalLines -join "`n"

    Write-Output 'BF-676 REFRESH COMPLETE'
    Write-Output 'All nine governed refresh stages completed successfully.'
    Write-Output 'BF-676 did not submit a Sleeper transaction.'
    Write-Output '--- FINAL GOVERNED SUMMARY ---'
    Write-Output $finalText
}
finally {
    Pop-Location
}
