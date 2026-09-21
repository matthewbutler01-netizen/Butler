param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-817 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-FunctionBlock {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$StartMarker,
        [Parameter(Mandatory = $true)][string]$NextMarker,
        [Parameter(Mandatory = $true)][string]$Replacement,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $start = $Text.IndexOf($StartMarker, [System.StringComparison]::Ordinal)
    if ($start -lt 0) {
        throw "BF-817 BLOCKED: $Contract start marker is missing."
    }
    $secondStart = $Text.IndexOf($StartMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($secondStart -ge 0) {
        throw "BF-817 BLOCKED: $Contract start marker is ambiguous."
    }
    $next = $Text.IndexOf($NextMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($next -lt 0) {
        throw "BF-817 BLOCKED: $Contract end marker is missing."
    }

    return $Text.Substring(0, $start) + $Replacement.TrimEnd() + "`r`n`r`n" + $Text.Substring($next)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$autoFillReplacement = @'
function ConvertTo-AutoFillHtml {
    param([Parameter(Mandatory = $true)]$AutoFill)

    if (-not $AutoFill.Requested) {
        return '<section class="panel recommendation-panel"><div class="manager-head"><div><div class="eyebrow">Lineup advisor</div><h2>This week''s lineup decision</h2><p class="lede">Butler has not evaluated this weekly lineup yet.</p></div><span class="status done">NOT REVIEWED</span></div><div class="manager-summary"><div class="summary-card"><h3>Decision</h3><p>No lineup recommendation yet. Run a read-only lineup review when you want Butler to evaluate the current starters.</p></div><div class="summary-card"><h3>Why</h3><p>No weekly projection frame has been requested from this page, so Butler will not imply that the current lineup is already optimal.</p></div></div><div class="button-row"><a class="btn btn-primary" href="/team/autofill">Review Lineup</a></div><p class="meta"><strong>Read only:</strong> projection evidence is requested only after you request a lineup review. Nothing is submitted to Sleeper.</p></section>'
    }

    $frame = "Week $(ConvertTo-HtmlText $AutoFill.Week) &middot; $(ConvertTo-HtmlText $AutoFill.Scoring) scoring"
    if (-not $AutoFill.Ready) {
        $reason = [string]$AutoFill.Reason
        $projectionGap = $reason.IndexOf('projection', [System.StringComparison]::OrdinalIgnoreCase) -ge 0
        if ($projectionGap) {
            $gapTitle = 'Lineup recommendation needs current projections'
            $gapLede = 'Butler has your current roster, but current weekly projection evidence is unavailable.'
            $decisionCopy = 'No lineup recommendation until current weekly projections can be verified.'
            $whyCopy = 'The roster is available, but Butler cannot prove a weekly START/SIT recommendation without current projection evidence.'
            $retryLabel = 'Refresh Projections'
            $gapStatus = 'PROJECTIONS NEEDED'
        }
        else {
            $gapTitle = 'Lineup decision blocked by an evidence gap'
            $gapLede = 'Butler could not prove a complete weekly lineup, so there is no recommendation to follow.'
            $decisionCopy = 'No lineup recommendation. Review the current starters manually while the evidence gap remains.'
            $whyCopy = 'A required roster, scoring, identity, eligibility, or weekly evidence check is incomplete.'
            $retryLabel = 'Retry Lineup Review'
            $gapStatus = 'EVIDENCE GAP'
        }
        return "<section class=`"panel recommendation-panel`"><div class=`"manager-head`"><div><div class=`"eyebrow`">Lineup advisor</div><h2>$(ConvertTo-HtmlText $gapTitle)</h2><p class=`"lede`">$(ConvertTo-HtmlText $gapLede)</p></div><span class=`"status warn`">$(ConvertTo-HtmlText $gapStatus)</span></div><div class=`"manager-summary`"><div class=`"summary-card`"><h3>Decision</h3><p>$(ConvertTo-HtmlText $decisionCopy)</p></div><div class=`"summary-card`"><h3>Why</h3><p>$(ConvertTo-HtmlText $whyCopy)</p></div></div><details><summary>View evidence details</summary><div class=`"callout callout-danger`">$(ConvertTo-HtmlText $AutoFill.Reason)</div></details><div class=`"source-note`"><span>$frame &middot; Butler will not guess when required weekly evidence is missing.</span><div class=`"button-row`"><a class=`"btn btn-primary`" href=`"/team/autofill`">$(ConvertTo-HtmlText $retryLabel)</a><a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a></div></div><p class=`"meta`"><strong>Read only:</strong> Butler did not submit a lineup to Sleeper.</p></section>"
    }

    $rows = ''
    foreach ($assignment in $AutoFill.Assignments) {
        $rowClass = if ($assignment.Changed) { 'lineup-row changed' } else { 'lineup-row' }
        $decisionClass = if ($assignment.Changed) { 'decision-chip decision-change' } else { 'decision-chip decision-keep' }
        $decision = if ($assignment.Changed) { 'CHANGE' } else { 'KEEP' }
        $rows += "<div class=`"$rowClass`"><div><span class=`"position-chip`">$(ConvertTo-HtmlText $assignment.Slot)</span></div><div class=`"lineup-choice current`"><small>Current</small><strong>$(ConvertTo-HtmlText $assignment.Current)</strong></div><div class=`"lineup-arrow`">&rarr;</div><div class=`"lineup-choice recommended`"><small>Recommended</small><strong>$(ConvertTo-HtmlText $assignment.Recommended)</strong></div><div class=`"projection`">$(ConvertTo-HtmlText $assignment.Points)<span>proj pts</span></div><span class=`"$decisionClass`">$decision</span></div>"
    }

    $changedAssignments = @($AutoFill.Assignments | Where-Object { $_.Changed })
    $changedCount = $changedAssignments.Count
    $projectionHolds = @($AutoFill.ProjectionHolds)
    $holdCount = $projectionHolds.Count
    $partialCoverage = ([string]$AutoFill.ProjectionCoverage -ceq 'PARTIAL') -or $holdCount -gt 0
    $holdNames = @($projectionHolds | ForEach-Object { [string]$_.Name })
    $holdText = if ($holdNames.Count -eq 0) { '' } else { $holdNames -join ', ' }

    $benchNames = @($AutoFill.BenchMoves | ForEach-Object { [string]$_.Name })
    $promotionNames = @($AutoFill.Promotions | ForEach-Object { [string]$_.Name })
    $benchText = if ($benchNames.Count -eq 0) { 'No starter moves' } else { $benchNames -join ', ' }
    $promotionText = if ($promotionNames.Count -eq 0) { 'No starter moves' } else { $promotionNames -join ', ' }

    $benchChips = if ($AutoFill.BenchMoves.Count -eq 0) {
        '<span class="chip">No changes</span>'
    } else {
        (@($AutoFill.BenchMoves | ForEach-Object { "<span class=`"chip`">$(ConvertTo-HtmlText $_.Name)</span>" }) -join '')
    }
    $promotionChips = if ($AutoFill.Promotions.Count -eq 0) {
        '<span class="chip">No changes</span>'
    } else {
        (@($AutoFill.Promotions | ForEach-Object { "<span class=`"chip`">$(ConvertTo-HtmlText $_.Name)</span>" }) -join '')
    }
    $gainClass = if ([string]$AutoFill.Gain -match '^-') { 'metric-value metric-negative' } else { 'metric-value metric-positive' }

    if ($partialCoverage) {
        $holdWord = if ($holdCount -eq 1) { 'player' } else { 'players' }
        if ($changedCount -gt 0) {
            $changeWord = if ($changedCount -eq 1) { 'change' } else { 'changes' }
            $decisionTitle = "Make $changedCount lineup $changeWord; review $holdCount projection-held $holdWord"
            $decisionCopy = 'Butler found actionable changes among scoreable slots, but incomplete projection evidence prevents a full-lineup claim.'
        }
        else {
            $decisionTitle = "No proven changes in scoreable slots; review $holdCount projection-held $holdWord"
            $decisionCopy = 'Butler found no proven change among the scoreable slots. Projection-held players remain unchanged and still need manual review.'
        }
        $decisionStatus = 'PARTIAL REVIEW'
        $decisionStatusClass = 'warn'
        $whyCopy = "Projection-held: $holdText. Butler preserved those players in their current lineup state and excluded them from projected totals. The comparable scoreable-slot projection is $($AutoFill.CurrentTotal) current versus $($AutoFill.RecommendedTotal) recommended, a change of $($AutoFill.Gain)."
    }
    elseif ($changedCount -gt 0) {
        $changeWord = if ($changedCount -eq 1) { 'change' } else { 'changes' }
        $decisionTitle = "Make $changedCount lineup $changeWord"
        $decisionStatus = 'CHANGES FOUND'
        $decisionStatusClass = 'good'
        $decisionCopy = "Butler found a stronger legal projected lineup for this weekly frame. Review the START/SIT moves below before deciding what to do."
        $whyCopy = "The recommended legal lineup raises the projected starter total from $($AutoFill.CurrentTotal) to $($AutoFill.RecommendedTotal), a projected change of $($AutoFill.Gain) points. Butler is not claiming a separate per-player delta that is not present in the evidence."
    }
    else {
        $decisionTitle = 'Keep the current lineup'
        $decisionStatus = 'NO CHANGES'
        $decisionStatusClass = 'done'
        $decisionCopy = 'The read-only weekly review completed and found no proven lineup improvement over the current starters.'
        $whyCopy = "Within the current legal roster and weekly projection frame, no alternate starter assignment improved on the current projected starter total of $($AutoFill.CurrentTotal)."
    }

    $currentMetricLabel = if ($partialCoverage) { 'Comparable current' } else { 'Current projection' }
    $recommendedMetricLabel = if ($partialCoverage) { 'Comparable recommended' } else { 'Recommended' }
    $holdCallout = if ($partialCoverage) {
        "<div class=`"callout`"><strong>Projection hold:</strong> $(ConvertTo-HtmlText $holdText). Butler kept projection-held players in their current lineup state and did not assign synthetic points.</div>"
    } else { '' }

    return "<section class=`"panel recommendation-panel`"><div class=`"manager-head`"><div><div class=`"eyebrow`">Lineup advisor</div><h2>$(ConvertTo-HtmlText $decisionTitle)</h2><p class=`"lede`">$(ConvertTo-HtmlText $decisionCopy)</p></div><span class=`"status $decisionStatusClass`">$(ConvertTo-HtmlText $decisionStatus)</span></div><div class=`"grid four`"><div class=`"summary-card`"><h3>Decision</h3><p>$(ConvertTo-HtmlText $decisionTitle)</p></div><div class=`"summary-card`"><h3>Why</h3><p>$(ConvertTo-HtmlText $whyCopy)</p></div><div class=`"summary-card`"><h3>Start</h3><p>$(ConvertTo-HtmlText $promotionText)</p></div><div class=`"summary-card`"><h3>Sit</h3><p>$(ConvertTo-HtmlText $benchText)</p></div></div>$holdCallout<div class=`"autofill-summary`"><div class=`"metric-card`"><span class=`"metric-label`">$(ConvertTo-HtmlText $currentMetricLabel)</span><span class=`"metric-value`">$(ConvertTo-HtmlText $AutoFill.CurrentTotal)</span></div><div class=`"metric-card`"><span class=`"metric-label`">$(ConvertTo-HtmlText $recommendedMetricLabel)</span><span class=`"metric-value`">$(ConvertTo-HtmlText $AutoFill.RecommendedTotal)</span></div><div class=`"metric-card`"><span class=`"metric-label`">Projected change</span><span class=`"$gainClass`">$(ConvertTo-HtmlText $AutoFill.Gain)</span></div></div><div class=`"lineup-board`">$rows</div><div class=`"movement-strip`"><div class=`"movement-box`"><strong>Promote to lineup</strong><div>$promotionChips</div></div><div class=`"movement-box`"><strong>Move to bench</strong><div>$benchChips</div></div></div><div class=`"source-note`"><span>Projections: $(ConvertTo-HtmlText $AutoFill.Source) &middot; $frame &middot; Preview only</span><div class=`"button-row`"><a class=`"btn btn-secondary`" href=`"/team/autofill`">Refresh projection</a><a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a></div></div><p class=`"meta`"><strong>Read only:</strong> Butler did not submit this lineup to Sleeper.</p></section>"
}
'@

$core = Replace-FunctionBlock -Text $core -StartMarker 'function ConvertTo-AutoFillHtml {' -NextMarker 'function ConvertTo-TeamHtml {' -Replacement $autoFillReplacement -Contract 'Lineup Advisor decision summary'

# BF-822: the team-level badge describes roster synchronization only. It must not imply
# that weekly lineup projection evidence is also current.
$upToDateCount = [regex]::Matches($core, '>UP TO DATE<').Count
if ($upToDateCount -eq 1) {
    $core = $core.Replace('>UP TO DATE<', '>ROSTER CURRENT<')
}
elseif ($upToDateCount -gt 1) {
    throw "BF-822 BLOCKED: roster status badge is ambiguous ($upToDateCount matches)."
}

# These checks validate the raw generated PowerShell source. The idle HTML lives inside a
# single-quoted generated string, so its apostrophe is represented by two apostrophes here.
if (-not $autoFillReplacement.Contains("This week''s lineup decision")) {
    throw 'BF-817 BLOCKED: idle lineup decision summary is missing.'
}
if (-not $autoFillReplacement.Contains('Lineup recommendation needs current projections')) {
    throw 'BF-822 BLOCKED: provider-neutral projection evidence-gap summary is missing.'
}
if (-not $autoFillReplacement.Contains('PROJECTIONS NEEDED')) {
    throw 'BF-822 BLOCKED: projection-specific manager status is missing.'
}
if (-not $autoFillReplacement.Contains('View evidence details')) {
    throw 'BF-822 BLOCKED: evidence detail disclosure is missing.'
}
if (-not $autoFillReplacement.Contains('Keep the current lineup')) {
    throw 'BF-817 BLOCKED: no-change lineup decision summary is missing.'
}
if (-not $autoFillReplacement.Contains('PARTIAL REVIEW')) {
    throw 'BF-902 BLOCKED: projection-hold partial review summary is missing.'
}
if (-not $autoFillReplacement.Contains('did not assign synthetic points')) {
    throw 'BF-902 BLOCKED: projection-hold no-synthetic-points boundary is missing.'
}
if (-not $autoFillReplacement.Contains('Butler is not claiming a separate per-player delta')) {
    throw 'BF-817 BLOCKED: projection explanation boundary is missing.'
}
if ($core -notmatch 'Players by lineup state') {
    throw 'BF-817 BLOCKED: existing starter and bench roster board regressed.'
}

# Validate only the presentation block. Earlier governed transforms may contain old provider
# identifiers, but the manager-facing Lineup Advisor must remain provider-neutral and read-only.
if ($autoFillReplacement -match 'Method = "POST"|BUTLER_FANTASYPROS_API_KEY|AutoFillLineupOptimizer|Invoke-RestMethod') {
    throw 'BF-822 BLOCKED: Lineup Advisor presentation introduced credential, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

# BF-823: after Lineup Advisor staging, replace raw evidence failures with manager-facing
# recovery states that lead to the existing explicit token-gated refresh confirmation.
$bf823Transform = Join-Path $PSScriptRoot 'butler-app-bf823-evidence-recovery-transform.ps1'
if (-not (Test-Path -LiteralPath $bf823Transform -PathType Leaf)) {
    throw "BF-823 BLOCKED: evidence recovery transform not found at $bf823Transform"
}
& $bf823Transform -CorePath $CorePath

# BF-825: carry exact read-only unavailable-player evidence into the manager-facing Lineup Advisor.
$bf825Transform = Join-Path $PSScriptRoot 'butler-app-bf825-lineup-availability-transform.ps1'
if (-not (Test-Path -LiteralPath $bf825Transform -PathType Leaf)) {
    throw "BF-825 BLOCKED: lineup availability transform not found at $bf825Transform"
}
& $bf825Transform -CorePath $CorePath

# BF-827: replace generic My Team placeholder cards with evidence-backed roster intelligence.
$bf827Transform = Join-Path $PSScriptRoot 'butler-app-bf827-roster-intelligence-transform.ps1'
if (-not (Test-Path -LiteralPath $bf827Transform -PathType Leaf)) {
    throw "BF-827 BLOCKED: roster intelligence transform not found at $bf827Transform"
}
& $bf827Transform -CorePath $CorePath

# BF-818: after Lineup Advisor staging, upgrade the sibling Waiver Board into a
# manager-first decision summary while preserving the existing governed shortlist.
$stagedDashboard = Join-Path (Split-Path -Parent $CorePath) 'butler-dashboard.ps1'
if (Test-Path -LiteralPath $stagedDashboard -PathType Leaf) {
    $bf818Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf818-waiver-advisor-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf818Transform -PathType Leaf)) {
        throw "BF-818 BLOCKED: Waiver Advisor transform not found at $bf818Transform"
    }
    & $bf818Transform -DashboardPath $stagedDashboard
}