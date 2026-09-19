param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-871 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-871 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-871 BLOCKED: Dashboard renderer function boundary is missing.'
}

$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)

$viewAnchor = '    $lineupViews = @{'
$heroDefaults = @'
    # BF-871 is presentation-only. Capture the exact final priority-01 card values
    # after all existing priority and route overlays have resolved them.
    $bf871HeroKind = "Decision"
    $bf871HeroTitle = "Review Butler's top priority"
    $bf871HeroCopy = [string]$managerHeroCopy
    $bf871HeroStatus = "REVIEW"
    $bf871HeroStatusClass = "done"
    $bf871HeroActionHref = "/"
    $bf871HeroActionLabel = "Review Dashboard"

    $lineupViews = @{
'@
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $viewAnchor -New $heroDefaults.TrimEnd() -Contract 'hero default binding'

$cardAnchor = '        $cardClass = if ($managerIndex -eq 0) { "manager-decision-card primary" } else { "manager-decision-card" }'
$cardCapture = @'
        if ($managerIndex -eq 0) {
            $bf871HeroKind = [string]$kind
            $bf871HeroTitle = [string]$title
            $bf871HeroCopy = [string]$copy
            $bf871HeroStatus = [string]$status
            $bf871HeroStatusClass = [string]$statusClass
            $bf871HeroActionHref = [string]$actionHref
            $bf871HeroActionLabel = [string]$actionLabel
        }

        $cardClass = if ($managerIndex -eq 0) { "manager-decision-card primary" } else { "manager-decision-card" }
'@
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $cardAnchor -New $cardCapture.TrimEnd() -Contract 'priority-01 hero capture'

$heroOld = '<section class="panel command-hero manager-hero"><div class="command-head"><div><div class="command-kicker">Butler Command Center</div><h1 class="command-title">What matters now</h1><p class="command-copy">$(ConvertTo-HtmlText $managerHeroCopy)</p></div><div class="status done">DECISION QUEUE</div></div><div class="command-meta"><span>$(ConvertTo-HtmlText $target)</span><span>Read-only manager view</span><span>Saved decisions remain traceable</span></div></section>'
$heroNew = '<section class="panel command-hero manager-hero"><div class="command-head"><div><div class="command-kicker">Priority 01 &middot; $(ConvertTo-HtmlText $bf871HeroKind)</div><h1 class="command-title">$(ConvertTo-HtmlText $bf871HeroTitle)</h1><p class="command-copy">$(ConvertTo-HtmlText $bf871HeroCopy)</p><div class="manager-card-actions"><a class="command-button" href="$(ConvertTo-HtmlText $bf871HeroActionHref)">$(ConvertTo-HtmlText $bf871HeroActionLabel)</a><a class="command-button secondary" href="#decision-details">View decision details</a></div></div><div class="status $bf871HeroStatusClass">$(ConvertTo-HtmlText $bf871HeroStatus)</div></div><div class="command-meta"><span>$(ConvertTo-HtmlText $target)</span><span>$(ConvertTo-HtmlText $managerHeroCopy)</span><span>Read-only manager view</span></div></section>'
$dashboardBlock = Replace-ExactlyOnce -Text $dashboardBlock -Old $heroOld -New $heroNew -Contract 'decision-first manager hero'

$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

foreach ($required in @(
    'BF-871 is presentation-only',
    'Priority 01 &middot;',
    '$bf871HeroTitle',
    '$bf871HeroCopy',
    '$bf871HeroStatus',
    '$bf871HeroActionHref',
    '$bf871HeroActionLabel',
    'href="#decision-details">View decision details</a>',
    '<h2>Your decision queue</h2>',
    'id="decision-details"'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-871 BLOCKED: required decision-first marker is missing: $required"
    }
}

$installedDashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
$installedDashboard = $text.Substring($dashboardStart, $installedDashboardEnd - $dashboardStart)
if ($installedDashboard -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-871 BLOCKED: decision-first polish introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-871 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-871 Dashboard decision-first polish applied.'
