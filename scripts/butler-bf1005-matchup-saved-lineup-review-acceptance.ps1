Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1005-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1','butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $core = [IO.File]::ReadAllText((Join-Path $root 'butler-app-shell-core-single.ps1'))
    foreach ($required in @(
        'function Get-Bf1005SavedLineupReview',
        'function ConvertTo-Bf1005SavedLineupReviewHtml',
        'The saved Week $week Lineup Review is current for this roster',
        'No new provider request was made to display it.',
        'Refresh full Lineup Review',
        '-SavedReview $savedReview'
    )) {
        if ($core.IndexOf($required,[System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1005 BLOCKED: staged marker is missing: $required"
        }
    }

    $tokens=$null; $errors=$null
    $ast=[System.Management.Automation.Language.Parser]::ParseInput($core,[ref]$tokens,[ref]$errors)
    if (@($errors).Count -gt 0) { throw 'BF-1005 BLOCKED: staged core failed parse.' }

    function Get-OneFunction {
        param([string]$Name)
        $matches=@($ast.FindAll({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $Name},$true))
        if ($matches.Count -ne 1) { throw "BF-1005 BLOCKED: expected one $Name function." }
        $matches[0]
    }

    foreach ($name in @('ConvertTo-HtmlText','Get-MatchupLineupDecisionView','ConvertTo-Bf1005SavedLineupReviewHtml')) {
        Invoke-Expression (Get-OneFunction -Name $name).Extent.Text
    }

    $idle=[pscustomobject]@{Requested=$false}
    $snapshot=[pscustomobject]@{
        Ready=$true; Week='4'; Source='Sleeper weekly projections'; CurrentTotal='94.51'; RecommendedTotal='95'; Gain='+0.49'; ChangedCount=2;
        GeneratedUtc=[DateTimeOffset]::UtcNow.ToString('o'); AvailabilityExclusions=@();
        ProjectionHolds=@(
            [pscustomobject]@{Name='Keenan Allen';Status='Active';InjuryStatus='Questionable'},
            [pscustomobject]@{Name='Jadarian Price';Status='Active';InjuryStatus='Out'},
            [pscustomobject]@{Name='Josh Jacobs';Status='Active';InjuryStatus='NA'},
            [pscustomobject]@{Name='Hunter Henry';Status='none';InjuryStatus='none'}
        )
    }

    $decision=Get-MatchupLineupDecisionView -AutoFill $idle -SavedReview $snapshot
    if ([string]$decision.Status -cne 'MANUAL REVIEW' -or [string]$decision.Title -notmatch 'Week 4' -or [string]$decision.Detail -notmatch 'Keenan Allen' -or [string]$decision.Detail -notmatch 'Hunter Henry') {
        throw 'BF-1005 BLOCKED: saved current review did not hydrate Matchup decision.'
    }

    $html=ConvertTo-Bf1005SavedLineupReviewHtml -Snapshot $snapshot
    foreach ($required in @('Week 4 lineup review needs attention','Keenan Allen','Jadarian Price','Josh Jacobs','Hunter Henry','No new provider request was made to display it.','Refresh full Lineup Review')) {
        if ($html.IndexOf($required,[System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1005 BLOCKED: saved review render marker missing: $required"
        }
    }
    if ($html.IndexOf('NOT REVIEWED',[System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-1005 BLOCKED: current saved review still renders as NOT REVIEWED.'
    }

    Write-Host 'BF-1005 MATCHUP SAVED LINEUP REVIEW ACCEPTANCE: PASS'
    Write-Host 'Coverage: current saved weekly review -> Matchup decision + compact advisor summary; no new provider request or Sleeper write.'
}
finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
