param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-960 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-960 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$candidateStart = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', [System.StringComparison]::Ordinal)
$candidateEnd = $text.IndexOf('function Send-HttpResponse {', $candidateStart, [System.StringComparison]::Ordinal)
if ($candidateStart -lt 0 -or $candidateEnd -le $candidateStart) {
    throw 'BF-960 BLOCKED: Waiver Candidate Detail renderer boundary is missing.'
}
$candidate = $text.Substring($candidateStart, $candidateEnd - $candidateStart)

foreach ($required in @(
    'Back to Waiver Board',
    '/waivers/compare?left=',
    '/waivers/roster-compare?candidate=',
    '$Candidate.SleeperId',
    'READ ONLY &middot; EXACT ID ONLY.'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-960 BLOCKED: required candidate workflow capability is missing: $required"
    }
}

$returnStart = $candidate.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
if ($returnStart -lt 0) {
    throw 'BF-960 BLOCKED: Waiver Candidate Detail HTML return is missing.'
}

$workflowPrelude = @'
    $candidateWorkflowActions = '<div class="actions candidate-workflow-actions" style="margin-top:18px"><a class="button" href="/waivers">Back to Waiver Board</a></div>'
    if ([string]$Candidate.SleeperId -match '^[0-9]+$') {
        $candidateWorkflowHrefId = [System.Uri]::EscapeDataString([string]$Candidate.SleeperId)
        $candidateWorkflowActions = '<div class="actions candidate-workflow-actions" style="margin-top:18px"><a class="button" href="/waivers/compare?left=' + (ConvertTo-HtmlText $candidateWorkflowHrefId) + '">Compare candidate</a><a class="button" href="/waivers/roster-compare?candidate=' + (ConvertTo-HtmlText $candidateWorkflowHrefId) + '">Compare to roster</a><a class="button" href="/waivers">Back to Waiver Board</a></div>'
    }

'@
$candidate = $candidate.Insert($returnStart, $workflowPrelude)

$actionsOld = '<div class="actions" style="margin-top:18px"><a class="button" href="/waivers">Back to Waiver Board</a></div>'
$actionsNew = '$candidateWorkflowActions'
$candidate = Replace-ExactlyOnce -Text $candidate -Old $actionsOld -New $actionsNew -Contract 'candidate workflow actions'

$text = $text.Substring(0, $candidateStart) + $candidate + $text.Substring($candidateEnd)

foreach ($required in @(
    '$candidateWorkflowActions',
    '$candidateWorkflowHrefId = [System.Uri]::EscapeDataString([string]$Candidate.SleeperId)',
    'candidate-workflow-actions',
    'href="/waivers/compare?left=',
    '">Compare candidate</a>',
    'href="/waivers/roster-compare?candidate=',
    '">Compare to roster</a>',
    '">Back to Waiver Board</a>'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-960 BLOCKED: required candidate workflow marker is missing: $required"
    }
}

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($text, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-960 BLOCKED: generated staged Dashboard failed PowerShell parse: $summary"
}

$bf960Surface = $workflowPrelude + $actionsNew
if ($bf960Surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-960 BLOCKED: candidate workflow actions introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-960 Waiver Candidate Detail workflow actions applied.'
