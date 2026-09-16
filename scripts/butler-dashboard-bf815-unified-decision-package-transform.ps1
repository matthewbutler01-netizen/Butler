param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-815 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-815 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-815 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-815 BLOCKED: dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)
$finalReturn = $dashboardBlock.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
if ($finalReturn -lt 0) {
    throw 'BF-815 BLOCKED: final dashboard HTML return is missing.'
}

$packageDerivation = @'
    $decisionPackageKind = "Decision"
    $decisionPackageStatus = "REVIEW"
    $decisionPackageStatusClass = "done"
    $decisionPackageTitle = "Review Butler's priority 01"
    $decisionPackageTrust = "Review the current evidence frame"
    $decisionPackageAction = [string]$primaryNextActionLabel
    $decisionPackageRecord = "Review the current decision record"

    if ($null -ne $priorityOne) {
        $decisionPackageKind = [string]$priorityOne.Kind
        $decisionPackageStatus = [string]$priorityOne.Status
        $decisionPackageStatusClass = [string]$priorityOne.StatusClass
        $decisionPackageTitle = [string]$priorityOne.Title

        switch ($decisionPackageKind) {
            "Lineup" {
                switch ([string]$lineupSignalStatus) {
                    "EVIDENCE GAP" {
                        $decisionPackageTrust = "Projection evidence incomplete"
                        $decisionPackageRecord = "AutoFill result saved locally"
                    }
                    "REFRESH AUTOFILL" {
                        $decisionPackageTrust = "Saved lineup frame needs refresh"
                        $decisionPackageRecord = "Saved lineup frame retained"
                    }
                    "AUTOFILL READY" {
                        $decisionPackageTrust = "Current projection evidence"
                        $decisionPackageRecord = "Recommendation saved locally"
                    }
                    "NO CHANGES" {
                        $decisionPackageTrust = "Current projection evidence"
                        $decisionPackageRecord = "No-change result saved locally"
                    }
                    "NEEDS ATTENTION" {
                        $decisionPackageTrust = "Roster state needs review"
                        $decisionPackageRecord = "No completed lineup review"
                    }
                    default {
                        $decisionPackageTrust = "No current AutoFill frame"
                        $decisionPackageRecord = "No lineup review recorded"
                    }
                }
            }
            "Waiver" {
                $decisionPackageRecord = "Decision history available"
                $decisionPackageTrust = switch ([string]$state) {
                    "CURRENT_AND_ACTIONABLE" { "Current waiver evidence" }
                    "CURRENT_REFRESH_RECOMMENDED" { "Waiver evidence needs refresh" }
                    "TRANSACTION_ALREADY_COMPLETE" { "Transaction state verified" }
                    "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "Pending transaction state verified" }
                    "STALE_DO_NOT_ACT" { "Saved waiver evidence is stale" }
                    "NO_TRANSACTION_TO_ACT_ON" { "No actionable waiver move" }
                    default { "Governed waiver evidence loaded" }
                }
            }
            "Trade" {
                $decisionPackageTrust = "No trade evidence loaded"
                $decisionPackageRecord = "No trade decision recorded"
            }
        }
    }

    $decisionPackageHtml = @"
<section class="panel">
  <div class="section-head"><div><div class="eyebrow">Priority 01 decision package</div><h2>$(ConvertTo-HtmlText $decisionPackageKind) &middot; $(ConvertTo-HtmlText $decisionPackageStatus)</h2><p class="meta">One scan of the active decision, trust frame, next action, and saved record.</p></div><div class="status $decisionPackageStatusClass">$(ConvertTo-HtmlText $decisionPackageStatus)</div></div>
  <div class="evidence-grid">
    <div class="evidence-card"><strong>Decision</strong><div class="evidence-value">$(ConvertTo-HtmlText $decisionPackageKind)</div><div class="evidence-note">$(ConvertTo-HtmlText $decisionPackageTitle)</div></div>
    <div class="evidence-card"><strong>Trust frame</strong><div class="evidence-value">$(ConvertTo-HtmlText $decisionPackageTrust)</div><div class="evidence-note">Evidence supporting priority 01</div></div>
    <div class="evidence-card"><strong>Next action</strong><div class="evidence-value">$(ConvertTo-HtmlText $decisionPackageAction)</div><div class="evidence-note">Manager action; Butler remains read-only</div></div>
    <div class="evidence-card"><strong>Record</strong><div class="evidence-value">$(ConvertTo-HtmlText $decisionPackageRecord)</div><div class="evidence-note">Current priority-01 record state</div></div>
  </div>
</section>
"@

'@

$dashboardBlock = $dashboardBlock.Substring(0, $finalReturn) + $packageDerivation + $dashboardBlock.Substring($finalReturn)

$packageAnchorOld = @'
</section>
<section class="panel priority-panel">
'@
$packageAnchorNew = @'
</section>
$decisionPackageHtml
<section class="panel priority-panel">
'@
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $packageAnchorOld.TrimEnd() -New $packageAnchorNew.TrimEnd() -Contract 'Command Center decision package placement'

$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

if ($text -notmatch 'Priority 01 decision package') {
    throw 'BF-815 BLOCKED: unified decision package marker is missing.'
}
if ($text -notmatch 'Projection evidence incomplete') {
    throw 'BF-815 BLOCKED: lineup evidence-gap trust summary is missing.'
}
if ($text -notmatch 'AutoFill result saved locally') {
    throw 'BF-815 BLOCKED: lineup record summary is missing.'
}
if ($text -notmatch 'No trade evidence loaded') {
    throw 'BF-815 BLOCKED: trade trust summary is missing.'
}
if ($text -notmatch '\$decisionPackageHtml') {
    throw 'BF-815 BLOCKED: decision package binding was not installed.'
}
if ($text -match 'FantasyProsApiClient|Invoke-RestMethod|BUTLER_FANTASYPROS_API_KEY|AutoFillLineupOptimizer|Method = "POST"') {
    throw 'BF-815 BLOCKED: decision package introduced provider, optimizer, credential, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

# BF-816: polish healthy, neutral, and not-yet-evaluated Command Center states only after
# the unified package and all detailed priority-01 sections have been installed.
$bf816Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf816-healthy-state-polish-transform.ps1'
if (-not (Test-Path -LiteralPath $bf816Transform -PathType Leaf)) {
    throw "BF-816 BLOCKED: healthy-state polish transform not found at $bf816Transform"
}
& $bf816Transform -DashboardPath $DashboardPath
