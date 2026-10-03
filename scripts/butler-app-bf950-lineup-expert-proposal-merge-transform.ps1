param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-950 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) { throw "BF-950 BLOCKED: $Contract expected one match, found $matches." }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    'current starter with an attributed SIT selection',
    'Review expert source',
    'Comparison evidence:$comparisonLinks',
    'Review $reviewQueueCount unresolved $reviewQueueNoun'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-950 BLOCKED: required prior lineup queue capability is missing: $required"
    }
}

$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-950 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}
$function = $core.Substring($functionStart, $functionEnd - $functionStart)

$expertOld = @'
        $matchingPicks = @($queuePicks | Where-Object { [string]$_.playerId -ceq $starterId })
        if ($matchingPicks.Count -eq 1 -and [string]$matchingPicks[0].selection -ceq 'SIT') {
            $pickIndex = [array]::IndexOf($queuePicks, $matchingPicks[0])
            $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Current)</strong>: current starter with an attributed SIT selection from $(ConvertTo-HtmlText $matchingPicks[0].author). Review scoring, roster fit and the source below; this opinion does not establish consensus. <a href=`"#lineup-expert-$pickIndex`">Review expert source</a></li>"
        }
'@

$expertNew = @'
        $matchingPicks = @($queuePicks | Where-Object { [string]$_.playerId -ceq $starterId })
        $expertConflictTail = ''
        if ($matchingPicks.Count -eq 1 -and [string]$matchingPicks[0].selection -ceq 'SIT') {
            $pickIndex = [array]::IndexOf($queuePicks, $matchingPicks[0])
            if ($assignment.Changed) {
                $expertConflictTail = " Expert signal: attributed SIT selection from $(ConvertTo-HtmlText $matchingPicks[0].author); this opinion does not establish consensus. <a href=`"#lineup-expert-$pickIndex`">Review expert source</a>"
            }
            else {
                $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Current)</strong>: current starter with an attributed SIT selection from $(ConvertTo-HtmlText $matchingPicks[0].author). Review scoring, roster fit and the source below; this opinion does not establish consensus. <a href=`"#lineup-expert-$pickIndex`">Review expert source</a></li>"
            }
        }
'@

$proposalOld = '$queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: optimizer slot placement $(ConvertTo-HtmlText $assignment.Current) &rarr; $(ConvertTo-HtmlText $assignment.Recommended). This may be part of the same manager move; check the promotion/bench summary and comparison evidence.$comparisonTail</li>"'
$proposalNew = '$queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: optimizer slot placement $(ConvertTo-HtmlText $assignment.Current) &rarr; $(ConvertTo-HtmlText $assignment.Recommended). This may be part of the same manager move; check the promotion/bench summary and comparison evidence.$expertConflictTail$comparisonTail</li>"'

$function = Replace-ExactlyOnce -Text $function -Old $expertOld.TrimEnd() -New $expertNew.TrimEnd() -Contract 'changed-starter expert conflict merge'
$function = Replace-ExactlyOnce -Text $function -Old $proposalOld -New $proposalNew -Contract 'proposal expert evidence attachment'
$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

if ($core.IndexOf('$expertConflictTail$comparisonTail', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-950 BLOCKED: projection task does not carry both expert and comparison evidence.'
}
if ($core.IndexOf('$expertConflictTail = " Expert signal:', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-950 BLOCKED: changed starter expert signal is not merged into its proposal.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-950 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-950 Lineup Review expert/proposal merge applied.'

$bf951Transform = Join-Path $PSScriptRoot 'butler-app-bf951-lineup-expert-comparison-merge-transform.ps1'
if (-not (Test-Path -LiteralPath $bf951Transform -PathType Leaf)) {
    throw "BF-951 BLOCKED: Lineup Review expert/comparison merge transform not found at $bf951Transform"
}
& $bf951Transform -CorePath $CorePath
