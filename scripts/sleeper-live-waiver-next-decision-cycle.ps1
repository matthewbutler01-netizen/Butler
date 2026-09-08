param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$gradle = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path -LiteralPath $gradle)) {
    throw "BF-641 BLOCKED: Gradle wrapper not found at $gradle"
}

function Invoke-ButlerGradleStep {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [Parameter(Mandatory = $true)]
        [string]$Task,
        [switch]$CaptureOutput
    )

    Write-Host ""
    Write-Host "============================================================"
    Write-Host "BF-641 START: $Label"
    Write-Host "Task: $Task"
    Write-Host "============================================================"

    $gradleArgs = @($Task, "--args=$LeagueId")
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 can promote native stderr redirected with 2>&1 into
        # NativeCommandError records when ErrorActionPreference is Stop. Gradle/JDK
        # warnings are valid stderr even when Gradle exits 0, so native invocation is
        # temporarily non-terminating and the real gate remains LASTEXITCODE.
        $ErrorActionPreference = "Continue"

        if ($CaptureOutput) {
            $lines = & $gradle @gradleArgs 2>&1
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

    if ($CaptureOutput) {
        $lines | ForEach-Object { Write-Host $_ }
    }

    if ($exitCode -ne 0) {
        throw "BF-641 STOPPED: $Label failed with Gradle exit code $exitCode. No later stage was executed."
    }

    Write-Host "BF-641 PASS: $Label"
    if ($CaptureOutput) {
        return $lines
    }
}

Push-Location $repoRoot
try {
    Write-Host "BF-641 governed one-command next-decision cycle"
    Write-Host "Butler league: $LeagueId"
    Write-Host "Boundary: operator-invoked Butler evidence/recommendation orchestration only. This runner never submits, cancels, or replaces a Sleeper transaction and never sets FAAB."

    $preflightLines = Invoke-ButlerGradleStep `
        -Label "PRECHECK - BF-638/BF-639/BF-640 readiness" `
        -Task ":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary" `
        -CaptureOutput
    $preflight = $preflightLines -join "`n"

    $requiredPreflight = @(
        "Decision status: TRANSACTION_ALREADY_COMPLETE",
        "BF-629 live actionability: AUDITED_TRANSACTION_COMPLETE",
        "BF-639 post-transaction roster convergence: POST_TRANSACTION_ROSTER_CONVERGED",
        "Plan state: NEXT_DECISION_PLAN_READY"
    )

    foreach ($required in $requiredPreflight) {
        if (-not $preflight.Contains($required)) {
            throw "BF-641 BLOCKED: preflight did not prove '$required'. No BF-602/BF-603/etc. write stage was executed."
        }
    }

    Write-Host ""
    Write-Host "BF-641 PREFLIGHT VERIFIED: prior transaction is complete, roster convergence is verified, and BF-640 authorizes the next governed cycle."

    $steps = @(
        @{ Order = 1; Bf = "BF-602"; Task = ":bet:bet-cli:sleeperLiveWaiverSnapshotSync" },
        @{ Order = 2; Bf = "BF-603"; Task = ":bet:bet-cli:sleeperLiveWaiverMarketAttentionSync" },
        @{ Order = 3; Bf = "BF-605"; Task = ":bet:bet-cli:sleeperLiveWaiverProductionHydration" },
        @{ Order = 4; Bf = "BF-606"; Task = ":bet:bet-cli:sleeperLiveWaiverAvailabilitySync" },
        @{ Order = 5; Bf = "BF-607"; Task = ":bet:bet-cli:sleeperLiveWaiverCurrentWeekStatSync" },
        @{ Order = 6; Bf = "BF-612"; Task = ":bet:bet-cli:sleeperLiveWaiverTargetRosterProductionHydration" },
        @{ Order = 7; Bf = "BF-618/BF-620"; Task = ":bet:bet-cli:sleeperLiveWaiverFinalRecommendationBundle" },
        @{ Order = 8; Bf = "BF-627"; Task = ":bet:bet-cli:sleeperLiveWaiverRecommendationAuditCapture" },
        @{ Order = 9; Bf = "BF-629/BF-631/BF-633/BF-635/BF-638/BF-639"; Task = ":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary" }
    )

    foreach ($step in $steps) {
        Invoke-ButlerGradleStep `
            -Label ("STEP {0}/9 - {1}" -f $step.Order, $step.Bf) `
            -Task $step.Task
    }

    Write-Host ""
    Write-Host "============================================================"
    Write-Host "BF-641 CYCLE COMPLETE"
    Write-Host "All nine governed stages completed successfully."
    Write-Host "Review the final compact decision above before taking any human action in Sleeper."
    Write-Host "BF-641 did not submit a Sleeper transaction."
    Write-Host "============================================================"
}
finally {
    Pop-Location
}
