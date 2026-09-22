param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId,

    [switch]$PreflightOnly
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
$runtimeLibDir = Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli\lib'

$localAppData = [string]$env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-676 BLOCKED: LocalApplicationData is unavailable.'
}

function Resolve-Bf676RuntimeDataDir {
    $configured = [string]$env:BUTLER_APP_DATA_DIR
    if ([string]::IsNullOrWhiteSpace($configured)) {
        $candidate = Join-Path $localAppData 'Butler\data'
    }
    else {
        if (-not [IO.Path]::IsPathRooted($configured)) {
            throw 'BF-676 BLOCKED: BUTLER_APP_DATA_DIR must be an absolute path.'
        }
        $candidate = $configured
    }

    $resolved = [IO.Path]::GetFullPath($candidate)
    $sourceRoot = [IO.Path]::GetFullPath($repoRoot).TrimEnd('\')
    $sourcePrefix = $sourceRoot + '\'
    if ($resolved.Equals($sourceRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $resolved.StartsWith($sourcePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'BF-676 BLOCKED: Butler runtime data directory must be outside the source/package tree.'
    }
    if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
        throw "BF-676 BLOCKED: governed Butler runtime data directory not found at $resolved"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $resolved 'butler.db') -PathType Leaf)) {
        throw "BF-676 BLOCKED: governed Butler runtime database not found at $resolved"
    }
    return $resolved
}

function Get-Bf676JavaExecutable {
    if (-not [string]::IsNullOrWhiteSpace([string]$env:JAVA_HOME)) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    try {
        return [string](Get-Command java.exe -ErrorAction Stop).Source
    }
    catch {
        throw 'BF-676 BLOCKED: java.exe is unavailable for governed refresh.'
    }
}

function Get-Bf676BoundedTail {
    param(
        [AllowNull()][object[]]$Lines,
        [int]$Limit = 2400
    )

    if ($null -eq $Lines -or @($Lines).Count -eq 0) { return 'no captured task output' }
    $text = ((@($Lines) | ForEach-Object { "$_" }) -join ' ')
    $text = [regex]::Replace($text, '\s+', ' ').Trim()
    if ([string]::IsNullOrWhiteSpace($text)) { return 'no captured task output' }
    if ($text.Length -le $Limit) { return $text }
    return '...' + $text.Substring($text.Length - $Limit)
}

function Get-Bf676MainClass {
    param([Parameter(Mandatory = $true)][string]$Task)

    switch ($Task) {
        ':bet:bet-cli:sleeperCurrentWeekMatchupSync' { return 'io.butler.bet.cli.ButlerSleeperCurrentWeekMatchupSyncCli' }
        ':bet:bet-cli:sleeperLiveWaiverSnapshotSync' { return 'io.butler.bet.cli.ButlerSleeperLiveWaiverSnapshotSyncCli' }
        ':bet:bet-cli:sleeperLiveWaiverMarketAttentionSync' { return 'io.butler.bet.cli.ButlerSleeperLiveWaiverMarketAttentionSyncCli' }
        ':bet:bet-cli:sleeperLiveWaiverProductionHydration' { return 'io.butler.bet.cli.ButlerSleeperLiveWaiverProductionHydrationCli' }
        ':bet:bet-cli:sleeperLiveWaiverAvailabilitySync' { return 'io.butler.bet.cli.ButlerSleeperLiveWaiverAvailabilitySyncCli' }
        ':bet:bet-cli:sleeperLiveWaiverCurrentWeekStatSync' { return 'io.butler.bet.cli.ButlerSleeperLiveWaiverCurrentWeekStatSyncCli' }
        ':bet:bet-cli:sleeperLiveWaiverTargetRosterProductionHydration' { return 'io.butler.bet.cli.ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli' }
        ':bet:bet-cli:sleeperLiveWaiverFinalRecommendationBundle' { return 'io.butler.bet.cli.ButlerSleeperLiveWaiverFinalRecommendationBundleCli' }
        ':bet:bet-cli:sleeperLiveWaiverRecommendationAuditCapture' { return 'io.butler.bet.cli.ButlerSleeperLiveWaiverRecommendationAuditCaptureCli' }
        ':bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary' { return 'io.butler.bet.cli.ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli' }
        default { return $null }
    }
}

$dataDir = Resolve-Bf676RuntimeDataDir
$java = Get-Bf676JavaExecutable
if (-not (Test-Path -LiteralPath $runtimeLibDir -PathType Container)) {
    throw "BF-676 BLOCKED: prepared Butler runtime library not found at $runtimeLibDir"
}
$runtimeJars = @(Get-ChildItem -LiteralPath $runtimeLibDir -Filter '*.jar' -File -ErrorAction Stop)
$appJars = @($runtimeJars | Where-Object { $_.Name -like 'bet-cli*.jar' })
if ($runtimeJars.Count -eq 0 -or $appJars.Count -ne 1) {
    throw 'BF-676 BLOCKED: prepared Butler runtime must contain exactly one bet-cli application JAR and its dependencies.'
}
$classPath = Join-Path $runtimeLibDir '*'

function Invoke-Bf676RuntimeStep {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Task,
        [switch]$CaptureOutput
    )

    Write-Host ("BF-676 START: {0}" -f $Label)
    $mainClass = Get-Bf676MainClass -Task $Task
    if ([string]::IsNullOrWhiteSpace([string]$mainClass)) {
        throw "BF-676 BLOCKED: refresh task is not authorized for direct runtime execution: $Task"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $exitCode = -1
    $lines = @()
    Push-Location $dataDir
    try {
        try {
            # Windows PowerShell 5.1 can surface native stderr as error records.
            # LASTEXITCODE remains the authoritative process-success gate.
            $ErrorActionPreference = 'Continue'
            $lines = @(& $java '--enable-native-access=ALL-UNNAMED' '-cp' $classPath $mainClass $LeagueId 2>&1)
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
    }
    finally {
        Pop-Location
    }

    if ($exitCode -ne 0) {
        $tail = Get-Bf676BoundedTail -Lines $lines
        throw "BF-676 STOPPED: $Label failed with runtime exit code $exitCode. No later stage was executed. Captured output: $tail"
    }

    Write-Host ("BF-676 PASS: {0}" -f $Label)
    if ($CaptureOutput) {
        return $lines
    }
    foreach ($line in $lines) {
        Write-Host "$line"
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

function Test-Bf676NoTransactionLineage {
    param([Parameter(Mandatory = $true)][string]$LineageState)

    return @(
        'LATEST_EVIDENCE_LINEAGE_VERIFIED',
        'MARKET_LINEAGE_SUPERSEDED',
        'WAIVER_LINEAGE_SUPERSEDED',
        'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED'
    ) -ccontains $LineageState
}

Push-Location $repoRoot
try {
    Write-Host 'BF-676 manual governed waiver refresh'
    Write-Host ("Butler league: {0}" -f $LeagueId)
    Write-Host 'Boundary: explicit Butler evidence/recommendation refresh only. This runner never submits, cancels, or replaces a Sleeper transaction and never sets FAAB.'

    $preflightLines = @(Invoke-Bf676RuntimeStep `
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
        if ($bf629State -cne 'NO_TRANSACTION_TO_REVALIDATE' -or
            -not (Test-Bf676NoTransactionLineage -LineageState $bf631State)) {
            throw 'BF-676 BLOCKED: no-transaction preflight did not retain the exact BF-629 gate and an approved BF-631 refresh lineage. No BF-602/BF-603/etc. write stage was executed.'
        }
        Write-Host ("BF-676 PREFLIGHT VERIFIED: governed no-transaction state is eligible for manual refresh with BF-631 lineage {0}." -f $bf631State)
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

    if ($PreflightOnly) {
        Write-Output 'BF-676 PREFLIGHT ONLY: PASS'
        Write-Output ("Decision status: {0}" -f $decisionState)
        Write-Output ("BF-629 live actionability: {0}" -f $bf629State)
        Write-Output ("BF-631 evidence lineage: {0}" -f $bf631State)
        Write-Output 'Boundary: preflight-only mode executed no BF-840/BF-602/BF-603/etc. write stage and submitted no Sleeper transaction.'
        return
    }

    Invoke-Bf676RuntimeStep `
        -Label 'BF-840 PRE-STAGE - exact weekly matchup pairing' `
        -Task ':bet:bet-cli:sleeperCurrentWeekMatchupSync'

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
        Invoke-Bf676RuntimeStep `
            -Label ("STEP {0}/9 - {1}" -f $step.Order, $step.Bf) `
            -Task $step.Task
    }

    $finalLines = @(Invoke-Bf676RuntimeStep `
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
