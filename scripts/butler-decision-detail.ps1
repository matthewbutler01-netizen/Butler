# BF-672/BF-682/BF-684/BF-685 read-only Decision Detail extension for BF-671 History.
# BF-628 validates the exact immutable audit before BF-653 explanation lookup.

function ConvertFrom-DecisionHistoryRequestTarget {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $query = @{}
    $question = $RequestTarget.IndexOf('?')
    if ($question -lt 0 -or $question + 1 -ge $RequestTarget.Length) { return $query }

    $rawQuery = $RequestTarget.Substring($question + 1)
    foreach ($pair in ($rawQuery -split '&')) {
        if ([string]::IsNullOrWhiteSpace($pair)) { continue }
        $equals = $pair.IndexOf('=')
        if ($equals -lt 0) {
            $rawKey = $pair.Replace('+', ' ')
            $rawValue = ''
        }
        else {
            $rawKey = $pair.Substring(0, $equals).Replace('+', ' ')
            $rawValue = $pair.Substring($equals + 1).Replace('+', ' ')
        }
        $key = [System.Uri]::UnescapeDataString($rawKey)
        $value = [System.Uri]::UnescapeDataString($rawValue)
        if ($key -cnotin @('load', 'audit', 'detail')) {
            throw "BF-672 BLOCKED: unsupported Decision History query parameter: $key"
        }
        if ($query.ContainsKey($key)) {
            throw "BF-672 BLOCKED: duplicate Decision History query parameter: $key"
        }
        $query[$key] = $value
    }
    return $query
}

function Get-ExactDecisionHistoryEntry {
    param(
        [Parameter(Mandatory = $true)]$History,
        [Parameter(Mandatory = $true)][string]$AuditId
    )

    if ($AuditId -cnotmatch '^[A-Za-z0-9-]{1,128}$') {
        throw 'BF-672 BLOCKED: requested audit id has an invalid shape.'
    }
    $matches = @($History.Entries | Where-Object { $_.AuditId -ceq $AuditId })
    if ($matches.Count -ne 1) {
        throw "BF-672 BLOCKED: requested BF-627 audit id must resolve exactly once in BF-628 history; found $($matches.Count)."
    }
    return $matches[0]
}

function Add-DecisionDetailLinks {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)]$History
    )

    $result = $Html
    foreach ($entry in @($History.Entries)) {
        $safeAudit = ConvertTo-HtmlText $entry.AuditId
        $marker = "<p class=`"history-lineage`">Audit: $safeAudit</p>"
        $markerIndex = $result.IndexOf($marker, [System.StringComparison]::Ordinal)
        if ($markerIndex -lt 0) {
            throw "BF-672 BLOCKED: BF-671 history card marker is missing for audit $($entry.AuditId)."
        }
        $endIndex = $result.IndexOf('</article>', $markerIndex, [System.StringComparison]::Ordinal)
        if ($endIndex -lt 0) {
            throw "BF-672 BLOCKED: BF-671 history card is malformed for audit $($entry.AuditId)."
        }
        $encodedAudit = [System.Uri]::EscapeDataString($entry.AuditId)
        $link = "<p><a href=`"/history?audit=$encodedAudit`" style=`"display:inline-block;margin-top:4px;color:#a9c6ff;font-weight:800;text-decoration:none`">View decision</a></p>"
        $result = $result.Insert($endIndex, $link)
    }
    return $result
}

function Get-DecisionDetailLoadingHtml {
    param(
        [Parameter(Mandatory = $true)][string]$LeagueId,
        [Parameter(Mandatory = $true)][string]$AuditId
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'history'
    $safeLeague = ConvertTo-HtmlText $LeagueId
    $safeAudit = ConvertTo-HtmlText $AuditId
    $encodedAudit = [System.Uri]::EscapeDataString($AuditId)
    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="1;url=/history?audit=$encodedAudit&amp;detail=1"><title>Butler Decision Detail</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$safeLeague</div></header>
$nav
<section class="panel"><div class="eyebrow">Governed decision detail</div><div class="statusrow"><div><h1 class="headline">Opening Decision Detail...</h1><p class="lede">Validating the exact immutable BF-627 audit in BF-628 before reading its persisted BF-653 explanation.</p></div><div class="status done">READ ONLY</div></div><div class="empty">Audit: $safeAudit</div></section>
</main></body></html>
"@
}

function ConvertTo-DecisionExplanationView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $policy = [regex]::Match($Text, '(?m)^Policy:\s+(?<value>.+?)\s*$')
    $state = [regex]::Match($Text, '(?m)^Lookup state:\s+(?<value>\S+)\s*$')
    $audit = [regex]::Match($Text, '(?m)^BF-627 audit id:\s+(?<value>\S+)\s*$')
    $explanation = [regex]::Match($Text, '(?m)^Explanation id:\s+(?<value>\S+)\s*$')
    $captured = [regex]::Match($Text, '(?m)^Captured at UTC:\s+(?<value>\S+)\s*$')
    $snapshots = [regex]::Match($Text, '(?m)^BF-603 market / BF-602 waiver snapshot:\s+(?<market>\S+)\s+/\s+(?<waiver>\S+)\s*$')
    $players = [regex]::Match($Text, '(?m)^Audited add / drop Sleeper ids:\s+(?<add>\S+)\s+/\s+(?<drop>\S+)\s*$')
    $type = [regex]::Match($Text, '(?m)^Explanation type:\s+(?<value>.+?)\s*$')
    $why = [regex]::Match($Text, '(?m)^Why this move:\s+(?<value>.*?)\s*$')
    $evidencePolicy = [regex]::Match($Text, '(?m)^Evidence policy:\s+(?<value>.+?)\s*$')
    $evidenceTrace = [regex]::Match($Text, '(?m)^Evidence trace:\s+(?<value>.*?)\s*$')
    if (-not $policy.Success -or -not $state.Success -or -not $audit.Success -or -not $explanation.Success -or
        -not $captured.Success -or -not $snapshots.Success -or -not $players.Success -or -not $type.Success -or
        -not $why.Success -or -not $evidencePolicy.Success -or -not $evidenceTrace.Success) {
        throw 'BF-672 BLOCKED: BF-653 explanation output is missing required fields.'
    }

    $view = [pscustomobject]@{
        Policy = $policy.Groups['value'].Value.Trim()
        State = $state.Groups['value'].Value.Trim()
        AuditId = $audit.Groups['value'].Value.Trim()
        ExplanationId = $explanation.Groups['value'].Value.Trim()
        Captured = $captured.Groups['value'].Value.Trim()
        MarketSnapshotId = $snapshots.Groups['market'].Value.Trim()
        WaiverSnapshotId = $snapshots.Groups['waiver'].Value.Trim()
        AddSleeperId = $players.Groups['add'].Value.Trim()
        DropSleeperId = $players.Groups['drop'].Value.Trim()
        ExplanationType = $type.Groups['value'].Value.Trim()
        ExplanationText = $why.Groups['value'].Value.Trim()
        EvidencePolicy = $evidencePolicy.Groups['value'].Value.Trim()
        EvidenceTrace = $evidenceTrace.Groups['value'].Value.Trim()
    }
    if ($view.State -cnotin @('EXPLANATION_READY', 'EXPLANATION_NOT_CAPTURED')) {
        throw "BF-672 BLOCKED: unsupported BF-653 lookup state $($view.State)."
    }
    if ($view.State -ceq 'EXPLANATION_NOT_CAPTURED') {
        foreach ($value in @($view.ExplanationId, $view.Captured, $view.ExplanationType, $view.ExplanationText, $view.EvidencePolicy, $view.EvidenceTrace)) {
            if ($value -cne 'none') {
                throw 'BF-672 BLOCKED: BF-653 no-explanation state contains unexpected persisted explanation fields.'
            }
        }
    }
    elseif ($view.ExplanationId -ceq 'none' -or $view.Captured -ceq 'none' -or $view.ExplanationType -ceq 'none' -or $view.ExplanationText -ceq 'none') {
        throw 'BF-672 BLOCKED: BF-653 ready explanation is missing required persisted fields.'
    }
    return $view
}

function Assert-DecisionExplanationReconciled {
    param(
        [Parameter(Mandatory = $true)]$Entry,
        [Parameter(Mandatory = $true)]$Explanation
    )

    if ($Explanation.AuditId -cne $Entry.AuditId -or
        $Explanation.MarketSnapshotId -cne $Entry.MarketSnapshotId -or
        $Explanation.WaiverSnapshotId -cne $Entry.WaiverSnapshotId -or
        $Explanation.AddSleeperId -cne $Entry.AddSleeperId -or
        $Explanation.DropSleeperId -cne $Entry.DropSleeperId) {
        throw 'BF-672 BLOCKED: BF-653 explanation does not exactly reconcile to the selected BF-628 audit.'
    }
}

function ConvertTo-DecisionDetailHtml {
    param(
        [Parameter(Mandatory = $true)]$History,
        [Parameter(Mandatory = $true)]$Entry,
        [Parameter(Mandatory = $true)]$Explanation
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'history'
    $capturedLabel = ConvertTo-HistoryCapturedLabel -Captured $Entry.Captured
    $explanationCapturedLabel = if ($Explanation.State -ceq 'EXPLANATION_READY') { ConvertTo-HistoryCapturedLabel -Captured $Explanation.Captured } else { $Explanation.Captured }
    $selectionLabel = if ($Entry.SelectionState -ceq 'NO_HISTORICAL_FINALIST') { 'No unique finalist' } else { $Entry.SelectionState }
    $recommendationLabel = if ($Entry.RecommendationState -ceq 'NO_GOVERNED_TRANSACTION') { 'No governed transaction' } else { $Entry.RecommendationState }
    $explanationTypeLabel = if ($Explanation.ExplanationType -ceq 'NO_GOVERNED_TRANSACTION') { 'No governed transaction' } else { $Explanation.ExplanationType }
    $detailCss = @'
.detail-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:16px}.detail-item{padding:14px;border:1px solid #2b3962;border-radius:12px;background:#0d1630}.detail-item strong{display:block;color:#8797bd;font-size:10px;text-transform:uppercase;letter-spacing:.07em}.detail-item span{display:block;margin-top:5px;font-weight:800;word-break:break-word}.explanation-copy{font-size:18px;line-height:1.55;color:#e8edfb}.mono{font:12px Consolas,monospace;color:#a9b5d2;word-break:break-word}.back-link{color:#a9c6ff;font-weight:800;text-decoration:none}@media(max-width:760px){.detail-grid{grid-template-columns:1fr}}
'@
    $decisionLabel = if ($Entry.RecommendationState -ceq 'NO_GOVERNED_TRANSACTION') { 'No governed transaction' } elseif ($Entry.RecommendationState -ceq 'RECOMMEND_ADD_DROP') { "ADD $(ConvertTo-HtmlText $Entry.AddSleeperId) / DROP $(ConvertTo-HtmlText $Entry.DropSleeperId)" } else { ConvertTo-HtmlText $Entry.RecommendationState }
    $explanationPanel = if ($Explanation.State -ceq 'EXPLANATION_READY') {
        @"
<section class="panel"><div class="eyebrow">Persisted governed explanation</div><div class="statusrow"><div><h2 class="headline">Why this decision?</h2><p class="explanation-copy">$(ConvertTo-HtmlText $Explanation.ExplanationText)</p></div><div class="status good">EXPLANATION READY</div></div><div class="detail-grid"><div class="detail-item"><strong>Explanation type</strong><span>$(ConvertTo-HtmlText $explanationTypeLabel)</span></div><div class="detail-item"><strong>Explanation captured</strong><span>$(ConvertTo-HtmlText $explanationCapturedLabel)</span></div><div class="detail-item"><strong>Explanation id</strong><span>$(ConvertTo-HtmlText $Explanation.ExplanationId)</span></div></div><details><summary>Explanation evidence lineage</summary><p class="mono">Captured UTC: $(ConvertTo-HtmlText $Explanation.Captured)</p><p class="mono">Explanation type: $(ConvertTo-HtmlText $Explanation.ExplanationType)</p><p class="mono">Policy: $(ConvertTo-HtmlText $Explanation.Policy)</p><p class="mono">Evidence policy: $(ConvertTo-HtmlText $Explanation.EvidencePolicy)</p><p class="mono">Evidence trace: $(ConvertTo-HtmlText $Explanation.EvidenceTrace)</p></details></section>
"@
    }
    else {
        @"
<section class="panel"><div class="eyebrow">Persisted governed explanation</div><div class="statusrow"><div><h2 class="headline">No persisted explanation</h2><p class="lede">BF-653 reports EXPLANATION_NOT_CAPTURED for this immutable audit. Butler will not reconstruct or generate historical reasoning that was not persisted with the decision.</p></div><div class="status warn">LEGACY / NOT CAPTURED</div></div></section>
"@
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Decision Detail</title><style>$css$detailCss</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $History.LeagueId) &middot; roster $(ConvertTo-HtmlText $History.RosterId)</div></header>
$nav
<p><a class="back-link" href="/history?load=1">&larr; Back to Decision History</a></p>
<section class="panel"><div class="eyebrow">Immutable decision</div><div class="statusrow"><div><h1 class="headline">$decisionLabel</h1><p class="lede">Captured $(ConvertTo-HtmlText $capturedLabel). This detail view is reconciled to one exact BF-628 integrity-verified BF-627 audit.</p></div><div class="status good">$(ConvertTo-HtmlText $Entry.IntegrityState)</div></div><div class="detail-grid"><div class="detail-item"><strong>Provider frame</strong><span>$(ConvertTo-HtmlText $Entry.ProviderSeason) / $(ConvertTo-HtmlText $Entry.ProviderStatus) / $(ConvertTo-HtmlText $Entry.ProviderLeg)</span></div><div class="detail-item"><strong>Selection state</strong><span>$(ConvertTo-HtmlText $selectionLabel)</span></div><div class="detail-item"><strong>Recommendation</strong><span>$(ConvertTo-HtmlText $recommendationLabel)</span></div></div><details><summary>Audit and evidence lineage</summary><p class="mono">Captured UTC: $(ConvertTo-HtmlText $Entry.Captured)</p><p class="mono">Selection state: $(ConvertTo-HtmlText $Entry.SelectionState)</p><p class="mono">Recommendation state: $(ConvertTo-HtmlText $Entry.RecommendationState)</p><p class="mono">Audit: $(ConvertTo-HtmlText $Entry.AuditId)</p><p class="mono">BF-603 market: $(ConvertTo-HtmlText $Entry.MarketSnapshotId)</p><p class="mono">BF-602 waiver: $(ConvertTo-HtmlText $Entry.WaiverSnapshotId)</p><p class="mono">ADD / DROP Sleeper ids: $(ConvertTo-HtmlText $Entry.AddSleeperId) / $(ConvertTo-HtmlText $Entry.DropSleeperId)</p></details></section>
$explanationPanel
<section class="panel boundary"><span class="lock">READ ONLY.</span> BF-672 validates the selected audit through BF-628 and reads only its persisted BF-653 explanation state. It cannot capture an explanation, rerun a recommendation, refresh evidence, execute BF-641, set FAAB, alter a roster, or submit a Sleeper transaction.</section>
</main></body></html>
"@
}

function Invoke-DecisionHistoryHtml {
    param(
        [Parameter(Mandatory = $true)][string]$LeagueId,
        [Parameter(Mandatory = $true)][string]$RequestTarget
    )

    $query = ConvertFrom-DecisionHistoryRequestTarget -RequestTarget $RequestTarget
    $auditId = if ($query.ContainsKey('audit')) { [string]$query['audit'] } else { $null }
    $detail = $query.ContainsKey('detail') -and [string]$query['detail'] -ceq '1'

    if (-not [string]::IsNullOrWhiteSpace($auditId) -and -not $detail) {
        if ($auditId -cnotmatch '^[A-Za-z0-9-]{1,128}$') { throw 'BF-672 BLOCKED: requested audit id has an invalid shape.' }
        return Get-DecisionDetailLoadingHtml -LeagueId $LeagueId -AuditId $auditId
    }
    if ($detail -and [string]::IsNullOrWhiteSpace($auditId)) {
        throw 'BF-672 BLOCKED: detail mode requires one exact audit id.'
    }

    $raw = Invoke-ButlerReadOnlyTask -Task ':bet:bet-cli:sleeperLiveWaiverRecommendationAuditHistory' -Arguments $LeagueId -BoundaryName 'BF-672'
    $history = ConvertTo-DecisionHistoryView -Text $raw
    if ($history.LeagueId -cne $LeagueId) {
        throw 'BF-672 BLOCKED: BF-628 history league does not match the configured Butler league.'
    }

    if ([string]::IsNullOrWhiteSpace($auditId)) {
        $html = ConvertTo-DecisionHistoryHtml -History $history
        return Add-DecisionDetailLinks -Html $html -History $history
    }

    $entry = Get-ExactDecisionHistoryEntry -History $history -AuditId $auditId
    $explanationRaw = Invoke-ButlerReadOnlyTask -Task ':bet:bet-cli:sleeperLiveWaiverGovernedExplanationLookup' -Arguments "$LeagueId $auditId" -BoundaryName 'BF-672'
    $explanation = ConvertTo-DecisionExplanationView -Text $explanationRaw
    Assert-DecisionExplanationReconciled -Entry $entry -Explanation $explanation
    return ConvertTo-DecisionDetailHtml -History $history -Entry $entry -Explanation $explanation
}
