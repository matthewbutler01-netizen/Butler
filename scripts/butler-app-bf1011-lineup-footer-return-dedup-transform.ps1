param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-1011 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)
$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-1011 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}

$function = $core.Substring($functionStart, $functionEnd - $functionStart)
$matchupAnchor = '<a class=`"btn btn-secondary`" href=`"/matchup`">Back to Matchup</a>'
$teamAnchor = '<a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a>'
$dashboardAnchor = '<a class=`"btn btn-secondary`" href=`"/`">Back to Dashboard</a>'
$refreshAnchor = '<a class=`"btn btn-secondary`" href=`"/team/autofill`">Refresh projection</a>'

$matchupCount = [regex]::Matches($function, [regex]::Escape($matchupAnchor)).Count
$teamCount = [regex]::Matches($function, [regex]::Escape($teamAnchor)).Count
$dashboardCount = [regex]::Matches($function, [regex]::Escape($dashboardAnchor)).Count
$refreshCount = [regex]::Matches($function, [regex]::Escape($refreshAnchor)).Count

if ($dashboardCount -ne 1 -or $refreshCount -ne 1) {
    throw "BF-1011 BLOCKED: expected one Dashboard and one Refresh projection return action; found dashboard=$dashboardCount refresh=$refreshCount."
}

if ($matchupCount -eq 2 -and $teamCount -eq 0) {
    $lastMatchup = $function.LastIndexOf($matchupAnchor, [System.StringComparison]::Ordinal)
    if ($lastMatchup -lt 0) {
        throw 'BF-1011 BLOCKED: duplicate Matchup return could not be located.'
    }
    $function = $function.Substring(0, $lastMatchup) + $teamAnchor + $function.Substring($lastMatchup + $matchupAnchor.Length)
}
elseif ($matchupCount -eq 1 -and $teamCount -eq 1) {
    # Canonical return set already present.
}
else {
    throw "BF-1011 BLOCKED: unexpected Lineup Review return set; matchup=$matchupCount team=$teamCount."
}

$finalMatchupCount = [regex]::Matches($function, [regex]::Escape($matchupAnchor)).Count
$finalTeamCount = [regex]::Matches($function, [regex]::Escape($teamAnchor)).Count
$finalDashboardCount = [regex]::Matches($function, [regex]::Escape($dashboardAnchor)).Count
$finalRefreshCount = [regex]::Matches($function, [regex]::Escape($refreshAnchor)).Count
if ($finalMatchupCount -ne 1 -or $finalTeamCount -ne 1 -or $finalDashboardCount -ne 1 -or $finalRefreshCount -ne 1) {
    throw "BF-1011 BLOCKED: canonical Lineup Review footer was not established."
}

$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1011 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-1011 Lineup Review footer returns normalized.'
