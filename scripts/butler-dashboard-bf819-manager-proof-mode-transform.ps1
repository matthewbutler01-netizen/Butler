param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-819 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-819 BLOCKED: dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)
$finalReturn = $dashboardBlock.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
$functionClose = $dashboardBlock.LastIndexOf('}', [System.StringComparison]::Ordinal)
if ($finalReturn -lt 0 -or $functionClose -le $finalReturn) {
    throw 'BF-819 BLOCKED: final dashboard renderer boundary is missing.'
}

$managerPrelude = @'
    # BF-819 is presentation-only. It reuses the already-derived priority, evidence, record,
    # and safety state, then separates the fast manager scan from expandable proof.
    $managerAttentionCount = @($orderedPrioritySignals | Where-Object { [string]$_.AttentionGroup -ceq "attention" }).Count
    $managerHeroCopy = if ($managerAttentionCount -eq 1) { "1 item needs your attention." } elseif ($managerAttentionCount -gt 1) { "$managerAttentionCount items need your attention." } else { "Nothing needs immediate attention." }

    $lineupViews = @{
        "REFRESH AUTOFILL" = @("Your lineup recommendation is out of date", "Your roster or projection data changed since the last lineup review.", "REFRESH", "warn", "/team/autofill", "Refresh Lineup")
        "EVIDENCE GAP" = @("Lineup review needs more evidence", "Butler could not verify every projection needed for a complete lineup recommendation.", "CHECK EVIDENCE", "warn", "/team", "Review My Team")
        "AUTOFILL READY" = @("Lineup changes are ready to review", "The latest lineup review found a stronger projected legal lineup.", "REVIEW", "good", "/team", "Review Lineup")
        "NO CHANGES" = @("No lineup change proven", "The latest lineup review supports keeping your current starters.", "NO CHANGE", "done", "/team", "View Lineup")
        "NEEDS ATTENTION" = @("Your roster needs review first", "Butler cannot rely on the current roster state for a lineup recommendation yet.", "REVIEW", "warn", "/team", "Review My Team")
        "NOT REVIEWED" = @("Lineup has not been reviewed yet", "Run a read-only lineup review when you want Butler to evaluate this week's starters.", "NOT REVIEWED", "done", "/team/autofill", "Review Lineup")
    }
    $waiverViews = @{
        "CURRENT_AND_ACTIONABLE" = @("A waiver move is ready to review", "One add/drop move passed Butler's current checks.", "MOVE READY", "good", "/waivers", "Review Waiver Move")
        "CURRENT_REFRESH_RECOMMENDED" = @("Refresh waiver evidence before acting", "A saved add/drop move exists, but its supporting evidence should be refreshed first.", "REFRESH", "warn", "/waivers", "Open Waiver Board")
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" = @("Your waiver move is already pending", "Sleeper is processing the saved transaction. Do not submit it again.", "WAIT", "warn", "/history", "View History")
        "TRANSACTION_ALREADY_COMPLETE" = @("Waiver move complete", "The saved transaction is complete. No duplicate action is needed.", "COMPLETE", "done", "/history", "View History")
        "STALE_DO_NOT_ACT" = @("Do not act on the saved waiver move", "The saved move no longer passes Butler's current safety checks.", "DO NOT ACT", "danger", "/waivers", "Open Waiver Board")
        "NO_TRANSACTION_TO_ACT_ON" = @("No waiver move proven", "Current review found no add/drop strong enough to recommend.", "NO MOVE", "done", "/waivers", "Waiver Board")
    }

    $managerCardList = New-Object System.Collections.Generic.List[string]
    for ($managerIndex = 0; $managerIndex -lt $orderedPrioritySignals.Count; $managerIndex++) {
        $signal = $orderedPrioritySignals[$managerIndex]
        $kind = [string]$signal.Kind
        $title = [string]$signal.Title
        $copy = [string]$signal.Copy
        $status = [string]$signal.Status
        $statusClass = [string]$signal.StatusClass
        $actionHref = "/"
        $actionLabel = "Review"
        $secondaryActions = ""
        $chips = ""
        $extraHtml = [string]$signal.ExtraHtml

        if ($kind -ceq "Lineup") {
            $view = $lineupViews[[string]$lineupSignalStatus]
            if ($null -eq $view) { $view = $lineupViews["NOT REVIEWED"] }
            $title = $view[0]; $copy = $view[1]; $status = $view[2]; $statusClass = $view[3]; $actionHref = $view[4]; $actionLabel = $view[5]
            $rosterChipClass = if ($verification.RosterOk) { "ok" } else { "warn" }
            $rosterChipText = if ($verification.RosterOk) { "Roster verified" } else { "Roster needs review" }
            $projectionChipClass = if ([string]$lineupSignalStatus -ceq "REFRESH AUTOFILL" -or [string]$lineupSignalStatus -ceq "EVIDENCE GAP") { "warn" } else { "ok" }
            $projectionChipText = switch ([string]$lineupSignalStatus) { "REFRESH AUTOFILL" { "Projections stale" } "EVIDENCE GAP" { "Projection gap" } "NOT REVIEWED" { "Projections not reviewed" } default { "Projection frame loaded" } }
            $chips = "<span class=`"manager-chip $rosterChipClass`">$(ConvertTo-HtmlText $rosterChipText)</span><span class=`"manager-chip $projectionChipClass`">$(ConvertTo-HtmlText $projectionChipText)</span>"
            if ($null -ne $lineupSnapshot) {
                $frameParts = New-Object System.Collections.Generic.List[string]
                if (-not [string]::IsNullOrWhiteSpace([string]$lineupSnapshot.Week)) { $frameParts.Add("Week $($lineupSnapshot.Week)") }
                if (-not [string]::IsNullOrWhiteSpace([string]$lineupSnapshot.Scoring)) { $frameParts.Add([string]$lineupSnapshot.Scoring) }
                if ($frameParts.Count -gt 0) { $chips += "<span class=`"manager-chip`">$(ConvertTo-HtmlText ($frameParts -join ' | '))</span>" }
            }
            $secondaryActions = '<a class="command-button secondary" href="/team">My Team</a>'
        }
        elseif ($kind -ceq "Waiver") {
            $view = $waiverViews[[string]$state]
            if ($null -ne $view) { $title = $view[0]; $copy = $view[1]; $status = $view[2]; $statusClass = $view[3]; $actionHref = $view[4]; $actionLabel = $view[5] }
            else { $title = "No waiver decision available"; $copy = "Butler does not have a current waiver move to act on."; $status = "UNAVAILABLE"; $statusClass = "warn"; $actionHref = "/waivers"; $actionLabel = "Waiver Board" }
            $secondaryActions = '<a class="command-button secondary" href="/history">History</a>'
        }
        elseif ($kind -ceq "Trade") {
            $title = "No active trade decision"; $copy = "Use Trade Lab when you have a deal to evaluate."; $status = "ON DEMAND"; $statusClass = "done"; $actionHref = "/trade"; $actionLabel = "Trade Lab"; $extraHtml = ""
        }

        $cardClass = if ($managerIndex -eq 0) { "manager-decision-card primary" } else { "manager-decision-card" }
        $displayPriority = "{0:D2}" -f ($managerIndex + 1)
        $chipRow = if ([string]::IsNullOrWhiteSpace($chips)) { "" } else { "<div class=`"manager-chip-row`">$chips</div>" }
        $managerCardList.Add(@"
<article class="$cardClass"><div class="manager-priority-index">$displayPriority</div><div class="manager-decision-main"><div class="manager-kind">$(ConvertTo-HtmlText $kind)</div><h3>$(ConvertTo-HtmlText $title)</h3><p>$(ConvertTo-HtmlText $copy)</p>$chipRow$extraHtml<div class="manager-card-actions"><a class="command-button" href="$(ConvertTo-HtmlText $actionHref)">$(ConvertTo-HtmlText $actionLabel)</a>$secondaryActions</div></div><div class="status $statusClass">$(ConvertTo-HtmlText $status)</div></article>
"@)
    }
    $managerQueueHtml = $managerCardList -join "`n"

    $proofChangeCopy = "No additional change detail is available for this priority."
    if ($null -ne $priorityOne) {
        if ([string]$priorityOne.Kind -ceq "Lineup") {
            $proofChangeCopy = switch ([string]$lineupSignalStatus) { "REFRESH AUTOFILL" { "The saved lineup review no longer matches the current roster or weekly projection frame." } "EVIDENCE GAP" { "The latest lineup review stopped because required projection evidence was incomplete." } "AUTOFILL READY" { "The latest lineup review found projected starter changes worth reviewing." } "NO CHANGES" { "The latest lineup review completed without proving an improvement over the current starters." } "NEEDS ATTENTION" { "The current roster state must be reviewed before Butler can rely on a lineup result." } default { "No lineup review has been requested for this weekly frame yet." } }
        }
        elseif ([string]$priorityOne.Kind -ceq "Waiver") {
            $proofChangeCopy = switch ([string]$state) { "CURRENT_AND_ACTIONABLE" { "The current waiver review proved one add/drop pair that remains actionable." } "CURRENT_REFRESH_RECOMMENDED" { "The saved waiver move remains available, but its evidence crossed the refresh warning threshold." } "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "Sleeper now reports the saved transaction as pending." } "TRANSACTION_ALREADY_COMPLETE" { "Sleeper now reports the saved transaction as complete." } "STALE_DO_NOT_ACT" { "A current safety check invalidated action on the saved waiver move." } "NO_TRANSACTION_TO_ACT_ON" { "The current waiver review completed without proving an add/drop move." } default { "No current governed waiver transaction is available to act on." } }
        }
        elseif ([string]$priorityOne.Kind -ceq "Trade") { $proofChangeCopy = "No trade proposal is loaded on the Dashboard; Trade Lab remains on demand." }
    }

    $bf819Css = @"
.manager-hero{padding:24px 26px;background:linear-gradient(135deg,#17365f 0%,#0c1f3c 65%);border-color:#315f96}.manager-hero .command-copy{max-width:720px;font-size:17px}.manager-hero .command-meta{display:flex;gap:9px;flex-wrap:wrap;align-items:center;margin-top:18px}.manager-hero .command-meta span{display:inline-flex;align-items:center;padding:6px 10px;border:1px solid #31557f;border-radius:999px;background:#0b2341;color:#a9ccf7;font-size:12px;font-weight:800;line-height:1.2}.manager-queue{padding:22px}.manager-queue-head{margin-bottom:16px}.manager-queue-head h2{margin:4px 0 0}.manager-queue-head p{margin:6px 0 0;color:#9fc5ff}.manager-decision-stack{display:grid;gap:16px}.manager-decision-card{display:grid;grid-template-columns:44px minmax(0,1fr) auto;gap:16px;align-items:start;border:1px solid #25466f;border-radius:15px;background:#071a31;padding:18px}.manager-decision-card.primary{background:#102f54;border-color:#3d73b2}.manager-priority-index{display:flex;align-items:center;justify-content:center;min-width:34px;height:34px;border-radius:10px;background:#0f3765;color:#8fc3ff;font-weight:900;font-size:13px}.manager-kind{text-transform:uppercase;letter-spacing:.12em;color:#7db7ff;font-size:11px;font-weight:900}.manager-decision-main h3{margin:5px 0 7px;font-size:20px;line-height:1.15}.manager-decision-main p{margin:0;color:#b9d8ff;line-height:1.45;max-width:780px}.manager-card-actions,.manager-chip-row{display:flex;gap:9px;flex-wrap:wrap;margin-top:13px}.manager-chip-row{margin-top:12px;gap:8px}.manager-chip{display:inline-flex;border:1px solid #34587f;background:#0a203a;border-radius:999px;padding:6px 9px;color:#b9d8ff;font-size:11px;font-weight:800}.manager-chip.ok{border-color:#2b6d62;color:#9ce5d1}.manager-chip.warn{border-color:#755617;color:#ffd27a}.proof-mode{margin-top:14px;border:1px solid #27486e;border-radius:15px;background:#091a31;overflow:hidden}.proof-mode>summary{cursor:pointer;list-style:none;padding:15px 18px;color:#b9d8ff;font-weight:900}.proof-mode>summary::-webkit-details-marker{display:none}.proof-mode>summary:before{content:'+';display:inline-flex;align-items:center;justify-content:center;width:22px;height:22px;margin-right:8px;border-radius:7px;background:#153d6b;color:#fff}.proof-mode[open]>summary:before{content:'-'}.proof-body{border-top:1px solid #27486e;padding:18px;display:grid;gap:16px}.proof-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.proof-card,.proof-evidence{border:1px solid #25466f;border-radius:12px;background:#06172c;padding:15px}.proof-card h3{margin:5px 0 7px;font-size:16px}.proof-card p{margin:0;color:#b9d8ff;line-height:1.45}.proof-evidence>.eyebrow{margin-bottom:12px}.manager-readonly{margin-top:14px;padding:13px 16px;border:1px solid #244263;border-radius:12px;background:#07172a;color:#91b8e8;font-size:12px}@media(max-width:760px){.manager-decision-card{grid-template-columns:38px minmax(0,1fr)}.manager-decision-card>.status{grid-column:2;justify-self:start}.proof-grid{grid-template-columns:1fr}.manager-card-actions .command-button{width:100%;text-align:center}}
"@
'@

$newReturn = @'
    return @"
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Butler - Command Center</title><style>$css
$bf819Css</style></head><body><main class="shell">
$header
<div class="butler-refresh-contract" hidden><div>Decision state: $(ConvertTo-HtmlText $state)</div><div>BF-629: $(ConvertTo-HtmlText $bf629)</div><div>BF-631: $(ConvertTo-HtmlText $bf631)</div><div>BF-636 plan state: $(ConvertTo-HtmlText $refreshPlan.State)</div><div>BF-636 plan policy: $(ConvertTo-HtmlText $refreshPlan.Policy)</div><div>Governed step count: $($refreshPlan.Steps.Count)</div></div>
<section class="panel command-hero manager-hero"><div class="command-head"><div><div class="command-kicker">Butler Command Center</div><h1 class="command-title">What matters now</h1><p class="command-copy">$(ConvertTo-HtmlText $managerHeroCopy)</p></div><div class="status done">DECISION QUEUE</div></div><div class="command-meta"><span>$(ConvertTo-HtmlText $target)</span><span>Read-only manager view</span><span>Saved decisions remain traceable</span></div></section>
<section class="panel manager-queue"><div class="manager-queue-head"><div><div class="eyebrow">Butler's priorities</div><h2>Your decision queue</h2><p>Act on what needs attention; leave completed and on-demand states alone.</p></div></div><div class="manager-decision-stack">$managerQueueHtml</div>
<details id="decision-details" class="proof-mode"><summary>View decision details</summary><div class="proof-body"><div class="proof-grid"><article class="proof-card"><div class="eyebrow">Why Butler says this</div><h3>$(ConvertTo-HtmlText $primaryExplanationTitle)</h3><p>$(ConvertTo-HtmlText $primaryExplanationCopy)</p></article><article class="proof-card"><div class="eyebrow">What changed</div><h3>Current decision state</h3><p>$(ConvertTo-HtmlText $proofChangeCopy)</p></article></div><div class="proof-evidence"><div class="eyebrow">Evidence used</div>$primaryEvidenceHtml</div><div class="proof-grid"><article class="proof-card"><div class="eyebrow">Saved decision</div><h3>$(ConvertTo-HtmlText $decisionPackageRecord)</h3><p>Trust status: $(ConvertTo-HtmlText $decisionPackageTrust)</p></article><article class="proof-card"><div class="eyebrow">What to do next</div><h3>$(ConvertTo-HtmlText $primaryNextActionLabel)</h3><p>$(ConvertTo-HtmlText $primaryNextActionCopy)</p><div class="manager-card-actions"><a class="command-button" href="$(ConvertTo-HtmlText $primaryNextActionHref)">$(ConvertTo-HtmlText $primaryNextActionLabel)</a></div></article></div><div class="manager-readonly"><strong>Read only:</strong> Butler does not change your Sleeper roster or submit lineup, waiver, or trade transactions from this Dashboard.</div></div></details></section>
<div class="manager-readonly"><strong>Butler is read only.</strong> Recommendations stay reviewable and traceable; roster actions remain yours.</div></main></body></html>
"@
'@

$dashboardBlock = $dashboardBlock.Substring(0, $finalReturn) + $managerPrelude + "`r`n" + $newReturn.TrimEnd() + "`r`n" + $dashboardBlock.Substring($functionClose)
$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

foreach ($required in @('1 item needs your attention.','Your lineup recommendation is out of date','Refresh Lineup','No waiver move proven','No active trade decision','View decision details','Evidence used','What changed','Saved decision','Butler is read only.','butler-refresh-contract','Decision state: $(ConvertTo-HtmlText $state)','BF-629: $(ConvertTo-HtmlText $bf629)','BF-631: $(ConvertTo-HtmlText $bf631)','BF-636 plan state: $(ConvertTo-HtmlText $refreshPlan.State)','BF-636 plan policy: $(ConvertTo-HtmlText $refreshPlan.Policy)','Governed step count: $($refreshPlan.Steps.Count)')) {
    if ($text -notmatch [regex]::Escape($required)) { throw "BF-819 BLOCKED: manager/proof contract '$required' is missing." }
}
if (($managerPrelude + $newReturn) -match 'Invoke-RestMethod|Invoke-ButlerReadOnly|Method = "POST"|AutoFillLineupOptimizer|BUTLER_FANTASYPROS_API_KEY') {
    throw 'BF-819 BLOCKED: Manager Mode presentation introduced provider, optimizer, credential, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))