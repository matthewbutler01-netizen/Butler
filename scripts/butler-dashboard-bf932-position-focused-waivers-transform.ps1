param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-932 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-932 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$waiverStart = $text.IndexOf('function ConvertTo-WaiverHtml {', [System.StringComparison]::Ordinal)
$waiverEnd = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $waiverStart, [System.StringComparison]::Ordinal)
if ($waiverStart -lt 0 -or $waiverEnd -le $waiverStart) {
    throw 'BF-932 BLOCKED: Waiver Board renderer boundary is missing.'
}
$waiverBlock = $text.Substring($waiverStart, $waiverEnd - $waiverStart)

$paramOld = @'
function ConvertTo-WaiverHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$Summary,
        [Parameter(Mandatory = $true)][string]$RosterContext
    )
'@
$paramNew = @'
function ConvertTo-WaiverHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$Summary,
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [string]$PositionFocus = ""
    )
'@
$waiverBlock = Replace-ExactlyOnce -Text $waiverBlock -Old $paramOld.TrimEnd() -New $paramNew.TrimEnd() -Contract 'Waiver Board position-focus parameter'

$countAnchor = @'
    if ($candidates.Count -ne $counts.Total) {
        throw "BF-646 BLOCKED: parsed BF-616 shortlist count $($candidates.Count) does not match BF-617 authorized total $($counts.Total)"
    }

'@
$focusPrelude = @'
    $normalizedPositionFocus = ([string]$PositionFocus).Trim().ToUpperInvariant()
    if (@("QB", "RB", "WR", "TE") -cnotcontains $normalizedPositionFocus) {
        $normalizedPositionFocus = ""
    }
    $displayCandidates = @(
        if ([string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
            $candidates
        }
        else {
            $candidates | Where-Object { [string]$_.Position -ceq $normalizedPositionFocus }
        }
    )

'@
$anchorCount = [regex]::Matches($waiverBlock, [regex]::Escape($countAnchor)).Count
if ($anchorCount -ne 1) {
    throw "BF-932 BLOCKED: Waiver candidate-count anchor expected one match, found $anchorCount."
}
$waiverBlock = $waiverBlock.Replace($countAnchor, $countAnchor + $focusPrelude)

$loopOld = '    foreach ($candidate in $candidates) {'
$loopNew = '    foreach ($candidate in $displayCandidates) {'
$waiverBlock = Replace-ExactlyOnce -Text $waiverBlock -Old $loopOld -New $loopNew -Contract 'focused candidate display loop'

$emptyOld = @'
    if ($candidates.Count -eq 0) {
        $cards = '<div class="subtle">BF-616 has no authorized shortlist entries in the current governed frame.</div>'
    }

'@
$emptyNew = @'
    if (@($displayCandidates).Count -eq 0) {
        if ([string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
            $cards = '<div class="subtle">BF-616 has no authorized shortlist entries in the current governed frame.</div>'
        }
        else {
            $cards = "<div class=`"subtle`">No authorized $(ConvertTo-HtmlText $normalizedPositionFocus) candidates are in Butler's current waiver review pool.</div>"
        }
    }

'@
$waiverBlock = Replace-ExactlyOnce -Text $waiverBlock -Old $emptyOld -New $emptyNew -Contract 'focused empty state'

$returnStart = $waiverBlock.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
if ($returnStart -lt 0) {
    throw 'BF-932 BLOCKED: final Waiver Board HTML return is missing.'
}

$focusHtmlPrelude = @'
    $waiverFocusCount = @($displayCandidates).Count
    $waiverFocusSummary = if ([string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        "Showing all $($counts.Total) authorized candidates. Source order remains unchanged."
    }
    else {
        "Position focus: $normalizedPositionFocus - showing $waiverFocusCount of $($counts.Total) authorized candidates. Source order remains unchanged."
    }
    $waiverFocusHtml = @"
<div class="waiver-focus">
  <div class="eyebrow">Position focus</div>
  <div class="actions"><a class="button" href="/waivers">All</a><a class="button" href="/waivers?position=QB">QB</a><a class="button" href="/waivers?position=RB">RB</a><a class="button" href="/waivers?position=WR">WR</a><a class="button" href="/waivers?position=TE">TE</a></div>
  <div class="subtle">$(ConvertTo-HtmlText $waiverFocusSummary)</div>
</div>
"@

'@
$waiverBlock = $waiverBlock.Insert($returnStart, $focusHtmlPrelude)

$gridOld = '  <div class="board-grid">$cards</div>'
$gridNew = '  $waiverFocusHtml<div class="board-grid">$cards</div>'
$waiverBlock = Replace-ExactlyOnce -Text $waiverBlock -Old $gridOld -New $gridNew -Contract 'Waiver position-focus controls'

$text = $text.Substring(0, $waiverStart) + $waiverBlock + $text.Substring($waiverEnd)

$helperMarker = 'function Send-HttpResponse {'
$helperIndex = $text.IndexOf($helperMarker, [System.StringComparison]::Ordinal)
if ($helperIndex -lt 0) {
    throw 'BF-932 BLOCKED: dashboard response helper marker is missing.'
}
$positionHelper = @'
function Get-WaiverPositionFocusFromRequestTarget {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $question = $RequestTarget.IndexOf('?')
    if ($question -lt 0 -or $question + 1 -ge $RequestTarget.Length) { return "" }

    $rawQuery = $RequestTarget.Substring($question + 1)
    foreach ($pair in ($rawQuery -split '&')) {
        if ([string]::IsNullOrWhiteSpace($pair)) { continue }
        $equals = $pair.IndexOf('=')
        if ($equals -lt 0) { continue }

        $key = [System.Uri]::UnescapeDataString($pair.Substring(0, $equals).Replace('+', ' '))
        if ($key -cne 'position') { continue }

        $value = [System.Uri]::UnescapeDataString($pair.Substring($equals + 1).Replace('+', ' ')).Trim().ToUpperInvariant()
        if (@("QB", "RB", "WR", "TE") -ccontains $value) { return $value }
        return ""
    }
    return ""
}

'@
$text = $text.Insert($helperIndex, $positionHelper)

$routeOld = '                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext'
$routeNew = '                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
$routeCount = [regex]::Matches($text, [regex]::Escape($routeOld)).Count
if ($routeCount -ne 1) {
    throw "BF-932 BLOCKED: staged /waivers renderer call expected one match, found $routeCount."
}
$text = $text.Replace($routeOld, $routeNew)

foreach ($required in @(
    'function Get-WaiverPositionFocusFromRequestTarget',
    '[string]$PositionFocus = ""',
    '$displayCandidates',
    'Position focus',
    'href="/waivers?position=QB">QB</a>',
    'href="/waivers?position=RB">RB</a>',
    'href="/waivers?position=WR">WR</a>',
    'href="/waivers?position=TE">TE</a>',
    'Source order remains unchanged.',
    '-PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-932 BLOCKED: required focused-waiver marker is missing: $required"
    }
}

$installedWaiverStart = $text.IndexOf('function ConvertTo-WaiverHtml {', [System.StringComparison]::Ordinal)
$installedWaiverEnd = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $installedWaiverStart, [System.StringComparison]::Ordinal)
$installedWaiver = $text.Substring($installedWaiverStart, $installedWaiverEnd - $installedWaiverStart)
if ($installedWaiver -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-932 BLOCKED: focused Waiver Board introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-932 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-932 position-focused Waiver Board applied.'
