param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-807 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-807 BLOCKED: dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)
$tradeAnchor = '    $tradeSignalClass = "done"'
$tradeAnchorMatches = [regex]::Matches($dashboardBlock, [regex]::Escape($tradeAnchor)).Count
if ($tradeAnchorMatches -ne 1) {
    throw "BF-807 BLOCKED: expected one BF-806 trade signal anchor, found $tradeAnchorMatches."
}

$orderingPrelude = @'

    $waiverAttentionGroup = switch ($state) {
        "CURRENT_AND_ACTIONABLE" { "attention" }
        "CURRENT_REFRESH_RECOMMENDED" { "attention" }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "attention" }
        "STALE_DO_NOT_ACT" { "attention" }
        default { "neutral" }
    }
    $lineupAttentionGroup = if ($verification.RosterOk) { "review" } else { "attention" }
    $tradeAttentionGroup = "neutral"

    $prioritySignals = @(
        [pscustomobject]@{
            Kind = "Waiver"
            Title = $waiverSignalTitle
            Copy = $waiverSignalCopy
            Status = $waiverSignalStatus
            StatusClass = $waiverSignalClass
            AttentionGroup = $waiverAttentionGroup
            ExtraHtml = $priorityMoveHtml
            ActionsHtml = '<a class="command-button" href="/waivers">Open Waiver Board</a><a class="command-button secondary" href="/history">Decision History</a>'
        },
        [pscustomobject]@{
            Kind = "Lineup"
            Title = $lineupSignalTitle
            Copy = $lineupSignalCopy
            Status = $lineupSignalStatus
            StatusClass = $lineupSignalClass
            AttentionGroup = $lineupAttentionGroup
            ExtraHtml = ""
            ActionsHtml = '<a class="command-button" href="/team">Open Lineup Advisor</a>'
        },
        [pscustomobject]@{
            Kind = "Trade"
            Title = $tradeSignalTitle
            Copy = $tradeSignalCopy
            Status = $tradeSignalStatus
            StatusClass = $tradeSignalClass
            AttentionGroup = $tradeAttentionGroup
            ExtraHtml = ""
            ActionsHtml = '<a class="command-button" href="/trade">Open Trade Analyzer</a>'
        }
    )

    $orderedPrioritySignals = @()
    foreach ($attentionGroup in @("attention", "review", "neutral")) {
        foreach ($signal in $prioritySignals) {
            if ($signal.AttentionGroup -ceq $attentionGroup) {
                $orderedPrioritySignals += $signal
            }
        }
    }

    $priorityCardList = New-Object System.Collections.Generic.List[string]
    for ($priorityIndex = 0; $priorityIndex -lt $orderedPrioritySignals.Count; $priorityIndex++) {
        $signal = $orderedPrioritySignals[$priorityIndex]
        $cardClass = if ($priorityIndex -eq 0) { "priority-card primary" } else { "priority-card" }
        $displayPriority = "{0:D2}" -f ($priorityIndex + 1)
        $extraHtml = [string]$signal.ExtraHtml
        $cardHtml = @"
    <article class="$cardClass"><div class="priority-index">$displayPriority</div><div><div class="priority-type">$(ConvertTo-HtmlText $signal.Kind)</div><div class="priority-title">$(ConvertTo-HtmlText $signal.Title)</div><div class="priority-copy">$(ConvertTo-HtmlText $signal.Copy)</div>$extraHtml<div class="priority-actions">$($signal.ActionsHtml)</div></div><div class="status $($signal.StatusClass)">$(ConvertTo-HtmlText $signal.Status)</div></article>
"@
        $priorityCardList.Add($cardHtml)
    }
    $priorityQueueHtml = $priorityCardList -join "`n"
'@

$dashboardBlock = $dashboardBlock.Replace($tradeAnchor, $tradeAnchor + $orderingPrelude)

$priorityStartMarker = '<section class="panel priority-panel">'
$whyStartMarker = '<section class="panel"><div class="eyebrow">Why Butler says this</div>'
$priorityStart = $dashboardBlock.IndexOf($priorityStartMarker, [System.StringComparison]::Ordinal)
$whyStart = $dashboardBlock.IndexOf($whyStartMarker, $priorityStart, [System.StringComparison]::Ordinal)
if ($priorityStart -lt 0 -or $whyStart -le $priorityStart) {
    throw 'BF-807 BLOCKED: BF-806 priority panel boundary is missing.'
}

$priorityReplacement = @'
<section class="panel priority-panel">
  <div class="section-head"><div><div class="eyebrow">Butler's Priorities</div><h2>Your decision queue</h2><p class="meta">Signals with an explicit action, safety stop, refresh need, or roster attention rise above review and neutral states. Order reflects Butler's existing state, not a separate priority score.</p></div></div>
  <div class="priority-stack">
$priorityQueueHtml
  </div>
</section>
'@

$dashboardBlock = $dashboardBlock.Substring(0, $priorityStart) + $priorityReplacement + $dashboardBlock.Substring($whyStart)
$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

if ($text -notmatch '\$orderedPrioritySignals') {
    throw 'BF-807 BLOCKED: ordered priority queue was not installed.'
}
if ($text -notmatch 'foreach \(\$attentionGroup in @\("attention", "review", "neutral"\)\)') {
    throw 'BF-807 BLOCKED: explicit attention ordering contract is missing.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
