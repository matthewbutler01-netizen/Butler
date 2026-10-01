param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-949 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) { throw "BF-949 BLOCKED: $Contract expected one match, found $matches." }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    'Review $reviewQueueCount unresolved $reviewQueueNoun',
    'lineup-review-queue-head',
    'review the projection proposal',
    'Review $(ConvertTo-HtmlText $assignment.Slot) comparisons:'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-949 BLOCKED: required prior lineup queue capability is missing: $required"
    }
}

$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-949 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}
$function = $core.Substring($functionStart, $functionEnd - $functionStart)

$old = @'
        if ($assignment.Changed) {
            $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: review the projection proposal $(ConvertTo-HtmlText $assignment.Current) &rarr; $(ConvertTo-HtmlText $assignment.Recommended). Check holds and the comparison evidence before making a move.</li>"
        }
        $comparisonLinks = ''
        for ($reviewIndex = 0; $reviewIndex -lt $structuredReviews.Count; $reviewIndex++) {
            $queueReview = $structuredReviews[$reviewIndex]
            if ([string]$queueReview.ordinal -ceq [string]$assignment.Ordinal) {
                $comparisonLinks += " <a href=`"#lineup-comparison-$reviewIndex`">$(ConvertTo-HtmlText $queueReview.current) &rarr; $(ConvertTo-HtmlText $queueReview.proposed)</a>"
            }
        }
        if ($comparisonLinks.Length -gt 0) { $queueItems += "<li>Review $(ConvertTo-HtmlText $assignment.Slot) comparisons:$comparisonLinks</li>" }
'@

$new = @'
        $comparisonLinks = ''
        for ($reviewIndex = 0; $reviewIndex -lt $structuredReviews.Count; $reviewIndex++) {
            $queueReview = $structuredReviews[$reviewIndex]
            if ([string]$queueReview.ordinal -ceq [string]$assignment.Ordinal) {
                $comparisonLinks += " <a href=`"#lineup-comparison-$reviewIndex`">$(ConvertTo-HtmlText $queueReview.current) &rarr; $(ConvertTo-HtmlText $queueReview.proposed)</a>"
            }
        }
        if ($assignment.Changed) {
            $comparisonTail = if ($comparisonLinks.Length -gt 0) { " Comparison evidence:$comparisonLinks" } else { '' }
            $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: review the projection proposal $(ConvertTo-HtmlText $assignment.Current) &rarr; $(ConvertTo-HtmlText $assignment.Recommended). Check holds and the comparison evidence before making a move.$comparisonTail</li>"
        }
        elseif ($comparisonLinks.Length -gt 0) {
            $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: review comparison evidence:$comparisonLinks</li>"
        }
'@

$function = Replace-ExactlyOnce -Text $function -Old $old.TrimEnd() -New $new.TrimEnd() -Contract 'projection/comparison queue de-duplication'
$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

if ($core.IndexOf('Review $(ConvertTo-HtmlText $assignment.Slot) comparisons:', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'BF-949 BLOCKED: duplicate comparison-only queue wording remains.'
}
if ($core.IndexOf('Comparison evidence:$comparisonLinks', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-949 BLOCKED: proposal task no longer carries its comparison evidence link.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-949 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-949 Lineup Review queue de-duplication applied.'

$bf950Transform = Join-Path $PSScriptRoot 'butler-app-bf950-lineup-expert-proposal-merge-transform.ps1'
if (-not (Test-Path -LiteralPath $bf950Transform -PathType Leaf)) {
    throw "BF-950 BLOCKED: Lineup Review expert/proposal merge transform not found at $bf950Transform"
}
& $bf950Transform -CorePath $CorePath
