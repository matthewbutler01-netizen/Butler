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
if ($finalReturn -lt 0) {
    throw 'BF-819 BLOCKED: final dashboard HTML return is missing.'
}

$returnEndMarker = "`r`n\"@"
$returnEnd = $dashboardBlock.IndexOf($returnEndMarker, $finalReturn, [System.StringComparison]::Ordinal)
if ($returnEnd -lt 0) {
    $returnEndMarker = "`n\"@"
    $returnEnd = $dashboardBlock.IndexOf($returnEndMarker, $finalReturn, [System.StringComparison]::Ordinal)
}
if ($returnEnd -lt 0) {
    throw 'BF-819 BLOCKED: final dashboard HTML here-string terminator is missing.'
}
$returnEnd += $returnEndMarker.Length

$managerPrelude = @'
    # BF-819 keeps Butler's governed derivation intact and changes only how the already-derived
    # decision state is presented. Manager Mode stays concise; Proof Mode keeps the evidence,
    # traceability, and read-only boundaries available on demand.
    $managerAttentionCount = @($orderedPrioritySignals | Where-Object { [string]$_.AttentionGroup -ceq "attention" }).Count
    $managerHeroCopy = if ($managerAttentionCount -eq 1) {
        "1 item needs your attention."
    }
    elseif ($managerAttentionCount -gt 1) {
        "$managerAttentionCount items need your attention."
    }
    else {
        "Nothing needs immediate attention."
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

        switch ($kind) {
            "Lineup" {
                switch ([string]$lineupSignalStatus) {
                    "REFRESH AUTOFILL" {
                        $title = "Your lineup recommendation is out of date"
                        $copy = "Your roster or projection data changed since the last lineup review."
                        $status = "REFRESH"
                        $statusClass = "warn"
                        $actionHref = "/team/autofill"
                        $actionLabel = "Refresh Lineup"
                    }
                    "EVIDENCE GAP" {
                        $title = "Lineup review needs more evidence"
                        $copy = "Butler could not verify every projection needed for a complete lineup recommendation."
                        $status = "CHECK EVIDENCE"
                        $statusClass = "warn"
                        $actionHref = "/team"
                        $actionLabel = "Review My Team"
                    }
                    "AUTOFILL READY" {
                        $title = "Lineup changes are ready to review"
                        $copy = "The latest lineup review found a stronger projected legal lineup."
                        $status = "REVIEW"
                        $statusClass = "good"
                        $actionHref = "/team"
                        $actionLabel = "Review Lineup"
                    }
                    "NO CHANGES" {
                        $title = "No lineup change proven"
                        $copy = "The latest lineup review supports keeping your current starters."
                        $status = "NO CHANGE"
                        $statusClass = "done"
                        $actionHref = "/team"
                        $actionLabel = "View Lineup"
                    }
                    "NEEDS ATTENTION" {
                        $title = "Your roster needs review first"
                        $copy = "Butler cannot rely on the current roster state for a lineup recommendation yet."
                        $status = "REVIEW"
                        $statusClass = "warn"
                        $actionHref = "/team"
                        $actionLabel = "Review My Team"
                    }
                    default {
                        $title = "Lineup has not been reviewed yet"
                        $copy = "Run a read-only lineup review when you want Butler to evaluate this week's starters."
                        $status = "NOT REVIEWED"
                        $statusClass = "done"
                        $actionHref = "/team/autofill"
                        $actionLabel = "Review Lineup"
                    }
                }

                $rosterChipClass = if ($verification.RosterOk) { "ok" } else { "warn" }
                $rosterChipText = if ($verification.RosterOk) { "Roster verified" } else { "Roster needs review" }
                $projectionChipClass = if ([string]$lineupSignalStatus -ceq "REFRESH AUTOFILL" -or [string]$lineupSignalStatus -ceq "EVIDENCE GAP") { "warn" } else { "ok" }
                $projectionChipText = switch ([string]$lineupSignalStatus) {
                    "REFRESH AUTOFILL" { "Projections stale" }
                    "EVIDENCE GAP" { "Projection gap" }
                    "NOT REVIEWED" { "Projections not reviewed" }
                    default { "Projection frame loaded" }
                }
                $chips = "<span class=`"manager-chip $rosterChipClass`">$(ConvertTo-HtmlText $rosterChipText)</span><span class=`"manager-chip $projectionChipClass`">$(ConvertTo-HtmlText $projectionChipText)</span>"
                if ($null -ne $lineupSnapshot) {
                    $frameWeek = [string]$lineupSnapshot.Week
                    $frameScoring = [string]$lineupSnapshot.Scoring
                    if (-not [string]::IsNullOrWhiteSpace($frameWeek) -or -not [string]::IsNullOrWhiteSpace($frameScoring)) {
                        $frameParts = New-Object System.Collections.Generic.List[string]
                        if (-not [string]::IsNullOrWhiteSpace($frameWeek)) { $frameParts.Add("Week $frameWeek") }
                        if (-not [string]::IsNullOrWhiteSpace($frameScoring)) { $frameParts.Add($frameScoring) }
                        $chips += "<span class=`"manager-chip`">$(ConvertTo-HtmlText ($frameParts -join ' · '))</span>"
                    }
                }
                $secondaryActions = '<a class="command-button secondary" href="/team">My Team</a>'
            }
            "Waiver" {
                switch ([string]$state) {
                    "CURRENT_AND_ACTIONABLE" {
                        $title = "A waiver move is ready to review"
                        $copy = "One add/drop move passed Butler's current checks."
                        $status = "MOVE READY"
                        $statusClass = "good"
                        $actionHref = "/waivers"
                        $actionLabel = "Review Waiver Move"
                    }
                    "CURRENT_REFRESH_RECOMMENDED" {
                        $title = "Refresh waiver evidence before acting"
                        $copy = "A saved add/drop move exists, but its supporting evidence should be refreshed first."
                        $status = "REFRESH"
                        $statusClass = "warn"
                        $actionHref = "/waivers"
                        $actionLabel = "Open Waiver Board"
                    }
                    "TRANSACTION_PENDING_DO_NOT_DUPLICATE" {
                        $title = "Your waiver move is already pending"
                        $copy = "Sleeper is processing the saved transaction. Do not submit it again."
                        $status = "WAIT"
                        $statusClass = "warn"
                        $actionHref = "/history"
                        $actionLabel = "View History"
                    }
                    "TRANSACTION_ALREADY_COMPLETE" {
                        $title = "Waiver move complete"
                        $copy = "The saved transaction is complete. No duplicate action is needed."
                        $status = "COMPLETE"
                        $statusClass = "done"
                        $actionHref = "/history"
                        $actionLabel = "View History"
                    }
                    "STALE_DO_NOT_ACT" {
                        $title = "Do not act on the saved waiver move"
                        $copy = "The saved move no longer passes Butler's current safety checks."
                        $status = "DO NOT ACT"
                        $statusClass = "danger"
                        $actionHref = "/waivers"
                        $actionLabel = "Open Waiver Board"
                    }
                    "NO_TRANSACTION_TO_ACT_ON" {
                        $title = "No waiver move proven"
                        $copy = "Current review found no add/drop strong enough to recommend."
                        $status = "NO MOVE"
                        $statusClass = "done"
                        $actionHref = "/waivers"
                        $actionLabel = "Waiver Board"
                    }
                    default {
                        $title = "No waiver decision available"
                        $copy = "Butler does not have a current waiver move to act on."
                        $status = "UNAVAILABLE"
                        $statusClass = "warn"
                        $actionHref = "/waivers"
                        $actionLabel = "Waiver Board"
                    }
                }
                $secondaryActions = '<a class="command-button secondary" href="/history">History</a>'
            }
            "Trade" {
                $title = "No active trade decision"
                $copy = "Use Trade Lab when you have a deal to evaluate."
                $status = "ON DEMAND"
                $statusClass = "done"
                $actionHref = "/trade"
                $actionLabel = "Trade Lab"
                $extraHtml = ""
            }
        }

        $cardClass = if ($managerIndex -eq 0) { "manager-decision-card primary" } else { "manager-decision-card" }
        $displayPriority = "{0:D2}" -f ($managerIndex + 1)
        $chipRow = if ([string]::IsNullOrWhiteSpace($chips)) { "" } else { "<div class=`"manager-chip-row`">$chips</div>" }
        $cardHtml = @"
<article class="$cardClass">
  <div class="manager-priority-index">$displayPriority</div>
  <div class="manager-decision-main">
    <div class="manager-kind">$(ConvertTo-HtmlText $kind)</div>
    <h3>$(ConvertTo-HtmlText $title)</h3>
    <p>$(ConvertTo-HtmlText $copy)</p>
    $chipRow
    $extraHtml
    <div class="manager-card-actions"><a class="command-button" href="$(ConvertTo-HtmlText $actionHref)">$(ConvertTo-HtmlText $actionLabel)</a>$secondaryActions</div>
  </div>
  <div class="status $statusClass">$(ConvertTo-HtmlText $status)</div>
</article>
"@
        $managerCardList.Add($cardHtml)
    }
    $managerQueueHtml = $managerCardList -join "`n"

    $proofChangeCopy = "No additional change detail is available for this priority."
    if ($null -ne $priorityOne) {
        switch ([string]$priorityOne.Kind) {
            "Lineup" {
                $proofChangeCopy = switch ([string]$lineupSignalStatus) {
                    "REFRESH AUTOFILL" { "The saved lineup review no longer matches the current roster or weekly projection frame." }
                    "EVIDENCE GAP" { "The latest lineup review stopped because required projection evidence was incomplete." }
                    "AUTOFILL READY" { "The latest lineup review found one or more projected starter changes worth reviewing." }
                    "NO CHANGES" { "The latest lineup review completed without proving an improvement over the current starters." }
                    "NEEDS ATTENTION" { "The current roster state must be reviewed before Butler can rely on a lineup result." }
                    default { "No lineup review has been requested for this weekly frame yet." }
                }
            }
            "Waiver" {
                $proofChangeCopy = switch ([string]$state) {
                    "CURRENT_AND_ACTIONABLE" { "The current waiver review proved one add/drop pair that remains actionable." }
                    "CURRENT_REFRESH_RECOMMENDED" { "The saved waiver move remains available, but its evidence crossed the refresh warning threshold." }
                    "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "Sleeper now reports the saved transaction as pending." }
                    "TRANSACTION_ALREADY_COMPLETE" { "Sleeper now reports the saved transaction as complete." }
                    "STALE_DO_NOT_ACT" { "A current safety check invalidated action on the saved waiver move." }
                    "NO_TRANSACTION_TO_ACT_ON" { "The current waiver review completed without proving an add/drop move." }
                    default { "No current governed waiver transaction is available to act on." }
                }
            }
            "Trade" {
                $proofChangeCopy = "No trade proposal is loaded on the Dashboard; Trade Lab remains an on-demand workflow."
            }
        }
    }

    $bf819Css = @"
.manager-hero{padding:24px 26px;background:linear-gradient(135deg,#17365f 0%,#0c1f3c 65%);border-color:#315f96}
.manager-hero .command-copy{max-width:720px;font-size:17px}
.manager-queue{padding:22px}
.manager-queue-head{display:flex;justify-content:space-between;gap:18px;align-items:end;margin-bottom:16px}
.manager-queue-head h2{margin:4px 0 0}
.manager-queue-head p{margin:6px 0 0;color:#9fc5ff}
.manager-decision-stack{display:grid;gap:12px}
.manager-decision-card{display:grid;grid-template-columns:44px minmax(0,1fr) auto;gap:16px;align-items:start;border:1px solid #25466f;border-radius:15px;background:#071a31;padding:18px}
.manager-decision-card.primary{background:#102f54;border-color:#3d73b2;box-shadow:0 0 0 1px rgba(75,139,211,.08)}
.manager-priority-index{display:flex;align-items:center;justify-content:center;min-width:34px;height:34px;border-radius:10px;background:#0f3765;color:#8fc3ff;font-weight:900;font-size:13px}
.manager-kind{text-transform:uppercase;letter-spacing:.12em;color:#7db7ff;font-size:11px;font-weight:900}
.manager-decision-main h3{margin:5px 0 7px;font-size:20px;line-height:1.15}
.manager-decision-main p{margin:0;color:#b9d8ff;line-height:1.45;max-width:780px}
.manager-card-actions{display:flex;gap:9px;flex-wrap:wrap;margin-top:13px}
.manager-chip-row{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}
.manager-chip{display:inline-flex;align-items:center;border:1px solid #34587f;background:#0a203a;border-radius:999px;padding:6px 9px;color:#b9d8ff;font-size:11px;font-weight:800}
.manager-chip.ok{border-color:#2b6d62;color:#9ce5d1}
.manager-chip.warn{border-color:#755617;color:#ffd27a}
.proof-mode{margin-top:14px;border:1px solid #27486e;border-radius:15px;background:#091a31;overflow:hidden}
.proof-mode>summary{cursor:pointer;list-style:none;padding:15px 18px;color:#b9d8ff;font-weight:900;display:flex;align-items:center;gap:8px}
.proof-mode>summary::-webkit-details-marker{display:none}
.proof-mode>summary:before{content:'+';display:inline-flex;align-items:center;justify-content:center;width:22px;height:22px;border-radius:7px;background:#153d6b;color:#fff}
.proof-mode[open]>summary:before{content:'–'}
.proof-body{border-top:1px solid #27486e;padding:18px;display:grid;gap:16px}
.proof-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
.proof-card{border:1px solid #25466f;border-radius:12px;background:#06172c;padding:15px}
.proof-card h3{margin:5px 0 7px;font-size:16px}
.proof-card p{margin:0;color:#b9d8ff;line-height:1.45}
.proof-evidence{border:1px solid #25466f;border-radius:12px;background:#06172c;padding:15px}
.proof-evidence>.eyebrow{margin-bottom:12px}
.manager-readonly{margin-top:14px;padding:13px 16px;border:1px solid #244263;border-radius:12px;background:#07172a;color:#91b8e8;font-size:12px}
@media(max-width:760px){.manager-decision-card{grid-template-columns:38px minmax(0,1fr)}.manager-decision-card>.status{grid-column:2;justify-self:start}.proof-grid{grid-template-columns:1fr}.manager-queue-head{align-items:start;flex-direction:column}.manager-card-actions .command-button{width:100%;text-align:center}}
"@
'@

$newReturn = @'
    return @"
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Butler - Command Center</title><style>$css
$bf819Css</style></head><body><main class="shell">
$header
<section class="panel command-hero manager-hero">
  <div class="command-head"><div><div class="command-kicker">Butler Command Center</div><h1 class="command-title">What matters now</h1><p class="command-copy">$(ConvertTo-HtmlText $managerHeroCopy)</p></div><div class="status done">DECISION QUEUE</div></div>
  <div class="command-meta"><span>$(ConvertTo-HtmlText $target)</span><span>Read-only manager view</span><span>Saved decisions remain traceable</span></div>
</section>
<section class="panel manager-queue">
  <div class="manager-queue-head"><div><div class="eyebrow">Butler's priorities</div><h2>Your decision queue</h2><p>Act on what needs attention; leave completed and on-demand states alone.</p></div></div>
  <div class="manager-decision-stack">
$managerQueueHtml
  </div>
  <details id="decision-details" class="proof-mode">
    <summary>View decision details</summary>
    <div class="proof-body">
      <div class="proof-grid">
        <article class="proof-card"><div class="eyebrow">Why Butler says this</div><h3>$(ConvertTo-HtmlText $primaryExplanationTitle)</h3><p>$(ConvertTo-HtmlText $primaryExplanationCopy)</p></article>
        <article class="proof-card"><div class="eyebrow">What changed</div><h3>Current decision state</h3><p>$(ConvertTo-HtmlText $proofChangeCopy)</p></article>
      </div>
      <div class="proof-evidence"><div class="eyebrow">Evidence used</div>$primaryEvidenceHtml</div>
      <div class="proof-grid">
        <article class="proof-card"><div class="eyebrow">Saved decision</div><h3>$(ConvertTo-HtmlText $decisionPackageRecord)</h3><p>Trust status: $(ConvertTo-HtmlText $decisionPackageTrust)</p></article>
        <article class="proof-card"><div class="eyebrow">What to do next</div><h3>$(ConvertTo-HtmlText $primaryNextActionLabel)</h3><p>$(ConvertTo-HtmlText $primaryNextActionCopy)</p><div class="manager-card-actions"><a class="command-button" href="$(ConvertTo-HtmlText $primaryNextActionHref)">$(ConvertTo-HtmlText $primaryNextActionLabel)</a></div></article>
      </div>
      <div class="manager-readonly"><strong>Read only:</strong> Butler does not change your Sleeper roster or submit lineup, waiver, or trade transactions from this Dashboard.</div>
    </div>
  </details>
</section>
<div class="manager-readonly"><strong>Butler is read only.</strong> Recommendations stay reviewable and traceable; roster actions remain yours.</div>
</main></body></html>
"@
'@

$dashboardBlock = $dashboardBlock.Substring(0, $finalReturn) + $managerPrelude + "`r`n" + $newReturn.TrimEnd() + $dashboardBlock.Substring($returnEnd)
$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

foreach ($required in @(
    '1 item needs your attention.',
    'Your lineup recommendation is out of date',
    'Refresh Lineup',
    'No waiver move proven',
    'No active trade decision',
    'View decision details',
    'Evidence used',
    'What changed',
    'Saved decision',
    'Butler is read only.'
)) {
    if ($text -notmatch [regex]::Escape($required)) {
        throw "BF-819 BLOCKED: manager/proof contract '$required' is missing."
    }
}

$renderedReturn = $newReturn + $managerPrelude
if ($renderedReturn -match 'Invoke-RestMethod|Invoke-ButlerReadOnly|Method = "POST"|AutoFillLineupOptimizer|BUTLER_FANTASYPROS_API_KEY') {
    throw 'BF-819 BLOCKED: Manager Mode presentation introduced provider, optimizer, credential, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
