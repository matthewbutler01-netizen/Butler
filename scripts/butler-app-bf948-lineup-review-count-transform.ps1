param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-948 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) { throw "BF-948 BLOCKED: $Contract expected one match, found $matches." }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @('Review queue','Unresolved signals may overlap.','Back to review queue','lineup-evidence-section','Back to Matchup')) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-948 BLOCKED: required prior lineup-review capability is missing: $required"
    }
}

$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) { throw 'BF-948 BLOCKED: final Lineup Advisor renderer boundary is missing.' }
$function = $core.Substring($functionStart, $functionEnd - $functionStart)

$queueOld = @'
    $reviewQueueReturn = ''
    if ($queueItems.Length -gt 0) {
        $reviewQueueReturn = '<p><a href="#lineup-review-queue">Back to review queue</a></p>'
        $holdEvidenceHtml = "<section id=`"lineup-review-queue`" tabindex=`"-1`" class=`"swap-review-card review-queue`"><h3>Review queue</h3><p class=`"meta`">Unresolved signals may overlap. Projection totals do not settle these decisions.</p><ul>$queueItems</ul></section>$holdEvidenceHtml"
    }
'@

$queueNew = @'
    $reviewQueueReturn = ''
    if ($queueItems.Length -gt 0) {
        $reviewQueueCount = [regex]::Matches($queueItems, '<li>').Count
        $reviewQueueNoun = if ($reviewQueueCount -eq 1) { 'item' } else { 'items' }
        $reviewQueueBadge = "$reviewQueueCount $($reviewQueueNoun.ToUpperInvariant())"
        $decisionTitle = "Review $reviewQueueCount unresolved $reviewQueueNoun"
        $reviewQueueReturn = '<p><a href="#lineup-review-queue">Back to review queue</a></p>'
        $holdEvidenceHtml = "<section id=`"lineup-review-queue`" tabindex=`"-1`" class=`"swap-review-card review-queue`"><div class=`"lineup-review-queue-head`"><h3>Review queue</h3><span class=`"status warn`">$reviewQueueBadge</span></div><p class=`"meta`">Unresolved signals may overlap. Projection totals do not settle these decisions.</p><ul>$queueItems</ul></section>$holdEvidenceHtml"
    }
'@

$function = Replace-ExactlyOnce -Text $function -Old $queueOld.TrimEnd() -New $queueNew.TrimEnd() -Contract 'review queue count and decision-title summary'
$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

$cssMarker = '/* BF-947 compact lineup comparison evidence. */'
$cssReplacement = @'
/* BF-947 compact lineup comparison evidence. */
/* BF-948 unresolved review queue count. */
.review-queue .lineup-review-queue-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.review-queue .lineup-review-queue-head h3{margin:0}.review-queue .lineup-review-queue-head .status{flex:0 0 auto}
'@
$core = Replace-ExactlyOnce -Text $core -Old $cssMarker -New $cssReplacement.TrimEnd() -Contract 'BF-947 lineup CSS marker'

if ([regex]::Matches($core, [regex]::Escape('class=`"lineup-review-queue-head`"')).Count -ne 1) {
    throw 'BF-948 BLOCKED: review queue count header was not installed exactly once.'
}
if ($core.IndexOf('Review $reviewQueueCount unresolved $reviewQueueNoun', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-948 BLOCKED: decision title no longer follows the actual unresolved queue.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-948 BLOCKED: generated staged core failed PowerShell parse: $summary"
}
Write-Host 'BF-948 Lineup Review unresolved-item count applied.'
