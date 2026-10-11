Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
if (-not (Test-Path -LiteralPath $workerPath -PathType Leaf)) {
    throw "BF-1028 BLOCKED: request worker missing at $workerPath"
}

$text = [IO.File]::ReadAllText($workerPath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1028 BLOCKED: request worker parse failed: $summary"
}

foreach ($functionName in @('Get-V04AutoPilotApprovalPolicy','Get-V04AutoPilotApprovalQueue','Get-V04AutoPilotHtml')) {
    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-1028 BLOCKED: expected one $functionName function, found $($matches.Count)."
    }
    . ([scriptblock]::Create($matches[0].Extent.Text))
}

function Get-AppCss { return '' }

$policy = Get-V04AutoPilotApprovalPolicy

$blockedWatch = [pscustomobject]@{
    Ready = $true
    Attention = '2 NEED ATTENTION'
    StartSit = 'REFRESH'
    Waivers = 'DO NOT ACT'
    Roster = 'Hard(CORE)-Dynasty | nuke the whales | roster 6'
}
$blockedQueue = Get-V04AutoPilotApprovalQueue -WatchState $blockedWatch -ApprovalPolicy $policy
$blockedHtml = Get-V04AutoPilotHtml -WatchState $blockedWatch -ApprovalPolicy $policy -ApprovalQueue $blockedQueue

foreach ($required in @(
    'PREPARED START/SIT REVIEW',
    'Recommendation packet',
    'BLOCKED',
    'NO RECOMMENDATION PREPARED',
    'PREPARE ONLY',
    'Refresh or resolve the current evidence state before Butler prepares a lineup change.',
    'href="/matchup/autofill">Open review</a>'
)) {
    if ($blockedHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1028 BLOCKED: blocked prepared-review marker missing: $required"
    }
}

# BF-1092: a ready Dashboard is never an all-green weekly watch
# until the independently sourced player recommendation has been checked.
# Unknown card values and forged no-change labels also fail closed.
foreach ($untrustedSignal in @(
    'UNKNOWN SIGNAL', 'NO CHANGE VERIFIED', 'REVIEW', 'START 1 / SIT 1'
)) {
    $untrusted = [pscustomobject]@{
        Ready = $true
        Attention = 'ON TRACK'
        StartSit = $untrustedSignal
        Waivers = 'NO MOVE'
        Roster = 'Test Team'
        EvidenceStatus = 'CURRENT'
    }
    $untrustedQueue = Get-V04AutoPilotApprovalQueue -WatchState $untrusted -ApprovalPolicy $policy
    $untrustedHtml = Get-V04AutoPilotHtml -WatchState $untrusted -ApprovalPolicy $policy -ApprovalQueue $untrustedQueue
    if ($untrustedHtml.IndexOf('<span class="status good">CURRENT SNAPSHOT</span>', [StringComparison]::Ordinal) -ge 0 -or
        $untrustedHtml.IndexOf('<span class="status warn">LINEUP SOURCE NOT VERIFIED</span>', [StringComparison]::Ordinal) -lt 0 -or
        $untrustedHtml.IndexOf('NO RECOMMENDATION PREPARED', [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1092 BLOCKED: unsourced '$untrustedSignal' appeared as a green current watch."
    }
}

$readyWatch = [pscustomobject]@{
    Ready = $true
    Attention = 'READY'
    StartSit = 'START 1 / SIT 1'
    StartSitReviewOutcome = 'VERIFIED_CHANGE'
    StartSitStatusFetchedAtUtc = '2026-10-10T15:00:00Z'
    StartSitProjectionFetchedAtUtc = '2026-10-10T14:59:00Z'
    Waivers = 'NO MOVE'
    Roster = 'Hard(CORE)-Dynasty | nuke the whales | roster 6'
}
# BF-1090: a populated/green Dashboard summary cannot create an
# approval packet until the independent source-gate outcome is present.
$rawWatch = [pscustomobject]@{
    Ready = $true; Attention = 'READY'; StartSit = 'START 1 / SIT 1'
    Waivers = 'NO MOVE'; Roster = 'Test Team'
}
$rawQueue = Get-V04AutoPilotApprovalQueue -WatchState $rawWatch -ApprovalPolicy $policy
if ($rawQueue.StartSitNext.IndexOf('not been independently verified', [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1091 BLOCKED: unverified source queue copy falsely advertised a ready proposal.'
}
$rawHtml = Get-V04AutoPilotHtml -WatchState $rawWatch -ApprovalPolicy $policy -ApprovalQueue $rawQueue
if ($rawHtml.IndexOf('NO RECOMMENDATION PREPARED', [StringComparison]::Ordinal) -lt 0 -or
    $rawHtml.IndexOf('LINEUP SOURCE NOT VERIFIED', [StringComparison]::Ordinal) -lt 0 -or
    $rawHtml.IndexOf('READY FOR MANAGER REVIEW', [StringComparison]::Ordinal) -ge 0 -or
    $rawHtml.IndexOf('<span class="status good">CURRENT SNAPSHOT</span>', [StringComparison]::Ordinal) -ge 0) {
    throw 'BF-1090 BLOCKED: a raw Dashboard card incorrectly passed deep source verification.'
}
$readyQueue = Get-V04AutoPilotApprovalQueue -WatchState $readyWatch -ApprovalPolicy $policy
if ($readyQueue.StartSitNext.IndexOf('verified current lineup proposal', [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1091 BLOCKED: checked proposal no longer offers an appropriate manager review.'
}
$readyHtml = Get-V04AutoPilotHtml -WatchState $readyWatch -ApprovalPolicy $policy -ApprovalQueue $readyQueue

foreach ($required in @(
    'READY FOR MANAGER REVIEW',
    'START 1 / SIT 1',
    'The current Butler lineup signal is ready to review. Manager approval is still required.',
    'PREPARE ONLY'
)) {
    if ($readyHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1028 BLOCKED: ready prepared-review marker missing: $required"
    }
}

# BF-1099: the actual manager page must show an immediately readable
# source-evidence panel. A green watch cannot hide where/when evidence
# was fetched; a held watch cannot display the green proof label.
foreach ($check in @(
    @{ Html = $blockedHtml; State = 'held'; Label = 'SOURCE CHECK REQUIRED'; Time = 'Lineup source fetch times not independently verified for this view.' },
    @{ Html = $rawHtml; State = 'held'; Label = 'SOURCE CHECK REQUIRED'; Time = 'Lineup source fetch times not independently verified for this view.' },
    @{ Html = $readyHtml; State = 'verified-change'; Label = 'SOURCED LINEUP CHANGE'; Time = 'Sleeper status map retrieved 2026-10-10T15:00:00Z UTC' }
)) {
    $rendered = [string]$check.Html
    foreach ($required in @(
        'role="status" aria-label="Start/Sit source evidence"',
        ('data-butler-lineup-proof="' + [string]$check.State + '"'),
        [string]$check.Label,
        [string]$check.Time,
        'Source fetch times are not injury clearance.',
        'href="/matchup/autofill">Review Start/Sit evidence</a>'
    )) {
        if ($rendered.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
            throw "BF-1099 BLOCKED: source evidence panel missing or incorrect: $required"
        }
    }
    if ([regex]::Matches($rendered, 'data-butler-lineup-proof=').Count -ne 1) {
        throw 'BF-1099 BLOCKED: duplicate/missing manager evidence panel.'
    }
}
$keepWatch = [pscustomobject]@{
    Ready = $true
    Attention = 'READY'
    StartSit = 'NO CHANGE VERIFIED'
    StartSitReviewOutcome = 'VERIFIED_NO_CHANGE'
    StartSitProjectionFetchedAtUtc = '2026-10-10T14:59:00Z'
    Waivers = 'NO MOVE'
    Roster = 'Test Team'
}
$keepQueue = Get-V04AutoPilotApprovalQueue -WatchState $keepWatch -ApprovalPolicy $policy
$keepHtml = Get-V04AutoPilotHtml -WatchState $keepWatch -ApprovalPolicy $policy -ApprovalQueue $keepQueue
foreach ($required in @(
    'data-butler-lineup-proof="verified-no-change"',
    'SOURCED NO CHANGE',
    'Projections retrieved 2026-10-10T14:59:00Z UTC.',
    'No swap player-status fetch required because no lineup change was proposed.',
    'NO ACTION REQUIRED'
)) {
    if ($keepHtml.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1099 BLOCKED: sourced no-change UI falsely implies a player-status lookup: $required"
    }
}
# Team/league names are HTML-encoded; presentation must not add writes.
if ($readyHtml -match '<form|type="submit"|onclick=|data-action=') {
    throw 'BF-1099 BLOCKED: source panel introduced write-capable UI.'
}

if ($readyHtml -match '<form|type="submit"|onclick=|data-action=') {
    throw 'BF-1028 BLOCKED: prepared Start/Sit review must remain navigation-only.'
}

$start = $text.IndexOf('# BF-1028: a prepared Start/Sit review packet', [System.StringComparison]::Ordinal)
$end = $text.IndexOf('function Send-HttpResponse {', $start, [System.StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) {
    throw 'BF-1028 BLOCKED: prepared Start/Sit source boundary is missing.'
}
$surface = $text.Substring($start, $end - $start)
if ($surface -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|https://api\.sleeper\.app|Invoke-RestMethod|Invoke-WebRequest') {
    throw 'BF-1028 BLOCKED: prepared Start/Sit review introduced provider, optimizer, or write behavior.'
}

Write-Host 'BF-1028 V0.4 AUTO-PILOT PREPARED START/SIT ACCEPTANCE: PASS'
Write-Host 'Coverage: blocked packet on incomplete evidence, ready packet on governed signal, manager-approval policy, direct review navigation, and no write behavior.'
