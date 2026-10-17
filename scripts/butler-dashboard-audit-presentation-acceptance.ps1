# Exercise the real public presentation and unchanged strict audit parser.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$source=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-app-request-worker-cache.ps1'))
$match=[regex]::Match($source,'(?s)\$presentationFunctions = @''\r?\n(?<block>.*?)\r?\n''@')
if(-not $match.Success) {throw 'Real public presentation block missing'}
. ([scriptblock]::Create($match.Groups['block'].Value))
. (Join-Path $PSScriptRoot 'butler-decision-refresh.ps1')
function New-Fixture {
    param([string]$State='CURRENT_AND_ACTIONABLE',[string]$Extra='',[string]$Gate='LIVE_ACTIONABLE_VERIFIED',[string]$Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED')
    return '<html><body><main><div class="dashboard-summary-row"></div><p>A waiver move is ready to review</p><div class="butler-refresh-contract" hidden><div>Decision state: '+$State+'</div><div>BF-629: '+$Gate+'</div><div>BF-631: '+$Lineage+'</div><div>Governed step count: 11</div>'+$Extra+'</div></main></body></html>'
}
foreach($state in @('CURRENT_AND_ACTIONABLE','STALE_DO_NOT_ACT','NO_TRANSACTION_TO_ACT_ON')) {
    $html=New-Fixture -State $state
    $public=ConvertTo-ButlerUserFacingHtml -Html $html
    foreach($spec in @(@('Decision state:',$state),@('BF-629:','LIVE_ACTIONABLE_VERIFIED'),@('BF-631:','LATEST_EVIDENCE_LINEAGE_VERIFIED'))) {
        $value=Get-DecisionRefreshTechnicalField -Html $public -Label $spec[0]
        if($value -cne $spec[1]) {throw 'Canonical public field was lost or duplicated'}
    }
    if($public -notmatch '<p>A waiver move is ready to review</p>') {throw 'Visible manager copy changed'}
}
foreach($extra in @('<div>Decision state: CURRENT_AND_ACTIONABLE</div>','<div>Decision state: STALE_DO_NOT_ACT</div>','<div>Decision state: Ready to act</div>','<div>BF-629: LIVE_ACTIONABLE_VERIFIED</div>','<div>BF-631: MARKET_LINEAGE_SUPERSEDED</div>')) {
    $public=ConvertTo-ButlerUserFacingHtml -Html (New-Fixture -Extra $extra)
    if($public -match 'class="butler-m2-dashboard-audit"') {throw 'Duplicate or conflicting source emitted canonical audit proof'}
}
foreach($state in @('','Ready to act','CURRENT_AND_ACTIONABLE<script>')) {
    $public=ConvertTo-ButlerUserFacingHtml -Html (New-Fixture -State $state)
    if($public -match 'class="butler-m2-dashboard-audit"') {throw 'Missing or malformed enum emitted canonical audit proof'}
}
Write-Host 'DASHBOARD AUDIT PRESENTATION: PASS (real hidden contract, canonical fields unique, stale retained, duplicate/conflicting/malformed evidence rejected)'
