param([Parameter(Mandatory=$true)][string]$DashboardPath, [string]$OutputHtmlPath='')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($DashboardPath,[ref]$tokens,[ref]$errors)
if (@($errors).Count) { throw 'Queue render input does not parse.' }
$functions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))
# Load definitions only: never execute the app's top-level server loop.
foreach($fn in $functions) { . ([scriptblock]::Create($fn.Extent.Text)) }
$LeagueId='00000000-0000-0000-0000-000000000001'
function Get-Bf808AutoFillSnapshot { param([string]$LeagueKey) return $null }
foreach($fn in @($functions | Where-Object {$_.Name.StartsWith('Invoke-')})) {
    . ([scriptblock]::Create('function '+$fn.Name+' { throw "Unexpected external lookup in synthetic Dashboard render." }'))
}
$summary=@'
Target: SYNTHETIC QUEUE RENDER - no live league evidence
Audit: none
Decision status: STALE_DO_NOT_ACT
BF-629 live actionability: UNVERIFIED
BF-631 evidence lineage: UNVERIFIED
Operator guard: DO NOT ACT
'@
$html=ConvertTo-DashboardHtml -Summary $summary
$queue=[regex]::Matches($html,'(?s)<section class="panel weekly-decision-queue".*?</section>')
if ($queue.Count -ne 1 -or -not $queue[0].Value.Contains('WAIVER HELD') -or
    $queue[0].Value.Contains('RECORDED MOVE TO REVIEW') -or
    $html.IndexOf('aria-label="Weekly decision queue"') -ge $html.IndexOf('<section class="panel week-glance">')) {
    throw 'Generated Dashboard lost the held queue or expected overview order.'
}
$ambiguous=$summary.Replace('STALE_DO_NOT_ACT','CURRENT_AND_ACTIONABLE').Replace('BF-629 live actionability: UNVERIFIED','BF-629 live actionability: LIVE_ACTIONABLE_VERIFIED').Replace('BF-631 evidence lineage: UNVERIFIED','BF-631 evidence lineage: LATEST_EVIDENCE_LINEAGE_VERIFIED')+"`r`nbf-629 live actionability: UNVERIFIED"
$ambiguousHtml=ConvertTo-DashboardHtml -Summary $ambiguous
$ambiguousQueue=[regex]::Match($ambiguousHtml,'(?s)<section class="panel weekly-decision-queue".*?</section>').Value
if (-not $ambiguousQueue.Contains('WAIVER HELD') -or $ambiguousQueue.Contains('RECORDED MOVE TO REVIEW') -or
    -not $ambiguousHtml.Contains('Dashboard evidence needs review') -or
    $ambiguousHtml.Contains('A waiver move is ready to review') -or
    $ambiguousHtml.Contains('butler-refresh-contract') -or
    $ambiguousHtml.Contains('dashboard-summary-card')) {
    throw 'Generated Dashboard used first-match duplicate audit proof.'
}
$missingHtml=ConvertTo-DashboardHtml -Summary 'Target: synthetic incomplete source'
if (-not $missingHtml.Contains('Dashboard evidence needs review') -or $missingHtml.Contains('butler-refresh-contract')) {
    throw 'Missing audit fields reached the normal Dashboard.'
}
$snapshot=[pscustomobject]@{
    Ready=$true; Week=6; Source='<script>fixture & source</script>'; GeneratedUtc='2026-10-17T01:02:03-05:00'
    AvailabilityExclusions=@()
    ProjectionHolds=@([pscustomobject]@{Name='Synthetic starter';Id='101';RosterSlot='STARTER';LineupSlot='WR';Status='Active';InjuryStatus='Questionable'})
}
$attention=Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $snapshot -LineupSignalStatus 'NEEDS ATTENTION'
foreach($required in @('Saved Lineup Review: week 6','source: &lt;script&gt;fixture &amp; source&lt;/script&gt;',
    'generated: 2026-10-17 06:02:03 UTC','not a live injury or starter check','1 starter needs weekly review')) {
    if (-not $attention.Contains($required)) { throw "Saved lineup context missing: $required" }
}
if ($attention.Contains('<script>') -or [regex]::Matches($attention,'class="meta saved-lineup-context"').Count -ne 1) { throw 'Saved context unsafe or duplicated.' }
foreach($value in @('', '2026-10-17', '2026-10-17T06:02:03', '<script>time</script>', '2026-99-99T06:02:03Z')) {
    $snapshot.GeneratedUtc=$value
    if (-not (Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $snapshot -LineupSignalStatus 'NEEDS ATTENTION').Contains('generated: unknown')) { throw 'Ambiguous or invalid snapshot time received a timestamp.' }
}
$missing=[pscustomobject]@{Ready=$true;AvailabilityExclusions=@();ProjectionHolds=$snapshot.ProjectionHolds}
$unknown=Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $missing -LineupSignalStatus 'NEEDS ATTENTION'
if (-not $unknown.Contains('week unknown; source: unknown; generated: unknown')) { throw 'Missing snapshot metadata invented context.' }
foreach($week in @(0,19,'bad')) {
    $snapshot.Week=$week
    if (-not (Get-ButlerSavedLineupContextHtml -Snapshot $snapshot).Contains('week unknown')) { throw 'Invalid saved week received a regular-season label.' }
}
foreach($state in @('REFRESH AUTOFILL','EVIDENCE GAP','NOT REVIEWED')) {
    if (-not [string]::IsNullOrEmpty((Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $snapshot -LineupSignalStatus $state))) { throw 'Saved metadata bypassed the existing lineup hold gate.' }
}
if (-not [string]::IsNullOrWhiteSpace($OutputHtmlPath)) {
    [IO.File]::WriteAllText($OutputHtmlPath,$html,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText([IO.Path]::ChangeExtension($OutputHtmlPath,'.held.html'),$ambiguousHtml,[Text.UTF8Encoding]::new($false))
}
Write-Host 'GENERATED DASHBOARD QUEUE RENDER: PASS (real renderer, saved lineup metadata/unknowns/escaping/gates, synthetic evidence, no provider lookups).'
