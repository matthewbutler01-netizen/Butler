param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-969 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

$old = '<div class="week-tools"><strong>Quick tools</strong><a class="week-tool" href="/trade">Trade Analyzer</a><a class="week-tool" href="/players">Player Search</a><a class="week-tool" href="/compare">Player Compare</a><a class="week-tool" href="/league">League</a></div>'
$new = '<div class="week-tools"><strong>Quick tools</strong><a class="week-tool" href="/trade">Trade Analyzer</a><a class="week-tool" href="/players">Player Search</a><a class="week-tool" href="/compare">Player Compare</a><a class="week-tool" href="/league">League</a><a class="week-tool" href="/history?load=1">Decision History</a></div>'

$count = [regex]::Matches($text, [regex]::Escape($old)).Count
if ($count -ne 1) {
    throw "BF-969 BLOCKED: Dashboard Quick tools row expected one match, found $count."
}

$text = $text.Replace($old, $new)

foreach ($required in @(
    'Week at a glance',
    'Quick tools',
    'href="/trade">Trade Analyzer</a>',
    'href="/players">Player Search</a>',
    'href="/compare">Player Compare</a>',
    'href="/league">League</a>',
    'href="/history?load=1">Decision History</a>'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-969 BLOCKED: required Dashboard shortcut marker is missing: $required"
    }
}

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($text, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-969 BLOCKED: generated staged Dashboard failed PowerShell parse: $summary"
}

$surface = $new
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-969 BLOCKED: Dashboard History shortcut introduced provider, backend-read, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-969 Dashboard Decision History shortcut applied.'
