Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$script=Join-Path $PSScriptRoot 'butler-bf1521-waiver-no-verified-move-transform.ps1'
$root=Join-Path ([IO.Path]::GetTempPath()) ('butler-bf1521-'+[guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    $file=Join-Path $root 'synthetic-dashboard.ps1'
    $fixture=@'
function ConvertTo-WaiverHtml {
    param([string]$State, [int]$CandidateCount, [string]$Actionability='', [string]$Lineage='')
    $current=[pscustomobject]@{State=$State;Bf629=$Actionability;Bf631=$Lineage}
    $counts=[pscustomobject]@{Total=$CandidateCount}
    $waiverDecisionTitle='Waiver review complete; no move recommended'
    $waiverDecisionCopy='No waiver action is needed.'
    $waiverDecisionStatus='NO MOVE'
    $waiverDecisionClass='done'
    $waiverNextActionCopy='Hold'
    $waiverQuickActions=''
    $waiverHistoryLink=''
    if ($State -ceq 'NO_TRANSACTION_TO_ACT_ON') { $waiverDecisionStatus='NO MOVE' }
    if ($State -ceq 'NO_AUDITED_DECISION') { $waiverDecisionStatus='UNAVAILABLE' }
    return @"
<title>$waiverDecisionTitle</title><p>$waiverDecisionCopy</p><span>$waiverDecisionStatus</span><div>$waiverDecisionClass</div><aside>$waiverNextActionCopy</aside><div>$waiverQuickActions$waiverHistoryLink</div>
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
    $eligible=ConvertTo-WaiverHtml -State 'NO_TRANSACTION_TO_ACT_ON' -CandidateCount 4 -Actionability 'NO_TRANSACTION_TO_REVALIDATE' -Lineage 'LATEST_EVIDENCE_LINEAGE_VERIFIED'
    if ($eligible -notmatch 'href="/refresh"' -or
        $eligible -notmatch 'confirmation required') {
        throw 'BF-1521 BLOCKED: governed no-transaction refresh path not discoverable.'
    }
    $superseded=ConvertTo-WaiverHtml -State 'NO_TRANSACTION_TO_ACT_ON' -CandidateCount 4 -Actionability 'NO_TRANSACTION_TO_REVALIDATE' -Lineage 'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED'
    if ($superseded -notmatch 'href="/refresh"') {
        throw 'BF-1521 BLOCKED: authorized superseded lineage refresh path missing.'
    }
    # BF-1529: rollover is an explicit MANUAL check only. The previously
    # audited pair remains blocked, even though a new evidence cycle is offered.
    foreach ($lineage in @('LATEST_EVIDENCE_LINEAGE_VERIFIED',
            'MARKET_LINEAGE_SUPERSEDED','WAIVER_LINEAGE_SUPERSEDED',
            'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED')) {
        $rollover=ConvertTo-WaiverHtml -State 'STALE_DO_NOT_ACT' -CandidateCount 4 -Actionability 'AUDITED_WEEK_ROLLOVER_DO_NOT_ACT' -Lineage $lineage
        if ($rollover -notmatch 'href="/refresh"' -or
            $rollover -notmatch 'Recheck current-week waiver evidence' -or
            $rollover -notmatch 'old move blocked' -or
            $rollover -notmatch 'confirmation required') {
            throw "BF-1529 BLOCKED: manual rollover refresh missing on $lineage"
        }
        if ($rollover -match 'data-butler-auto-waiver=') {
            throw 'BF-1529 BLOCKED: adjacent-week decision armed an automatic refresh.'
        }
    }
    foreach ($params in @(
        @{State='STALE_DO_NOT_ACT';Actionability='AUDITED_WEEK_ROLLOVER_DO_NOT_ACT';Lineage='UNKNOWN'},
        @{State='CURRENT_AND_ACTIONABLE';Actionability='AUDITED_WEEK_ROLLOVER_DO_NOT_ACT';Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'},
        @{State='STALE_DO_NOT_ACT';Actionability='AUDITED_WEEK_MISMATCH_DO_NOT_ACT';Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'},
        @{State='STALE_DO_NOT_ACT';Actionability='AUDITED_WEEK_UNVERIFIED_DO_NOT_ACT';Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'},
        @{State='STALE_DO_NOT_ACT';Actionability='AUDITED_TRANSACTION_PENDING';Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'},
        @{State='STALE_DO_NOT_ACT';Actionability='AUDITED_TRANSACTION_COMPLETE';Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'},
        @{State='NO_TRANSACTION_TO_ACT_ON';Actionability='NO_TRANSACTION_TO_REVALIDATE';Lineage='UNKNOWN'},
        @{State='NO_TRANSACTION_TO_ACT_ON';Actionability='LIVE_ACTIONABLE_VERIFIED';Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'},
        @{State='NO_AUDITED_DECISION';Actionability='NO_TRANSACTION_TO_REVALIDATE';Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'},
        @{State='STALE_DO_NOT_ACT';Actionability='NO_TRANSACTION_TO_REVALIDATE';Lineage='LATEST_EVIDENCE_LINEAGE_VERIFIED'}
    )) {
        $unsafe=ConvertTo-WaiverHtml -State $params.State -CandidateCount 4 -Actionability $params.Actionability -Lineage $params.Lineage
        if ($unsafe -match 'href="/refresh"') {
            throw 'BF-1521 BLOCKED: unverified state was given a refresh capability.'
        }
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
