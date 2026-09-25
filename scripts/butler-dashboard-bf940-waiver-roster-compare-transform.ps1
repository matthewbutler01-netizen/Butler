param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath,
    [string]$CorePath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-940 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-940 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

if (-not [string]::IsNullOrWhiteSpace($CorePath)) {
    if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
        throw "BF-940 BLOCKED: staged Butler core not found at $CorePath"
    }

    $core = [System.IO.File]::ReadAllText($CorePath)

    $compareLineOld = @'
            $waiverCompare = $path -eq "/waivers/compare"
'@
    $compareLineNew = @'
            $waiverCompare = $path -eq "/waivers/compare"
            $waiverRosterCompare = $path -eq "/waivers/roster-compare"
'@
    $core = Replace-ExactlyOnce -Text $core -Old $compareLineOld.TrimEnd() -New $compareLineNew.TrimEnd() -Contract 'app-shell waiver roster compare route marker'

    $knownOld = @'
            $knownDashboardPath = $path -eq "/" -or $path -eq "/index.html" -or $path -eq "/waivers" -or $waiverCompare -or $candidate
'@
    $knownNew = @'
            $knownDashboardPath = $path -eq "/" -or $path -eq "/index.html" -or $path -eq "/waivers" -or $waiverCompare -or $waiverRosterCompare -or $candidate
'@
    $core = Replace-ExactlyOnce -Text $core -Old $knownOld.TrimEnd() -New $knownNew.TrimEnd() -Contract 'app-shell waiver roster compare allowlist'

    $proxyOld = @'
                $dashboardRequestTarget = if ($path -eq "/waivers" -or $path -eq "/waivers/compare") { $parts[1] } else { $path }
'@
    $proxyNew = @'
                $dashboardRequestTarget = if ($path -eq "/waivers" -or $path -eq "/waivers/compare" -or $path -eq "/waivers/roster-compare") { $parts[1] } else { $path }
'@
    $core = Replace-ExactlyOnce -Text $core -Old $proxyOld.TrimEnd() -New $proxyNew.TrimEnd() -Contract 'app-shell waiver roster compare query preservation'

    foreach ($required in @(
        '$waiverRosterCompare = $path -eq "/waivers/roster-compare"',
        '$path -eq "/waivers/roster-compare") { $parts[1] }',
        'Invoke-GovernedDashboardGet -InnerPort $innerPort -Path $dashboardRequestTarget'
    )) {
        if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-940 BLOCKED: required app-shell waiver roster compare marker is missing: $required"
        }
    }

    [System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
    $tokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
    if (@($parseErrors).Count -gt 0) {
        $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-940 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
    }
}

$cardActionOld = @'
  <div class="actions" style="margin-top:12px"><a class="button" href="/waivers/candidate/$(ConvertTo-HtmlText $candidate.SleeperId)">View governed details</a><a class="button" href="/waivers/compare?left=$(ConvertTo-HtmlText $candidate.SleeperId)">Compare candidate</a></div>
'@
$cardActionNew = @'
  <div class="actions" style="margin-top:12px"><a class="button" href="/waivers/candidate/$(ConvertTo-HtmlText $candidate.SleeperId)">View governed details</a><a class="button" href="/waivers/compare?left=$(ConvertTo-HtmlText $candidate.SleeperId)">Compare candidate</a><a class="button" href="/waivers/roster-compare?candidate=$(ConvertTo-HtmlText $candidate.SleeperId)">Compare to roster</a></div>
'@
$text = Replace-ExactlyOnce -Text $text -Old $cardActionOld.TrimEnd() -New $cardActionNew.TrimEnd() -Contract 'Waiver Board roster compare entry action'

$helperMarker = 'function Send-HttpResponse {'
$helperIndex = $text.IndexOf($helperMarker, [System.StringComparison]::Ordinal)
if ($helperIndex -lt 0) {
    throw 'BF-940 BLOCKED: dashboard response helper marker is missing.'
}

$compareFunctions = @'
function Get-WaiverRosterCompareRequest {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $values = @{
        candidate = @()
        roster = @()
    }

    $question = $RequestTarget.IndexOf('?')
    if ($question -ge 0 -and $question + 1 -lt $RequestTarget.Length) {
        foreach ($pair in @($RequestTarget.Substring($question + 1) -split '&')) {
            if ([string]::IsNullOrWhiteSpace($pair)) { continue }
            $equals = $pair.IndexOf('=')
            if ($equals -lt 0) { continue }

            $key = [System.Uri]::UnescapeDataString($pair.Substring(0, $equals).Replace('+', ' ')).Trim()
            if (-not $values.ContainsKey($key)) { continue }

            $value = [System.Uri]::UnescapeDataString($pair.Substring($equals + 1).Replace('+', ' ')).Trim()
            $values[$key] += $value
        }
    }

    if (@($values.candidate).Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$values.candidate[0])) {
        throw 'BF-940 BLOCKED: Waiver Roster Compare requires exactly one candidate id.'
    }
    if (@($values.roster).Count -gt 1) {
        throw 'BF-940 BLOCKED: Waiver Roster Compare accepts at most one roster player id.'
    }

    $candidate = [string]$values.candidate[0]
    $roster = if (@($values.roster).Count -eq 1) { [string]$values.roster[0] } else { '' }

    if ($candidate -notmatch '^[0-9]+$') {
        throw 'BF-940 BLOCKED: waiver candidate id is malformed.'
    }
    if (-not [string]::IsNullOrWhiteSpace($roster) -and $roster -notmatch '^[0-9]+$') {
        throw 'BF-940 BLOCKED: roster player id is malformed.'
    }
    if (-not [string]::IsNullOrWhiteSpace($roster) -and $candidate -ceq $roster) {
        throw 'BF-940 BLOCKED: Waiver Roster Compare requires two different exact player ids.'
    }

    return [pscustomobject]@{
        CandidateId = $candidate
        RosterId = $roster
    }
}

function Resolve-WaiverRosterPlayerById {
    param(
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [Parameter(Mandatory = $true)][string]$SleeperId
    )

    if ($SleeperId -notmatch '^[0-9]+$') { return $null }

    $players = @(Get-RosterPlayers -RosterContext $RosterContext)
    $matches = @($players | Where-Object { [string]$_.SleeperId -ceq $SleeperId })
    if ($matches.Count -gt 1) {
        throw "BF-940 BLOCKED: duplicate exact Sleeper id $SleeperId appeared in the verified BF-610 target roster."
    }
    if ($matches.Count -eq 0) { return $null }
    return $matches[0]
}

function Assert-WaiverRosterCompareTarget {
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$RosterContext
    )

    $waiverTarget = Get-WaiverTargetView -Bundle $Bundle
    $rosterTarget = Get-Bf623TargetView -Text $RosterContext -BoundaryName 'BF-940'
    foreach ($field in @('SleeperLeagueId','RosterId','LeagueName','DisplayName','TeamName','Role')) {
        if ([string]$waiverTarget.$field -cne [string]$rosterTarget.$field) {
            throw "BF-940 BLOCKED: BF-616 waiver target and BF-610 roster target disagree on $field."
        }
    }
    return $waiverTarget
}

function ConvertTo-WaiverRosterCandidateHtml {
    param([Parameter(Mandatory = $true)]$Candidate)

    $lane = Get-WaiverLanePresentation -Lane $Candidate.Lane
    $injuryText = if ($Candidate.Injury -eq 'none') { 'None reported' } else { $Candidate.Injury }
    $depthText = if ($Candidate.Depth -eq 'none/none') { 'Not available' } else { $Candidate.Depth }
    $hrefId = [System.Uri]::EscapeDataString([string]$Candidate.SleeperId)

    return @"
<article class="waiver-roster-card">
  <div class="eyebrow">Waiver candidate</div>
  <div class="candidate-top">
    <div><h2>$(ConvertTo-HtmlText $Candidate.Name)</h2><div class="meta">$(ConvertTo-HtmlText $Candidate.Position) &middot; NFL $(ConvertTo-HtmlText $Candidate.Team)</div></div>
    <span class="lane $($lane.Class)">$(ConvertTo-HtmlText $lane.Label)</span>
  </div>
  <div class="candidate-facts">
    <div><strong>Status</strong>$(ConvertTo-HtmlText $Candidate.Status)</div>
    <div><strong>Injury</strong>$(ConvertTo-HtmlText $injuryText)</div>
    <div><strong>Depth</strong>$(ConvertTo-HtmlText $depthText)</div>
    <div><strong>BF-616 lane</strong>$(ConvertTo-HtmlText $Candidate.Lane)</div>
  </div>
  <div class="market"><strong>Market attention:</strong> add $(ConvertTo-HtmlText $Candidate.MarketAdd) / drop $(ConvertTo-HtmlText $Candidate.MarketDrop) / net $(ConvertTo-HtmlText $Candidate.MarketNet)</div>
  <div class="actions" style="margin-top:12px"><a class="button" href="/waivers/candidate/$hrefId">Open candidate detail</a></div>
  <details><summary>Candidate traceability</summary><div class="tech"><div>Candidate-supported comparators: $(ConvertTo-HtmlText $Candidate.SupportedComparators)</div><div>Eligible comparators: $(ConvertTo-HtmlText $Candidate.EligibleComparators)</div></div></details>
</article>
"@
}

function ConvertTo-WaiverRosterPlayerHtml {
    param([Parameter(Mandatory = $true)]$Player)

    $slot = Get-RosterStatusLabel -Player $Player
    $lineup = if ([string]::IsNullOrWhiteSpace([string]$Player.LineupSlot)) { 'Not in a starter lineup slot' } else { [string]$Player.LineupSlot }

    return @"
<article class="waiver-roster-card">
  <div class="eyebrow">Current roster player</div>
  <div class="candidate-top"><div><h2>$(ConvertTo-HtmlText $Player.Name)</h2><div class="meta">$(ConvertTo-HtmlText $Player.Position) &middot; NFL $(ConvertTo-HtmlText $Player.Team)</div></div><span class="status done">VERIFIED ROSTER</span></div>
  <div class="candidate-facts">
    <div><strong>Roster status</strong>$(ConvertTo-HtmlText $slot)</div>
    <div><strong>Lineup slot</strong>$(ConvertTo-HtmlText $lineup)</div>
    <div><strong>Mapping</strong>$(ConvertTo-HtmlText $Player.Mapping)</div>
    <div><strong>Source</strong>BF-610 target roster</div>
  </div>
  <details><summary>Roster traceability</summary><div class="tech"><div>Exact Sleeper ID: $(ConvertTo-HtmlText $Player.SleeperId)</div><div>Roster slot: $(ConvertTo-HtmlText $Player.RosterSlot)</div><div>Butler player mapping: $(ConvertTo-HtmlText $Player.ButlerPlayer)</div></div></details>
</article>
"@
}

function ConvertTo-WaiverRosterCompareHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [Parameter(Mandatory = $true)]$Request
    )

    $target = Assert-WaiverRosterCompareTarget -Bundle $Bundle -RosterContext $RosterContext

    $candidate = Resolve-WaiverCandidateById -Bundle $Bundle -SleeperId $Request.CandidateId
    if ($null -eq $candidate) {
        throw "BF-940 BLOCKED: candidate id $($Request.CandidateId) is not in the current BF-616 authorized shortlist."
    }

    $players = @(Get-RosterPlayers -RosterContext $RosterContext)
    if ($players.Count -eq 0) {
        throw 'BF-940 BLOCKED: verified BF-610 target roster returned no players.'
    }

    $css = Get-SharedCss
    $header = Get-HeaderHtml -Target $target.Human -Active 'waivers'
    $compareCss = @"
.waiver-roster-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-top:16px}.waiver-roster-card{padding:18px;border:1px solid #2b3962;border-radius:15px;background:#0d1630}.waiver-roster-card h2{margin:5px 0 4px}.waiver-roster-choice-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:16px}.waiver-roster-choice{padding:15px;border:1px solid #2b3962;border-radius:13px;background:#0d1630}.waiver-roster-choice .name{font-size:18px;font-weight:800}.waiver-roster-choice .meta{margin-top:4px;color:#aebada;font-size:12px}@media(max-width:760px){.waiver-roster-grid,.waiver-roster-choice-grid{grid-template-columns:1fr}}
"@

    $candidateHtml = ConvertTo-WaiverRosterCandidateHtml -Candidate $candidate
    $candidateHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)

    if ([string]::IsNullOrWhiteSpace([string]$Request.RosterId)) {
        $choices = ''
        foreach ($player in @($players)) {
            if ([string]$player.SleeperId -ceq [string]$candidate.SleeperId) { continue }
            $rosterHref = [System.Uri]::EscapeDataString([string]$player.SleeperId)
            $slot = Get-RosterStatusLabel -Player $player
            $choices += @"
<article class="waiver-roster-choice"><div class="name">$(ConvertTo-HtmlText $player.Name)</div><div class="meta">$(ConvertTo-HtmlText $player.Position) &middot; NFL $(ConvertTo-HtmlText $player.Team) &middot; $(ConvertTo-HtmlText $slot)</div><div class="actions" style="margin-top:10px"><a class="button" href="/waivers/roster-compare?candidate=$candidateHref&roster=$rosterHref">Compare with this roster player</a></div></article>
"@
        }
        if ([string]::IsNullOrWhiteSpace($choices)) {
            throw 'BF-940 BLOCKED: verified target roster has no distinct player available for comparison.'
        }

        return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Waiver Roster Compare</title><style>$css$compareCss</style></head><body><main class="shell">
$header
<section class="panel"><div class="eyebrow">Waiver Roster Compare</div><div class="statusrow"><div><h1 class="headline">Candidate selected</h1><p class="lede">Choose one exact player from your verified BF-610 roster. Butler will show the existing context side by side without telling you who to keep or drop.</p></div><span class="status done">NOT A RANKING</span></div><div class="waiver-roster-grid">$candidateHtml</div><div class="actions" style="margin-top:14px"><a class="button" href="/waivers">Back to Waiver Board</a></div></section>
<section class="panel"><div class="eyebrow">Your roster</div><h2 class="headline">Choose roster player</h2><p class="lede">Source order is preserved. These players are not ranked or filtered into drop recommendations.</p><div class="waiver-roster-choice-grid">$choices</div></section>
<section class="panel boundary"><span class="lock">READ ONLY &middot; NOT A RANKING.</span> This surface compares one current BF-616 authorized waiver candidate with one verified BF-610 roster player. It does not create a winner, preference, recommendation, drop instruction, FAAB amount, refresh, Butler write, or Sleeper transaction.</section>
</main></body></html>
"@
    }

    $rosterPlayer = Resolve-WaiverRosterPlayerById -RosterContext $RosterContext -SleeperId $Request.RosterId
    if ($null -eq $rosterPlayer) {
        throw "BF-940 BLOCKED: roster player id $($Request.RosterId) is not in the verified BF-610 target roster."
    }
    if ([string]$candidate.SleeperId -ceq [string]$rosterPlayer.SleeperId) {
        throw 'BF-940 BLOCKED: Waiver Roster Compare requires two different exact players.'
    }

    $rosterHtml = ConvertTo-WaiverRosterPlayerHtml -Player $rosterPlayer

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Waiver Roster Compare</title><style>$css$compareCss</style></head><body><main class="shell">
$header
<section class="panel"><div class="eyebrow">Waiver Roster Compare</div><div class="statusrow"><div><h1 class="headline">Candidate vs roster context</h1><p class="lede">Neutral evidence for one exact authorized waiver candidate and one exact verified roster player. Butler does not select a winner or turn this into a drop recommendation.</p></div><span class="status done">NOT A RANKING</span></div><div class="actions" style="margin-top:14px"><a class="button" href="/waivers/roster-compare?candidate=$candidateHref">Compare another roster player</a><a class="button" href="/waivers">Back to Waiver Board</a></div></section>
<section class="panel"><div class="waiver-roster-grid">$candidateHtml$rosterHtml</div></section>
<section class="panel boundary"><span class="lock">READ ONLY &middot; NOT A RANKING.</span> No score, winner, preference, recommendation, drop instruction, FAAB amount, provider refresh, Butler write, or Sleeper transaction is created here.</section>
</main></body></html>
"@
}

'@

$text = $text.Insert($helperIndex, $compareFunctions)

$knownOld = @'
            $knownStaticPath = $path -eq "/" -or $path -eq "/index.html" -or $path -eq "/team" -or $path -eq "/waivers" -or $path -eq "/waivers/compare"
'@
$knownNew = @'
            $knownStaticPath = $path -eq "/" -or $path -eq "/index.html" -or $path -eq "/team" -or $path -eq "/waivers" -or $path -eq "/waivers/compare" -or $path -eq "/waivers/roster-compare"
'@
$text = Replace-ExactlyOnce -Text $text -Old $knownOld.TrimEnd() -New $knownNew.TrimEnd() -Contract 'dashboard waiver roster compare allowlist'

$routeOld = @'
                elseif ($path -eq "/waivers/compare") {
                    $waiverBundle = Invoke-ButlerReadOnlyWaiverBoard
                    $compareRequest = Get-WaiverCompareRequest -RequestTarget $parts[1]
                    $html = ConvertTo-WaiverCompareHtml -Bundle $waiverBundle -Request $compareRequest
                }
'@
$routeNew = @'
                elseif ($path -eq "/waivers/roster-compare") {
                    $waiverEvidence = Invoke-ButlerReadOnlyWaiverEvidenceBundle
                    $rosterCompareRequest = Get-WaiverRosterCompareRequest -RequestTarget $parts[1]
                    $html = ConvertTo-WaiverRosterCompareHtml -Bundle $waiverEvidence.WaiverBoard -RosterContext $waiverEvidence.RosterContext -Request $rosterCompareRequest
                }
                elseif ($path -eq "/waivers/compare") {
                    $waiverBundle = Invoke-ButlerReadOnlyWaiverBoard
                    $compareRequest = Get-WaiverCompareRequest -RequestTarget $parts[1]
                    $html = ConvertTo-WaiverCompareHtml -Bundle $waiverBundle -Request $compareRequest
                }
'@
$text = Replace-ExactlyOnce -Text $text -Old $routeOld.TrimEnd() -New $routeNew.TrimEnd() -Contract 'dashboard waiver roster compare route'

foreach ($required in @(
    'Compare to roster',
    'function Get-WaiverRosterCompareRequest',
    'function Resolve-WaiverRosterPlayerById',
    'function ConvertTo-WaiverRosterCompareHtml',
    'Waiver Roster Compare',
    'Candidate selected',
    'Choose roster player',
    'Compare with this roster player',
    'Candidate vs roster context',
    'Compare another roster player',
    'Source order is preserved.',
    '$path -eq "/waivers/roster-compare"',
    '$waiverEvidence = Invoke-ButlerReadOnlyWaiverEvidenceBundle'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-940 BLOCKED: required Waiver Roster Compare marker is missing: $required"
    }
}

$installedStart = $text.IndexOf('function Get-WaiverRosterCompareRequest', [System.StringComparison]::Ordinal)
$installedEnd = $text.IndexOf('function Send-HttpResponse {', $installedStart, [System.StringComparison]::Ordinal)
if ($installedStart -lt 0 -or $installedEnd -le $installedStart) {
    throw 'BF-940 BLOCKED: installed Waiver Roster Compare function boundary is missing.'
}
$installed = $text.Substring($installedStart, $installedEnd - $installedStart)
if ($installed -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab') {
    throw 'BF-940 BLOCKED: Waiver Roster Compare introduced provider, write, or FAAB behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-940 BLOCKED: generated staged Dashboard failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-940 Waiver Roster Compare applied.'
