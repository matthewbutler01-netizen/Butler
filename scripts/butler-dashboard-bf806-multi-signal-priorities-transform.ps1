param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-806 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-806 BLOCKED: dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)
$lineageAnchor = '    $lineageStatusText = if ($verification.LineageOk) { "Verified" } else { "Needs refresh" }'
$lineageMatches = [regex]::Matches($dashboardBlock, [regex]::Escape($lineageAnchor)).Count
if ($lineageMatches -ne 1) {
    throw "BF-806 BLOCKED: expected one Command Center evidence anchor, found $lineageMatches."
}

$signalPrelude = @'
    $waiverSignalTitle = switch ($state) {
        "CURRENT_AND_ACTIONABLE" { "Review Butler's add/drop move" }
        "CURRENT_REFRESH_RECOMMENDED" { "Refresh before relying on this waiver move" }
        "TRANSACTION_ALREADY_COMPLETE" { "Waiver move already complete" }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "Waiver move is already pending" }
        "STALE_DO_NOT_ACT" { "Do not act on the saved waiver move" }
        "NO_TRANSACTION_TO_ACT_ON" { "No waiver move to make" }
        "NO_AUDITED_DECISION" { "No waiver decision available" }
        default { "Waiver status unavailable" }
    }
    $waiverSignalCopy = switch ($state) {
        "CURRENT_AND_ACTIONABLE" { "One saved add/drop move passed Butler's current checks. Review the players and evidence before deciding whether to act." }
        "CURRENT_REFRESH_RECOMMENDED" { "A saved add/drop move exists, but its supporting evidence should be refreshed before you rely on it." }
        "TRANSACTION_ALREADY_COMPLETE" { "The saved waiver move is already complete. No duplicate action is needed." }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "The saved waiver move is already pending. Do not submit it again." }
        "STALE_DO_NOT_ACT" { "The saved waiver move no longer passes Butler's safety checks. Wait for a new governed decision." }
        "NO_TRANSACTION_TO_ACT_ON" { "Current evidence does not support one clear add/drop move, so Butler recommends no waiver move." }
        "NO_AUDITED_DECISION" { "Butler does not have a saved waiver decision in the current evidence frame." }
        default { "Butler cannot prove a waiver action from the current evidence frame." }
    }
    $waiverSignalStatus = switch ($state) {
        "CURRENT_AND_ACTIONABLE" { "ACTIONABLE" }
        "CURRENT_REFRESH_RECOMMENDED" { "REFRESH" }
        "TRANSACTION_ALREADY_COMPLETE" { "COMPLETE" }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "PENDING" }
        "STALE_DO_NOT_ACT" { "BLOCKED" }
        "NO_TRANSACTION_TO_ACT_ON" { "NO MOVE" }
        default { "UNAVAILABLE" }
    }
    $waiverSignalClass = switch ($state) {
        "CURRENT_AND_ACTIONABLE" { "good" }
        "CURRENT_REFRESH_RECOMMENDED" { "warn" }
        "TRANSACTION_ALREADY_COMPLETE" { "done" }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "warn" }
        "STALE_DO_NOT_ACT" { "danger" }
        "NO_TRANSACTION_TO_ACT_ON" { "done" }
        default { "warn" }
    }

    $lineupSignalTitle = if ($verification.RosterOk) { "Review this week's lineup" } else { "Roster context needs attention" }
    $lineupSignalCopy = if ($verification.RosterOk) {
        "Butler's roster check is verified. Open My Team to review starters and request the read-only AutoFill preview; projections are not fetched from this Dashboard."
    }
    else {
        "Butler cannot verify the current roster context from the evidence already loaded here. Open My Team before relying on a lineup decision."
    }
    $lineupSignalStatus = if ($verification.RosterOk) { "READY TO REVIEW" } else { "NEEDS ATTENTION" }
    $lineupSignalClass = if ($verification.RosterOk) { "good" } else { "warn" }

    $tradeSignalTitle = "No trade priority is proven here"
    $tradeSignalCopy = "Command Center has no governed trade recommendation in its current evidence frame. Open Trade Lab when you want Butler to evaluate a deal."
    $tradeSignalStatus = "ON DEMAND"
    $tradeSignalClass = "done"

'@

$dashboardBlock = $dashboardBlock.Replace($lineageAnchor, $signalPrelude + $lineageAnchor)

$priorityStartMarker = '<section class="panel priority-panel">'
$whyStartMarker = '<section class="panel"><div class="eyebrow">Why Butler says this</div>'
$priorityStart = $dashboardBlock.IndexOf($priorityStartMarker, [System.StringComparison]::Ordinal)
$whyStart = $dashboardBlock.IndexOf($whyStartMarker, $priorityStart, [System.StringComparison]::Ordinal)
if ($priorityStart -lt 0 -or $whyStart -le $priorityStart) {
    throw 'BF-806 BLOCKED: BF-804 priority panel boundary is missing.'
}

$priorityReplacement = @'
<section class="panel priority-panel">
  <div class="section-head"><div><div class="eyebrow">Butler's Priorities</div><h2>Your decision queue</h2><p class="meta">Waiver status uses Butler's saved governed decision. Lineup and trade stay explicit about what Butler can and cannot prove from the evidence already loaded here.</p></div></div>
  <div class="priority-stack">
    <article class="priority-card primary"><div class="priority-index">01</div><div><div class="priority-type">Waiver</div><div class="priority-title">$(ConvertTo-HtmlText $waiverSignalTitle)</div><div class="priority-copy">$(ConvertTo-HtmlText $waiverSignalCopy)</div>$priorityMoveHtml<div class="priority-actions"><a class="command-button" href="/waivers">Open Waiver Board</a><a class="command-button secondary" href="/history">Decision History</a></div></div><div class="status $waiverSignalClass">$(ConvertTo-HtmlText $waiverSignalStatus)</div></article>
    <article class="priority-card"><div class="priority-index">02</div><div><div class="priority-type">Lineup</div><div class="priority-title">$(ConvertTo-HtmlText $lineupSignalTitle)</div><div class="priority-copy">$(ConvertTo-HtmlText $lineupSignalCopy)</div><div class="priority-actions"><a class="command-button" href="/team">Open Lineup Advisor</a></div></div><div class="status $lineupSignalClass">$(ConvertTo-HtmlText $lineupSignalStatus)</div></article>
    <article class="priority-card"><div class="priority-index">03</div><div><div class="priority-type">Trade</div><div class="priority-title">$(ConvertTo-HtmlText $tradeSignalTitle)</div><div class="priority-copy">$(ConvertTo-HtmlText $tradeSignalCopy)</div><div class="priority-actions"><a class="command-button" href="/trade">Open Trade Lab</a></div></div><div class="status $tradeSignalClass">$(ConvertTo-HtmlText $tradeSignalStatus)</div></article>
  </div>
</section>
'@

$dashboardBlock = $dashboardBlock.Substring(0, $priorityStart) + $priorityReplacement + $dashboardBlock.Substring($whyStart)

$heroOld = '<div class="command-head"><div><div class="command-kicker">Butler Command Center</div><h1 class="command-title">What matters now</h1><p class="command-copy">Your current governed decision, evidence status, and fastest paths to the tools that matter this week.</p></div><div class="status $($presentation.Class)">$(ConvertTo-HtmlText $presentation.Headline)</div></div>'
$heroNew = '<div class="command-head"><div><div class="command-kicker">Butler Command Center</div><h1 class="command-title">What matters now</h1><p class="command-copy">Your lineup, waiver, and trade attention points in one read-only decision queue.</p></div><div class="status done">DECISION QUEUE</div></div>'
$heroMatches = [regex]::Matches($dashboardBlock, [regex]::Escape($heroOld)).Count
if ($heroMatches -ne 1) {
    throw "BF-806 BLOCKED: expected one Command Center hero contract, found $heroMatches."
}
$dashboardBlock = $dashboardBlock.Replace($heroOld, $heroNew)

$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

$managerMarkupStart = $text.IndexOf('<section class="panel command-hero">', [System.StringComparison]::Ordinal)
$managerMarkupEnd = $text.IndexOf('</main></body></html>', $managerMarkupStart, [System.StringComparison]::Ordinal)
if ($managerMarkupStart -lt 0 -or $managerMarkupEnd -le $managerMarkupStart) {
    throw 'BF-806 BLOCKED: Command Center manager markup boundary is missing.'
}
$managerMarkup = $text.Substring($managerMarkupStart, $managerMarkupEnd - $managerMarkupStart)
if ($managerMarkup -match 'recorded no-transaction|from this audit|final method|cross-position ties|BF-\d|UUID') {
    throw 'BF-806 BLOCKED: normal Command Center still contains implementation-heavy manager copy.'
}
if ($managerMarkup -notmatch '>Waiver<' -or $managerMarkup -notmatch '>Lineup<' -or $managerMarkup -notmatch '>Trade<') {
    throw 'BF-806 BLOCKED: all three manager signals were not installed.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
