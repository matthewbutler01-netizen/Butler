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
    param([string]$Page,[string]$OtherPage)
    $watch = [pscustomobject]@{
        Ready = $true; StartSit = 'START 1 / SIT 1'
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
foreach ($bad in @(
    '',
    $matchup,
    ($startSit.Replace('Our &amp; League', 'Other league')),
    ($startSit.Replace('data-butler-week-number="5"', 'data-butler-week-number="6"').Replace('Week 5', 'Week 6')),
    ($startSit.Replace('data-butler-week-state="MATCH"', 'data-butler-week-state="UNVERIFIED"')),
    ($startSit.Replace('data-butler-matchup-season="2026"', 'data-butler-matchup-season="2025"')),
    ($startSit + $startSit),
    ($startSit.Replace($proof, '')),
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
foreach ($name in @('Get-V04AutoPilotApprovalQueue','Get-V04AutoPilotHtml')) {
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
$queue = Get-V04AutoPilotApprovalQueue -WatchState $heldView -ApprovalPolicy $policy
$html = Get-V04AutoPilotHtml -WatchState $heldView -ApprovalPolicy $policy -ApprovalQueue $queue
if ($html -notmatch 'CURRENT SNAPSHOT' -or
    $html -notmatch 'READY FOR MANAGER REVIEW' -or
    $html -match 'LINEUP SOURCE NOT VERIFIED') {
    throw 'BF-1075 BLOCKED: supported Start/Sit source never displayed a ready review.'
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

Write-Host 'BF-1074 AUTO-PILOT SOURCED START/SIT: PASS'
Write-Host 'Coverage: exact source/season/week/league, hold, UTC recency, invalid dates, duplicate proof, read-only route and independent waiver.'
