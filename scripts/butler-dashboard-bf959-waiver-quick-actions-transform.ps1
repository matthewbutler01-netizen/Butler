param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-959 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-959 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$waiverStart = $text.IndexOf('function ConvertTo-WaiverHtml {', [System.StringComparison]::Ordinal)
$waiverEnd = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $waiverStart, [System.StringComparison]::Ordinal)
if ($waiverStart -lt 0 -or $waiverEnd -le $waiverStart) {
    throw 'BF-959 BLOCKED: Waiver Board renderer boundary is missing.'
}
$waiver = $text.Substring($waiverStart, $waiverEnd - $waiverStart)

foreach ($required in @(
    '$waiverHistoryLink',
    '$waiverPairDetailHtml',
    '<strong>Next step</strong>',
    '/waivers/candidate/',
    '/waivers/roster-compare?candidate='
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-959 BLOCKED: required Waiver Board capability is missing: $required"
    }
}

$returnStart = $waiver.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
if ($returnStart -lt 0) {
    throw 'BF-959 BLOCKED: Waiver Board HTML return is missing.'
}

$quickActionPrelude = @'
    $waiverQuickActions = ''
    if ($pair.Active -and [string]$pair.AddSleeperId -match '^[0-9]+$') {
        $waiverAddHrefId = [System.Uri]::EscapeDataString([string]$pair.AddSleeperId)
        $waiverQuickActions = '<div class="waiver-quick-actions"><a class="button waiver-quick-primary" href="/waivers/candidate/' + (ConvertTo-HtmlText $waiverAddHrefId) + '">Open governed ADD</a><a class="button" href="/waivers/roster-compare?candidate=' + (ConvertTo-HtmlText $waiverAddHrefId) + '">Compare ADD to roster</a></div>'
    }

'@
$waiver = $waiver.Insert($returnStart, $quickActionPrelude)

$nextOld = '<div class="waiver-next"><strong>Next step</strong><p>$(ConvertTo-HtmlText $waiverNextActionCopy)</p>$waiverHistoryLink</div>'
$nextNew = '<div class="waiver-next"><strong>Next step</strong><p>$(ConvertTo-HtmlText $waiverNextActionCopy)</p>$(if ([string]$current.State -ceq ''STALE_DO_NOT_ACT'') { ''<a class="waiver-history-link" href="/refresh">Check refresh options</a>'' })$waiverQuickActions$waiverHistoryLink</div>'
$waiver = Replace-ExactlyOnce -Text $waiver -Old $nextOld -New $nextNew -Contract 'Waiver decision quick actions'

$styleEnd = $waiver.LastIndexOf('</style>', [System.StringComparison]::Ordinal)
if ($styleEnd -lt 0) {
    throw 'BF-959 BLOCKED: Waiver Board style terminator is missing.'
}
$css = @'
/* BF-959 Waiver decision quick actions. */
.waiver-quick-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}.waiver-quick-actions .button{margin:0}.waiver-quick-primary{border-color:var(--turf);color:var(--ink)}@media(max-width:760px){.waiver-quick-actions .button{flex:1 1 100%;justify-content:center}}
'@
$waiver = $waiver.Insert($styleEnd, $css.TrimEnd() + [Environment]::NewLine)

$text = $text.Substring(0, $waiverStart) + $waiver + $text.Substring($waiverEnd)

foreach ($required in @(
    'BF-959 Waiver decision quick actions',
    '$waiverQuickActions',
    '$waiverAddHrefId = [System.Uri]::EscapeDataString([string]$pair.AddSleeperId)',
    'Open governed ADD',
    'Compare ADD to roster',
    'href="/waivers/candidate/',
    'href="/waivers/roster-compare?candidate=',
    '$waiverQuickActions$waiverHistoryLink'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-959 BLOCKED: required waiver quick-action marker is missing: $required"
    }
}

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($text, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-959 BLOCKED: generated staged Dashboard failed PowerShell parse: $summary"
}

$bf959Surface = $quickActionPrelude + $nextNew + $css
if ($bf959Surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-959 BLOCKED: Waiver quick actions introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-959 Waiver decision quick actions applied.'

$bf960Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf960-waiver-candidate-actions-transform.ps1'
if (-not (Test-Path -LiteralPath $bf960Transform -PathType Leaf)) {
    throw "BF-960 BLOCKED: Waiver Candidate Detail workflow transform not found at $bf960Transform"
}
& $bf960Transform -DashboardPath $DashboardPath
