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
    StartSitVerifiedChangeCount = 1
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

# BF-1106: verified lineup count is visible in the proof panel,
# manager queue and direct Start/Sit navigation. A false count or a held
# Dashboard card alone must never advertise a sourced lineup change.
if ($readyHtml.IndexOf('1 sourced lineup change need your review.', [StringComparison]::Ordinal) -lt 0 -or
    $readyHtml.IndexOf('verified current lineup proposal: 1 sourced lineup change.', [StringComparison]::Ordinal) -lt 0 -or
    $readyHtml.IndexOf('href="/matchup/autofill">Open the sourced Start/Sit recommendations</a>', [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1106 BLOCKED: Auto-Pilot lost concrete sourced change count or one-click manager review.'
}
if ($rawHtml.IndexOf('sourced lineup change need your review.', [StringComparison]::Ordinal) -ge 0 -or
    $blockedHtml.IndexOf('sourced lineup change need your review.', [StringComparison]::Ordinal) -ge 0) {
    throw 'BF-1106 BLOCKED: unverified or held lineup acquired sourced count.'
}
$multiWatch = [pscustomobject]@{
    Ready = $true; Attention = 'READY'; StartSit = 'REVIEW'
    StartSitReviewOutcome = 'VERIFIED_CHANGE'
    StartSitVerifiedChangeCount = 2
    StartSitStatusFetchedAtUtc = '2026-10-10T15:00:00Z'
    StartSitProjectionFetchedAtUtc = '2026-10-10T14:59:00Z'
    Waivers = 'NO MOVE'; Roster = 'Test Team'
}
$multiQueue = Get-V04AutoPilotApprovalQueue -WatchState $multiWatch -ApprovalPolicy $policy
$multiHtml = Get-V04AutoPilotHtml -WatchState $multiWatch -ApprovalPolicy $policy -ApprovalQueue $multiQueue
if ($multiHtml.IndexOf('2 sourced lineup changes need your review.', [StringComparison]::Ordinal) -lt 0 -or
    $multiHtml.IndexOf('verified current lineup proposal: 2 sourced lineup changes.', [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1106 BLOCKED: independently verified multi-change count or pluralization incorrect.'
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
# BF-1100: source-blocked Auto-Pilot should show the actual known
# reason and a useful safe route, not a generic Refresh instruction.
foreach ($fixture in @(
    @{ Status = 'CURRENT'; Ready = $true; Signal = 'REFRESH'; Reason = 'source-hold'; Message = 'current-week Start/Sit source check did not verify'; Href = '/matchup/autofill'; Label = 'Review Start/Sit evidence' },
    @{ Status = 'STALE'; Ready = $false; Signal = 'REFRESH'; Reason = 'stale-evidence'; Message = 'Dashboard decision evidence needs an update'; Href = '/'; Label = 'Check Dashboard freshness' },
    @{ Status = 'WEEK_MISMATCH'; Ready = $false; Signal = 'DO NOT ACT'; Reason = 'week-mismatch'; Message = 'saved matchup does not match'; Href = '/matchup'; Label = 'Check weekly matchup' },
    @{ Status = 'WEEK_UNVERIFIED'; Ready = $false; Signal = 'UNAVAILABLE'; Reason = 'week-unverified'; Message = 'current NFL week or exact saved matchup could not'; Href = '/matchup'; Label = 'Check weekly matchup' },
    @{ Status = 'UNVERIFIED'; Ready = $false; Signal = 'UNAVAILABLE'; Reason = 'snapshot-unavailable'; Message = 'current manager snapshot is incomplete'; Href = '/'; Label = 'Open Dashboard' }
)) {
    $heldWatch = [pscustomobject]@{
        Ready = [bool]$fixture.Ready
        Attention = 'UNAVAILABLE'
        StartSit = [string]$fixture.Signal
        Waivers = 'UNAVAILABLE'
        Roster = 'Manager tools'
        EvidenceStatus = [string]$fixture.Status
    }
    $heldQueue = Get-V04AutoPilotApprovalQueue -WatchState $heldWatch -ApprovalPolicy $policy
    $heldPage = Get-V04AutoPilotHtml -WatchState $heldWatch -ApprovalPolicy $policy -ApprovalQueue $heldQueue
    foreach ($required in @(
        ('data-butler-lineup-proof="held" data-butler-lineup-reason="' + $fixture.Reason + '"'),
        [string]$fixture.Message,
        ('href="' + $fixture.Href + '">' + $fixture.Label + '</a>'),
        'SOURCE CHECK REQUIRED',
        'NO RECOMMENDATION PREPARED'
    )) {
        if ($heldPage.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
            throw "BF-1100 BLOCKED: held $($fixture.Reason) missing exact status or next step: $required"
        }
    }
    if ($heldPage.IndexOf('SOURCED LINEUP CHANGE', [StringComparison]::Ordinal) -ge 0 -or
        $heldPage.IndexOf('SOURCED NO CHANGE', [StringComparison]::Ordinal) -ge 0) {
        throw "BF-1100 BLOCKED: held $($fixture.Reason) presented a sourced green label."
    }
    if ([regex]::Matches($heldPage, 'data-butler-lineup-reason=').Count -ne 1) {
        throw 'BF-1100 BLOCKED: ambiguous or missing actionable reason.'
    }
}
foreach ($known in @(
    @{ Page = $readyHtml; Reason = 'verified-change'; Snippet = 'A sourced change is ready for your review' },
    @{ Page = $keepHtml; Reason = 'verified-no-change'; Snippet = 'complete projection frame supports keeping the current lineup' }
)) {
    if ($known.Page.IndexOf('data-butler-lineup-reason="' + $known.Reason + '"', [StringComparison]::Ordinal) -lt 0 -or
        $known.Page.IndexOf($known.Snippet, [StringComparison]::Ordinal) -lt 0 -or
        $known.Page.IndexOf('href="/matchup/autofill">Review Start/Sit evidence</a>', [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1100 BLOCKED: sourced result $($known.Reason) lost its evidence/action."
    }
}

# BF-1101: an optional failed deep source GET cannot be confused with
# a verified player status or projection. The display gives useful,
# bounded transport feedback only when the proposal was already held.
foreach ($case in @(
    @{ Issue = 'TIMEOUT'; Expected = 'source-timeout'; Text = 'exceeded its 12-second safety limit' },
    @{ Issue = 'UNAVAILABLE'; Expected = 'source-unavailable'; Text = 'did not return a usable response' }
)) {
    $issueWatch = [pscustomobject]@{
        Ready = $true
        Attention = 'ON TRACK'
        StartSit = 'BLOCKED - CHECK START/SIT'
        Waivers = 'ADD 1 / DROP 1'
        Roster = 'Test Team'
        EvidenceStatus = 'CURRENT'
        StartSitReadIssue = [string]$case.Issue
    }
    $issueQueue = Get-V04AutoPilotApprovalQueue -WatchState $issueWatch -ApprovalPolicy $policy
    $issueHtml = Get-V04AutoPilotHtml -WatchState $issueWatch -ApprovalPolicy $policy -ApprovalQueue $issueQueue
    foreach ($required in @(
        ('data-butler-lineup-proof="held" data-butler-lineup-reason="' + $case.Expected + '"'),
        [string]$case.Text,
        'href="/matchup/autofill">Review Start/Sit evidence</a>',
        'NO RECOMMENDATION PREPARED',
        'SOURCE CHECK REQUIRED'
    )) {
        if ($issueHtml.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
            throw "BF-1101 BLOCKED: $($case.Issue) source issue did not keep the lineup held: $required"
        }
    }
    if ($issueHtml.IndexOf('CURRENT SNAPSHOT', [StringComparison]::Ordinal) -ge 0 -or
        $issueHtml.IndexOf('SOURCED LINEUP CHANGE', [StringComparison]::Ordinal) -ge 0 -or
        $issueHtml.IndexOf('SOURCED NO CHANGE', [StringComparison]::Ordinal) -ge 0) {
        throw 'BF-1101 BLOCKED: failed source fetch was presented as verified.'
    }
}
# A misleading untrusted read annotation cannot override the actual
# independently sourced recommendation or create an extra provider read.
$readyWatch | Add-Member -NotePropertyName StartSitReadIssue -NotePropertyValue 'TIMEOUT' -Force
$sourceStillReady = Get-V04AutoPilotHtml -WatchState $readyWatch -ApprovalPolicy $policy -ApprovalQueue $readyQueue
if ($sourceStillReady.IndexOf('data-butler-lineup-reason="verified-change"', [StringComparison]::Ordinal) -lt 0 -or
    $sourceStillReady.IndexOf('data-butler-lineup-reason="source-timeout"', [StringComparison]::Ordinal) -ge 0) {
    throw 'BF-1101 BLOCKED: transport error label outranked verified source proof.'
}
$expectedTransportContract = @(
    "-TimeoutMs 12000",
    "StartSitReadIssue -NotePropertyValue",
    "[System.Net.WebExceptionStatus]::Timeout",
    "StartSitReadIssue -ceq 'TIMEOUT'",
    "StartSitReadIssue -ceq 'UNAVAILABLE'"
)
foreach ($marker in $expectedTransportContract) {
    if ($text.IndexOf($marker, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1101 BLOCKED: production bounded deep-read wiring absent: $marker"
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
