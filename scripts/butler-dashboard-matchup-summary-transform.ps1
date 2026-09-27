param([Parameter(Mandatory = $true)][string]$CorePath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) { throw 'Dashboard matchup: staged core is missing.' }
$core = [IO.File]::ReadAllText($CorePath)
$marker = 'function Add-LeagueNavigation {'
if ([regex]::Matches($core, [regex]::Escape($marker)).Count -ne 1) { throw 'Dashboard matchup: navigation boundary is missing.' }

$helper = @'
function Add-DashboardMatchupSummary {
    param([Parameter(Mandatory = $true)][string]$Html)

    $cardPattern = [regex]::new('(?s)<article class="week-glance-card"><div class="week-kind">Matchup</div>.*?</article>')
    if (-not $cardPattern.IsMatch($Html)) { throw 'Dashboard matchup: summary card is missing.' }
    $title = 'Opponent unavailable'
    $status = 'MATCHUP DATA NEEDED'
    $statusClass = 'warn'
    try {
        # This is the same read-only, non-AutoFill bundle used by GET /matchup.
        # Validate the exact team, season, and week before showing an opponent.
        $bundle = Invoke-ButlerReadOnlyTask -Task ':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit' -Arguments "$LeagueId --weekly-matchup-bundle" -BoundaryName 'Dashboard matchup'
        $roster = ConvertTo-MatchupRosterContextView -Text (Get-TeamEvidenceBundleSection -Text $bundle -Name 'MATCHUP_CONTEXT')
        $week = 0
        if (-not [int]::TryParse([string]$roster.ProviderLeg, [ref]$week) -or $week -le 0) { throw 'Current week unavailable.' }
        $rawMatchup = Get-TeamEvidenceBundleSection -Text $bundle -Name 'MATCHUP'
        $matchup = ConvertTo-WeeklyMatchupView -Text $rawMatchup
        if ($matchup.UserTeamId -cne $roster.ButlerTeamId -or $matchup.Season -ne $roster.Season -or $matchup.Week -ne $week) {
            throw 'Matchup frame does not match the bound team.'
        }
        $title = "Week $($matchup.Week): $($matchup.UserTeamName) vs. $($matchup.OpponentTeamName)"
        $status = 'OPPONENT CONFIRMED'
        $statusClass = 'good'
    }
    catch {
        # Missing or stale matchup evidence must not block the rest of Dashboard.
    }
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="/matchup">Open Weekly Matchup &rarr;</a></div></article>'
    return $cardPattern.Replace($Html, [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $card }, 1)
}

'@
$core = $core.Replace($marker, $helper + $marker)

$old = '                $body = Add-LeagueNavigation -Html $body'
$new = @'
                if (($path -eq '/' -or $path -eq '/index.html') -and $proxied.StatusCode -eq 200) {
                    $body = Add-DashboardMatchupSummary -Html $body
                }
                $body = Add-LeagueNavigation -Html $body
'@
if ([regex]::Matches($core, [regex]::Escape($old)).Count -ne 1) { throw 'Dashboard matchup: dashboard response boundary is missing.' }
$core = $core.Replace($old, $new.TrimEnd())

$tokens = $null; $errors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) { throw "Dashboard matchup: generated core has $(@($errors).Count) parse error(s)." }
[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'Dashboard weekly matchup summary applied.'
