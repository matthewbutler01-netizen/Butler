Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'butler-weekly-decision-queue.ps1')
foreach ($case in @(
    @{State='CURRENT_AND_ACTIONABLE'; Action='LIVE_ACTIONABLE_VERIFIED'; Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'; Expected='RECORDED MOVE TO REVIEW'},
    @{State='CURRENT_AND_ACTIONABLE'; Action='UNVERIFIED'; Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'; Expected='WAIVER HELD'},
    @{State='CURRENT_AND_ACTIONABLE'; Action='LIVE_ACTIONABLE_VERIFIED'; Lineage='WAIVER_LINEAGE_SUPERSEDED'; Expected='WAIVER HELD'},
    @{State='CURRENT_AND_ACTIONABLE CURRENT_AND_ACTIONABLE'; Action='LIVE_ACTIONABLE_VERIFIED'; Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'; Expected='WAIVER HELD'},
    @{State='STALE_DO_NOT_ACT'; Action='LIVE_ACTIONABLE_VERIFIED'; Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'; Expected='WAIVER HELD'},
    @{State=''; Action=''; Lineage=''; Expected='WAIVER HELD'},
    @{State='NO_TRANSACTION_TO_ACT_ON'; Action='NO_TRANSACTION_TO_REVALIDATE'; Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'; Expected='NO RECORDED MOVE'},
    @{State='NO_TRANSACTION_TO_ACT_ON'; Action='NO_TRANSACTION_TO_REVALIDATE'; Lineage='WAIVER_LINEAGE_SUPERSEDED'; Expected='WAIVER HELD'},
    @{State='TRANSACTION_PENDING_DO_NOT_DUPLICATE'; Action=''; Lineage=''; Expected='DO NOT DUPLICATE'},
    @{State='TRANSACTION_ALREADY_COMPLETE'; Action=''; Lineage=''; Expected='RECORDED COMPLETE'}
)) {
    $items=@(Get-ButlerWeeklyDecisionQueue -LineupState 'EVIDENCE GAP' -WaiverState $case.State -Actionability $case.Action -Lineage $case.Lineage)
    $waiver=@($items | Where-Object Kind -CEQ 'Waivers')
    if ($items.Count -ne 2 -or $waiver.Count -ne 1 -or $waiver[0].Status -cne $case.Expected) { throw 'Queue failed waiver evidence isolation.' }
    $lineup=@($items | Where-Object Kind -CEQ 'Lineup')[0]
    if ($lineup.Status -cne 'LINEUP HELD') { throw 'Queue lost lineup hold.' }
    for ($i=1;$i -lt $items.Count;$i++) { if ($items[$i].Priority -lt $items[$i-1].Priority) { throw 'Holds are not ordered first.' } }
    $html=ConvertTo-ButlerWeeklyDecisionQueueHtml -Items $items -Team '<script>team & test</script>'
    if ($html.Contains('<script>') -or -not $html.Contains('&lt;script&gt;team &amp; test&lt;/script&gt;') -or
        $html.Contains('READY FOR MANAGER REVIEW') -or $html.Contains('<form') -or -not $html.Contains('Week and source freshness must be checked')) { throw 'Unsafe queue presentation.' }
}
foreach($state in @('AUTOFILL READY','NO CHANGES','NOT REVIEWED','RECHECK LINEUP','unknown')) {
    $items=@(Get-ButlerWeeklyDecisionQueue -LineupState $state)
    if (@($items|Where-Object {$_.Kind -ceq 'Lineup' -and $_.Href -ceq '/matchup/autofill'}).Count -ne 1 -or
        ($items.Reason -join ' ').Contains('keeping your current starters')) { throw 'Saved lineup was promoted to verified advice.' }
}

# Stage the real production transform chain and verify the installed call/slot.
# Keep these fixtures in an explicitly verified disposable directory.
$fixtureRoot=Join-Path ([IO.Path]::GetTempPath()) ('butler-weekly-queue-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($fixtureRoot)
try {
    foreach($name in @('butler-dashboard.ps1','butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $fixtureRoot
    }
    $path=Join-Path $fixtureRoot 'butler-dashboard.ps1'
    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath $path
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if (@($errors).Count) { throw 'Production queue staging failed parse.' }
    foreach($name in @('Get-ButlerWeeklyDecisionQueue','ConvertTo-ButlerWeeklyDecisionQueueHtml','ConvertTo-DashboardHtml')) {
        $found=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($found.Count -ne 1) { throw "Production queue function missing or ambiguous: $name" }
        if ($name -ceq 'ConvertTo-DashboardHtml') {
            $body=$found[0].Extent.Text
            if (-not $body.Contains('-LineupState ([string]$lineupSignalStatus)') -or
                -not $body.Contains('-Actionability ([string]$bf629) -Lineage ([string]$bf631)') -or
                -not $body.Contains('$weeklyDecisionQueueHtml'+[Environment]::NewLine+'$bf907WeekGlanceHtml')) { throw 'Production queue lost governed source binding or position.' }
        }
    }
    # Transform must reject duplicate installation without changing the file.
    $before=[IO.File]::ReadAllText($path);$rejected=$false
    try { & (Join-Path $PSScriptRoot 'butler-weekly-decision-queue-transform.ps1') -DashboardPath $path } catch { $rejected=$true }
    if (-not $rejected -or [IO.File]::ReadAllText($path) -cne $before) { throw 'Duplicate queue installation was not rejected safely.' }
}
finally {
    $resolved=[IO.Path]::GetFullPath($fixtureRoot)
    $temporary=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolved.StartsWith($temporary,[StringComparison]::OrdinalIgnoreCase) -or
        -not ([IO.Path]::GetFileName($resolved)).StartsWith('butler-weekly-queue-')) { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Host 'WEEKLY DECISION QUEUE: PASS (governed states, independent waivers, escaping, actual staging, duplicate rejection).'
