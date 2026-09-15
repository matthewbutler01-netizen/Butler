param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-800 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $first = $Text.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) {
        throw "BF-800 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-800 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$teamFunctionAnchor = 'function ConvertTo-TeamHtml {'
$autoFillFunctions = @'
function ConvertTo-AutoFillView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $state = [regex]::Match($Text, '(?m)^State:\s+(?<value>READY|UNAVAILABLE)\s*$')
    $frame = [regex]::Match($Text, '(?m)^Season/week:\s+(?<season>\d+)/(?<week>\S+)\s*$')
    $scoring = [regex]::Match($Text, '(?m)^Scoring basis:\s+(?<value>\S+)\s*$')
    if (-not $state.Success -or -not $frame.Success -or -not $scoring.Success) {
        throw 'BF-800 BLOCKED: AutoFill bundle section is missing required state/frame/scoring fields.'
    }

    if ($state.Groups['value'].Value -ceq 'UNAVAILABLE') {
        $reason = [regex]::Match($Text, '(?m)^Reason:\s+(?<value>.+?)\s*$')
        if (-not $reason.Success) {
            throw 'BF-800 BLOCKED: unavailable AutoFill bundle section is missing its reason.'
        }
        return [pscustomobject]@{
            Ready = $false
            Season = $frame.Groups['season'].Value
            Week = $frame.Groups['week'].Value
            Scoring = $scoring.Groups['value'].Value
            Reason = $reason.Groups['value'].Value.Trim()
            Source = ''
            SourceSurface = ''
            CurrentTotal = ''
            RecommendedTotal = ''
            Gain = ''
            Assignments = @()
            BenchMoves = @()
            Promotions = @()
        }
    }

    $source = [regex]::Match($Text, '(?m)^Projection source:\s+(?<value>.+?)\s*$')
    $sourceSurface = [regex]::Match($Text, '(?m)^Projection source surface:\s+(?<value>.+?)\s*$')
    $current = [regex]::Match($Text, '(?m)^Current projected starter total:\s+(?<value>-?\d+(?:\.\d+)?)\s*$')
    $recommended = [regex]::Match($Text, '(?m)^Recommended projected starter total:\s+(?<value>-?\d+(?:\.\d+)?)\s*$')
    $gain = [regex]::Match($Text, '(?m)^Projected gain:\s+(?<value>[+-]?\d+(?:\.\d+)?)\s*$')
    if (-not $source.Success -or -not $sourceSurface.Success -or -not $current.Success -or
        -not $recommended.Success -or -not $gain.Success) {
        throw 'BF-800 BLOCKED: ready AutoFill bundle section is missing projection summary fields.'
    }

    $assignments = @()
    $benchMoves = @()
    $promotions = @()
    $mode = ''
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -ceq 'Moves to bench:') { $mode = 'BENCH'; continue }
        if ($line -ceq 'Promotions to starting lineup:') { $mode = 'PROMOTE'; continue }
        if ($line -match '^\s{2}#(?<ordinal>\d+)\s+(?<slot>\S+)\s+\|\s+current=(?<current>.*?)\s+\[(?<currentId>[^\]]+)\]\s+\|\s+recommended=(?<recommended>.*?)\s+\[(?<recommendedId>[^\]]+)\]\s+\|\s+projected=(?<points>-?\d+(?:\.\d+)?)\s+\|\s+action=(?<action>KEEP|CHANGE)\s*$') {
            $assignments += [pscustomobject]@{
                Ordinal = [int]$Matches['ordinal']
                Slot = $Matches['slot']
                Current = $Matches['current'].Trim()
                CurrentId = $Matches['currentId'].Trim()
                Recommended = $Matches['recommended'].Trim()
                RecommendedId = $Matches['recommendedId'].Trim()
                Points = $Matches['points']
                Changed = $Matches['action'] -ceq 'CHANGE'
            }
            $mode = ''
            continue
        }
        if (($mode -ceq 'BENCH' -or $mode -ceq 'PROMOTE') -and $line -match '^\s{2}(?<name>.+?)\s+\[(?<id>[^\]]+)\]\s*$') {
            if ($Matches['name'] -ceq 'none') { continue }
            $entry = [pscustomobject]@{ Name = $Matches['name'].Trim(); Id = $Matches['id'].Trim() }
            if ($mode -ceq 'BENCH') { $benchMoves += $entry } else { $promotions += $entry }
        }
    }
    if ($assignments.Count -eq 0) {
        throw 'BF-800 BLOCKED: ready AutoFill bundle section contains no recommended lineup rows.'
    }

    return [pscustomobject]@{
        Ready = $true
        Season = $frame.Groups['season'].Value
        Week = $frame.Groups['week'].Value
        Scoring = $scoring.Groups['value'].Value
        Reason = ''
        Source = $source.Groups['value'].Value.Trim()
        SourceSurface = $sourceSurface.Groups['value'].Value.Trim()
        CurrentTotal = $current.Groups['value'].Value
        RecommendedTotal = $recommended.Groups['value'].Value
        Gain = $gain.Groups['value'].Value
        Assignments = @($assignments | Sort-Object Ordinal)
        BenchMoves = @($benchMoves)
        Promotions = @($promotions)
    }
}

function ConvertTo-AutoFillHtml {
    param([Parameter(Mandatory = $true)]$AutoFill)

    $frame = "Week $(ConvertTo-HtmlText $AutoFill.Week) &middot; $(ConvertTo-HtmlText $AutoFill.Scoring) projection basis"
    if (-not $AutoFill.Ready) {
        return "<section class=`"panel`"><div class=`"eyebrow`">Weekly lineup</div><h2>AutoFill Roster</h2><p class=`"lede`">Preview the strongest legal lineup Butler can support from your active roster.</p><details><summary>AutoFill Roster</summary><div class=`"empty`">$(ConvertTo-HtmlText $AutoFill.Reason)</div><div class=`"meta`">$frame</div><p class=`"meta`">Preview only. Butler did not submit a lineup to Sleeper.</p></details></section>"
    }

    $cards = ''
    foreach ($assignment in $AutoFill.Assignments) {
        $change = if ($assignment.Changed) {
            "Current: $(ConvertTo-HtmlText $assignment.Current) &rarr; recommended: $(ConvertTo-HtmlText $assignment.Recommended)"
        } else {
            "Keep $(ConvertTo-HtmlText $assignment.Recommended)"
        }
        $cards += "<article class=`"card`"><div class=`"rank`">$(ConvertTo-HtmlText $assignment.Slot)</div><div class=`"pressure-tier`">$(ConvertTo-HtmlText $assignment.Recommended)</div><div class=`"meta`">Projected $(ConvertTo-HtmlText $assignment.Points) pts</div><div class=`"meta`">$change</div></article>"
    }

    $bench = if ($AutoFill.BenchMoves.Count -eq 0) {
        'None'
    } else {
        (@($AutoFill.BenchMoves | ForEach-Object { $_.Name }) -join ', ')
    }
    $promotions = if ($AutoFill.Promotions.Count -eq 0) {
        'None'
    } else {
        (@($AutoFill.Promotions | ForEach-Object { $_.Name }) -join ', ')
    }

    return "<section class=`"panel`"><div class=`"eyebrow`">Weekly lineup</div><h2>AutoFill Roster</h2><p class=`"lede`">Preview the highest-projected legal lineup from your active Sleeper roster using FantasyPros weekly consensus projections.</p><details><summary>AutoFill Roster</summary><div class=`"stats`"><div class=`"stat`"><strong>Current projection</strong><span>$(ConvertTo-HtmlText $AutoFill.CurrentTotal)</span></div><div class=`"stat`"><strong>AutoFill projection</strong><span>$(ConvertTo-HtmlText $AutoFill.RecommendedTotal)</span></div><div class=`"stat`"><strong>Projected change</strong><span>$(ConvertTo-HtmlText $AutoFill.Gain)</span></div></div><div class=`"grid`">$cards</div><p class=`"meta`"><strong>Moves to bench:</strong> $(ConvertTo-HtmlText $bench)</p><p class=`"meta`"><strong>Promotions:</strong> $(ConvertTo-HtmlText $promotions)</p><p class=`"meta`">$frame &middot; Projection data: $(ConvertTo-HtmlText $AutoFill.Source)</p><details><summary>Projection details</summary><div class=`"technical`">$(ConvertTo-HtmlText $AutoFill.SourceSurface)</div></details><p class=`"meta`"><strong>READ ONLY.</strong> This is a preview. Butler did not submit a lineup to Sleeper.</p></details></section>"
}

function ConvertTo-TeamHtml {
'@
$core = Replace-ExactlyOnce -Text $core -Old $teamFunctionAnchor -New $autoFillFunctions -Contract 'My Team renderer function'

$signatureOld = @'
        [Parameter(Mandatory = $true)]$Posture,
        [Parameter(Mandatory = $true)]$Capital
    )
'@
$signatureNew = @'
        [Parameter(Mandatory = $true)]$Posture,
        [Parameter(Mandatory = $true)]$Capital,
        [Parameter(Mandatory = $true)]$AutoFill
    )
'@
$core = Replace-ExactlyOnce -Text $core -Old $signatureOld -New $signatureNew -Contract 'My Team renderer parameters'

$cssAnchor = @'
    $css = Get-AppCss
    $nav = Get-AppNav -Active "team"
'@
$cssReplacement = @'
    $autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill
    $css = Get-AppCss
    $nav = Get-AppNav -Active "team"
'@
$core = Replace-ExactlyOnce -Text $core -Old $cssAnchor -New $cssReplacement -Contract 'My Team AutoFill HTML composition'

$rosterPanelAnchor = @'
<section class="panel"><div class="eyebrow">Current roster</div><h2>Players</h2><p class="lede">Exact BF-623-bound live roster context from BF-610. Players are grouped for readability only, not ranked.</p>$rosterHtml</section>
'@
$rosterPanelReplacement = @'
$autoFillHtml
<section class="panel"><div class="eyebrow">Current roster</div><h2>Players</h2><p class="lede">Exact BF-623-bound live roster context from BF-610. Players are grouped for readability only, not ranked.</p>$rosterHtml</section>
'@
$core = Replace-ExactlyOnce -Text $core -Old $rosterPanelAnchor -New $rosterPanelReplacement -Contract 'My Team roster panel'

$boundaryOld = @'
<section class="panel boundary"><span class="lock">READ ONLY.</span> BF-668 composes existing governed team context, roster strength, positional pressure, posture, future capital, and exact roster evidence. It does not create a new score, recommend a lineup, rerank players, refresh evidence, set FAAB, execute trades, or submit Sleeper transactions.</section>
'@
$boundaryNew = @'
<section class="panel boundary"><span class="lock">READ ONLY.</span> BF-800 adds a weekly AutoFill lineup preview to the existing My Team evidence. It may recommend starter changes, but it cannot submit a lineup, mutate a Sleeper roster, set FAAB, execute trades, or perform any Sleeper transaction.</section>
'@
$core = Replace-ExactlyOnce -Text $core -Old $boundaryOld -New $boundaryNew -Contract 'My Team read-only boundary'

$routeOld = @'
                    $capital = ConvertTo-FutureCapitalView -Text (Get-TeamEvidenceBundleSection -Text $bundleText -Name "FUTURE_CAPITAL") -TeamId $teamId
                    $html = ConvertTo-TeamHtml -Roster $rosterView -Context $context -Strength $strength -Pressure $pressure -Posture $posture -Capital $capital
'@
$routeNew = @'
                    $capital = ConvertTo-FutureCapitalView -Text (Get-TeamEvidenceBundleSection -Text $bundleText -Name "FUTURE_CAPITAL") -TeamId $teamId
                    $autoFill = ConvertTo-AutoFillView -Text (Get-TeamEvidenceBundleSection -Text $bundleText -Name "AUTOFILL")
                    $html = ConvertTo-TeamHtml -Roster $rosterView -Context $context -Strength $strength -Pressure $pressure -Posture $posture -Capital $capital -AutoFill $autoFill
'@
$core = Replace-ExactlyOnce -Text $core -Old $routeOld -New $routeNew -Contract 'My Team route AutoFill binding'

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
