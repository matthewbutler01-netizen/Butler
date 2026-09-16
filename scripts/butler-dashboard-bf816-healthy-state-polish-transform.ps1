param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-816 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $first = $Text.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) {
        throw "BF-816 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-816 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-816 BLOCKED: dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)

# Unevaluated lineup is a deliberate state, not a successful recommendation.
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$lineupSignalTitle = if ($verification.RosterOk) { "Review this week''s lineup" } else { "Roster context needs attention" }' `
    -New '$lineupSignalTitle = if ($verification.RosterOk) { "Lineup review not requested yet" } else { "Roster context needs attention" }' `
    -Contract 'unevaluated lineup title'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '"Butler''s roster check is verified. Open My Team to review starters and request the read-only AutoFill preview; projections are not fetched from this Dashboard."' `
    -New '"Butler has verified the roster, but no read-only AutoFill review has been requested for this weekly frame. This is unevaluated, not a recommendation."' `
    -Contract 'unevaluated lineup copy'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$lineupSignalStatus = if ($verification.RosterOk) { "READY TO REVIEW" } else { "NEEDS ATTENTION" }' `
    -New '$lineupSignalStatus = if ($verification.RosterOk) { "NOT REVIEWED" } else { "NEEDS ATTENTION" }' `
    -Contract 'unevaluated lineup status'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$lineupSignalClass = if ($verification.RosterOk) { "good" } else { "warn" }' `
    -New '$lineupSignalClass = if ($verification.RosterOk) { "done" } else { "warn" }' `
    -Contract 'unevaluated lineup status class'

# A no-change AutoFill result is a successful completed review.
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$lineupSignalTitle = "Latest AutoFill found no lineup changes"' `
    -New '$lineupSignalTitle = "Lineup review complete; no change proven"' `
    -Contract 'no-change lineup title'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$lineupSignalCopy = "Week $($lineupSnapshot.Week), $($lineupSnapshot.Scoring): the latest proven AutoFill keeps the current starters. Projection source: $($lineupSnapshot.Source)."' `
    -New '$lineupSignalCopy = "Week $($lineupSnapshot.Week), $($lineupSnapshot.Scoring): Butler completed the read-only AutoFill review and found no proven improvement over the current starters. Keeping the current starters is the supported outcome for this frame. Projection source: $($lineupSnapshot.Source)."' `
    -Contract 'no-change lineup queue copy'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '"NO CHANGES" { "The latest saved AutoFill proved that the current starters should remain in place for this evidence frame. $lineupSignalCopy" }' `
    -New '"NO CHANGES" { "The latest read-only AutoFill review completed successfully and found no proven improvement over the current starters. This is a valid no-change result. $lineupSignalCopy" }' `
    -Contract 'no-change lineup explanation'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$primaryNextActionCopy = "No lineup change is currently proven. Review My Team if you want to inspect the current starters, but Butler has no change to recommend from this evidence frame."' `
    -New '$primaryNextActionCopy = "No lineup action is needed from this evidence frame. The current starters remain the supported choice; open My Team only if you want to inspect them."' `
    -Contract 'no-change lineup next action'

# No saved AutoFill frame means not evaluated yet, not missing evidence.
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old 'default { "Butler has verified the roster, but there is no current saved AutoFill result yet. Open Lineup Advisor to request the read-only weekly projection check." }' `
    -New 'default { "No lineup review has been requested for this weekly frame yet. This is an unevaluated state, not a recommendation. Open Lineup Advisor when you want the read-only weekly projection check." }' `
    -Contract 'unevaluated lineup explanation'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$primaryNextActionCopy = "Open Lineup Advisor and explicitly request the read-only AutoFill check when you want Butler to evaluate this week''s starters."' `
    -New '$primaryNextActionCopy = "Request the read-only AutoFill check when you want Butler to evaluate this week''s starters. Until then, Butler has not made a lineup recommendation."' `
    -Contract 'unevaluated lineup next action'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$snapshotNote = "No saved AutoFill result is available for this lineup frame."' `
    -New '$snapshotNote = "No AutoFill review has been requested for this weekly lineup frame."' `
    -Contract 'unevaluated lineup snapshot evidence'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$coverageNote = "No current AutoFill projection result is available."' `
    -New '$coverageNote = "Projection coverage has not been evaluated because no AutoFill review was requested."' `
    -Contract 'unevaluated lineup projection evidence'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '<strong>No-change lineup result saved locally</strong><span>The latest read-only AutoFill result is saved for this lineup frame and did not prove a starter change. No lineup was submitted.</span>' `
    -New '<strong>Completed lineup review saved locally</strong><span>The latest read-only AutoFill completed with no proven starter improvement. Keeping the current starters is the supported result for this frame. No lineup was submitted.</span>' `
    -Contract 'no-change lineup Decision Record'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '<strong>No lineup review recorded yet</strong><span>No current explicit AutoFill result is saved for this lineup frame. Butler has not evaluated or submitted a lineup from the Dashboard.</span>' `
    -New '<strong>Lineup review not requested yet</strong><span>No read-only AutoFill review has been requested for this weekly frame. Butler has not evaluated or submitted a lineup from the Dashboard.</span>' `
    -Contract 'unevaluated lineup Decision Record'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$decisionPackageTrust = "Current projection evidence"`r`n                        $decisionPackageRecord = "No-change result saved locally"' `
    -New '$decisionPackageTrust = "Complete weekly projection evidence"`r`n                        $decisionPackageRecord = "Completed no-change review saved locally"' `
    -Contract 'no-change decision package summary'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$decisionPackageTrust = "No current AutoFill frame"`r`n                        $decisionPackageRecord = "No lineup review recorded"' `
    -New '$decisionPackageTrust = "Lineup not evaluated yet"`r`n                        $decisionPackageRecord = "No lineup review requested"' `
    -Contract 'unevaluated decision package summary'

# A no-move waiver outcome is a completed governed review, not absent data.
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '"NO_TRANSACTION_TO_ACT_ON" { "No waiver move to make" }' `
    -New '"NO_TRANSACTION_TO_ACT_ON" { "Waiver review complete; no move proven" }' `
    -Contract 'no-move waiver title'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '"NO_TRANSACTION_TO_ACT_ON" { "Current evidence does not support one clear add/drop move, so Butler recommends no waiver move." }' `
    -New '"NO_TRANSACTION_TO_ACT_ON" { "Butler completed the current waiver review and did not prove one clear add/drop move. No waiver action is needed from this evidence frame." }' `
    -Contract 'no-move waiver queue copy'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '$primaryExplanationCopy = $whyCopy' `
    -New '$primaryExplanationCopy = if ([string]$state -ceq "NO_TRANSACTION_TO_ACT_ON") { "The current waiver review completed without a proven add/drop move. This is a valid no-action outcome, not missing recommendation data." } else { $whyCopy }' `
    -Contract 'no-move waiver explanation'

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock `
    -Old '"NO_TRANSACTION_TO_ACT_ON" { "No actionable waiver move" }' `
    -New '"NO_TRANSACTION_TO_ACT_ON" { "No waiver action proven" }' `
    -Contract 'no-move waiver decision package trust'

$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

if ($text -notmatch 'Lineup review complete; no change proven') {
    throw 'BF-816 BLOCKED: completed no-change lineup treatment is missing.'
}
if ($text -notmatch 'This is unevaluated, not a recommendation') {
    throw 'BF-816 BLOCKED: unevaluated lineup treatment is missing.'
}
if ($text -notmatch 'Waiver review complete; no move proven') {
    throw 'BF-816 BLOCKED: completed no-move waiver treatment is missing.'
}
if ($text -notmatch 'No trade evidence loaded') {
    throw 'BF-816 BLOCKED: trade on-demand treatment regressed.'
}
if ($text -notmatch 'Latest AutoFill hit an evidence gap') {
    throw 'BF-816 BLOCKED: evidence-gap treatment regressed.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
