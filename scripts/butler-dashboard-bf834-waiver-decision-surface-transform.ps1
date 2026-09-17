param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-834 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$waiverStart = $text.IndexOf('function ConvertTo-WaiverHtml {', [System.StringComparison]::Ordinal)
$waiverEnd = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $waiverStart, [System.StringComparison]::Ordinal)
if ($waiverStart -lt 0 -or $waiverEnd -le $waiverStart) {
    throw 'BF-834 BLOCKED: Waiver Board renderer function boundary is missing.'
}

$waiverBlock = $text.Substring($waiverStart, $waiverEnd - $waiverStart)
$decisionAnchor = @'
    if ($candidates.Count -eq 0) {
        $cards = '<div class="subtle">BF-616 has no authorized shortlist entries in the current governed frame.</div>'
    }

'@
$anchorMatches = [regex]::Matches($waiverBlock, [regex]::Escape($decisionAnchor)).Count
if ($anchorMatches -ne 1) {
    throw "BF-834 BLOCKED: Waiver Board decision anchor expected one match, found $anchorMatches."
}

$decisionPrelude = @'
    $waiverDecisionTitle = switch ([string]$current.State) {
        "CURRENT_AND_ACTIONABLE" { "Review Butler's proven add/drop move" }
        "CURRENT_REFRESH_RECOMMENDED" { "Refresh before relying on this waiver move" }
        "TRANSACTION_ALREADY_COMPLETE" { "Waiver move already complete" }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "Waiver move already pending" }
        "STALE_DO_NOT_ACT" { "Do not act on the saved waiver move" }
        "NO_TRANSACTION_TO_ACT_ON" { "Waiver review complete; no move proven" }
        "NO_AUDITED_DECISION" { "No waiver decision available" }
        default { "Waiver decision needs review" }
    }
    $waiverDecisionCopy = switch ([string]$current.State) {
        "CURRENT_AND_ACTIONABLE" { "Butler has one currently governed add/drop pair. Review the exact players and supporting evidence before deciding whether to act." }
        "CURRENT_REFRESH_RECOMMENDED" { "A governed add/drop pair exists, but Butler requires fresher evidence before you rely on it." }
        "TRANSACTION_ALREADY_COMPLETE" { "The governed transaction is already complete. No duplicate waiver action is needed." }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "The governed transaction is already pending. Do not submit the same move again." }
        "STALE_DO_NOT_ACT" { "The saved waiver move no longer passes Butler's current safety frame. Wait for a new governed decision." }
        "NO_TRANSACTION_TO_ACT_ON" { "Butler completed the current waiver review and did not prove one clear add/drop move. No waiver action is needed from this evidence frame." }
        "NO_AUDITED_DECISION" { "Butler does not have a saved governed waiver decision for the current evidence frame." }
        default { "Butler cannot prove a waiver action from the current governed evidence frame." }
    }
    $waiverDecisionStatus = switch ([string]$current.State) {
        "CURRENT_AND_ACTIONABLE" { "MOVE PROVEN" }
        "CURRENT_REFRESH_RECOMMENDED" { "REFRESH" }
        "TRANSACTION_ALREADY_COMPLETE" { "COMPLETE" }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "PENDING" }
        "STALE_DO_NOT_ACT" { "BLOCKED" }
        "NO_TRANSACTION_TO_ACT_ON" { "NO MOVE" }
        default { "UNAVAILABLE" }
    }
    $waiverDecisionClass = switch ([string]$current.State) {
        "CURRENT_AND_ACTIONABLE" { "good" }
        "CURRENT_REFRESH_RECOMMENDED" { "warn" }
        "TRANSACTION_ALREADY_COMPLETE" { "done" }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "warn" }
        "STALE_DO_NOT_ACT" { "danger" }
        "NO_TRANSACTION_TO_ACT_ON" { "done" }
        default { "warn" }
    }

    $waiverPairHtml = ""
    if ($pair.Active) {
        $pairLead = if ([string]$current.State -ceq "CURRENT_AND_ACTIONABLE") {
            "Exact governed transaction"
        }
        else {
            "Saved governed transaction requiring refresh"
        }
        $waiverPairHtml = @"
<div class="waiver-pair">
  <article class="waiver-action-card add"><div class="waiver-action-label">ADD</div><div class="waiver-action-name">$(ConvertTo-HtmlText $pair.Add.Name)</div><div class="waiver-action-meta">$(ConvertTo-HtmlText $pair.Add.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Add.Team) &middot; Sleeper $(ConvertTo-HtmlText $pair.Add.SleeperId)</div></article>
  <article class="waiver-action-card drop"><div class="waiver-action-label">DROP</div><div class="waiver-action-name">$(ConvertTo-HtmlText $pair.Drop.Name)</div><div class="waiver-action-meta">$(ConvertTo-HtmlText $pair.Drop.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Drop.Team) &middot; Sleeper $(ConvertTo-HtmlText $pair.Drop.SleeperId)</div></article>
</div>
<div class="waiver-pair-note"><strong>$(ConvertTo-HtmlText $pairLead).</strong> This is Butler's already-audited exact pair; it is not inferred from board order.</div>
"@
    }

    $waiverNextActionCopy = switch ([string]$current.State) {
        "CURRENT_AND_ACTIONABLE" { "Review the exact pair and evidence. If you choose to act, make the roster move in Sleeper yourself; Butler remains read-only." }
        "CURRENT_REFRESH_RECOMMENDED" { "Refresh the governed evidence before relying on the saved pair. Do not act from the stale frame." }
        "TRANSACTION_ALREADY_COMPLETE" { "No waiver action is needed. Use History if you want to review the completed decision record." }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "Do not submit a duplicate move. Wait for the pending transaction state to resolve." }
        "STALE_DO_NOT_ACT" { "Take no waiver action from this saved result. Wait for Butler to produce a new governed frame." }
        "NO_TRANSACTION_TO_ACT_ON" { "Hold. No add/drop move is proven right now; use the review pool below only as context, not as a ranking." }
        default { "Review the current governed evidence before making a waiver decision." }
    }

'@

$waiverBlock = $waiverBlock.Replace($decisionAnchor, $decisionAnchor + $decisionPrelude)

$returnStart = $waiverBlock.IndexOf('    return @"', [System.StringComparison]::Ordinal)
if ($returnStart -lt 0) {
    throw 'BF-834 BLOCKED: Waiver Board HTML return is missing.'
}
$returnEndMarker = "`n`"@`n}`n`n"
$returnEnd = $waiverBlock.IndexOf($returnEndMarker, $returnStart, [System.StringComparison]::Ordinal)
if ($returnEnd -lt 0) {
    throw 'BF-834 BLOCKED: Waiver Board HTML return terminator is missing.'
}
$returnEnd += $returnEndMarker.Length

$managerReturn = @'
    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Waiver Board</title><style>$css
.waiver-decision-hero{border-color:#35507e}.waiver-decision-head{display:flex;justify-content:space-between;align-items:flex-start;gap:18px}.waiver-decision-head h1{margin-bottom:7px}.waiver-state-line{color:#9ba8c8;font-size:12px;margin-top:10px}.waiver-pair{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:18px}.waiver-action-card{padding:17px;border:1px solid #2b3962;border-radius:14px;background:#0d1630}.waiver-action-card.add{border-left:4px solid #2d8a5f}.waiver-action-card.drop{border-left:4px solid #a64d5c}.waiver-action-label{font-size:11px;font-weight:900;letter-spacing:.1em;color:#8ea0c7}.waiver-action-name{font-size:22px;font-weight:900;margin-top:5px}.waiver-action-meta{font-size:12px;color:#9ba8c8;margin-top:4px}.waiver-pair-note{margin-top:10px;color:#aebada;font-size:12px}.waiver-next{margin-top:18px;padding:15px 16px;border:1px solid #33436f;border-radius:14px;background:#0a142c}.waiver-next strong{display:block;margin-bottom:5px}.waiver-next p{margin:0;color:#cbd4eb}.waiver-board-head{display:flex;justify-content:space-between;gap:18px;align-items:flex-start}.waiver-board-head .lede{max-width:760px}@media(max-width:760px){.waiver-decision-head,.waiver-board-head{display:block}.waiver-decision-head .status{display:inline-block;margin-top:10px}.waiver-pair{grid-template-columns:1fr}}
</style></head><body><main class="shell">
$header
<section class="panel waiver-decision-hero">
  <div class="eyebrow">Butler waiver decision</div>
  <div class="waiver-decision-head"><div><h1 class="headline">$(ConvertTo-HtmlText $waiverDecisionTitle)</h1><p class="lede">$(ConvertTo-HtmlText $waiverDecisionCopy)</p><div class="waiver-state-line">Governed state: $(ConvertTo-HtmlText $current.State) &middot; authorized review pool: $($counts.Total)</div></div><div class="status $waiverDecisionClass">$(ConvertTo-HtmlText $waiverDecisionStatus)</div></div>
  $waiverPairHtml
  <div class="waiver-next"><strong>What to do now</strong><p>$(ConvertTo-HtmlText $waiverNextActionCopy)</p></div>
  <div class="actions" style="margin-top:15px"><a class="button" href="/history">Decision History</a><a class="button" href="/team">My Team</a><a class="button" href="/">Dashboard</a></div>
</section>
<section class="panel">
  <div class="waiver-board-head"><div><div class="eyebrow">Authorized review pool</div><h2 class="headline">Players Butler authorized for review</h2><p class="lede">Use these cards to inspect the governed BF-616 shortlist after reading the decision above. Board order does not create a recommendation.</p></div><div class="status done">CONTEXT ONLY</div></div>
  <div class="board-note"><span class="not-rank">NOT A RANKING.</span> Cards remain in BF-616 deterministic display order. Top-to-bottom placement is not preference, value, priority, or advice.</div>
  <div class="board-stats">
    <div class="board-stat"><strong>$($counts.Total)</strong><span>Authorized shortlist</span></div>
    <div class="board-stat"><strong>$($counts.Historical)</strong><span>Historical directional lane</span></div>
    <div class="board-stat"><strong>$($counts.Newcomer)</strong><span>Newcomer review lane &middot; nonnumeric</span></div>
  </div>
  <div class="board-grid">$cards</div>
  <div class="board-disclaimer">Status, injury, depth, and market attention are descriptive only. Market attention is not Butler's score. Newcomers remain nonnumeric. If shown, <strong>Current governed ADD</strong> and <strong>Paired audited DROP</strong> come only from the already-audited exact transaction; they do not alter BF-616 order.</div>
  <details><summary>Technical and audit details</summary><div class="tech"><div>BF-623 target: $(ConvertTo-HtmlText $target.Human)</div><div>Raw Sleeper league / roster: $(ConvertTo-HtmlText $target.SleeperLeagueId) / $(ConvertTo-HtmlText $target.RosterId)</div><div>Raw comparison identity: $(ConvertTo-HtmlText $target.RawComparison)</div><div>BF-623 target gate: $(ConvertTo-HtmlText $target.Gate)</div><div>BF-623 role: $(ConvertTo-HtmlText $target.Role)</div><div>Current decision state: $(ConvertTo-HtmlText $current.State)</div><div>Current audit ID: $(ConvertTo-HtmlText $current.AuditId)</div><div>Current ADD Sleeper ID: $(ConvertTo-HtmlText $current.SleeperId)</div><div>Paired ADD Sleeper ID: $(ConvertTo-HtmlText $pair.AddSleeperId)</div><div>Paired DROP Sleeper ID: $(ConvertTo-HtmlText $pair.DropSleeperId)</div><div>BF-629 current gate: $(ConvertTo-HtmlText $current.Bf629)</div><div>BF-631 current gate: $(ConvertTo-HtmlText $current.Bf631)</div><div>Audited BF-603 / BF-602: $(ConvertTo-HtmlText $current.AuditedLineageRaw)</div><div>Bundle BF-603 / BF-602: $(ConvertTo-HtmlText $current.BundleLineageRaw)</div><div>BF-603 / BF-602: $(ConvertTo-HtmlText $lineage)</div><div>BF-614 methodology: $(ConvertTo-HtmlText $methodology)</div><div>BF-615: $(ConvertTo-HtmlText $bf615)</div><div>BF-616: $(ConvertTo-HtmlText $bf616)</div><div>BF-617: $(ConvertTo-HtmlText $bf617)</div><div>Parsed shortlist: $($candidates.Count)</div></div></details>
</section>
<section class="panel boundary"><span class="lock">READ ONLY &middot; MANAGER DECISION SUPPORT.</span> Butler surfaces the existing governed waiver state and exact audited pair when available. It does not rerank BF-616, invent player values, choose a new add or drop, set FAAB, refresh evidence automatically, or submit a Sleeper transaction.</section>
</main></body></html>
"@
}

'@

$waiverBlock = $waiverBlock.Substring(0, $returnStart) + $managerReturn + $waiverBlock.Substring($returnEnd)
$text = $text.Substring(0, $waiverStart) + $waiverBlock + $text.Substring($waiverEnd)

foreach ($required in @(
    'Butler waiver decision',
    'What to do now',
    'Authorized review pool',
    'Review Butler''s proven add/drop move',
    'Waiver review complete; no move proven',
    'Refresh before relying on this waiver move',
    'READ ONLY &middot; MANAGER DECISION SUPPORT.',
    'Current governed ADD',
    'Paired audited DROP'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-834 BLOCKED: required Waiver Board manager marker is missing: $required"
    }
}

$installedWaiverStart = $text.IndexOf('function ConvertTo-WaiverHtml {', [System.StringComparison]::Ordinal)
$installedWaiverEnd = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $installedWaiverStart, [System.StringComparison]::Ordinal)
$installedWaiverBlock = $text.Substring($installedWaiverStart, $installedWaiverEnd - $installedWaiverStart)
if ($installedWaiverBlock -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-834 BLOCKED: Waiver decision presentation introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    $parseSummary = ($parseErrors | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-834 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}
