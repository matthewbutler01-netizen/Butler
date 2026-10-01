param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-952 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) { throw "BF-952 BLOCKED: $Contract expected one match, found $matches." }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    'Replacement review for ',
    '$reviewQueueCount = [regex]::Matches($queueItems, ''<li>'').Count',
    'lineup-review-queue-head',
    'Unresolved signals may overlap.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-952 BLOCKED: required prior lineup queue capability is missing: $required"
    }
}

$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-952 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}
$function = $core.Substring($functionStart, $functionEnd - $functionStart)

$evidenceOld = @'
    if ($null -ne $AutoFill.PSObject.Properties['DecisionEvidence']) {
        foreach ($evidence in @($AutoFill.DecisionEvidence)) {
            if ([string]$evidence -cmatch '^Replacement review for ') { $queueItems += "<li>$(ConvertTo-HtmlText $evidence)</li>" }
        }
    }
'@

$evidenceNew = @'
    $queueContextItems = ''
    if ($null -ne $AutoFill.PSObject.Properties['DecisionEvidence']) {
        foreach ($evidence in @($AutoFill.DecisionEvidence)) {
            if ([string]$evidence -cmatch '^Replacement review for ') { $queueContextItems += "<li>$(ConvertTo-HtmlText $evidence)</li>" }
        }
    }
    $queueContextHtml = if ($queueContextItems.Length -gt 0) {
        "<details class=`"lineup-review-context`"><summary>Replacement search context</summary><p class=`"meta`">This explains replacement coverage; it does not add another manager decision.</p><ul>$queueContextItems</ul></details>"
    }
    else {
        ''
    }
'@

$queueOld = '$holdEvidenceHtml = "<section id=`"lineup-review-queue`" tabindex=`"-1`" class=`"swap-review-card review-queue`"><div class=`"lineup-review-queue-head`"><h3>Review queue</h3><span class=`"status warn`">$reviewQueueBadge</span></div><p class=`"meta`">Unresolved signals may overlap. Projection totals do not settle these decisions.</p><ul>$queueItems</ul></section>$holdEvidenceHtml"'
$queueNew = '$holdEvidenceHtml = "<section id=`"lineup-review-queue`" tabindex=`"-1`" class=`"swap-review-card review-queue`"><div class=`"lineup-review-queue-head`"><h3>Review queue</h3><span class=`"status warn`">$reviewQueueBadge</span></div><p class=`"meta`">Unresolved signals may overlap. Projection totals do not settle these decisions.</p><ul>$queueItems</ul>$queueContextHtml</section>$holdEvidenceHtml"'

$function = Replace-ExactlyOnce -Text $function -Old $evidenceOld.TrimEnd() -New $evidenceNew.TrimEnd() -Contract 'replacement-search context extraction'
$function = Replace-ExactlyOnce -Text $function -Old $queueOld -New $queueNew -Contract 'review queue context disclosure'
$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

if ($core.IndexOf('$queueItems += "<li>$(ConvertTo-HtmlText $evidence)</li>"', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'BF-952 BLOCKED: replacement search context still inflates unresolved queue items.'
}
if ($core.IndexOf('Replacement search context', [System.StringComparison]::Ordinal) -lt 0 -or
    $core.IndexOf('$queueContextHtml</section>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-952 BLOCKED: replacement search context disclosure was not installed.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-952 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-952 Lineup Review replacement context de-count applied.'

$bf953Transform = Join-Path $PSScriptRoot 'butler-app-bf953-lineup-hold-expert-merge-transform.ps1'
if (-not (Test-Path -LiteralPath $bf953Transform -PathType Leaf)) {
    throw "BF-953 BLOCKED: Lineup Review hold/expert merge transform not found at $bf953Transform"
}
& $bf953Transform -CorePath $CorePath
