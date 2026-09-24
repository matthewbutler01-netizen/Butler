param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-926 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) {
        throw "BF-926 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$confirmedVarsOld = @'
    $displayTeam = if (-not [string]::IsNullOrWhiteSpace([string]$Roster.TeamName) -and $Roster.TeamName -cne 'none') { $Roster.TeamName } else { $Roster.ButlerTeamName }
    $decision = Get-MatchupLineupDecisionView -AutoFill $AutoFill
'@
$confirmedVarsNew = @'
    $displayTeam = if (-not [string]::IsNullOrWhiteSpace([string]$Roster.TeamName) -and $Roster.TeamName -cne 'none') { $Roster.TeamName } else { $Roster.ButlerTeamName }
    $opponentHrefId = [System.Uri]::EscapeDataString([string]$Matchup.OpponentTeamId)
    $decision = Get-MatchupLineupDecisionView -AutoFill $AutoFill
'@
$core = Replace-ExactlyOnce -Text $core -Old $confirmedVarsOld.TrimEnd() -New $confirmedVarsNew.TrimEnd() -Contract 'confirmed Matchup opponent href'

$confirmedHeroOld = '<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Weekly matchup &middot; lineup decision first</div><h1 class="headline">$(ConvertTo-HtmlText $decision.Title)</h1><p class="lede">$(ConvertTo-HtmlText $displayTeam) vs. $(ConvertTo-HtmlText $Matchup.OpponentTeamName). $(ConvertTo-HtmlText $decision.Copy)</p></div><span class="status $($decision.StatusClass)">$(ConvertTo-HtmlText $decision.Status)</span></div><div class="next"><strong>What to do now</strong><p>$(ConvertTo-HtmlText $decision.Detail)</p></div>$decisionAction<div class="stats">'
$confirmedHeroNew = '<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Weekly matchup &middot; lineup decision first</div><h1 class="headline">$(ConvertTo-HtmlText $decision.Title)</h1><p class="lede">$(ConvertTo-HtmlText $displayTeam) vs. $(ConvertTo-HtmlText $Matchup.OpponentTeamName). $(ConvertTo-HtmlText $decision.Copy)</p></div><span class="status $($decision.StatusClass)">$(ConvertTo-HtmlText $decision.Status)</span></div><div class="next"><strong>What to do now</strong><p>$(ConvertTo-HtmlText $decision.Detail)</p></div>$decisionAction<div class="button-row"><a class="btn btn-secondary" href="/team">Open My Team</a><a class="btn btn-secondary" href="/franchise?id=$opponentHrefId">Scout opponent</a><a class="btn btn-secondary" href="/trade?opponent=$opponentHrefId">Trade with opponent</a></div><div class="stats">'
$core = Replace-ExactlyOnce -Text $core -Old $confirmedHeroOld -New $confirmedHeroNew -Contract 'confirmed Matchup opponent actions'

$unavailableHeroOld = '<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Weekly matchup &middot; lineup decision first</div><h1 class="headline">$(ConvertTo-HtmlText $decision.Title)</h1><p class="lede">Opponent data is incomplete, but your existing Lineup Advisor decision remains the first manager task. $(ConvertTo-HtmlText $decision.Copy)</p></div><span class="status $($decision.StatusClass)">$(ConvertTo-HtmlText $decision.Status)</span></div><div class="next"><strong>What to do now</strong><p>$(ConvertTo-HtmlText $decision.Detail)</p></div>$decisionAction</section>'
$unavailableHeroNew = '<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Weekly matchup &middot; lineup decision first</div><h1 class="headline">$(ConvertTo-HtmlText $decision.Title)</h1><p class="lede">Opponent data is incomplete, but your existing Lineup Advisor decision remains the first manager task. $(ConvertTo-HtmlText $decision.Copy)</p></div><span class="status $($decision.StatusClass)">$(ConvertTo-HtmlText $decision.Status)</span></div><div class="next"><strong>What to do now</strong><p>$(ConvertTo-HtmlText $decision.Detail)</p></div>$decisionAction<div class="button-row"><a class="btn btn-secondary" href="/team">Open My Team</a></div></section>'
$core = Replace-ExactlyOnce -Text $core -Old $unavailableHeroOld -New $unavailableHeroNew -Contract 'unavailable Matchup safe action'

foreach ($required in @(
    '$opponentHrefId = [System.Uri]::EscapeDataString([string]$Matchup.OpponentTeamId)',
    'href="/team">Open My Team</a>',
    'href="/franchise?id=$opponentHrefId">Scout opponent</a>',
    'href="/trade?opponent=$opponentHrefId">Trade with opponent</a>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-926 BLOCKED: required Matchup action marker is missing: $required"
    }
}

$matchupStart = $core.IndexOf('function ConvertTo-MatchupHtml {', [System.StringComparison]::Ordinal)
$matchupEnd = $core.IndexOf('function ConvertTo-MatchupUnavailableHtml {', $matchupStart, [System.StringComparison]::Ordinal)
$unavailableEnd = $core.IndexOf('function Get-PlayerDetailRequestId {', $matchupEnd, [System.StringComparison]::Ordinal)
if ($matchupStart -lt 0 -or $matchupEnd -le $matchupStart -or $unavailableEnd -le $matchupEnd) {
    throw 'BF-926 BLOCKED: Matchup renderer boundaries are missing.'
}
$presentation = $core.Substring($matchupStart, $unavailableEnd - $matchupStart)
if ($presentation -match 'Invoke-RestMethod|Invoke-WebRequest|Method = "POST"|submitTransaction|setFaab|win probability|predict a winner') {
    throw 'BF-926 BLOCKED: Matchup opponent actions introduced provider, write, or outcome-prediction behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-926 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-926 Matchup opponent actions applied.'
