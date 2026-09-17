param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-814 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-814 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-814 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-814 BLOCKED: dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)
$finalReturn = $dashboardBlock.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
if ($finalReturn -lt 0) {
    throw 'BF-814 BLOCKED: final dashboard HTML return is missing.'
}

$recordDerivation = @'
    $primaryRecordHtml = @"
<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>Waiver decision saved and traceable</strong><span>This governed waiver result remains available in Butler history even after roster conditions change.</span></div><div class="status done">RECORDED</div></div><div class="manager-actions"><a class="command-button" href="/history">View Decision History</a><a class="command-button secondary" href="/waivers">Waiver Board</a><a class="command-button secondary" href="/league">League</a></div></section>
"@

    if ($null -ne $priorityOne) {
        switch ([string]$priorityOne.Kind) {
            "Lineup" {
                switch ([string]$lineupSignalStatus) {
                    "EVIDENCE GAP" {
                        $primaryRecordHtml = @"
<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>Lineup review saved locally</strong><span>The latest explicit read-only AutoFill result is saved for this lineup frame, but projection coverage is incomplete. No lineup was submitted.</span></div><div class="status warn">EVIDENCE GAP</div></div><div class="manager-actions"><a class="command-button" href="/team">Review My Team</a></div></section>
"@
                    }
                    "REFRESH AUTOFILL" {
                        $primaryRecordHtml = @"
<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>Saved lineup frame needs refresh</strong><span>The previous read-only AutoFill result is preserved locally, but it is no longer current for this roster or evidence frame. No lineup was submitted.</span></div><div class="status warn">REFRESH</div></div><div class="manager-actions"><a class="command-button" href="/team/autofill">Run AutoFill again</a><a class="command-button secondary" href="/team">My Team</a></div></section>
"@
                    }
                    "AUTOFILL READY" {
                        $primaryRecordHtml = @"
<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>Lineup recommendation saved locally</strong><span>The read-only current-versus-recommended AutoFill result is saved for manager review. Butler has not submitted a lineup.</span></div><div class="status good">SAVED</div></div><div class="manager-actions"><a class="command-button" href="/team">Open Lineup Advisor</a></div></section>
"@
                    }
                    "NO CHANGES" {
                        $primaryRecordHtml = @"
<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>No-change lineup result saved locally</strong><span>The latest read-only AutoFill result is saved for this lineup frame and did not prove a starter change. No lineup was submitted.</span></div><div class="status done">SAVED</div></div><div class="manager-actions"><a class="command-button" href="/team">Review My Team</a></div></section>
"@
                    }
                    "NEEDS ATTENTION" {
                        $primaryRecordHtml = @"
<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>Lineup review needs attention</strong><span>Butler cannot treat the current roster state as a completed lineup review. Open My Team before relying on a lineup decision.</span></div><div class="status warn">REVIEW</div></div><div class="manager-actions"><a class="command-button" href="/team">Review My Team</a></div></section>
"@
                    }
                    default {
                        $primaryRecordHtml = @"
<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>No lineup review recorded yet</strong><span>No current explicit AutoFill result is saved for this lineup frame. Butler has not evaluated or submitted a lineup from the Dashboard.</span></div><div class="status done">NOT RECORDED</div></div><div class="manager-actions"><a class="command-button" href="/team">Open Lineup Advisor</a></div></section>
"@
                    }
                }
            }
            "Waiver" {
                $primaryRecordHtml = @"
<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>Waiver decision saved and traceable</strong><span>This governed waiver result remains available in Butler history even after roster conditions change.</span></div><div class="status done">RECORDED</div></div><div class="manager-actions"><a class="command-button" href="/history">View Decision History</a><a class="command-button secondary" href="/waivers">Waiver Board</a><a class="command-button secondary" href="/league">League</a></div></section>
"@
            }
            "Trade" {
                $primaryRecordHtml = @"
<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>No trade decision recorded</strong><span>No specific trade has been evaluated in the current Dashboard frame. Open Trade Lab with a deal or target before Butler can produce a trade record.</span></div><div class="status done">ON DEMAND</div></div><div class="manager-actions"><a class="command-button" href="/trade">Open Trade Lab</a></div></section>
"@
            }
        }
    }

'@

$dashboardBlock = $dashboardBlock.Substring(0, $finalReturn) + $recordDerivation + $dashboardBlock.Substring($finalReturn)

$recordOld = '<section class="panel"><div class="decision-strip"><div><div class="eyebrow">Decision record</div><strong>Saved and traceable</strong><span>This governed result remains available in Butler history even after roster conditions change.</span></div><div class="status done">RECORDED</div></div><div class="manager-actions"><a class="command-button" href="/history">View Decision History</a><a class="command-button secondary" href="/team">My Team</a><a class="command-button secondary" href="/league">League</a></div></section>'
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $recordOld -New '$primaryRecordHtml' -Contract 'Command Center Decision Record panel'

$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

if ($text -notmatch 'Lineup review saved locally') {
    throw 'BF-814 BLOCKED: lineup evidence-gap Decision Record contract is missing.'
}
if ($text -notmatch 'No lineup was submitted') {
    throw 'BF-814 BLOCKED: lineup read-only Decision Record boundary is missing.'
}
if ($text -notmatch 'Waiver decision saved and traceable') {
    throw 'BF-814 BLOCKED: waiver Decision Record contract is missing.'
}
if ($text -notmatch 'No trade decision recorded') {
    throw 'BF-814 BLOCKED: trade Decision Record contract is missing.'
}
if ($text -notmatch '\$primaryRecordHtml') {
    throw 'BF-814 BLOCKED: priority-aware Decision Record binding was not installed.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

# BF-815: after the detailed priority-aware record is installed, add a compact summary
# that lets the manager scan decision, trust frame, next action, and record as one package.
$bf815Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf815-unified-decision-package-transform.ps1'
if (-not (Test-Path -LiteralPath $bf815Transform -PathType Leaf)) {
    throw "BF-815 BLOCKED: unified decision package transform not found at $bf815Transform"
}
& $bf815Transform -DashboardPath $DashboardPath

# BF-832: BF-815 chains through BF-816 and the current My Team staging work before
# returning. Apply the Dashboard visual system last so no older command-center pass can
# restore the legacy navy presentation afterward.
$bf832Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf832-command-center-visual-transform.ps1'
if (-not (Test-Path -LiteralPath $bf832Transform -PathType Leaf)) {
    throw "BF-832 BLOCKED: Dashboard command-center visual transform not found at $bf832Transform"
}
& $bf832Transform -DashboardPath $DashboardPath
