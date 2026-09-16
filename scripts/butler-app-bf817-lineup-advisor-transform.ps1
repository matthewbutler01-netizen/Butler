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
        return '<section class="panel recommendation-panel"><div class="manager-head"><div><div class="eyebrow">Lineup advisor</div><h2>This week''s lineup decision</h2><p class="lede">Butler has not evaluated this weekly lineup yet.</p></div><span class="status done">NOT REVIEWED</span></div><div class="manager-summary"><div class="summary-card"><h3>Decision</h3><p>No lineup recommendation yet. Run the read-only AutoFill review when you want Butler to evaluate the current starters.</p></div><div class="summary-card"><h3>Why</h3><p>No weekly projection frame has been requested from this page, so Butler will not imply that the current lineup is already optimal.</p></div></div><div class="button-row"><a class="btn btn-primary" href="/team/autofill">Run AutoFill</a></div><p class="meta"><strong>Read only:</strong> projection evidence is requested only after you run AutoFill. Nothing is submitted to Sleeper.</p></section>'
    }

    $frame = "Week $(ConvertTo-HtmlText $AutoFill.Week) &middot; $(ConvertTo-HtmlText $AutoFill.Scoring) scoring"
    if (-not $AutoFill.Ready) {
        return "<section class=`"panel recommendation-panel`"><div class=`"manager-head`"><div><div class=`"eyebrow`">Lineup advisor</div><h2>Lineup decision blocked by an evidence gap</h2><p class=`"lede`">Butler could not prove a complete weekly lineup, so there is no recommendation to follow.</p></div><span class=`"status warn`">EVIDENCE GAP</span></div><div class=`"manager-summary`"><div class=`"summary-card`"><h3>Decision</h3><p>No lineup recommendation. Review the current starters manually while the evidence gap remains.</p></div><div class=`"summary-card`"><h3>Why</h3><p>$(ConvertTo-HtmlText $AutoFill.Reason)</p></div></div><div class=`"callout callout-danger`">$(ConvertTo-HtmlText $AutoFill.Reason)</div><div class=`"source-note`"><span>$frame &middot; Retry only after the missing projection or provider evidence becomes available.</span><div class=`"button-row`"><a class=`"btn btn-primary`" href=`"/team/autofill`">Retry AutoFill</a><a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a></div></div><p class=`"meta`"><strong>Read only:</strong> Butler did not submit a lineup to Sleeper.</p></section>"
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

    if ($changedCount -gt 0) {
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

    return "<section class=`"panel recommendation-panel`"><div class=`"manager-head`"><div><div class=`"eyebrow`">Lineup advisor</div><h2>$(ConvertTo-HtmlText $decisionTitle)</h2><p class=`"lede`">$(ConvertTo-HtmlText $decisionCopy)</p></div><span class=`"status $decisionStatusClass`">$(ConvertTo-HtmlText $decisionStatus)</span></div><div class=`"grid four`"><div class=`"summary-card`"><h3>Decision</h3><p>$(ConvertTo-HtmlText $decisionTitle)</p></div><div class=`"summary-card`"><h3>Why</h3><p>$(ConvertTo-HtmlText $whyCopy)</p></div><div class=`"summary-card`"><h3>Start</h3><p>$(ConvertTo-HtmlText $promotionText)</p></div><div class=`"summary-card`"><h3>Sit</h3><p>$(ConvertTo-HtmlText $benchText)</p></div></div><div class=`"autofill-summary`"><div class=`"metric-card`"><span class=`"metric-label`">Current projection</span><span class=`"metric-value`">$(ConvertTo-HtmlText $AutoFill.CurrentTotal)</span></div><div class=`"metric-card`"><span class=`"metric-label`">Recommended</span><span class=`"metric-value`">$(ConvertTo-HtmlText $AutoFill.RecommendedTotal)</span></div><div class=`"metric-card`"><span class=`"metric-label`">Projected change</span><span class=`"$gainClass`">$(ConvertTo-HtmlText $AutoFill.Gain)</span></div></div><div class=`"lineup-board`">$rows</div><div class=`"movement-strip`"><div class=`"movement-box`"><strong>Promote to lineup</strong><div>$promotionChips</div></div><div class=`"movement-box`"><strong>Move to bench</strong><div>$benchChips</div></div></div><div class=`"source-note`"><span>Projections: $(ConvertTo-HtmlText $AutoFill.Source) &middot; $frame &middot; Preview only</span><div class=`"button-row`"><a class=`"btn btn-secondary`" href=`"/team/autofill`">Refresh projection</a><a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a></div></div><p class=`"meta`"><strong>Read only:</strong> Butler did not submit this lineup to Sleeper.</p></section>"
}
'@

$core = Replace-FunctionBlock -Text $core -StartMarker 'function ConvertTo-AutoFillHtml {' -NextMarker 'function ConvertTo-TeamHtml {' -Replacement $autoFillReplacement -Contract 'Lineup Advisor decision summary'

if ($core -notmatch 'This week''s lineup decision') {
    throw 'BF-817 BLOCKED: idle lineup decision summary is missing.'
}
if ($core -notmatch 'Lineup decision blocked by an evidence gap') {
    throw 'BF-817 BLOCKED: evidence-gap lineup decision summary is missing.'
}
if ($core -notmatch 'Keep the current lineup') {
    throw 'BF-817 BLOCKED: no-change lineup decision summary is missing.'
}
if ($core -notmatch 'Butler is not claiming a separate per-player delta') {
    throw 'BF-817 BLOCKED: projection explanation boundary is missing.'
}
if ($core -notmatch 'Players by lineup state') {
    throw 'BF-817 BLOCKED: existing starter and bench roster board regressed.'
}
if ($core -match 'Method = "POST"|BUTLER_FANTASYPROS_API_KEY|AutoFillLineupOptimizer') {
    throw 'BF-817 BLOCKED: Lineup Advisor presentation introduced credential, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
