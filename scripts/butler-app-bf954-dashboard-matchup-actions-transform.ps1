param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-954 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-954 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)
foreach ($required in @(
    'function Add-DashboardMatchupSummary {',
    'Week $($matchup.Week): $($matchup.UserTeamName) vs. $($matchup.OpponentTeamName)',
    "OPPONENT CONFIRMED",
    'Open Weekly Matchup &rarr;'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-954 BLOCKED: required Dashboard matchup capability is missing: $required"
    }
}

$functionStart = $core.IndexOf('function Add-DashboardMatchupSummary {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function Add-LeagueNavigation {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-954 BLOCKED: Dashboard matchup summary function boundary is missing.'
}
$function = $core.Substring($functionStart, $functionEnd - $functionStart)

$stateOld = @'
    $title = 'Opponent unavailable'
    $status = 'MATCHUP DATA NEEDED'
    $statusClass = 'warn'
'@

$stateNew = @'
    $title = 'Opponent unavailable'
    $status = 'MATCHUP DATA NEEDED'
    $statusClass = 'warn'
    $opponentHrefId = ''
'@

$confirmedOld = @'
        $title = "Week $($matchup.Week): $($matchup.UserTeamName) vs. $($matchup.OpponentTeamName)"
        $status = 'OPPONENT CONFIRMED'
        $statusClass = 'good'
'@

$confirmedNew = @'
        $title = "Week $($matchup.Week): $($matchup.UserTeamName) vs. $($matchup.OpponentTeamName)"
        $status = 'OPPONENT CONFIRMED'
        $statusClass = 'good'
        $opponentHrefId = [System.Uri]::EscapeDataString([string]$matchup.OpponentTeamId)
'@

$cardOld = @'
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="/matchup">Open Weekly Matchup &rarr;</a></div></article>'
'@

$cardNew = @'
    $opponentTools = if (-not [string]::IsNullOrWhiteSpace($opponentHrefId)) {
        '<div class="week-tools"><strong>Opponent</strong><a class="week-tool" href="/franchise?id=' + (ConvertTo-HtmlText $opponentHrefId) + '">Scout</a><a class="week-tool" href="/trade?opponent=' + (ConvertTo-HtmlText $opponentHrefId) + '">Trade</a></div>'
    }
    else {
        ''
    }
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="/matchup">Open Weekly Matchup &rarr;</a></div>' + $opponentTools + '</article>'
'@

$function = Replace-ExactlyOnce -Text $function -Old $stateOld.TrimEnd() -New $stateNew.TrimEnd() -Contract 'matchup-card opponent action state'
$function = Replace-ExactlyOnce -Text $function -Old $confirmedOld.TrimEnd() -New $confirmedNew.TrimEnd() -Contract 'confirmed opponent link identity'
$function = Replace-ExactlyOnce -Text $function -Old $cardOld.TrimEnd() -New $cardNew.TrimEnd() -Contract 'Dashboard matchup opponent actions'
$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

foreach ($required in @(
    '$opponentHrefId = [System.Uri]::EscapeDataString([string]$matchup.OpponentTeamId)',
    'href="/franchise?id=',
    '">Scout</a>',
    'href="/trade?opponent=',
    '">Trade</a>',
    '$opponentTools'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-954 BLOCKED: required Dashboard opponent-action marker is missing: $required"
    }
}

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-954 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$installedFunctionEnd = $core.IndexOf('function Add-LeagueNavigation {', $functionStart, [System.StringComparison]::Ordinal)
$installed = $core.Substring($functionStart, $installedFunctionEnd - $functionStart)
if ($installed -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-954 BLOCKED: Dashboard matchup opponent actions introduced write or optimizer behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-954 Dashboard matchup opponent actions applied.'
