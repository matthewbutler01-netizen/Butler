param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-941 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-941 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$autoFillStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$teamStart = $core.IndexOf('function ConvertTo-TeamHtml {', $autoFillStart, [System.StringComparison]::Ordinal)
if ($autoFillStart -lt 0 -or $teamStart -le $autoFillStart) {
    throw 'BF-941 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}

$helper = @'
function Get-LineupSwapCompareHref {
    param(
        [Parameter(Mandatory = $true)]$Assignment,
        $Roster = $null
    )

    if (-not $Assignment.Changed -or $null -eq $Roster) { return '' }
    if ($null -eq $Roster.PSObject.Properties['Players']) { return '' }

    $currentMatches = @($Roster.Players | Where-Object {
        [string]$_.SleeperId -ceq [string]$Assignment.CurrentId
    })
    $recommendedMatches = @($Roster.Players | Where-Object {
        [string]$_.SleeperId -ceq [string]$Assignment.RecommendedId
    })
    if ($currentMatches.Count -ne 1 -or $recommendedMatches.Count -ne 1) { return '' }

    $left = [string]$currentMatches[0].ButlerPlayerId
    $right = [string]$recommendedMatches[0].ButlerPlayerId
    foreach ($playerId in @($left,$right)) {
        if ([string]::IsNullOrWhiteSpace($playerId) -or
            $playerId -ceq 'none' -or
            $playerId.Length -gt 128 -or
            $playerId -notmatch '^[A-Za-z0-9._:-]+$') {
            return ''
        }
    }
    if ($left -ceq $right) { return '' }

    $leftHref = [System.Uri]::EscapeDataString($left)
    $rightHref = [System.Uri]::EscapeDataString($right)
    return "/compare?left=$leftHref&right=$rightHref"
}

'@
$core = $core.Insert($autoFillStart, $helper)

$signatureOld = @'
function ConvertTo-AutoFillHtml {
    param([Parameter(Mandatory = $true)]$AutoFill)
'@
$signatureNew = @'
function ConvertTo-AutoFillHtml {
    param(
        [Parameter(Mandatory = $true)]$AutoFill,
        $Roster = $null
    )
'@
$core = Replace-ExactlyOnce -Text $core -Old $signatureOld.TrimEnd() -New $signatureNew.TrimEnd() -Contract 'Lineup Advisor roster-aware signature'

$rowOld = @'
        $rows += "<div class=`"$rowClass`"><div><span class=`"position-chip`">$(ConvertTo-HtmlText $assignment.Slot)</span></div><div class=`"lineup-choice current`"><small>Current</small><strong>$(ConvertTo-HtmlText $assignment.Current)</strong></div><div class=`"lineup-arrow`">&rarr;</div><div class=`"lineup-choice recommended`"><small>Recommended</small><strong>$(ConvertTo-HtmlText $assignment.Recommended)</strong></div><div class=`"projection`">$(ConvertTo-HtmlText $assignment.Points)<span>proj pts</span></div><span class=`"$decisionClass`">$decision</span></div>"
'@
$rowNew = @'
        $swapCompareHref = Get-LineupSwapCompareHref -Assignment $assignment -Roster $Roster
        $swapCompareAction = if ([string]::IsNullOrWhiteSpace($swapCompareHref)) {
            ''
        } else {
            "<a class=`"btn btn-secondary lineup-swap-compare`" href=`"$(ConvertTo-HtmlText $swapCompareHref)`">Compare this swap</a>"
        }
        $rows += "<div class=`"$rowClass`"><div><span class=`"position-chip`">$(ConvertTo-HtmlText $assignment.Slot)</span></div><div class=`"lineup-choice current`"><small>Current</small><strong>$(ConvertTo-HtmlText $assignment.Current)</strong></div><div class=`"lineup-arrow`">&rarr;</div><div class=`"lineup-choice recommended`"><small>Recommended</small><strong>$(ConvertTo-HtmlText $assignment.Recommended)</strong></div><div class=`"projection`">$(ConvertTo-HtmlText $assignment.Points)<span>proj pts</span></div><span class=`"$decisionClass`">$decision</span><div class=`"lineup-swap-action`">$swapCompareAction</div></div>"
'@
$core = Replace-ExactlyOnce -Text $core -Old $rowOld.TrimEnd() -New $rowNew.TrimEnd() -Contract 'Lineup Advisor changed-slot compare action'

$teamCallOld = '$autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill'
$teamBlockStart = $core.IndexOf('function ConvertTo-TeamHtml {', [System.StringComparison]::Ordinal)
$teamBlockEnd = $core.IndexOf('function Add-LeagueNavigation {', $teamBlockStart, [System.StringComparison]::Ordinal)
if ($teamBlockStart -lt 0 -or $teamBlockEnd -le $teamBlockStart) {
    throw 'BF-941 BLOCKED: final My Team renderer boundary is missing.'
}
$teamBlock = $core.Substring($teamBlockStart, $teamBlockEnd - $teamBlockStart)
$teamCallCount = [regex]::Matches($teamBlock, [regex]::Escape($teamCallOld)).Count
if ($teamCallCount -ne 1) {
    throw "BF-941 BLOCKED: final My Team AutoFill composition expected one match, found $teamCallCount."
}
$teamBlock = $teamBlock.Replace(
    $teamCallOld,
    '$autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill -Roster $Roster'
)
$core = $core.Substring(0, $teamBlockStart) + $teamBlock + $core.Substring($teamBlockEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-941 BLOCKED: final manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-941 BLOCKED: final manager CSS terminator is missing.'
}
$css = @'
/* BF-941 exact AutoFill swap -> Player Compare loop. */
.lineup-swap-action{display:flex;justify-content:flex-end}.lineup-swap-compare{white-space:nowrap}@media(max-width:900px){.lineup-swap-action{justify-content:flex-start;grid-column:1/-1}}
'@
$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $css.TrimEnd() + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'function Get-LineupSwapCompareHref',
    'Compare this swap',
    'ConvertTo-AutoFillHtml -AutoFill $AutoFill -Roster $Roster',
    'ButlerPlayerId',
    '^[A-Za-z0-9._:-]+$',
    '/compare?left=$leftHref&right=$rightHref',
    'lineup-swap-action'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-941 BLOCKED: required lineup swap compare marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Get-LineupSwapCompareHref', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $installedStart, [System.StringComparison]::Ordinal)
if ($installedStart -lt 0 -or $installedEnd -le $installedStart) {
    throw 'BF-941 BLOCKED: installed lineup swap compare boundary is missing.'
}
$installed = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'https://api.sleeper.app',
    'Method = "POST"',
    'submitTransaction',
    'setFaab',
    'AutoFillLineupOptimizer'
)) {
    if ($installed.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-941 BLOCKED: lineup swap compare introduced forbidden behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-941 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-941 Lineup Swap Compare applied.'
