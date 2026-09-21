param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-825 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-825 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-825 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

function Replace-FunctionBlock {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$StartMarker,
        [Parameter(Mandatory = $true)][string]$NextMarker,
        [Parameter(Mandatory = $true)][string]$Replacement,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $start = $Text.IndexOf($StartMarker, [System.StringComparison]::Ordinal)
    if ($start -lt 0) {
        throw "BF-825 BLOCKED: $Contract start marker is missing."
    }
    $second = $Text.IndexOf($StartMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-825 BLOCKED: $Contract start marker is ambiguous."
    }
    $next = $Text.IndexOf($NextMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($next -lt 0) {
        throw "BF-825 BLOCKED: $Contract end marker is missing."
    }
    return $Text.Substring(0, $start) + $Replacement.TrimEnd() + "`r`n`r`n" + $Text.Substring($next)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$idleReplacement = @'
function New-AutoFillIdleView {
    return [pscustomobject]@{
        Requested = $false
        Ready = $false
        Season = ''
        Week = ''
        Scoring = ''
        Reason = ''
        Source = ''
        SourceSurface = ''
        CurrentTotal = ''
        RecommendedTotal = ''
        Gain = ''
        Assignments = @()
        BenchMoves = @()
        Promotions = @()
        AvailabilityExclusions = @()
    }
}
'@
$core = Replace-FunctionBlock -Text $core -StartMarker 'function New-AutoFillIdleView {' -NextMarker 'function ConvertTo-AutoFillView {' -Replacement $idleReplacement -Contract 'idle AutoFill availability shape'

$viewReplacement = @'
function ConvertTo-AutoFillView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $state = [regex]::Match($Text, '(?m)^State:\s+(?<value>READY|UNAVAILABLE)\s*$')
    $frame = [regex]::Match($Text, '(?m)^Season/week:\s+(?<season>\d+)/(?<week>\S+)\s*$')
    $scoring = [regex]::Match($Text, '(?m)^Scoring basis:\s+(?<value>\S+)\s*$')
    if (-not $state.Success -or -not $frame.Success -or -not $scoring.Success) {
        throw 'BF-825 BLOCKED: AutoFill bundle section is missing required state/frame/scoring fields.'
    }

    if ($state.Groups['value'].Value -ceq 'UNAVAILABLE') {
        $reason = [regex]::Match($Text, '(?m)^Reason:\s+(?<value>.+?)\s*$')
        if (-not $reason.Success) {
            throw 'BF-825 BLOCKED: unavailable AutoFill bundle section is missing its reason.'
        }
        return [pscustomobject]@{
            Requested = $true
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
            AvailabilityExclusions = @()
        }
    }

    $source = [regex]::Match($Text, '(?m)^Projection source:\s+(?<value>.+?)\s*$')
    $sourceSurface = [regex]::Match($Text, '(?m)^Projection source surface:\s+(?<value>.+?)\s*$')
    $current = [regex]::Match($Text, '(?m)^Current projected starter total:\s+(?<value>-?\d+(?:\.\d+)?)\s*$')
    $recommended = [regex]::Match($Text, '(?m)^Recommended projected starter total:\s+(?<value>-?\d+(?:\.\d+)?)\s*$')
    $gain = [regex]::Match($Text, '(?m)^Projected gain:\s+(?<value>[+-]?\d+(?:\.\d+)?)\s*$')
    if (-not $source.Success -or -not $sourceSurface.Success -or -not $current.Success -or
        -not $recommended.Success -or -not $gain.Success) {
        throw 'BF-825 BLOCKED: ready AutoFill bundle section is missing projection summary fields.'
    }

    $assignments = @()
    $benchMoves = @()
    $promotions = @()
    $availabilityExclusions = @()
    $mode = ''
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -ceq 'Moves to bench:') { $mode = 'BENCH'; continue }
        if ($line -ceq 'Promotions to starting lineup:') { $mode = 'PROMOTE'; continue }
        if ($line -ceq 'Availability exclusions:') { $mode = 'AVAILABILITY'; continue }
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
            continue
        }
        if ($mode -ceq 'AVAILABILITY' -and $line -match '^\s{2}(?<name>.+?)\s+\[(?<id>[^\]]+)\]\s+\|\s+status=(?<status>.*?)\s+\|\s+injury_status=(?<injury>.*?)\s+\|\s+reason=(?<reason>.+?)\s*$') {
            $availabilityExclusions += [pscustomobject]@{
                Name = $Matches['name'].Trim()
                Id = $Matches['id'].Trim()
                Status = $Matches['status'].Trim()
                InjuryStatus = $Matches['injury'].Trim()
                Reason = $Matches['reason'].Trim()
            }
            continue
        }
        if ($mode -ne '' -and $line -match '^\s{2}none\s*$') { continue }
    }
    if ($assignments.Count -eq 0) {
        throw 'BF-825 BLOCKED: ready AutoFill bundle section contains no recommended lineup rows.'
    }

    return [pscustomobject]@{
        Requested = $true
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
        AvailabilityExclusions = @($availabilityExclusions)
    }
}
'@
$core = Replace-FunctionBlock -Text $core -StartMarker 'function ConvertTo-AutoFillView {' -NextMarker 'function ConvertTo-AutoFillHtml {' -Replacement $viewReplacement -Contract 'AutoFill availability evidence parser'

$gainAnchor = @'
    $gainClass = if ([string]$AutoFill.Gain -match '^-') { 'metric-value metric-negative' } else { 'metric-value metric-positive' }

    if ($changedCount -gt 0) {
'@
$gainReplacement = @'
    $gainClass = if ([string]$AutoFill.Gain -match '^-') { 'metric-value metric-negative' } else { 'metric-value metric-positive' }

    $availabilityCount = @($AutoFill.AvailabilityExclusions).Count
    $availabilityEvidence = ''
    $availabilityWhySuffix = ''
    if ($availabilityCount -gt 0) {
        $availabilityRows = ''
        foreach ($exclusion in $AutoFill.AvailabilityExclusions) {
            $statusText = if ([string]::IsNullOrWhiteSpace([string]$exclusion.Status) -or [string]$exclusion.Status -ceq 'none') { 'not reported' } else { [string]$exclusion.Status }
            $injuryText = if ([string]::IsNullOrWhiteSpace([string]$exclusion.InjuryStatus) -or [string]$exclusion.InjuryStatus -ceq 'none') { 'not reported' } else { [string]$exclusion.InjuryStatus }
            $availabilityRows += "<div class=`"callout`"><strong>$(ConvertTo-HtmlText $exclusion.Name)</strong><br><span>Status: $(ConvertTo-HtmlText $statusText) &middot; Injury status: $(ConvertTo-HtmlText $injuryText)</span><br><span>$(ConvertTo-HtmlText $exclusion.Reason)</span></div>"
        }
        $availabilityEvidence = "<details><summary>Availability evidence</summary>$availabilityRows</details>"
        $playerWord = if ($availabilityCount -eq 1) { 'player' } else { 'players' }
        $availabilityWhySuffix = " Butler excluded $availabilityCount explicitly unavailable $playerWord from startable candidates using exact current Sleeper status evidence; no zero projection was invented."
    }

    if ($changedCount -gt 0) {
'@
$core = Replace-ExactlyOnce -Text $core -Old $gainAnchor -New $gainReplacement -Contract 'Lineup Advisor availability evidence summary'

$readyReturnAnchor = @'
    return "<section class=`"panel recommendation-panel`"><div class=`"manager-head`"><div><div class=`"eyebrow`">Lineup advisor</div><h2>$(ConvertTo-HtmlText $decisionTitle)
'@
$readyReturnReplacement = @'
    $whyCopy = $whyCopy + $availabilityWhySuffix

    return "<section class=`"panel recommendation-panel`"><div class=`"manager-head`"><div><div class=`"eyebrow`">Lineup advisor</div><h2>$(ConvertTo-HtmlText $decisionTitle)
'@
$core = Replace-ExactlyOnce -Text $core -Old $readyReturnAnchor -New $readyReturnReplacement -Contract 'Lineup Advisor availability explanation'

$evidenceAnchor = @'
<div class=`"movement-box`"><strong>Move to bench</strong><div>$benchChips</div></div></div><div class=`"source-note`"><span>Projections:
'@
$evidenceReplacement = @'
<div class=`"movement-box`"><strong>Move to bench</strong><div>$benchChips</div></div></div>$availabilityEvidence<div class=`"source-note`"><span>Projections:
'@
$core = Replace-ExactlyOnce -Text $core -Old $evidenceAnchor -New $evidenceReplacement -Contract 'Lineup Advisor availability detail disclosure'

if ($core -notmatch 'AvailabilityExclusions') {
    throw 'BF-825 BLOCKED: parsed availability exclusions are missing.'
}
if ($core -notmatch 'Availability evidence') {
    throw 'BF-825 BLOCKED: manager-facing availability evidence disclosure is missing.'
}
if ($core -notmatch 'no zero projection was invented') {
    throw 'BF-825 BLOCKED: no-zero-projection explanation is missing.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
