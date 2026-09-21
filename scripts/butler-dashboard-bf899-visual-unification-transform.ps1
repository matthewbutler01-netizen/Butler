param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-899 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$marker = 'BF-899 dashboard visual unification'

if (-not $text.Contains($marker)) {
    $dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
    $dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
    if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
        throw 'BF-899 BLOCKED: Dashboard renderer function boundary is missing.'
    }

    $dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)
    $cssStart = $dashboardBlock.IndexOf('$bf819Css = @"', [System.StringComparison]::Ordinal)
    if ($cssStart -lt 0) {
        throw 'BF-899 BLOCKED: BF-819 manager CSS block is missing.'
    }

    $cssEnd = $dashboardBlock.IndexOf('"@', $cssStart + '$bf819Css = @"'.Length, [System.StringComparison]::Ordinal)
    if ($cssEnd -lt 0) {
        throw 'BF-899 BLOCKED: BF-819 manager CSS terminator is missing.'
    }

    $override = @'
/* BF-899 dashboard visual unification: retire legacy blue manager chrome in favor of the accepted turf/neutral system. */
.manager-hero{background:var(--surface)!important;border-color:var(--line)!important}
.manager-hero .command-copy{color:var(--muted)!important}
.manager-hero .command-meta span{border-color:var(--line)!important;background:var(--surface-2)!important;color:var(--muted)!important}
.manager-queue-head p{color:var(--muted)!important}
.manager-decision-card{border-color:var(--line)!important;background:var(--surface-2)!important}
.manager-decision-card.primary{border-color:color-mix(in srgb,var(--turf) 60%,var(--line))!important;background:var(--surface-2)!important;box-shadow:inset 3px 0 0 var(--turf)}
.manager-priority-index{border:1px solid color-mix(in srgb,var(--turf) 35%,var(--line));background:color-mix(in srgb,var(--turf) 14%,var(--surface))!important;color:var(--turf-deep)!important}
.manager-kind{color:var(--turf)!important}
.manager-decision-main p{color:var(--muted)!important}
.manager-chip{border-color:var(--line)!important;background:var(--surface)!important;color:var(--muted)!important}
.manager-chip.ok{border-color:color-mix(in srgb,var(--turf) 45%,var(--line))!important;background:color-mix(in srgb,var(--turf) 10%,var(--surface))!important;color:var(--turf-deep)!important}
.manager-chip.warn{border-color:color-mix(in srgb,var(--gold) 48%,var(--line))!important;background:color-mix(in srgb,var(--gold) 10%,var(--surface))!important;color:var(--gold)!important}
.proof-mode{border-color:var(--line)!important;background:var(--surface-2)!important}
.proof-mode>summary{color:var(--ink)!important}
.proof-mode>summary:before{background:color-mix(in srgb,var(--turf) 16%,var(--surface))!important;color:var(--turf-deep)!important}
.proof-body{border-color:var(--line)!important}
.proof-card,.proof-evidence{border-color:var(--line)!important;background:var(--surface)!important}
.proof-card p{color:var(--muted)!important}
.manager-readonly{border-color:var(--line)!important;background:var(--surface-2)!important;color:var(--muted)!important}
'@

    $newline = [Environment]::NewLine
    $dashboardBlock = $dashboardBlock.Insert($cssEnd, $newline + $override.TrimEnd() + $newline)
    $text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)
}

foreach ($required in @(
    'BF-899 dashboard visual unification',
    '.manager-hero{background:var(--surface)!important',
    '.manager-decision-card.primary{border-color:color-mix',
    '.manager-chip.ok{border-color:color-mix',
    '.manager-chip.warn{border-color:color-mix',
    '.proof-mode{border-color:var(--line)!important'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-899 BLOCKED: required dashboard visual marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-899 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}

$installed = [System.IO.File]::ReadAllText($DashboardPath)
if ($installed -match 'Method = "POST"|submitTransaction|setFaab|Invoke-RestMethod|Invoke-WebRequest') {
    throw 'BF-899 BLOCKED: dashboard visual unification introduced an operational/write marker.'
}

Write-Host 'BF-899 dashboard visual unification applied.'
