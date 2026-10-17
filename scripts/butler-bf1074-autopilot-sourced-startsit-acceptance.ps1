Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$source = [IO.File]::ReadAllText($path)
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) { throw 'BF-1074 BLOCKED: worker parser error.' }
foreach ($name in @('Get-V04AutoPilotExactPageFrame','Limit-V04AutoPilotToSourcedStartSit')) {
    $functions = @($ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name
    }, $true))
    if ($functions.Count -ne 1) { throw "BF-1074 BLOCKED: $name missing/duplicate." }
    . ([scriptblock]::Create($functions[0].Extent.Text))
}
$clock = [DateTimeOffset]::Parse('2026-10-10T15:02:00Z')
$matchup = '<section class="panel butler-live-week-status" role="status" data-butler-week-state="MATCH" data-butler-week-season="2026" data-butler-week-number="5"></section><div class="target" data-butler-matchup-season="2026">Our &amp; League &middot; Week 5</div>'
$proof = '<p class="meta butler-startsit-source-proof" role="status">1 proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time. Full scoreable projection coverage; 0 player holds. no Sleeper move was submitted. Sources: projection snapshot retrieved 2026-10-10T14:59:00.123456789Z UTC; swap players status map retrieved 2026-10-10T15:00:00Z UTC.</p>'
$startSit = '<style>.start-sit-blocker{border:1px solid red}</style>' + $matchup + '<section class="panel recommendation-panel start-sit-assistant">' + $proof + '</section>'
function Test-Packet {
    param([string]$Page,[string]$OtherPage,[string]$DashboardSignal = 'START 1 / SIT 1')
    $watch = [pscustomobject]@{
        Ready = $true; StartSit = $DashboardSignal
        Waivers = 'ADD 1 / DROP 1'; EvidenceStatus = 'CURRENT'
    }
    $review = Limit-V04AutoPilotToSourcedStartSit -WatchState $watch -MatchupHtml $OtherPage -StartSitHtml $Page -CheckedAtUtc $clock
    if (-not $review.Ready -or $review.Waivers -cne 'ADD 1 / DROP 1' -or
        $review.EvidenceStatus -cne 'CURRENT') {
        throw 'BF-1074 BLOCKED: lineup source failure also blocked independent waiver/manager evidence.'
    }
    return [string]$review.StartSit
}
if ((Test-Packet -Page $startSit -OtherPage $matchup) -cne 'START 1 / SIT 1') {
    throw 'BF-1074 BLOCKED: valid recent player-status evidence was rejected.'
}
# BF-1106: only the independently checked, recent, zero-hold Start/Sit
# source may give Auto-Pilot an exact proposed change count. Do not infer
# this from a generic Dashboard START 1 / SIT 1 summary, and never
# attach a manager-count to an evidence hold.
$exactWatch = [pscustomobject]@{
    Ready = $true; StartSit = 'START 1 / SIT 1'
    Waivers = 'ADD 1 / DROP 1'; EvidenceStatus = 'CURRENT'
}
$exact = Limit-V04AutoPilotToSourcedStartSit -WatchState $exactWatch -MatchupHtml $matchup -StartSitHtml $startSit -CheckedAtUtc $clock
if ([string]$exact.StartSitReviewOutcome -cne 'VERIFIED_CHANGE' -or
    [int]$exact.StartSitVerifiedChangeCount -ne 1) {
    throw 'BF-1106 BLOCKED: single independently verified change count lost.'
}
$twoPage = $startSit.Replace('1 proposed lineup changes', '2 proposed lineup changes')
$twoWatch = [pscustomobject]@{
    Ready = $true; StartSit = 'REVIEW'
    Waivers = 'ADD 1 / DROP 1'; EvidenceStatus = 'CURRENT'
}
$two = Limit-V04AutoPilotToSourcedStartSit -WatchState $twoWatch -MatchupHtml $matchup -StartSitHtml $twoPage -CheckedAtUtc $clock
if ([string]$two.StartSitReviewOutcome -cne 'VERIFIED_CHANGE' -or
    [int]$two.StartSitVerifiedChangeCount -ne 2) {
    throw 'BF-1106 BLOCKED: sourced multi-change count not propagated.'
}
foreach ($badSource in @(
    ($startSit.Replace('0 player holds', '1 player hold')),
    ($startSit.Replace('2026-10-10T15:00:00Z', '2026-10-10T14:30:00Z')),
    ($startSit.Replace('1 proposed lineup changes', '1 proposed lineup changes. 2 proposed lineup changes'))
)) {
    $badWatch = [pscustomobject]@{
        Ready = $true; StartSit = 'START 1 / SIT 1'
        Waivers = 'ADD 1 / DROP 1'; EvidenceStatus = 'CURRENT'
    }
    $heldCount = Limit-V04AutoPilotToSourcedStartSit -WatchState $badWatch -MatchupHtml $matchup -StartSitHtml $badSource -CheckedAtUtc $clock
    if ([string]$heldCount.StartSit -cne 'BLOCKED - CHECK START/SIT' -or
        $null -ne $heldCount.PSObject.Properties['StartSitVerifiedChangeCount']) {
        throw 'BF-1106 BLOCKED: a held/ambiguous source supplied a lineup change count.'
    }
}

# BF-1089: a valid changed-source page cannot override a separately
# audited Dashboard that says no change, not reviewed, or unavailable.
foreach ($contradiction in @('NO CHANGE', 'NOT REVIEWED', 'REFRESH', 'CHECK EVIDENCE', 'DO NOT ACT', 'unknown')) {
    if ((Test-Packet -Page $startSit -OtherPage $matchup -DashboardSignal $contradiction) -cne 'BLOCKED - CHECK START/SIT') {
        throw "BF-1089 BLOCKED: contradictory Dashboard state '$contradiction' authorized a changed lineup."
    }
}
if ((Test-Packet -Page $startSit -OtherPage $matchup -DashboardSignal 'REVIEW') -cne 'REVIEW') {
    throw 'BF-1089 BLOCKED: real Dashboard REVIEW signal with valid changed lineup was withheld.'
}

foreach ($bad in @(
    '',
    $matchup,
    ($startSit.Replace('Our &amp; League', 'Other league')),
    ($startSit.Replace('data-butler-week-number="5"', 'data-butler-week-number="6"').Replace('Week 5', 'Week 6')),
    ($startSit.Replace('data-butler-week-state="MATCH"', 'data-butler-week-state="UNVERIFIED"')),
    ($startSit.Replace('data-butler-matchup-season="2026"', 'data-butler-matchup-season="2025"')),
    ($startSit + $startSit),
    ($startSit.Replace($proof, '')),
    # BF-1085: player holds are not separate manager moves but they MUST
    # prevent a claimed fully sourced / ready-to-review Auto-Pilot lineup.
    ($startSit.Replace('Full scoreable projection coverage', 'Partial projection coverage; missing projections are not zeros')),
    ($startSit.Replace('Full scoreable projection coverage', 'UNVERIFIED scoreable projection coverage')),
    # BF-1087: zero/ambiguous change counts or contradictory claims
    # cannot turn a held/no-change source into a prepared lineup action.
    ($startSit.Replace('1 proposed lineup changes', '0 proposed lineup changes')),
    ($startSit.Replace('1 proposed lineup changes', '01 proposed lineup changes')),
    ($startSit.Replace('1 proposed lineup changes', '1 proposed lineup changes. 2 proposed lineup changes')),
    ($startSit.Replace('1 proposed lineup changes', '2 proposed lineup changes. 1 proposed lineup changes')),
    ($startSit.Replace('0 player holds.', '0 player holds. 2 player holds.')),
    ($startSit.Replace('0 player holds.', '10 player holds. 0 player holds.')),
    ($startSit.Replace('Full scoreable projection coverage;', 'Full scoreable projection coverage; Full scoreable projection coverage;')),
    ($startSit.Replace('0 player holds', '1 player hold')),
    ($startSit.Replace('0 player holds', '2 player holds')),
    ($startSit.Replace('0 player holds', '10 player holds')),
    ($startSit.Replace('0 player holds', '100 player holds')),
    ($startSit.Replace('0 player holds', 'player holds unverified')),
    ($startSit.Replace('no Sleeper move was submitted', 'Transaction submitted')),
    ($startSit.Replace('proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time', 'No lineup change is ready')),
    ($startSit.Replace('2026-10-10T15:00:00Z', 'UNVERIFIED')),
    ($startSit.Replace('2026-10-10T15:00:00Z', '2026-10-10T14:30:00Z')),
    ($startSit.Replace('2026-10-10T15:00:00Z', '2026-10-10T15:04:00Z')),
    ($startSit.Replace('2026-10-10T15:00:00Z', '2026-02-30T15:00:00Z')),
    ($startSit.Replace('2026-10-10T14:59:00.123456789Z', '2026-10-10T12:30:00Z')),
    ($startSit.Replace('2026-10-10T14:59:00.123456789Z', 'yesterday')),
    ($startSit.Replace('</section>', $proof + '</section>')),
    ($startSit.Replace('recorded fetch time', 'review time')),
    ($startSit.Replace($proof, '<div class="callout callout-danger start-sit-blocker">BF-1066 BLOCKED</div>' + $proof))
)) {
    if ((Test-Packet -Page $bad -OtherPage $matchup) -cne 'BLOCKED - CHECK START/SIT') {
        throw 'BF-1074 BLOCKED: unverified/duplicate/mismatched/expired Start/Sit proof authorized prepared lineup.'
    }
}
if ((Test-Packet -Page $startSit -OtherPage $matchup.Replace('Our &amp; League','Other league')) -cne 'BLOCKED - CHECK START/SIT') {
    throw 'BF-1074 BLOCKED: two different leagues were considered aligned.'
}
$held = [pscustomobject]@{ Ready = $false; StartSit = 'DO NOT ACT' }
$result = Limit-V04AutoPilotToSourcedStartSit -WatchState $held -MatchupHtml $matchup -StartSitHtml $startSit -CheckedAtUtc $clock
if ($result.Ready -or $result.StartSit -cne 'DO NOT ACT') {
    throw 'BF-1074 BLOCKED: held week was upgraded by a lineup preview.'
}
$begin = $source.IndexOf("if ($([char]36)path -eq '/autopilot') {", [StringComparison]::Ordinal)
$end = $source.IndexOf("if ($([char]36)path -eq '/history') {", $begin, [StringComparison]::Ordinal)
if ($begin -lt 0 -or $end -le $begin) { throw 'BF-1074 BLOCKED: Auto-Pilot route missing.' }
$route = $source.Substring($begin, $end - $begin)
foreach ($required in @(
    'if ([bool]$watchState.Ready)',
    'Invoke-AppCoreGet -Port $InnerPort -RequestTarget ''/matchup/autofill'' -TimeoutMs 12000',
    'Limit-V04AutoPilotToSourcedStartSit -WatchState $watchState',
    '-MatchupHtml ([string]$resolvedWatch.MatchupHtml)',
    '-StartSitHtml $startSitHtmlForReview',
    'Get-V04AutoPilotApprovalQueue -WatchState $watchState'
)) {
    if ($route.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1074 BLOCKED: route lacks independent Start/Sit gate: $required"
    }
}
if ($route -match 'Sleeper.*(submit|transaction)|Set-Faab|Method\s*=\s*POST') {
    throw 'BF-1074 BLOCKED: new Auto-Pilot route added a Sleeper transaction.'
}
# BF-1075: the manager-facing card must explain why Start/Sit is held
# without incorrectly prescribing a blind Refresh or blocking waivers.
foreach ($name in @('Get-V04AutoPilotApprovalQueue','Get-V04AutoPilotCss','Get-V04AutoPilotHtml')) {
    $found = @($ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name
    }, $true))
    if ($found.Count -ne 1) { throw "BF-1075 BLOCKED: $name missing or ambiguous." }
    . ([scriptblock]::Create($found[0].Extent.Text))
}
function Get-AppCss { return '' }
$policy = [pscustomobject]@{
    Mode = 'MANAGER APPROVAL REQUIRED'
    StartSit = 'PREPARE ONLY'
    Waivers = 'RECOMMEND ONLY'
    Trades = 'NEVER AUTO-EXECUTE'
    HardBlockers = @('Evidence gap')
}
$heldView = [pscustomobject]@{
    Ready = $true
    StartSit = 'BLOCKED - CHECK START/SIT'
    Waivers = 'ADD 1 / DROP 1'
    Attention = '2 NEED ATTENTION'
    EvidenceStatus = 'CURRENT'
    Roster = 'Test Team'
}
$queue = Get-V04AutoPilotApprovalQueue -WatchState $heldView -ApprovalPolicy $policy
$html = Get-V04AutoPilotHtml -WatchState $heldView -ApprovalPolicy $policy -ApprovalQueue $queue
if ($queue.StartSitNext -notmatch 'Open Start/Sit Assistant' -or
    $queue.StartSitNext -notmatch 'pressing Refresh is not a substitute' -or
    $queue.WaiverNext -notmatch 'Review the current Waiver Board recommendation' -or
    $html -notmatch 'LINEUP SOURCE NOT VERIFIED' -or
    $html -notmatch 'NO RECOMMENDATION PREPARED' -or
    $html -notmatch 'BLOCKED - CHECK START/SIT' -or
    $html -match 'READY FOR MANAGER REVIEW' -or
    $html -match 'CURRENT SNAPSHOT') {
    throw 'BF-1075 BLOCKED: incomplete Start/Sit source was incorrectly advertised as a current prepared packet.'
}
# A matching page can explain its observed gaps without certifying freshness.
$gapPage = $startSit.Replace('Full scoreable projection coverage', 'Partial projection coverage; missing projections are not zeros').Replace('0 player holds', '3 player holds')
foreach ($gapCase in @(
    @{ Page = $gapPage; Matchup = $matchup; Expected = $true },
    @{ Page = $gapPage + $proof; Matchup = $matchup; Expected = $false },
    @{ Page = $gapPage.Replace('3 player holds', '3 player holds. 2 player holds'); Matchup = $matchup; Expected = $false },
    @{ Page = $gapPage.Replace('Partial projection coverage;', 'Full scoreable projection coverage. Partial projection coverage;'); Matchup = $matchup; Expected = $false },
    @{ Page = $gapPage; Matchup = $matchup.Replace('week-number="5"', 'week-number="6"'); Expected = $false }
)) {
    $gapWatch = [pscustomobject]@{
        Ready = $true; StartSit = 'REVIEW'; Waivers = 'ADD 1 / DROP 1'
        Attention = 'ON TRACK'; EvidenceStatus = 'CURRENT'; Roster = 'Test Team'
        StartSitObservedEvidenceGap = 'PARTIAL_WITH_HOLDS'
    }
    $gapWatch = Limit-V04AutoPilotToSourcedStartSit -WatchState $gapWatch -MatchupHtml $gapCase.Matchup -StartSitHtml $gapCase.Page -CheckedAtUtc $clock
    $gapQueue = Get-V04AutoPilotApprovalQueue -WatchState $gapWatch -ApprovalPolicy $policy
    $gapHtml = Get-V04AutoPilotHtml -WatchState $gapWatch -ApprovalPolicy $policy -ApprovalQueue $gapQueue
    if (($gapHtml.Contains('reports partial projection coverage and player holds')) -ne $gapCase.Expected -or
        $gapWatch.StartSit -cne 'BLOCKED - CHECK START/SIT' -or
        $gapHtml.Contains('READY FOR MANAGER REVIEW') -or
        $null -ne $gapWatch.PSObject.Properties['StartSitProjectionFetchedAtUtc'] -or
        $gapWatch.Waivers -cne 'ADD 1 / DROP 1') {
        throw 'Observed evidence gaps bypassed the hold or survived ambiguous/mismatched proof.'
    }
}
# BF-1083: a current Dashboard audit is not blanket approval for a
# deliberately held Start/Sit signal. Status needs warning contrast while
# an independently actionable waiver can still be reviewed.
foreach ($heldSignal in @('REFRESH','DO NOT ACT','HOLD FOR EVIDENCE','BLOCKED BY MANAGER','UNAVAILABLE')) {
    $heldView.StartSit = $heldSignal
    $queue = Get-V04AutoPilotApprovalQueue -WatchState $heldView -ApprovalPolicy $policy
    $html = Get-V04AutoPilotHtml -WatchState $heldView -ApprovalPolicy $policy -ApprovalQueue $queue
    if ($html.IndexOf('<span class="status warn">LINEUP ACTION HELD</span>',
            [StringComparison]::Ordinal) -lt 0 -or
        $html.IndexOf('NO RECOMMENDATION PREPARED', [StringComparison]::Ordinal) -lt 0 -or
        $html.IndexOf('READY FOR MANAGER REVIEW', [StringComparison]::Ordinal) -ge 0 -or
        $html.IndexOf('<span class="status good">CURRENT SNAPSHOT</span>',
            [StringComparison]::Ordinal) -ge 0 -or
        $queue.WaiverNext -notmatch 'Review the current Waiver Board recommendation') {
        throw "BF-1083 BLOCKED: held lineup '$heldSignal' displayed a green actionable Auto-Pilot."
    }
    if ($heldSignal -ceq 'DO NOT ACT' -and
        $queue.StartSitNext -notmatch 'explicitly holds action') {
        throw 'BF-1083 BLOCKED: deliberate DO NOT ACT was described as a prepared swap.'
    }
}
$heldView.StartSit = 'START 1 / SIT 1'
# BF-1090: the previous raw summary is deliberately insufficient. Pass
# through the exact sourced gate before expecting a ready proposal.
$heldView = Limit-V04AutoPilotToSourcedStartSit -WatchState $heldView -MatchupHtml $matchup -StartSitHtml $startSit -CheckedAtUtc $clock
if ([string]$heldView.StartSitReviewOutcome -cne 'VERIFIED_CHANGE') {
    throw 'BF-1090 BLOCKED: verified changed recommendation has no typed outcome.'
}
$queue = Get-V04AutoPilotApprovalQueue -WatchState $heldView -ApprovalPolicy $policy
$html = Get-V04AutoPilotHtml -WatchState $heldView -ApprovalPolicy $policy -ApprovalQueue $queue
if ($html -notmatch 'CURRENT SNAPSHOT' -or
    $html -notmatch 'READY FOR MANAGER REVIEW' -or
    $html -match 'LINEUP SOURCE NOT VERIFIED') {
    throw 'BF-1075 BLOCKED: supported Start/Sit source never displayed a ready review.'
}

# BF-1086: on an accepted source read, only the two actual
# calendar- and age-verified timestamps may appear in Auto-Pilot.
$timeWatch = [pscustomobject]@{
    Ready = $true
    StartSit = 'START 1 / SIT 1'
    Waivers = 'ADD 1 / DROP 1'
    Attention = '2 NEED ATTENTION'
    Roster = 'Test Team'
    EvidenceStatus = 'CURRENT'
}
$timeWatch = Limit-V04AutoPilotToSourcedStartSit -WatchState $timeWatch -MatchupHtml $matchup -StartSitHtml $startSit -CheckedAtUtc $clock
if ($timeWatch.StartSit -cne 'START 1 / SIT 1' -or
    $timeWatch.StartSitStatusFetchedAtUtc -cne '2026-10-10T15:00:00Z' -or
    $timeWatch.StartSitProjectionFetchedAtUtc -cne '2026-10-10T14:59:00.123456789Z') {
    throw 'BF-1086 BLOCKED: exact sourced timestamps were missing or replaced by the local clock.'
}
$timeQueue = Get-V04AutoPilotApprovalQueue -WatchState $timeWatch -ApprovalPolicy $policy
$timedHtml = Get-V04AutoPilotHtml -WatchState $timeWatch -ApprovalPolicy $policy -ApprovalQueue $timeQueue
foreach ($marker in @(
    'Sleeper status map retrieved 2026-10-10T15:00:00Z UTC',
    'projections retrieved 2026-10-10T14:59:00.123456789Z UTC',
    'These are fetch times, not injury report publication times.'
)) {
    if ($timedHtml.IndexOf($marker, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1086 BLOCKED: current Start/Sit source observation not visible: $marker"
    }
}
$heldTimeWatch = [pscustomobject]@{
    Ready = $true
    StartSit = 'START 1 / SIT 1'
    Waivers = 'ADD 1 / DROP 1'
    Attention = '2 NEED ATTENTION'
    Roster = 'Test Team'
    EvidenceStatus = 'CURRENT'
}
$heldTimeWatch = Limit-V04AutoPilotToSourcedStartSit -WatchState $heldTimeWatch -MatchupHtml $matchup -StartSitHtml ($startSit.Replace('0 player holds','10 player holds')) -CheckedAtUtc $clock
if ($heldTimeWatch.StartSit -cne 'BLOCKED - CHECK START/SIT' -or
    $null -ne $heldTimeWatch.PSObject.Properties['StartSitStatusFetchedAtUtc'] -or
    $null -ne $heldTimeWatch.PSObject.Properties['StartSitProjectionFetchedAtUtc']) {
    throw 'BF-1086 BLOCKED: held lineup retained apparently verified source retrieval dates.'
}
$heldTimeQueue = Get-V04AutoPilotApprovalQueue -WatchState $heldTimeWatch -ApprovalPolicy $policy
$heldTimedHtml = Get-V04AutoPilotHtml -WatchState $heldTimeWatch -ApprovalPolicy $policy -ApprovalQueue $heldTimeQueue
if ($heldTimedHtml.IndexOf('Lineup source fetch times not independently verified', [StringComparison]::Ordinal) -lt 0 -or
    $heldTimedHtml.IndexOf('Sleeper status map retrieved 2026-10-10T15:00:00Z', [StringComparison]::Ordinal) -ge 0) {
    throw 'BF-1086 BLOCKED: a blocked Auto-Pilot page claimed real source retrieval observations.'
}

# BF-1088: a fully sourced no-change result is healthy but requires NO
# approval packet, and must not invent a player status fetch for a non-swap.
$noChangeProof = '<p class="meta butler-startsit-source-proof" role="status">No lineup change is ready. Full scoreable projection coverage; 0 player holds. If availability or a player''s projection cannot be verified, Butler holds the move rather than guessing. Recheck before kickoff. Sources: projection snapshot retrieved 2026-10-10T14:59:00.123456789Z UTC; exact swap status fetch time not verified. This records a source fetch, not an injury report publication time.</p>'
$noChangeHtml = $startSit.Replace($proof, $noChangeProof)
function Test-NoChangePacket {
    param([string]$Page, [string]$DashboardSignal = 'NO CHANGE')
    $review = [pscustomobject]@{
        Ready = $true; StartSit = $DashboardSignal; Waivers = 'ADD 1 / DROP 1'
        Attention = 'ON TRACK'; Roster = 'Test Team'; EvidenceStatus = 'CURRENT'
    }
    return (Limit-V04AutoPilotToSourcedStartSit -WatchState $review -MatchupHtml $matchup -StartSitHtml $Page -CheckedAtUtc $clock)
}
$verifiedNoChange = Test-NoChangePacket -Page $noChangeHtml
if ($verifiedNoChange.StartSit -cne 'NO CHANGE VERIFIED' -or
    $verifiedNoChange.StartSitReviewOutcome -cne 'VERIFIED_NO_CHANGE' -or
    $verifiedNoChange.StartSitProjectionFetchedAtUtc -cne '2026-10-10T14:59:00.123456789Z' -or
    $null -ne $verifiedNoChange.PSObject.Properties['StartSitStatusFetchedAtUtc'] -or
    $verifiedNoChange.Waivers -cne 'ADD 1 / DROP 1') {
    throw 'BF-1088 BLOCKED: exact recent no-change projection was not handled independently of waiver or swap-status data.'
}
$noChangeQueue = Get-V04AutoPilotApprovalQueue -WatchState $verifiedNoChange -ApprovalPolicy $policy
$noChangeRendered = Get-V04AutoPilotHtml -WatchState $verifiedNoChange -ApprovalPolicy $policy -ApprovalQueue $noChangeQueue
foreach ($marker in @(
    'NO ACTION REQUIRED', 'KEEP CURRENT LINEUP',
    'NO CHANGE VERIFIED', 'No lineup swap is recommended',
    'Projections retrieved 2026-10-10T14:59:00.123456789Z UTC'
)) {
    if ($noChangeRendered.IndexOf($marker, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1088 BLOCKED: verified no-change missing from rendered Auto-Pilot: $marker"
    }
}
if ($noChangeRendered.IndexOf('READY FOR MANAGER REVIEW', [StringComparison]::Ordinal) -ge 0 -or
    $noChangeRendered.IndexOf('Sleeper status map retrieved', [StringComparison]::Ordinal) -ge 0 -or
    $noChangeRendered.IndexOf('LINEUP SOURCE NOT VERIFIED', [StringComparison]::Ordinal) -ge 0) {
    throw 'BF-1088 BLOCKED: keeping lineup incorrectly created an actionable swap or fake status-map check.'
}
foreach ($bad in @(
    $noChangeHtml.Replace('0 player holds.', '1 player hold.'),
    $noChangeHtml.Replace('0 player holds.', '10 player holds. 0 player holds.'),
    $noChangeHtml.Replace('Full scoreable projection coverage', 'Partial projection coverage'),
    $noChangeHtml.Replace('Full scoreable projection coverage;', 'Full scoreable projection coverage; Full scoreable projection coverage;'),
    $noChangeHtml.Replace('No lineup change is ready.', '0 proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time.'),
    $noChangeHtml.Replace('Sources:', '1 proposed lineup changes. Sources:'),
    $noChangeHtml.Replace('2026-10-10T14:59:00.123456789Z', '2026-10-10T12:30:00Z'),
    $noChangeHtml.Replace('2026-10-10T14:59:00.123456789Z', '2026-10-10T15:10:00Z'),
    $noChangeHtml.Replace('2026-10-10T14:59:00.123456789Z', '2026-02-30T14:59:00Z'),
    $noChangeHtml.Replace('2026-10-10T14:59:00.123456789Z', 'UNVERIFIED'),
    $noChangeHtml.Replace('Sources:', 'Sources: projection snapshot retrieved 2026-10-10T14:59:00.123456789Z UTC;'),
    $noChangeHtml.Replace('data-butler-week-number="5"', 'data-butler-week-number="6"')
)) {
    $blockedNoChange = Test-NoChangePacket -Page $bad
    if ($blockedNoChange.StartSit -cne 'BLOCKED - CHECK START/SIT' -or
        $null -ne $blockedNoChange.PSObject.Properties['StartSitReviewOutcome'] -or
        $blockedNoChange.Waivers -cne 'ADD 1 / DROP 1') {
        throw 'BF-1088 BLOCKED: conflicting, stale, or forged no-change evidence was labeled verified.'
    }
}
$contradictedNoChange = Test-NoChangePacket -Page $noChangeHtml -DashboardSignal 'START 1 / SIT 1'
if ($contradictedNoChange.StartSit -cne 'BLOCKED - CHECK START/SIT') {
    throw 'BF-1088 BLOCKED: Dashboard swap request was overwritten by a contradictory zero-change proof.'
}

# BF-1089: a stand-alone, unaudited NO CHANGE or NOT REVIEWED card
# cannot render an actionable Start/Sit packet merely because Ready is true.
foreach ($unverified in @('NO CHANGE','NOT REVIEWED')) {
    $syntheticWatch = [pscustomobject]@{
        Ready = $true; StartSit = $unverified; Waivers = 'NO MOVE'
        Attention = 'ON TRACK'; Roster = 'Test Team'; EvidenceStatus = 'CURRENT'
    }
    $syntheticQueue = Get-V04AutoPilotApprovalQueue -WatchState $syntheticWatch -ApprovalPolicy $policy
    $syntheticHtml = Get-V04AutoPilotHtml -WatchState $syntheticWatch -ApprovalPolicy $policy -ApprovalQueue $syntheticQueue
    if ($syntheticHtml.IndexOf('READY FOR MANAGER REVIEW',[StringComparison]::Ordinal) -ge 0 -or
        $syntheticHtml.IndexOf('<span class="status good">CURRENT SNAPSHOT</span>',[StringComparison]::Ordinal) -ge 0 -or
        $syntheticHtml.IndexOf('NO RECOMMENDATION PREPARED',[StringComparison]::Ordinal) -lt 0) {
        throw "BF-1089 BLOCKED: unverified '$unverified' rendered a green lineup approval packet."
    }
}

# BF-1076: legacy manager GETs keep their original timeout. Only
# the extra, optional Auto-Pilot player-source check receives a 12s cap.
$coreGet = @($ast.FindAll({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -ceq 'Invoke-AppCoreGet'
}, $true))
if ($coreGet.Count -ne 1) {
    throw 'BF-1076 BLOCKED: exact inner core GET definition missing.'
}
$coreSource = $coreGet[0].Extent.Text
if ($coreSource.IndexOf('[ValidateRange(1000, 180000)][int]$TimeoutMs = 180000', [StringComparison]::Ordinal) -lt 0 -or
    $coreSource.IndexOf('$request.Timeout = $TimeoutMs', [StringComparison]::Ordinal) -lt 0 -or
    $coreSource.IndexOf('$request.ReadWriteTimeout = $TimeoutMs', [StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1076 BLOCKED: optional core HTTP read has no finite bounded transfer and connection timeout.'
}
if ([regex]::Matches($route, 'Invoke-AppCoreGet -Port \$InnerPort -RequestTarget ''/matchup/autofill'' -TimeoutMs 12000').Count -ne 1) {
    throw 'BF-1076 BLOCKED: optional sourced lineup read may delay Auto-Pilot for the full 180-second default.'
}

Write-Host 'BF-1074/BF-1085 AUTO-PILOT SOURCED START/SIT: PASS'
Write-Host 'Coverage: exact source/season/week/league, full projection coverage, zero holds (including 10/100), UTC recency, invalid dates, duplicate proof, read-only route and independent waivers.'
