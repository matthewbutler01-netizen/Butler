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
if (-not [string]::IsNullOrWhiteSpace($OutputHtmlPath)) {
    [IO.File]::WriteAllText($OutputHtmlPath,$html,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText([IO.Path]::ChangeExtension($OutputHtmlPath,'.held.html'),$ambiguousHtml,[Text.UTF8Encoding]::new($false))
}
Write-Host 'GENERATED DASHBOARD QUEUE RENDER: PASS (real renderer, synthetic summary, no provider lookups).'
