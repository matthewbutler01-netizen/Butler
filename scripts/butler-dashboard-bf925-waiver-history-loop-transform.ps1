param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-925 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) {
        throw "BF-925 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

$historyOld = @'
    $waiverHistoryStates = @(
        "CURRENT_AND_ACTIONABLE",
        "CURRENT_REFRESH_RECOMMENDED",
        "TRANSACTION_ALREADY_COMPLETE",
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE",
        "STALE_DO_NOT_ACT",
        "NO_TRANSACTION_TO_ACT_ON"
    )
    $waiverHistoryLink = if ($waiverHistoryStates -ccontains [string]$current.State) {
        '<a class="waiver-history-link" href="/history">Decision History</a>'
    }
    else {
        ''
    }
'@

$historyNew = @'
    $waiverHistoryLink = '<a class="waiver-history-link" href="/history?load=1">View Decision History</a>'
'@

$text = Replace-ExactlyOnce -Text $text -Old $historyOld.TrimEnd() -New $historyNew.TrimEnd() -Contract 'stable Waiver Board history action'

foreach ($required in @(
    '$waiverHistoryLink = ''<a class="waiver-history-link" href="/history?load=1">View Decision History</a>''',
    'Next step',
    'Decision details',
    'NOT A RANKING.'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-925 BLOCKED: required waiver-history marker is missing: $required"
    }
}

$waiverStart = $text.IndexOf('function ConvertTo-WaiverHtml {', [System.StringComparison]::Ordinal)
$waiverEnd = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $waiverStart, [System.StringComparison]::Ordinal)
if ($waiverStart -lt 0 -or $waiverEnd -le $waiverStart) {
    throw 'BF-925 BLOCKED: Waiver Board renderer boundary is missing.'
}
$waiverBlock = $text.Substring($waiverStart, $waiverEnd - $waiverStart)
if ($waiverBlock -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-925 BLOCKED: Waiver history loop introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-925 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-925 Waiver Board history loop applied.'
