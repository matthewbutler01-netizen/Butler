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
            }
            "Waiver" {
                $primaryExplanationTitle = "Why waiver is priority 01"
                $primaryExplanationCopy = $whyCopy
            }
            "Trade" {
                $primaryExplanationTitle = "Why trade is priority 01"
                $primaryExplanationCopy = $tradeSignalCopy
            }
            default {
                $primaryExplanationTitle = "Why this is priority 01"
                $primaryExplanationCopy = [string]$priorityOne.Copy
            }
        }
    }
'@

$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $priorityAnchor.TrimEnd() -New $priorityExplanation.TrimEnd() -Contract 'priority 01 explanation derivation'

$whyOld = '<section class="panel"><div class="eyebrow">Why Butler says this</div><h2>Decision explanation</h2><div class="why-card">$(ConvertTo-HtmlText $whyCopy)</div></section>'
$whyNew = '<section class="panel"><div class="eyebrow">Why Butler says this</div><h2>$(ConvertTo-HtmlText $primaryExplanationTitle)</h2><div class="why-card">$(ConvertTo-HtmlText $primaryExplanationCopy)</div></section>'
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
if ($text -match '<h2>Decision explanation</h2><div class="why-card">\$\(ConvertTo-HtmlText \$whyCopy\)') {
    throw 'BF-810 BLOCKED: waiver-only explanation markup still remains.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
