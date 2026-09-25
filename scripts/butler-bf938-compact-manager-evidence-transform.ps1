param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($path in @($DashboardPath, $CorePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "BF-938 BLOCKED: staged Butler file not found at $path"
    }
}

function Add-CssBeforeHereStringEnd {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$BlockStart,
        [Parameter(Mandatory = $true)][string]$BlockEnd,
        [Parameter(Mandatory = $true)][string]$Css,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $start = $Text.IndexOf($BlockStart, [System.StringComparison]::Ordinal)
    $end = $Text.IndexOf($BlockEnd, $start, [System.StringComparison]::Ordinal)
    if ($start -lt 0 -or $end -le $start) {
        throw "BF-938 BLOCKED: $Contract CSS block boundary is missing."
    }

    $block = $Text.Substring($start, $end - $start)
    $terminator = $block.LastIndexOf("'@", [System.StringComparison]::Ordinal)
    if ($terminator -lt 0) {
        $terminator = $block.LastIndexOf('"@', [System.StringComparison]::Ordinal)
    }
    if ($terminator -lt 0) {
        throw "BF-938 BLOCKED: $Contract CSS here-string terminator is missing."
    }

    $block = $block.Substring(0, $terminator) +
        [Environment]::NewLine + $Css.TrimEnd() + [Environment]::NewLine +
        $block.Substring($terminator)

    return $Text.Substring(0, $start) + $block + $Text.Substring($end)
}

# Core: add one shared disclosure treatment after every upstream manager transform is final.
$core = [System.IO.File]::ReadAllText($CorePath)
$coreCss = @'
/* BF-938 compact manager evidence. */
.manager-evidence-disclosure{margin:14px 0 0;border:1px solid var(--line);border-radius:12px;background:var(--surface)}.manager-evidence-disclosure>summary{cursor:pointer;padding:13px 15px;color:var(--turf-deep);font-weight:800}.manager-evidence-disclosure[open]>summary{border-bottom:1px solid var(--line)}.manager-evidence-body{padding:14px}.manager-evidence-body>.panel{margin:0 0 12px}.manager-evidence-body>.panel:last-child{margin-bottom:0}.compare-evidence-details{margin-top:12px}.compare-evidence-details .compare-flags{margin-top:10px}
'@
$core = Add-CssBeforeHereStringEnd -Text $core -BlockStart 'function Get-AppCss {' -BlockEnd 'function Get-AppNav {' -Css $coreCss -Contract 'shared manager'

# Player Compare: keep identity, summary metrics, and actions first-scan.
$compareCardStart = $core.IndexOf('function ConvertTo-PlayerCompareCardHtml {', [System.StringComparison]::Ordinal)
$compareCardEnd = $core.IndexOf('function ConvertTo-PlayerCompareHtml {', $compareCardStart, [System.StringComparison]::Ordinal)
if ($compareCardStart -lt 0 -or $compareCardEnd -le $compareCardStart) {
    throw 'BF-938 BLOCKED: Player Compare card renderer boundary is missing.'
}
$compareCard = $core.Substring($compareCardStart, $compareCardEnd - $compareCardStart)
$productionStart = $compareCard.IndexOf('<h3>Per-game production</h3>', [System.StringComparison]::Ordinal)
$actionStart = $compareCard.IndexOf('<div class="button-row">', $productionStart, [System.StringComparison]::Ordinal)
$actionEnd = $compareCard.IndexOf('</div>', $actionStart, [System.StringComparison]::Ordinal)
$supportStart = $compareCard.IndexOf('<details><summary>Supporting evidence</summary>', $actionEnd, [System.StringComparison]::Ordinal)
$supportEnd = $compareCard.IndexOf('</details>', $supportStart, [System.StringComparison]::Ordinal)
if ($productionStart -lt 0 -or $actionStart -le $productionStart -or $actionEnd -le $actionStart -or $supportStart -le $actionEnd -or $supportEnd -le $supportStart) {
    throw 'BF-938 BLOCKED: final Player Compare evidence/action order is missing.'
}
$actionEnd += '</div>'.Length
$supportEnd += '</details>'.Length

$productionHtml = $compareCard.Substring($productionStart, $actionStart - $productionStart).TrimEnd()
$actionHtml = $compareCard.Substring($actionStart, $actionEnd - $actionStart)
$supportHtml = $compareCard.Substring($supportStart, $supportEnd - $supportStart)
$compareOld = $compareCard.Substring($productionStart, $supportEnd - $productionStart)
$compareNew = $actionHtml + [Environment]::NewLine +
    '<details class="manager-evidence-disclosure compare-evidence-details"><summary>View player evidence</summary><div class="manager-evidence-body">' +
    [Environment]::NewLine + $productionHtml + [Environment]::NewLine + $supportHtml +
    [Environment]::NewLine + '</div></details>'
$compareCard = $compareCard.Replace($compareOld, $compareNew)
$core = $core.Substring(0, $compareCardStart) + $compareCard + $core.Substring($compareCardEnd)

# Franchise Scout: composition + manager actions stay first-scan; deeper profile evidence is disclosed.
$franchiseStart = $core.IndexOf('function ConvertTo-FranchiseDetailHtml {', [System.StringComparison]::Ordinal)
$franchiseEnd = $core.IndexOf('function ConvertTo-LeagueHtml {', $franchiseStart, [System.StringComparison]::Ordinal)
if ($franchiseStart -lt 0 -or $franchiseEnd -le $franchiseStart) {
    throw 'BF-938 BLOCKED: Franchise Scout renderer boundary is missing.'
}
$franchiseBlock = $core.Substring($franchiseStart, $franchiseEnd - $franchiseStart)
$franchiseEvidenceStart = $franchiseBlock.IndexOf('<section class="panel"><div class="section-head"><div><div class="eyebrow">Evidence quality</div>', [System.StringComparison]::Ordinal)
$franchiseBoundary = $franchiseBlock.IndexOf('<section class="panel boundary">', $franchiseEvidenceStart, [System.StringComparison]::Ordinal)
if ($franchiseEvidenceStart -lt 0 -or $franchiseBoundary -le $franchiseEvidenceStart) {
    throw 'BF-938 BLOCKED: final Franchise Scout evidence boundary is missing.'
}
$franchiseEvidence = $franchiseBlock.Substring($franchiseEvidenceStart, $franchiseBoundary - $franchiseEvidenceStart).TrimEnd()
$franchiseDisclosure = '<details class="manager-evidence-disclosure franchise-evidence-details"><summary>View franchise evidence</summary><div class="manager-evidence-body">' +
    [Environment]::NewLine + $franchiseEvidence + [Environment]::NewLine + '</div></details>' + [Environment]::NewLine
$franchiseBlock = $franchiseBlock.Substring(0, $franchiseEvidenceStart) + $franchiseDisclosure + $franchiseBlock.Substring($franchiseBoundary)
$core = $core.Substring(0, $franchiseStart) + $franchiseBlock + $core.Substring($franchiseEnd)

foreach ($required in @(
    'View player evidence',
    'Per-game production',
    'Compare with another ',
    'View Player Detail',
    'Scout franchise',
    'View franchise evidence',
    'Evidence quality',
    'Value concentration',
    'Positional evidence',
    'Scout this franchise',
    'Open Trade Analyzer'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-938 BLOCKED: required staged-core marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-938 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

# Dashboard: keep Priority 01 + Week at a glance visible and disclose the duplicate queue.
$dashboard = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $dashboard.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $dashboard.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-938 BLOCKED: final Dashboard renderer boundary is missing.'
}
$dashboardBlock = $dashboard.Substring($dashboardStart, $dashboardEnd - $dashboardStart)

$dashboardCssStart = $dashboardBlock.IndexOf('$bf819Css = @"', [System.StringComparison]::Ordinal)
$dashboardCssEnd = $dashboardBlock.IndexOf('"@', $dashboardCssStart + 1, [System.StringComparison]::Ordinal)
if ($dashboardCssStart -lt 0 -or $dashboardCssEnd -le $dashboardCssStart) {
    throw 'BF-938 BLOCKED: Dashboard manager CSS boundary is missing.'
}
$dashboardCss = '.dashboard-priority-disclosure{margin-top:14px;border:1px solid var(--line);border-radius:12px;background:var(--surface)}.dashboard-priority-disclosure>summary{cursor:pointer;padding:13px 15px;color:var(--turf-deep);font-size:12px;font-weight:800}.dashboard-priority-disclosure[open]>summary{border-bottom:1px solid var(--line)}.dashboard-priority-body{padding:14px}.dashboard-priority-body>.panel{margin:0;border:0;box-shadow:none}'
$dashboardBlock = $dashboardBlock.Insert($dashboardCssEnd, [Environment]::NewLine + $dashboardCss + [Environment]::NewLine)

$queueStart = $dashboardBlock.IndexOf('<section class="panel manager-queue">', [System.StringComparison]::Ordinal)
$queueBoundary = $dashboardBlock.IndexOf('<section class="panel boundary">', $queueStart, [System.StringComparison]::Ordinal)
if ($queueStart -lt 0 -or $queueBoundary -le $queueStart) {
    throw 'BF-938 BLOCKED: final Dashboard other-priority queue boundary is missing.'
}
$queueHtml = $dashboardBlock.Substring($queueStart, $queueBoundary - $queueStart).TrimEnd()
$queueDisclosure = '<details class="dashboard-priority-disclosure"><summary>View other priorities</summary><div class="dashboard-priority-body">' +
    [Environment]::NewLine + $queueHtml + [Environment]::NewLine + '</div></details>' + [Environment]::NewLine
$dashboardBlock = $dashboardBlock.Substring(0, $queueStart) + $queueDisclosure + $dashboardBlock.Substring($queueBoundary)
$dashboard = $dashboard.Substring(0, $dashboardStart) + $dashboardBlock + $dashboard.Substring($dashboardEnd)

foreach ($required in @(
    'Priority 01',
    'Week at a glance',
    'View other priorities',
    'After Priority 01',
    'Other priorities',
    'View full decision queue'
)) {
    if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-938 BLOCKED: required Dashboard marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($DashboardPath, $dashboard, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-938 BLOCKED: generated staged Dashboard failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-938 compact manager evidence batch applied.'
