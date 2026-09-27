param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-843 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-843 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-843 BLOCKED: Dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)

$queueAnchor = '        $cardClass = if ($managerIndex -eq 0) { "manager-decision-card primary" } else { "manager-decision-card" }'
$queueRouting = @'
        # BF-843: lineup review stays available even when exact opponent context is unavailable.
        if ($kind -ceq "Lineup") {
            switch ([string]$lineupSignalStatus) {
                "NOT REVIEWED" {
                    $actionHref = "/team/autofill"
                    $actionLabel = "Review Lineup"
                }
                "REFRESH AUTOFILL" {
                    $actionHref = "/team/autofill"
                    $actionLabel = "Refresh Lineup"
                }
                "AUTOFILL READY" {
                    $actionHref = "/team/autofill"
                    $actionLabel = "Review Lineup"
                }
                "NO CHANGES" {
                    $actionHref = "/matchup"
                    $actionLabel = "View Matchup"
                }
                "EVIDENCE GAP" {
                    $actionHref = "/team/autofill"
                    $actionLabel = "Review Lineup"
                }
            }
        }

'@
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $queueAnchor -New ($queueRouting + $queueAnchor) -Contract 'manager lineup-card route overlay'

$proofAnchor = '    $proofChangeCopy = "No additional change detail is available for this priority."'
$proofRouting = @'
    if ($null -ne $priorityOne -and [string]$priorityOne.Kind -ceq "Lineup") {
        switch ([string]$lineupSignalStatus) {
            "NOT REVIEWED" {
                $primaryNextActionHref = "/team/autofill"
                $primaryNextActionLabel = "Review Lineup"
            }
            "REFRESH AUTOFILL" {
                $primaryNextActionHref = "/team/autofill"
                $primaryNextActionLabel = "Refresh Lineup"
            }
            "AUTOFILL READY" {
                $primaryNextActionHref = "/team/autofill"
                $primaryNextActionLabel = "Review Lineup"
            }
            "NO CHANGES" {
                $primaryNextActionHref = "/matchup"
                $primaryNextActionLabel = "View Matchup"
            }
            "EVIDENCE GAP" {
                $primaryNextActionHref = "/team/autofill"
                $primaryNextActionLabel = "Review Lineup"
            }
        }
    }

'@
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $proofAnchor -New ($proofRouting + $proofAnchor) -Contract 'priority-01 lineup next-action route overlay'

$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

foreach ($required in @(
    'BF-843',
    '$actionHref = "/team/autofill"',
    '$actionHref = "/matchup"',
    '$primaryNextActionHref = "/team/autofill"',
    '$primaryNextActionHref = "/matchup"',
    '$actionLabel = "Review Lineup"',
    '$actionLabel = "Refresh Lineup"',
    '$actionLabel = "View Matchup"'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-843 BLOCKED: required Dashboard Matchup-routing marker is missing: $required"
    }
}

$installedDashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($installedDashboardEnd -le $dashboardStart) {
    throw 'BF-843 BLOCKED: installed Dashboard renderer boundary is missing.'
}
$installedDashboard = $text.Substring($dashboardStart, $installedDashboardEnd - $dashboardStart)
if ($installedDashboard -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-843 BLOCKED: Dashboard Matchup routing introduced provider, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-843 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-843 Dashboard Weekly Matchup routing applied.'
