param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-900 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-900 BLOCKED: $Contract expected one match, found $count."
    }

    return $Text.Replace($Old, $New)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

$queueOld = '    $managerQueueHtml = $managerCardList -join "`n"'
$queueNew = @'
    # BF-900: priority 01 already owns the hero, so the queue below shows only what comes after it.
    $managerQueueHtml = if ($managerCardList.Count -gt 1) {
        (@($managerCardList | Select-Object -Skip 1) -join "`n")
    }
    else {
        '<div class="manager-readonly">No additional priorities are waiting behind priority 01.</div>'
    }
'@

$text = Replace-ExactlyOnce -Text $text -Old $queueOld -New $queueNew.TrimEnd() -Contract 'manager queue de-duplication'

$copyOld = '<p>Act on what needs attention; leave completed and on-demand states alone.</p>'
$copyNew = '<p>Priority 01 is summarized above. These are the remaining decision areas in Butler''s current queue.</p>'
$text = Replace-ExactlyOnce -Text $text -Old $copyOld -New $copyNew -Contract 'manager queue supporting copy'

foreach ($required in @(
    'BF-900: priority 01 already owns the hero',
    'Select-Object -Skip 1',
    'No additional priorities are waiting behind priority 01.',
    'Priority 01 is summarized above. These are the remaining decision areas in Butler''s current queue.'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-900 BLOCKED: required dashboard queue marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-900 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}

$installed = [System.IO.File]::ReadAllText($DashboardPath)
if ($installed -match 'Method = "POST"|submitTransaction|setFaab|Invoke-RestMethod|Invoke-WebRequest') {
    throw 'BF-900 BLOCKED: dashboard queue polish introduced an operational/write marker.'
}

Write-Host 'BF-900 Dashboard priority de-duplication applied.'
