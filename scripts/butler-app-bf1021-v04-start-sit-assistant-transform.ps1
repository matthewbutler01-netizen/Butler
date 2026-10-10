param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-1021 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)
$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-1021 BLOCKED: final Start/Sit renderer boundary is missing.'
}

$function = $core.Substring($functionStart, $functionEnd - $functionStart)
foreach ($required in @(
    'Lineup advisor',
    'CurrentPoints',
    'RecommendedPoints',
    'SlotGain',
    'Compare this swap',
    'Review queue',
    'Projection hold',
    'Refresh projection'
)) {
    if ($function.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1021 BLOCKED: final lineup capability is missing: $required"
    }
}

$function = $function.Replace('Lineup advisor', 'Start/Sit Assistant')
$function = $function.Replace('This week''s lineup decision', 'Start/Sit Assistant')
$function = $function.Replace('recommendation-panel', 'recommendation-panel start-sit-assistant')
$function = $function.Replace('<h3>Decision</h3>', '<h3>What should I change?</h3>')
$function = $function.Replace('<h3>Proposed promotion</h3>', '<h3>START</h3>')
$function = $function.Replace('<h3>Proposed bench move</h3>', '<h3>SIT</h3>')
$function = $function.Replace('<small>Current</small>', '<small>Current starter</small>')
$function = $function.Replace('<small>Candidate</small>', '<small>Recommended starter</small>')
$function = $function.Replace('Projected change', 'Projected difference')
$function = $function.Replace('<strong>Projection hold:</strong>', '<strong>Player holds:</strong>')
$function = $function.Replace('Butler kept projection-held players in their current lineup state and did not assign synthetic points.', 'Butler kept held players in their current lineup state. Review each reason below; missing projections were not replaced with synthetic points.')

# BF-1038: direct Start/Sit signals should lead the review experience.
# Holds remain visible, but they are evidence checks rather than separate manager moves.
$queueInitOld = "    `$queueItems = ''"
$queueInitNew = "    `$queueItems = ''`n    `$holdQueueItems = ''"
if ($function.IndexOf($queueInitOld, [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1038 BLOCKED: review queue initialization is missing.'
}
$function = $function.Replace($queueInitOld, $queueInitNew)

$holdAppendOld = '        $queueItems += "<li><strong>$(ConvertTo-HtmlText $hold.Name)</strong>: review hold. Keep the current lineup state pending review.$holdLink$holdExpertTail</li>"'
$holdAppendNew = @'
        $holdCategory = 'Projection evidence incomplete'
        if ($null -ne $hold.PSObject.Properties['Reason']) {
            $holdCategory = if ([string]$hold.Reason -like 'Availability hold:*') { 'Availability review' }
                elseif ([string]$hold.Reason -like 'Close-call usage conflict:*') { 'Projection/workload conflict' }
                elseif ([string]$hold.Reason -like 'Usage review hold:*') { 'Workload decline' }
                else { 'Projection evidence incomplete' }
        }
        $holdQueueItems += "<li><strong>$(ConvertTo-HtmlText $hold.Name)</strong>: $(ConvertTo-HtmlText $holdCategory). Keep the current lineup state pending review.$holdLink$holdExpertTail</li>"
'@
if ($function.IndexOf($holdAppendOld, [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1038 BLOCKED: projection-hold queue binding is missing.'
}
$function = $function.Replace($holdAppendOld, $holdAppendNew)

$queueReturnOld = "    `$reviewQueueReturn = ''"
$queueReturnNew = "    `$directSignalCount = [regex]::Matches(`$queueItems, 'attributed SIT selection').Count`n    `$queueItems += `$holdQueueItems`n    `$reviewQueueReturn = ''"
if ($function.IndexOf($queueReturnOld, [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1038 BLOCKED: review queue return anchor is missing.'
}
$function = $function.Replace($queueReturnOld, $queueReturnNew)

$titleOld = '        $decisionTitle = "Review $reviewQueueCount unresolved $reviewQueueNoun"'
$titleNew = @'
        $holdReviewCount = @($AutoFill.ProjectionHolds).Count
        if ($directSignalCount -gt 0) {
            $signalNoun = if ($directSignalCount -eq 1) { 'start/sit signal' } else { 'start/sit signals' }
            $signalVerb = if ($directSignalCount -eq 1) { 'needs' } else { 'need' }
            $decisionTitle = "$directSignalCount $signalNoun $signalVerb review"
            $holdNoun = if ($holdReviewCount -eq 1) { 'player hold' } else { 'player holds' }
            $decisionCopy = "Start with the direct Start/Sit signal. $holdReviewCount $holdNoun still need evidence review before Butler can recommend a lineup change."
        }
        elseif ($holdReviewCount -gt 0) {
            $holdNoun = if ($holdReviewCount -eq 1) { 'player hold' } else { 'player holds' }
            $decisionTitle = "Review $holdReviewCount $holdNoun"
            $decisionCopy = 'No direct Start/Sit change is ready. Clear the player holds before treating the current lineup as settled.'
        }
        else {
            $decisionTitle = "Review $reviewQueueCount unresolved $reviewQueueNoun"
        }
'@
if ($function.IndexOf($titleOld, [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1038 BLOCKED: unresolved-count decision title is missing.'
}
$function = $function.Replace($titleOld, $titleNew.TrimEnd())

# Give the summary card a concrete review task without turning an opinion into a move.
$summaryAnchor = '    return "<section class=`"panel recommendation-panel start-sit-assistant`"'
$summarySetup = @'
    # BF-1067: report what this exact preview did and did not verify.
    # BF-1066 has already withheld changed assignments lacking source
    # player statuses; displaying a proposal is not health clearance.
    $proposedChanges = @($AutoFill.Assignments | Where-Object { [bool]$_.Changed }).Count
    $playerHolds = @($AutoFill.ProjectionHolds).Count
    $holdCopy = if ($playerHolds -eq 1) { '1 player hold' } else { "$playerHolds player holds" }
    $projectionBasisCopy = if ([string]$AutoFill.ProjectionCoverage -ceq 'FULL') { 'Full scoreable projection coverage' }
        else { 'Partial projection coverage; missing projections are not zeros' }
    if ($proposedChanges -gt 0) {
        $statusProofCopy = "$proposedChanges proposed lineup changes have exact player-status checks from Sleeper at review time. $projectionBasisCopy; $holdCopy. Recheck injury updates before kickoff. A projection is not availability clearance and no Sleeper move was submitted."
    }
    else {
        $statusProofCopy = "No lineup change is ready. $projectionBasisCopy; $holdCopy. If availability or a player's projection cannot be verified, Butler holds the move rather than guessing. Recheck before kickoff."
    }
    $decisionActionCopy = $decisionTitle
    if ($managerMoveCount -eq 0 -and $directSignalCount -gt 0) {
        $reviewTasks = @(
            foreach ($signal in [regex]::Matches($queueItems, '<li><strong>(?<player>.*?)</strong>: current starter with an attributed SIT selection')) {
                $playerName = [System.Net.WebUtility]::HtmlDecode($signal.Groups['player'].Value)
                $candidates = @($structuredReviews | Where-Object {
                    [string]$_.current -ceq $playerName -and
                    [string]$_.status -ceq 'MANUAL_REVIEW_REPLACEMENT' -and
                    -not [string]::IsNullOrWhiteSpace([string]$_.proposed) -and
                    [string]$_.proposed -cne $playerName
                } | ForEach-Object { [string]$_.proposed } | Select-Object -Unique)
                if ($candidates.Count -gt 0) { "$playerName vs. $($candidates -join ', ')" }
                else { "$playerName's SIT evidence" }
            }
        )
        if ($reviewTasks.Count -gt 0) {
            $decisionActionCopy = "Review $($reviewTasks -join '; '). No lineup change recommended yet."
        }
    }
'@
if ($function.IndexOf($summaryAnchor, [System.StringComparison]::Ordinal) -lt 0) {
    throw 'Start/Sit summary renderer anchor is missing.'
}
$function = $function.Replace($summaryAnchor, $summarySetup.TrimEnd() + "`n" + $summaryAnchor)
$summaryOld = '<h3>What should I change?</h3><p>$(ConvertTo-HtmlText $decisionTitle)</p>'
if ($function.IndexOf($summaryOld, [System.StringComparison]::Ordinal) -lt 0) {
    throw 'Start/Sit decision summary card is missing.'
}
$function = $function.Replace($summaryOld, '<h3>What should I change?</h3><p>$(ConvertTo-HtmlText $decisionActionCopy)</p><p class=`"meta butler-startsit-source-proof`" role=`"status`">$(ConvertTo-HtmlText $statusProofCopy)</p>')

$function = $function.Replace(
    '<h3>Review queue</h3>',
    '<h3>What needs your decision</h3>'
)
$function = $function.Replace(
    'Unresolved signals may overlap. Projection totals do not settle these decisions.',
    'Start with direct Start/Sit signals. Player holds are evidence checks, not separate lineup moves.'
)

$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-1021 BLOCKED: manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-1021 BLOCKED: manager CSS terminator is missing.'
}

$css = @'
/* BF-1021 v0.4 Start/Sit Assistant. */
.start-sit-assistant{padding:22px}.start-sit-assistant>.manager-head{padding-bottom:16px;margin-bottom:16px;border-bottom:1px solid var(--line)}.start-sit-assistant>.manager-head .eyebrow{font-size:11px;letter-spacing:.11em}.start-sit-assistant>.manager-head h2{font-size:clamp(24px,2.2vw,32px);line-height:1.1}.start-sit-assistant .grid.four{gap:10px}.start-sit-assistant .grid.four>.summary-card:nth-child(1){border-color:color-mix(in srgb,var(--turf) 55%,var(--line));background:color-mix(in srgb,var(--turf) 7%,var(--surface-2))}.start-sit-assistant .grid.four>.summary-card:nth-child(3){border-color:color-mix(in srgb,var(--good) 55%,var(--line))}.start-sit-assistant .grid.four>.summary-card:nth-child(3) h3{color:var(--good);letter-spacing:.06em}.start-sit-assistant .grid.four>.summary-card:nth-child(4){border-color:color-mix(in srgb,var(--danger) 50%,var(--line))}.start-sit-assistant .grid.four>.summary-card:nth-child(4) h3{color:var(--danger);letter-spacing:.06em}.start-sit-assistant .autofill-summary{margin-top:14px}.start-sit-assistant .lineup-row.changed{border-color:color-mix(in srgb,var(--turf) 55%,var(--line));background:color-mix(in srgb,var(--turf) 7%,var(--surface-2))}.start-sit-assistant .lineup-choice small{font-size:10px;text-transform:uppercase;letter-spacing:.05em}.start-sit-assistant .lineup-swap-compare{font-size:11px}.start-sit-assistant .review-queue{border-color:color-mix(in srgb,var(--gold) 45%,var(--line))}
.start-sit-assistant .butler-startsit-source-proof{display:block;padding:10px 12px;border:1px solid var(--line);border-left:4px solid var(--turf);border-radius:8px;background:var(--surface-2);color:inherit;line-height:1.5;font-size:13px}
@media(max-width:760px){.start-sit-assistant{padding:16px 14px}.start-sit-assistant .grid.four{grid-template-columns:1fr!important}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + [Environment]::NewLine + $css.TrimEnd() + [Environment]::NewLine + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1021 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

foreach ($required in @(
    'Start/Sit Assistant',
    'What should I change?',
    '<h3>START</h3>',
    '<h3>SIT</h3>',
    'Current starter',
    'Recommended starter',
    'Projected difference',
    'Compare this swap',
    'BF-1021 v0.4 Start/Sit Assistant',
    'Player holds:',
    'What needs your decision',
    'Start with direct Start/Sit signals.',
    'start/sit signal',
    'butler-startsit-source-proof',
    'Recheck injury updates before kickoff',
    '$holdQueueItems'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1021 BLOCKED: Start/Sit Assistant marker is missing: $required"
    }
}

$surface = $function + [Environment]::NewLine + $css
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-1021 BLOCKED: Start/Sit Assistant presentation introduced provider, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-1021 v0.4 Start/Sit Assistant applied.'
