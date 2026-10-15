# BF-1515 read-only historical framing and manager-friendly refresh titles.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$historyPath=Join-Path $PSScriptRoot 'butler-decision-history.ps1'
$detailPath=Join-Path $PSScriptRoot 'butler-decision-detail.ps1'
$refreshPath=Join-Path $PSScriptRoot 'butler-decision-refresh.ps1'
foreach($path in @($historyPath,$detailPath,$refreshPath)) {
    $tokens=$null; $errors=$null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if(@($errors).Count -ne 0) { throw 'BF-1515 BLOCKED: source parsing failed.' }
}
. $historyPath
. $detailPath
. $refreshPath
function ConvertTo-HtmlText {
    param([AllowNull()][object]$Value)
    return [System.Net.WebUtility]::HtmlEncode([string]$Value)
}
function Get-AppCss { return 'body{}' }
function Get-AppNav { param([string]$Active); return '<nav class="nav"></nav>' }

$id='bf1515-synthetic-league-0001'
$history=[pscustomobject]@{ LeagueId=$id; RosterId=6 }
$entry=[pscustomobject]@{
    Captured='2026-10-13T01:32:00Z'; ProviderSeason='2026'; ProviderStatus='REGULAR'
    ProviderLeg='5'; SelectionState='SELECTED'; IntegrityState='VERIFIED'
    RecommendationState='RECOMMEND_ADD_DROP'; AddSleeperId='11647'; DropSleeperId='11370'
    AuditId='audit-test'; MarketSnapshotId='market'; WaiverSnapshotId='waiver'
}
$explanation=[pscustomobject]@{
    State='EXPLANATION_NOT_CAPTURED'; Captured='none'; ExplanationType='none'; ExplanationText='none'
}
$page=ConvertTo-DecisionDetailHtml -History $history -Entry $entry -Explanation $explanation
foreach($required in @(
    'HISTORY ONLY','Season 2026 / Week 5','not a current approval','Check the current Waiver Board',
    'A recorded add/drop never authorizes a Sleeper transaction.',
    'ADD 11647 / DROP 11370','<div class="target">Decision History</div>','READ ONLY.'
)) { if(-not $page.Contains($required)) { throw "BF-1515 BLOCKED: missing $required" } }
if($page.Contains(('<div class="target">' + $id + '</div>'))) {
    throw 'BF-1515 BLOCKED: raw league id displayed in the normal manager header.'
}
if(-not $page.Contains(('Source Butler league: ' + $id))) {
    throw 'BF-1515 BLOCKED: source league reconciliation is unavailable in advanced evidence.'
}
$entry.ProviderLeg='POSTSEASON'
$other=ConvertTo-DecisionDetailHtml -History $history -Entry $entry -Explanation $explanation
if(-not $other.Contains('Recorded source frame: 2026 / REGULAR / POSTSEASON') -or
   $other.Contains('Week POSTSEASON')) {
    throw 'BF-1515 BLOCKED: non-week source leg called a week.'
}
$token='a'*64
$confirmation=Get-DecisionRefreshConfirmationHtml -LeagueId $id -Token $token
$success=Get-DecisionRefreshSuccessHtml -LeagueId $id -ResultText 'fixture'
foreach($html in @($confirmation,$success)) {
    if($html.Contains($id) -or -not $html.Contains('<div class="target">Butler data refresh</div>')) {
        throw 'BF-1515 BLOCKED: raw league id exposed on refresh page.'
    }
}
if(-not $confirmation.Contains('<form method="post" action="/refresh">') -or
   -not $confirmation.Contains(('name="token" value="' + $token + '"')) -or
   -not $confirmation.Contains('Nothing will be submitted to Sleeper.') -or
   -not $success.Contains('No changes were submitted to Sleeper.')) {
    throw 'BF-1515 BLOCKED: refresh transaction boundary changed.'
}
Write-Host 'BF-1515 MANAGER DECISION CLARITY ACCEPTANCE: PASS'
