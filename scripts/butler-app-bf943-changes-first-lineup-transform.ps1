param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-943 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)

$evidenceAnchor = '        SourceSurface = $sourceSurface.Groups[''value''].Value.Trim()'
if (-not $core.Contains($evidenceAnchor)) {
    throw 'BF-943 BLOCKED: lineup decision evidence parser anchor is missing.'
}
$core = $core.Replace($evidenceAnchor, $evidenceAnchor + "`n" + '        DecisionEvidence = @([regex]::Matches($Text, ''(?m)^Decision evidence: (?<value>.+)$'') | ForEach-Object { $_.Groups[''value''].Value.Trim() })' + "`n" + '        ExpertPicks = @([regex]::Matches($Text, ''(?m)^Expert pick: (?<value>.+)$'') | ForEach-Object { $_.Groups[''value''].Value.Trim() | ConvertFrom-Json })' + "`n" + '        SwapReviews = @([regex]::Matches($Text, ''(?m)^Decision review: (?<value>.+)$'') | ForEach-Object { $_.Groups[''value''].Value.Trim() | ConvertFrom-Json })')

$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-943 BLOCKED: final Lineup Advisor function boundary is missing.'
}

$function = $core.Substring($functionStart, $functionEnd - $functionStart)
foreach ($requiredPrior in @(
    'Compare this swap',
    'CurrentPoints',
    'RecommendedPoints',
    'SlotGain'
)) {
    if ($function.IndexOf($requiredPrior, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-943 BLOCKED: required prior Lineup Advisor capability is missing: $requiredPrior"
    }
}

$returnMarker = '    return "<section class=`"panel recommendation-panel`">'
# Earlier returns render blocked and incomplete-evidence states, before counts exist.
# Only the final ready-state return may receive assignment disclosure setup.
$returnPos = $function.LastIndexOf($returnMarker, [System.StringComparison]::Ordinal)
if ($returnPos -lt 0) {
    throw 'BF-943 BLOCKED: final Lineup Advisor ready-state return is missing.'
}

$disclosureSetup = @'
    $decisionTitle = if ($changedCount -gt 0) { "Review $changedCount projection proposal(s)" } else { 'No projection proposals after review holds' }
    $decisionCopy = 'Manual review required. Projections do not establish a complete start/sit decision when role, matchup, or expert evidence is incomplete.'
    $decisionStatus = 'MANUAL REVIEW'
    $decisionStatusClass = 'warn'
    $recommendedMetricLabel = if ($partialCoverage) { 'Comparable proposed' } else { 'Proposed projection' }
    $whyCopy = "Evaluated-slot projection: $($AutoFill.CurrentTotal) current versus $($AutoFill.RecommendedTotal) proposed, a change of $($AutoFill.Gain). Held players are excluded from these totals. Review holds and evidence gaps before changing your lineup."
    $holdEvidenceHtml = '<p class="meta">Availability, usage decline, and close-call conflicts can withhold a promotion. Remaining proposals still need review. Review attributed expert selections and coverage below, plus NFL matchup coverage in each comparison.</p>'
    $structuredReviews = @()
    if ($null -ne $AutoFill.PSObject.Properties['SwapReviews']) { $structuredReviews = @($AutoFill.SwapReviews) }
    $queueItems = ''
    $holdIndex = 0
    foreach ($hold in @($AutoFill.ProjectionHolds)) {
        $holdLink = if ($null -ne $hold.PSObject.Properties['Reason']) { " <a href=`"#lineup-hold-$holdIndex`">Review hold evidence</a>" } else { '' }
        $queueItems += "<li><strong>$(ConvertTo-HtmlText $hold.Name)</strong>: review hold. Keep the current lineup state pending review.$holdLink</li>"
        $holdIndex++
    }
    $queuePicks = @()
    if ($null -ne $AutoFill.PSObject.Properties['ExpertPicks']) { $queuePicks = @($AutoFill.ExpertPicks) }
    foreach ($assignment in @($AutoFill.Assignments)) {
        $starterId = [string]$assignment.CurrentId
        if ([string]::IsNullOrWhiteSpace($starterId)) { continue }
        $matchingPicks = @($queuePicks | Where-Object { [string]$_.playerId -ceq $starterId })
        if ($matchingPicks.Count -eq 1 -and [string]$matchingPicks[0].selection -ceq 'SIT') {
            $pickIndex = [array]::IndexOf($queuePicks, $matchingPicks[0])
            $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Current)</strong>: current starter with an attributed SIT selection from $(ConvertTo-HtmlText $matchingPicks[0].author). Review scoring, roster fit and the source below; this opinion does not establish consensus. <a href=`"#lineup-expert-$pickIndex`">Review expert source</a></li>"
        }
        if ($assignment.Changed) {
            $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: review the projection proposal $(ConvertTo-HtmlText $assignment.Current) &rarr; $(ConvertTo-HtmlText $assignment.Recommended). Check holds and the comparison evidence before making a move.</li>"
        }
        $comparisonLinks = ''
        for ($reviewIndex = 0; $reviewIndex -lt $structuredReviews.Count; $reviewIndex++) {
            $queueReview = $structuredReviews[$reviewIndex]
            if ([string]$queueReview.ordinal -ceq [string]$assignment.Ordinal) {
                $comparisonLinks += " <a href=`"#lineup-comparison-$reviewIndex`">$(ConvertTo-HtmlText $queueReview.current) &rarr; $(ConvertTo-HtmlText $queueReview.proposed)</a>"
            }
        }
        if ($comparisonLinks.Length -gt 0) { $queueItems += "<li>Review $(ConvertTo-HtmlText $assignment.Slot) comparisons:$comparisonLinks</li>" }
    }
    if ($null -ne $AutoFill.PSObject.Properties['DecisionEvidence']) {
        foreach ($evidence in @($AutoFill.DecisionEvidence)) {
            if ([string]$evidence -cmatch '^Replacement review for ') { $queueItems += "<li>$(ConvertTo-HtmlText $evidence)</li>" }
        }
    }
    if ($queueItems.Length -gt 0) {
        $holdEvidenceHtml = "<section class=`"swap-review-card review-queue`"><h3>Review queue</h3><p class=`"meta`">Unresolved signals may overlap. Projection totals do not settle these decisions.</p><ul>$queueItems</ul></section>$holdEvidenceHtml"
    }
    $reviewIndex = 0
    foreach ($review in $structuredReviews) {
        $reviewLabel = if ([string]$review.status -ceq 'WITHHELD_USAGE_CONFLICT') { 'WITHHELD: USAGE CONFLICT' } elseif ([string]$review.status -ceq 'MANUAL_REVIEW_REPLACEMENT') { 'BENCH ALTERNATIVE: REVIEW ONLY' } else { 'MANUAL REVIEW' }
        $sourcesHtml = ''
        $sourceLabels = @('Weekly usage data', 'Offensive snaps', 'Player identity crosswalk', 'NFL schedule')
        $sourceIndex = 0
        foreach ($source in @($review.sources)) {
            $uri = $null
            if ([uri]::TryCreate([string]$source, [System.UriKind]::Absolute, [ref]$uri) -and
                $uri.Scheme -ceq 'https' -and $uri.Host -in @('github.com', 'raw.githubusercontent.com')) {
                $label = if ($sourceIndex -lt $sourceLabels.Count) { $sourceLabels[$sourceIndex] } else { 'Source' }
                $sourcesHtml += "<a href=`"$(ConvertTo-HtmlText $source)`" target=`"_blank`" rel=`"noopener noreferrer`">$(ConvertTo-HtmlText $label)</a> "
            }
            $sourceIndex++
        }
        $matchupHtml = ''
        if ($null -ne $review.PSObject.Properties['currentMatchup']) {
            $matchupHtml = "<details><summary>NFL opponent and observed defense</summary><p><strong>Current:</strong> $(ConvertTo-HtmlText $review.currentMatchup)</p><p><strong>Candidate:</strong> $(ConvertTo-HtmlText $review.proposedMatchup)</p></details>"
        }
        $reasonHtml = "<p>$(ConvertTo-HtmlText $review.reason)</p>"
        $expertSignalsHtml = ''
        if ([string]$review.status -ceq 'MANUAL_REVIEW_REPLACEMENT') {
            $reasonHtml = "<p>Eligible bench option for a flagged starter. Review only; existing holds remain.</p><details><summary>Comparison limits and evidence gaps</summary><p>$(ConvertTo-HtmlText $review.reason)</p></details>"
            if ($null -ne $review.PSObject.Properties['currentExpert'] -and $null -ne $review.PSObject.Properties['proposedExpert']) {
                $expertSignalsHtml = "<p><strong>Current expert:</strong> $(ConvertTo-HtmlText $review.currentExpert)<br><strong>Candidate expert:</strong> $(ConvertTo-HtmlText $review.proposedExpert)</p><p class=`"meta`">Attributed opinion; no consensus. Source and dates are in expert coverage below.</p>"
            }
        }
        $comparisonHtml = "<section id=`"lineup-comparison-$reviewIndex`" tabindex=`"-1`" class=`"swap-review-card`"><h3>$(ConvertTo-HtmlText $review.slot): $(ConvertTo-HtmlText $review.current) &rarr; $(ConvertTo-HtmlText $review.proposed)</h3><span class=`"status warn`">$reviewLabel</span><p>Projected slot change: $(ConvertTo-HtmlText $review.projectedGain) points</p>$expertSignalsHtml$reasonHtml<table class=`"swap-usage-table`"><caption>Recent observed usage</caption><thead><tr><th scope=`"col`">Current: $(ConvertTo-HtmlText $review.current)</th><th scope=`"col`">Candidate: $(ConvertTo-HtmlText $review.proposed)</th></tr></thead><tbody><tr><td>$(ConvertTo-HtmlText $review.currentUsage)</td><td>$(ConvertTo-HtmlText $review.proposedUsage)</td></tr></tbody></table><p class=`"meta`">Carries and targets describe rushing and receiving opportunities; passing attempts are shown separately when available.</p>$matchupHtml<details><summary>Sources and commentary</summary><p>$(ConvertTo-HtmlText $review.commentary)</p><div class=`"swap-review-sources`">$sourcesHtml</div></details></section>"
        if ([string]$review.status -ceq 'MANUAL_REVIEW_REPLACEMENT') {
            $holdEvidenceHtml += "<details class=`"swap-review-card`"><summary>Bench comparison: $(ConvertTo-HtmlText $review.current) &rarr; $(ConvertTo-HtmlText $review.proposed)</summary>$comparisonHtml</details>"
        } else { $holdEvidenceHtml += $comparisonHtml }
        $reviewIndex++
    }
    if ($structuredReviews.Count -eq 0 -and $null -ne $AutoFill.PSObject.Properties['DecisionEvidence']) {
        $extraEvidence = ''
        foreach ($evidence in @($AutoFill.DecisionEvidence)) {
            $extraEvidence += "<p>$(ConvertTo-HtmlText $evidence)</p>"
        }
        if ($extraEvidence.Length -gt 0) { $holdEvidenceHtml += "<details class=`"swap-review-card`"><summary>Additional evidence and gaps</summary>$extraEvidence</details>" }
    }
    if ($null -ne $AutoFill.PSObject.Properties['ExpertPicks'] -and @($AutoFill.ExpertPicks).Count -gt 0) {
        $explicitPicks = @($AutoFill.ExpertPicks | Where-Object { [string]$_.selection -cin @('START', 'SIT') })
        $coverageGaps = @($AutoFill.ExpertPicks | Where-Object { [string]$_.selection -cnotin @('START', 'SIT') })
        $expertHtml = '<p class="meta">Attributed weekly opinions. Check scoring and roster fit; existing holds remain. No consensus or point adjustment inferred.</p>'
        $gapHtml = ''
        $pickIndex = 0
        foreach ($pick in $queuePicks) {
            $expertLink = ''
            $expertUri = $null
            if ([uri]::TryCreate([string]$pick.source, [System.UriKind]::Absolute, [ref]$expertUri) -and
                $expertUri.Scheme -ceq 'https' -and $expertUri.Host -ceq 'www.nfl.com' -and $expertUri.AbsolutePath.StartsWith('/news/')) {
                $expertLink = "<a href=`"$(ConvertTo-HtmlText $pick.source)`" target=`"_blank`" rel=`"noopener noreferrer`">NFL.com weekly column</a>"
            }
            $pickDetails = "<p id=`"lineup-expert-$pickIndex`" tabindex=`"-1`">$(ConvertTo-HtmlText $pick.coverage)</p>"
            if ([string]$pick.selection -cin @('START', 'SIT')) {
                $pickDetails += "<p class=`"meta`">Published $(ConvertTo-HtmlText $pick.publishedAt); updated $(ConvertTo-HtmlText $pick.modifiedAt).</p>"
            }
            $pickDetails += "<p class=`"meta`">Checked $(ConvertTo-HtmlText $pick.checkedAt). $expertLink</p>"
            if ([string]$pick.selection -cin @('START', 'SIT')) {
                $expertHtml += "<div class=`"expert-selection`"><strong>$(ConvertTo-HtmlText $pick.player): $(ConvertTo-HtmlText $pick.selection)</strong><span class=`"meta`">$(ConvertTo-HtmlText $pick.author)</span><details><summary>Source and dates</summary>$pickDetails</details></div>"
            } else {
                $gapHtml += "<details class=`"expert-gap`"><summary>$(ConvertTo-HtmlText $pick.player): UNVERIFIED</summary>$pickDetails</details>"
            }
            $pickIndex++
        }
        if ($explicitPicks.Count -eq 0) { $expertHtml += '<p>No verified expert selections available.</p>' }
        if ($coverageGaps.Count -gt 0) {
            $coverageNoun = if ($coverageGaps.Count -eq 1) { 'player' } else { 'players' }
            $expertHtml += "<details class=`"expert-coverage`"><summary>View $($coverageGaps.Count) $coverageNoun with unverified coverage</summary><p class=`"meta`">Missing selections do not establish a start or sit recommendation.</p>$gapHtml</details>"
        }
        $holdEvidenceHtml += "<section class=`"swap-review-card`"><h3>Attributed expert selections and coverage</h3>$expertHtml</section>"
    }
    $holdIndex = 0
    foreach ($hold in @($AutoFill.ProjectionHolds)) {
        if ($null -ne $hold.PSObject.Properties['Reason']) {
            $holdSummary = if ([string]$hold.Reason -like 'Close-call usage conflict:*') { 'Close projection edge conflicts with recent workload.' }
                elseif ([string]$hold.Reason -like 'Usage review hold:*') { 'Observed snaps and workload declined sharply.' }
                elseif ([string]$hold.Reason -like 'Availability hold:*') { 'Availability needs clearance before promotion.' }
                else { 'Usable weekly projection evidence is incomplete.' }
            $holdEvidenceHtml += "<details class=`"callout swap-review-card`"><summary>$(ConvertTo-HtmlText $hold.Name): review hold</summary><p id=`"lineup-hold-$holdIndex`" tabindex=`"-1`">$holdSummary</p><p>$(ConvertTo-HtmlText $hold.Reason)</p></details>"
        }
        $holdIndex++
    }
    $unchangedCount = @($AutoFill.Assignments | Where-Object { -not $_.Changed }).Count
    $lineupFocusHtml = if ($changedCount -gt 0) {
        $changeWord = if ($changedCount -eq 1) { 'change' } else { 'changes' }
        "<div class=`"lineup-focus`"><div class=`"lineup-focus-head`"><div><span class=`"eyebrow`">Projection proposals</span><h3>$changedCount $changeWord to review</h3></div><span class=`"status warn`">CHANGES FIRST</span></div><div class=`"lineup-board`">$rows</div></div>"
    }
    else {
        '<div class="lineup-focus lineup-clear"><div class="lineup-focus-head"><div><span class="eyebrow">Lineup proposals</span><h3>No projected changes after holds</h3></div><span class="status done">NO PROPOSALS</span></div><p class="meta">No higher projected lineup was found among the evaluated players. Review holds and evidence gaps before treating this as a complete lineup assessment. The unchanged lineup remains available below.</p></div>'
    }

    $unchangedDisclosure = if ($unchangedCount -gt 0) {
        $slotWord = if ($unchangedCount -eq 1) { 'slot' } else { 'slots' }
        "<details class=`"lineup-unchanged`"><summary>View $unchangedCount unchanged lineup $slotWord</summary><div class=`"lineup-board`">$rows</div></details>"
    }
    else {
        ''
    }

'@

$oldBoard = '<div class=`"lineup-board`">$rows</div>'
$boardCount = [regex]::Matches($function, [regex]::Escape($oldBoard)).Count
if ($boardCount -ne 1) {
    throw "BF-943 BLOCKED: expected one primary lineup-board binding, found $boardCount."
}
$function = $function.Replace($oldBoard, '$holdEvidenceHtml$lineupFocusHtml$unchangedDisclosure')
$function = $function.Substring(0, $returnPos) + $disclosureSetup + $function.Substring($returnPos)
$function = $function.Replace('<h3>Start</h3>', '<h3>Proposed promotion</h3>').Replace('<h3>Sit</h3>', '<h3>Proposed bench move</h3>')
$function = $function.Replace('<small>Recommended</small>', '<small>Candidate</small>')
$function = $function.Replace("'CHANGE'", "'REVIEW'")
$function = $function.Replace('<strong>Promote to lineup</strong>', '<strong>Proposed promotion</strong>').Replace('<strong>Move to bench</strong>', '<strong>Proposed bench move</strong>')

$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-943 BLOCKED: final manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-943 BLOCKED: final manager CSS terminator is missing.'
}
$css = @'
/* BF-943 changes-first lineup progressive disclosure. */
[id^="lineup-hold-"],[id^="lineup-expert-"],[id^="lineup-comparison-"]{scroll-margin-top:24px}[id^="lineup-"]:target{outline:2px solid var(--accent);outline-offset:4px}.review-queue a{display:inline-block;margin:4px 8px 4px 0}
.swap-review-card{margin:12px 0;padding:14px;border:1px solid var(--line);border-radius:12px;overflow-wrap:anywhere}.swap-review-card h3{margin-top:0}.swap-usage-table{width:100%;table-layout:fixed;border-collapse:collapse;margin:12px 0}.swap-usage-table caption{text-align:left;font-weight:700;margin-bottom:6px}.swap-usage-table th,.swap-usage-table td{padding:10px;vertical-align:top;text-align:left;border:1px solid var(--line);overflow-wrap:anywhere}.swap-review-sources{display:flex;flex-wrap:wrap;gap:12px}.swap-review-card summary{cursor:pointer;font-weight:700}.expert-selection{display:grid;gap:4px;padding:10px 0;border-top:1px solid var(--line)}.expert-selection details,.expert-coverage,.expert-gap{margin-top:6px}.expert-coverage{padding-top:10px;border-top:1px solid var(--line)}
.lineup-focus{margin-top:18px}.lineup-focus-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:10px}.lineup-focus-head h3{margin:2px 0 0}.lineup-focus .lineup-row:not(.changed){display:none}.lineup-clear{padding:16px;border:1px solid var(--line);border-radius:14px;background:var(--surface-soft)}.lineup-unchanged{margin-top:14px;border:1px solid var(--line);border-radius:14px;background:var(--surface-soft);overflow:hidden}.lineup-unchanged>summary{cursor:pointer;padding:14px 16px;font-weight:800;list-style-position:inside}.lineup-unchanged[open]>summary{border-bottom:1px solid var(--line)}.lineup-unchanged .lineup-row.changed{display:none}.lineup-unchanged .lineup-board{padding:10px 12px 12px}@media(max-width:900px){.lineup-focus-head{flex-direction:column;align-items:flex-start}.lineup-unchanged .lineup-board{padding:8px}}
/* Respond to the advisor panel width, including narrow desktop sidebars. */
.lineup-board{container-type:inline-size;container-name:lineup-board}.lineup-row>*{min-width:0}.lineup-choice strong{overflow-wrap:anywhere}.lineup-row .lineup-swap-action{grid-column:1/-1;justify-content:flex-start;min-width:0}.lineup-row .lineup-swap-compare{max-width:100%;white-space:normal}
@container lineup-board (max-width:650px){.lineup-row{grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:12px;padding:14px}.lineup-row>div:first-child{grid-column:1/-1;grid-row:auto}.lineup-row .lineup-choice.current{grid-column:1;grid-row:auto}.lineup-row .lineup-choice.recommended{grid-column:2;grid-row:auto}.lineup-row .lineup-arrow{display:none}.lineup-row .projection,.lineup-row .slot-delta{grid-column:1;grid-row:auto}.lineup-row .decision-chip{grid-column:2;grid-row:auto;justify-self:end;align-self:center}.lineup-row .lineup-swap-action{grid-column:1/-1;grid-row:auto}.lineup-row .lineup-choice small,.lineup-row .lineup-choice strong,.lineup-row .lineup-player-projection{display:block}}

'@
$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $css.TrimEnd() + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'View $unchangedCount unchanged lineup $slotWord',
    'CHANGES FIRST',
    'NO PROPOSALS',
    '$lineupFocusHtml',
    '$unchangedDisclosure',
    'Compare this swap',
    'CurrentPoints',
    'SlotGain',
    'lineup-unchanged'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-943 BLOCKED: required changes-first marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installed = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'submitTransaction',
    'setFaab'
)) {
    if ($installed.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-943 BLOCKED: changes-first lineup introduced forbidden behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-943 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-943 Changes-First Lineup applied.'
