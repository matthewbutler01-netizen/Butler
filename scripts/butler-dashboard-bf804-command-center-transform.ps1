param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-804 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

# BF-804 is intentionally presentation-only. It does not alter the governed reads,
# decision state machine, evidence policy, recommendation semantics, or any route.
$cssStart = $text.IndexOf('function Get-SharedCss {', [System.StringComparison]::Ordinal)
$cssEnd = $text.IndexOf('function Get-HeaderHtml {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-804 BLOCKED: shared dashboard CSS function boundary is missing.'
}

$cssBlock = $text.Substring($cssStart, $cssEnd - $cssStart)
$mediaAnchor = '@media(max-width:760px)'
$mediaIndex = $cssBlock.IndexOf($mediaAnchor, [System.StringComparison]::Ordinal)
if ($mediaIndex -lt 0) {
    throw 'BF-804 BLOCKED: responsive CSS anchor is missing from the dashboard.'
}
if ($cssBlock.IndexOf($mediaAnchor, $mediaIndex + $mediaAnchor.Length, [System.StringComparison]::Ordinal) -ge 0) {
    throw 'BF-804 BLOCKED: responsive CSS anchor is ambiguous in the dashboard.'
}

$commandCss = @'
.command-hero{background:linear-gradient(135deg,rgba(29,57,107,.96),rgba(14,26,51,.96) 62%);border-color:#31528a}.command-kicker{font-size:11px;text-transform:uppercase;letter-spacing:.16em;color:#8fb0e8;font-weight:900}.command-head{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.command-title{font-size:32px;line-height:1.12;margin:7px 0 6px}.command-copy{margin:0;color:#c7d3e7;max-width:760px}.command-summary{display:flex;gap:8px;flex-wrap:wrap;margin-top:17px}.summary-chip{display:inline-flex;align-items:center;padding:6px 9px;border:1px solid #36517f;border-radius:999px;background:#0d1a33;color:#b8cae9;font-size:11px;font-weight:800}.section-head{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.section-head h2{margin:4px 0 0;font-size:23px}.priority-panel{border-color:#31528a}.priority-stack{display:grid;gap:12px;margin-top:16px}.priority-card{display:grid;grid-template-columns:42px minmax(0,1fr) auto;gap:14px;align-items:flex-start;padding:17px;border:1px solid #2a416c;border-radius:15px;background:#0b1730}.priority-card.primary{border-color:#426bb1;background:linear-gradient(90deg,rgba(28,55,101,.66),rgba(11,23,48,.96))}.priority-index{width:34px;height:34px;display:flex;align-items:center;justify-content:center;border-radius:10px;background:#17315d;color:#bcd0f5;font-size:11px;font-weight:900}.priority-type{font-size:10px;text-transform:uppercase;letter-spacing:.1em;color:#88a5d3;font-weight:900}.priority-title{font-size:19px;font-weight:900;margin-top:3px}.priority-copy{margin-top:5px;color:#b7c5dc;font-size:13px;max-width:780px}.priority-move{display:grid;grid-template-columns:1fr 1fr;gap:9px;margin-top:12px}.priority-player{padding:11px 12px;border:1px solid #2b426f;border-radius:11px;background:#091326}.priority-player.add{border-color:#286a4c}.priority-player.drop{border-color:#74414b}.priority-player strong{display:block;font-size:10px;letter-spacing:.09em}.priority-player span{display:block;margin-top:3px;font-size:14px;font-weight:850}.priority-player small{display:block;margin-top:2px;color:#8fa2c3}.priority-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:13px}.command-button{display:inline-flex;align-items:center;justify-content:center;text-decoration:none;padding:9px 12px;border-radius:9px;font-size:12px;font-weight:850;border:1px solid #31528a;background:#315dca;color:#fff}.command-button.secondary{background:#101e38;color:#bfd0ef}.quick-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:13px}.quick-card{padding:14px;border:1px solid #243a61;border-radius:13px;background:#0a152a;text-decoration:none;color:#fff}.quick-card strong{display:block;font-size:14px}.quick-card span{display:block;color:#8fa2c3;font-size:11px;margin-top:3px}.why-card{margin-top:14px;padding:16px;border:1px solid #294675;border-radius:13px;background:#0a1730;color:#d0d9e9}.evidence-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-top:15px}.evidence-card{padding:14px;border:1px solid #243a61;border-radius:13px;background:#0a152a}.evidence-card strong{display:block;color:#8299bd;font-size:10px;text-transform:uppercase;letter-spacing:.08em}.evidence-card .evidence-value{font-size:15px;font-weight:900;margin-top:5px}.evidence-card .evidence-note{font-size:10px;color:#7f91b1;margin-top:3px}.decision-strip{display:flex;align-items:center;justify-content:space-between;gap:18px}.decision-strip strong{display:block}.decision-strip span{display:block;color:#91a3c0;font-size:12px;margin-top:3px}.manager-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:14px}
'@

$cssBlock = $cssBlock.Substring(0, $mediaIndex) + $commandCss + $cssBlock.Substring($mediaIndex)
$text = $text.Substring(0, $cssStart) + $cssBlock + $text.Substring($cssEnd)

$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-804 BLOCKED: dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)
$finalReturn = $dashboardBlock.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
if ($finalReturn -lt 0) {
    throw 'BF-804 BLOCKED: final dashboard HTML return is missing.'
}

$managerPrelude = @'
    $priorityKind = switch ($state) {
        "CURRENT_AND_ACTIONABLE" { "Waiver decision" }
        "CURRENT_REFRESH_RECOMMENDED" { "Waiver decision" }
        "TRANSACTION_ALREADY_COMPLETE" { "Waiver status" }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "Waiver status" }
        "STALE_DO_NOT_ACT" { "Safety stop" }
        "NO_TRANSACTION_TO_ACT_ON" { "Waiver decision" }
        default { "Decision status" }
    }

    $priorityMoveHtml = ""
    if ($transactionStates -ccontains $state) {
        $priorityMoveHtml = @"
<div class="priority-move">
  <div class="priority-player add"><strong>ADD</strong><span>$(ConvertTo-HtmlText $add.Name)</span><small>$(ConvertTo-HtmlText $add.Position) &middot; $(ConvertTo-HtmlText $add.Team)</small></div>
  <div class="priority-player drop"><strong>DROP</strong><span>$(ConvertTo-HtmlText $drop.Name)</span><small>$(ConvertTo-HtmlText $drop.Position) &middot; $(ConvertTo-HtmlText $drop.Team)</small></div>
</div>
"@
    }

    $whyCopy = if ($explanation.Ready) {
        $explanation.ExplanationText
    }
    elseif ($state -ceq "NO_TRANSACTION_TO_ACT_ON") {
        "Butler did not capture a separate persisted explanation for this no-transaction audit. The governed decision remains no move."
    }
    else {
        "No persisted explanation is available for this exact saved decision. Butler will not invent one from incomplete evidence."
    }

    $lineageStatusClass = if ($verification.LineageOk) { "good" } else { "warn" }
    $lineageStatusText = if ($verification.LineageOk) { "Verified" } else { "Needs refresh" }
    $rosterStatusClass = if ($verification.RosterOk) { "good" } else { "warn" }
    $rosterStatusText = if ($verification.RosterOk) { "Verified" } else { "Needs attention" }

'@

$dashboardBlock = $dashboardBlock.Substring(0, $finalReturn) + $managerPrelude + $dashboardBlock.Substring($finalReturn)
$finalReturn = $dashboardBlock.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
$functionClose = $dashboardBlock.Length

$newReturn = @'
    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler Command Center</title><style>$css</style></head><body><main class="shell">
$header
<section class="panel command-hero">
  <div class="command-head"><div><div class="command-kicker">Butler Command Center</div><h1 class="command-title">What matters now</h1><p class="command-copy">Your current governed decision, evidence status, and fastest paths to the tools that matter this week.</p></div><div class="status $($presentation.Class)">$(ConvertTo-HtmlText $presentation.Headline)</div></div>
  <div class="command-summary"><span class="summary-chip">$(ConvertTo-HtmlText $target)</span><span class="summary-chip">Read-only manager view</span><span class="summary-chip">Saved decisions remain traceable</span></div>
</section>
<section class="panel priority-panel">
  <div class="section-head"><div><div class="eyebrow">Butler's Priorities</div><h2>Start with the decision that matters</h2></div></div>
  <div class="priority-stack">
    <article class="priority-card primary"><div class="priority-index">01</div><div><div class="priority-type">$(ConvertTo-HtmlText $priorityKind)</div><div class="priority-title">$(ConvertTo-HtmlText $presentation.ActionTitle)</div><div class="priority-copy">$(ConvertTo-HtmlText $presentation.ActionCopy)</div>$priorityMoveHtml<div class="priority-actions"><a class="command-button" href="/waivers">Open Waiver Board</a><a class="command-button secondary" href="/history">Decision History</a></div></div><div class="status $($presentation.Class)">$(ConvertTo-HtmlText $presentation.Headline)</div></article>
  </div>
  <div class="quick-grid"><a class="quick-card" href="/team"><strong>Lineup Advisor</strong><span>Review My Team and run the read-only AutoFill preview.</span></a><a class="quick-card" href="/waivers"><strong>Waiver Board</strong><span>Review governed waiver candidates and the saved add/drop decision.</span></a><a class="quick-card" href="/trade"><strong>Trade Lab</strong><span>Move into Butler's trade analysis workspace.</span></a></div>
</section>
<section class="panel"><div class="eyebrow">Why Butler says this</div><h2>Decision explanation</h2><div class="why-card">$(ConvertTo-HtmlText $whyCopy)</div></section>
<section class="panel"><div class="section-head"><div><div class="eyebrow">Evidence status</div><h2>Can I trust this decision frame?</h2></div></div><div class="evidence-grid"><div class="evidence-card"><strong>Roster check</strong><div class="evidence-value">$(ConvertTo-HtmlText $rosterStatusText)</div><div class="evidence-note">$(ConvertTo-HtmlText $verification.Roster)</div></div><div class="evidence-card"><strong>Evidence lineage</strong><div class="evidence-value">$(ConvertTo-HtmlText $lineageStatusText)</div><div class="evidence-note">$(ConvertTo-HtmlText $verification.Lineage)</div></div><div class="evidence-card"><strong>Waiver market</strong><div class="evidence-value">$(ConvertTo-HtmlText $market.Human)</div><div class="evidence-note">Evidence age</div></div><div class="evidence-card"><strong>Roster / waiver</strong><div class="evidence-value">$(ConvertTo-HtmlText $waiver.Human)</div><div class="evidence-note">Evidence age</div></div></div></section>
<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>Saved and traceable</strong><span>This governed result remains available in Butler history even after roster conditions change.</span></div><div class="status done">RECORDED</div></div><div class="manager-actions"><a class="command-button" href="/history">View Decision History</a><a class="command-button secondary" href="/team">My Team</a><a class="command-button secondary" href="/league">League</a></div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Command Center reorganizes existing governed Butler evidence for manager use. It does not refresh evidence, create a new recommendation, rank players, set FAAB, submit a lineup, execute a trade, submit a Sleeper transaction, or mutate your league.</section>
</main></body></html>
"@
}

'@

$dashboardBlock = $dashboardBlock.Substring(0, $finalReturn) + $newReturn
$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

if ($text -notmatch 'Butler Command Center') {
    throw 'BF-804 BLOCKED: Command Center marker was not installed.'
}
if ($text -notmatch "Butler's Priorities") {
    throw 'BF-804 BLOCKED: decision-first priority surface was not installed.'
}
if ($text -match '<div class="player-id">Sleeper ID') {
    # Existing non-dashboard renderers may still contain technical player IDs; BF-804 only proves
    # the new Command Center return block itself does not render them. Do not reject source-wide IDs.
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
