Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$script=Join-Path $PSScriptRoot 'butler-bf1521-waiver-no-verified-move-transform.ps1'
$root=Join-Path ([IO.Path]::GetTempPath()) ('butler-bf1521-'+[guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    $file=Join-Path $root 'synthetic-dashboard.ps1'
    $fixture=@'
function ConvertTo-WaiverHtml {
    param([string]$State, [int]$CandidateCount)
    $current=[pscustomobject]@{State=$State}
    $counts=[pscustomobject]@{Total=$CandidateCount}
    $waiverDecisionTitle='Waiver review complete; no move recommended'
    $waiverDecisionCopy='No waiver action is needed.'
    $waiverDecisionStatus='NO MOVE'
    $waiverDecisionClass='done'
    $waiverNextActionCopy='Hold'
    if ($State -ceq 'NO_TRANSACTION_TO_ACT_ON') { $waiverDecisionStatus='NO MOVE' }
    if ($State -ceq 'NO_AUDITED_DECISION') { $waiverDecisionStatus='UNAVAILABLE' }
    return @"
<title>$waiverDecisionTitle</title><p>$waiverDecisionCopy</p><span>$waiverDecisionStatus</span><div>$waiverDecisionClass</div><aside>$waiverNextActionCopy</aside>
"@
}
function ConvertTo-WaiverCandidateDetailHtml {}
'@
    [IO.File]::WriteAllText($file,$fixture)
    & $script -DashboardPath $file
    . $file
    $noCandidates=ConvertTo-WaiverHtml -State 'NO_TRANSACTION_TO_ACT_ON' -CandidateCount 0
    foreach ($marker in @('NO VERIFIED MOVE','no authorized review candidates','does not prove','governed evidence review')) {
        if (-not $noCandidates.Contains($marker)) { throw "BF-1521 BLOCKED: missing empty shortlist reason $marker" }
    }
    $candidatePool=ConvertTo-WaiverHtml -State 'NO_TRANSACTION_TO_ACT_ON' -CandidateCount 4
    foreach ($marker in @('NO VERIFIED MOVE','context, not a ranking','current-week free-agent search')) {
        if (-not $candidatePool.Contains($marker)) { throw "BF-1521 BLOCKED: missing shortlist context $marker" }
    }
    $noAudit=ConvertTo-WaiverHtml -State 'NO_AUDITED_DECISION' -CandidateCount 4
    if ($noAudit -notmatch 'NOT VERIFIED' -or $noAudit -notmatch 'No verified waiver decision available' -or
        $noAudit -notmatch 'Do not act on old add/drop advice') {
        throw 'BF-1521 BLOCKED: missing audit should not be shown as complete.'
    }
    $realAction=ConvertTo-WaiverHtml -State 'CURRENT_AND_ACTIONABLE' -CandidateCount 4
    if ($realAction -notmatch 'NO MOVE' -or $realAction -match 'NO VERIFIED MOVE') {
        throw 'BF-1521 BLOCKED: other governed states should not be modified.'
    }
    $tokens=$null; $parseErrors=$null
    [void][Management.Automation.Language.Parser]::ParseFile($file,[ref]$tokens,[ref]$parseErrors)
    if (@($parseErrors).Count -gt 0) { throw 'BF-1521 BLOCKED: fixture final parse errors.' }
    Write-Host 'BF-1521 WAIVER NO VERIFIED MOVE ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
