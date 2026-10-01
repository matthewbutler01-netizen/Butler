param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-951 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) { throw "BF-951 BLOCKED: $Contract expected one match, found $matches." }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    '$expertConflictTail = ''''',
    'current starter with an attributed SIT selection',
    'review comparison evidence:$comparisonLinks',
    '$expertConflictTail$comparisonTail'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-951 BLOCKED: required prior lineup queue capability is missing: $required"
    }
}

$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-951 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}
$function = $core.Substring($functionStart, $functionEnd - $functionStart)

$expertOld = @'
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

$expertNew = @'
        $matchingPicks = @($queuePicks | Where-Object { [string]$_.playerId -ceq $starterId })
        $expertConflictTail = ''
        $expertStandaloneTask = ''
        if ($matchingPicks.Count -eq 1 -and [string]$matchingPicks[0].selection -ceq 'SIT') {
            $pickIndex = [array]::IndexOf($queuePicks, $matchingPicks[0])
            if ($assignment.Changed) {
                $expertConflictTail = " Expert signal: attributed SIT selection from $(ConvertTo-HtmlText $matchingPicks[0].author); this opinion does not establish consensus. <a href=`"#lineup-expert-$pickIndex`">Review expert source</a>"
            }
            else {
                $expertStandaloneTask = "<li><strong>$(ConvertTo-HtmlText $assignment.Current)</strong>: current starter with an attributed SIT selection from $(ConvertTo-HtmlText $matchingPicks[0].author). Review scoring, roster fit and the source below; this opinion does not establish consensus. <a href=`"#lineup-expert-$pickIndex`">Review expert source</a>"
            }
        }
'@

$branchOld = @'
        elseif ($comparisonLinks.Length -gt 0) {
            $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: review comparison evidence:$comparisonLinks</li>"
        }
'@

$branchNew = @'
        elseif ($expertStandaloneTask.Length -gt 0 -and $comparisonLinks.Length -gt 0) {
            $queueItems += "$expertStandaloneTask Comparison evidence:$comparisonLinks</li>"
        }
        elseif ($expertStandaloneTask.Length -gt 0) {
            $queueItems += "$expertStandaloneTask</li>"
        }
        elseif ($comparisonLinks.Length -gt 0) {
            $queueItems += "<li><strong>$(ConvertTo-HtmlText $assignment.Slot)</strong>: review comparison evidence:$comparisonLinks</li>"
        }
'@

$function = Replace-ExactlyOnce -Text $function -Old $expertOld.TrimEnd() -New $expertNew.TrimEnd() -Contract 'standalone expert task staging'
$function = Replace-ExactlyOnce -Text $function -Old $branchOld.TrimEnd() -New $branchNew.TrimEnd() -Contract 'expert/comparison unchanged-task merge'
$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

if ($core.IndexOf('$expertStandaloneTask Comparison evidence:$comparisonLinks</li>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-951 BLOCKED: unchanged expert conflict does not absorb its comparison evidence.'
}
if ($core.IndexOf('$expertStandaloneTask</li>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-951 BLOCKED: standalone expert conflict fallback was lost.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-951 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-951 Lineup Review expert/comparison merge applied.'
