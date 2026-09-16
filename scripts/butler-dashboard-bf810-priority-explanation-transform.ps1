param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-810 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $first = $Text.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) {
        throw "BF-810 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-810 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-810 BLOCKED: dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)

$priorityAnchor = @'
    $priorityQueueHtml = $priorityCardList -join "`n"
'@

$priorityExplanation = @'
    $priorityQueueHtml = $priorityCardList -join "`n"

    $priorityOne = if ($orderedPrioritySignals.Count -gt 0) { $orderedPrioritySignals[0] } else { $null }
    $primaryExplanationTitle = "Why this is priority 01"
    $primaryExplanationCopy = "Butler does not currently have enough evidence to explain a priority signal."
    $primaryNextActionCopy = "Review the priority signal above before making a roster decision."
    $primaryNextActionHref = "/"
    $primaryNextActionLabel = "Return to Dashboard"

    if ($null -ne $priorityOne) {
        switch ([string]$priorityOne.Kind) {
            "Lineup" {
                $primaryExplanationTitle = "Why lineup is priority 01"
                $primaryExplanationCopy = switch ([string]$lineupSignalStatus) {
                    "EVIDENCE GAP" { "The latest read-only AutoFill stopped because Butler could not prove a complete weekly lineup from the available projections. $lineupSignalCopy Butler will not guess at the missing projection." }
                    "REFRESH AUTOFILL" { "A saved AutoFill result exists, but it is no longer current for this roster or evidence frame. Run AutoFill again before relying on a lineup recommendation." }
                    "AUTOFILL READY" { "The latest saved AutoFill contains a proven lineup change, so lineup attention rises to the top of the queue. $lineupSignalCopy" }
                    "NO CHANGES" { "The latest saved AutoFill proved that the current starters should remain in place for this evidence frame. $lineupSignalCopy" }
                    "NEEDS ATTENTION" { $lineupSignalCopy }
                    default { "Butler has verified the roster, but there is no current saved AutoFill result yet. Open Lineup Advisor to request the read-only weekly projection check." }
                }
                switch ([string]$lineupSignalStatus) {
                    "EVIDENCE GAP" {
                        $primaryNextActionCopy = "Review your current starters manually. Retry AutoFill after the missing weekly projection or provider evidence becomes available; Butler will not guess in the meantime."
                        $primaryNextActionHref = "/team"
                        $primaryNextActionLabel = "Review My Team"
                    }
                    "REFRESH AUTOFILL" {
                        $primaryNextActionCopy = "Run AutoFill again before relying on the saved lineup result because the roster or evidence frame has changed."
                        $primaryNextActionHref = "/team/autofill"
                        $primaryNextActionLabel = "Run AutoFill again"
                    }
                    "AUTOFILL READY" {
                        $primaryNextActionCopy = "Review the saved lineup recommendation and its current-versus-recommended changes before deciding what to do. Butler still will not submit a lineup."
                        $primaryNextActionHref = "/team"
                        $primaryNextActionLabel = "Open Lineup Advisor"
                    }
                    "NO CHANGES" {
                        $primaryNextActionCopy = "No lineup change is currently proven. Review My Team if you want to inspect the current starters, but Butler has no change to recommend from this evidence frame."
                        $primaryNextActionHref = "/team"
                        $primaryNextActionLabel = "Review My Team"
                    }
                    "NEEDS ATTENTION" {
                        $primaryNextActionCopy = "Review My Team before relying on a lineup recommendation. The current roster state needs manager attention first."
                        $primaryNextActionHref = "/team"
                        $primaryNextActionLabel = "Review My Team"
                    }
                    default {
                        $primaryNextActionCopy = "Open Lineup Advisor and explicitly request the read-only AutoFill check when you want Butler to evaluate this week's starters."
                        $primaryNextActionHref = "/team"
                        $primaryNextActionLabel = "Open Lineup Advisor"
                    }
                }
            }
            "Waiver" {
                $primaryExplanationTitle = "Why waiver is priority 01"
                $primaryExplanationCopy = $whyCopy
                switch ([string]$state) {
                    "CURRENT_AND_ACTIONABLE" {
                        $primaryNextActionCopy = "Open Waiver Board and review the proven add/drop move and evidence before deciding whether to act."
                        $primaryNextActionHref = "/waivers"
                        $primaryNextActionLabel = "Review Waiver Move"
                    }
                    "CURRENT_REFRESH_RECOMMENDED" {
                        $primaryNextActionCopy = "Refresh the current waiver evidence before acting on the saved recommendation."
                        $primaryNextActionHref = "/waivers"
                        $primaryNextActionLabel = "Open Waiver Board"
                    }
                    "TRANSACTION_ALREADY_COMPLETE" {
                        $primaryNextActionCopy = "No duplicate action is needed. Review Decision History if you want to confirm the completed transaction record."
                        $primaryNextActionHref = "/history"
                        $primaryNextActionLabel = "View Decision History"
                    }
                    "TRANSACTION_PENDING_DO_NOT_DUPLICATE" {
                        $primaryNextActionCopy = "Wait for the existing transaction to resolve. Do not submit the same move again."
                        $primaryNextActionHref = "/history"
                        $primaryNextActionLabel = "View Decision History"
                    }
                    "STALE_DO_NOT_ACT" {
                        $primaryNextActionCopy = "Do not act on the stale saved move. Open Waiver Board for the current evidence before making a roster decision."
                        $primaryNextActionHref = "/waivers"
                        $primaryNextActionLabel = "Open Waiver Board"
                    }
                    "NO_TRANSACTION_TO_ACT_ON" {
                        $primaryNextActionCopy = "No waiver action is needed from the current evidence. Check Waiver Board only if you want to review the market."
                        $primaryNextActionHref = "/waivers"
                        $primaryNextActionLabel = "Open Waiver Board"
                    }
                    default {
                        $primaryNextActionCopy = "Open Waiver Board to review the current governed waiver evidence before deciding whether any move is needed."
                        $primaryNextActionHref = "/waivers"
                        $primaryNextActionLabel = "Open Waiver Board"
                    }
                }
            }
            "Trade" {
                $primaryExplanationTitle = "Why trade is priority 01"
                $primaryExplanationCopy = $tradeSignalCopy
                $primaryNextActionCopy = "Open Trade Lab when you have a specific deal or target to evaluate. Butler will not invent a trade rationale from an on-demand state."
                $primaryNextActionHref = "/trade"
                $primaryNextActionLabel = "Open Trade Lab"
            }
            default {
                $primaryExplanationTitle = "Why this is priority 01"
                $primaryExplanationCopy = [string]$priorityOne.Copy
                $primaryNextActionCopy = "Review the priority signal above before making a roster decision."
                $primaryNextActionHref = "/"
                $primaryNextActionLabel = "Return to Dashboard"
            }
        }
    }
'@

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $priorityAnchor.TrimEnd() -New $priorityExplanation.TrimEnd() -Contract 'priority 01 explanation derivation'

$whyOld = '<section class="panel"><div class="eyebrow">Why Butler says this</div><h2>Decision explanation</h2><div class="why-card">$(ConvertTo-HtmlText $whyCopy)</div></section>'
$whyNew = '<section class="panel"><div class="eyebrow">Why Butler says this</div><h2>$(ConvertTo-HtmlText $primaryExplanationTitle)</h2><div class="why-card">$(ConvertTo-HtmlText $primaryExplanationCopy)</div><div class="why-card"><div class="eyebrow">What to do next</div><div>$(ConvertTo-HtmlText $primaryNextActionCopy)</div><div class="priority-actions"><a class="command-button" href="$(ConvertTo-HtmlText $primaryNextActionHref)">$(ConvertTo-HtmlText $primaryNextActionLabel)</a></div></div></section>'
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $whyOld -New $whyNew -Contract 'Command Center explanation panel'

$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

if ($text -notmatch '\$priorityOne = if \(\$orderedPrioritySignals\.Count -gt 0\)') {
    throw 'BF-810 BLOCKED: priority 01 explanation binding was not installed.'
}
if ($text -notmatch 'Why lineup is priority 01') {
    throw 'BF-810 BLOCKED: lineup explanation contract is missing.'
}
if ($text -notmatch 'Why waiver is priority 01') {
    throw 'BF-810 BLOCKED: waiver explanation contract is missing.'
}
if ($text -notmatch 'What to do next') {
    throw 'BF-811 BLOCKED: priority next-action surface was not installed.'
}
if ($text -notmatch 'Retry AutoFill after the missing weekly projection or provider evidence becomes available') {
    throw 'BF-811 BLOCKED: lineup evidence-gap recovery guidance is missing.'
}
if ($text -match '<h2>Decision explanation</h2><div class="why-card">\$\(ConvertTo-HtmlText \$whyCopy\)') {
    throw 'BF-810 BLOCKED: waiver-only explanation markup still remains.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
