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
$proof = '<p class="meta butler-startsit-source-proof" role="status">1 proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time. Full scoreable projection coverage; 0 player holds. No Sleeper move was submitted. Sources: projection snapshot retrieved 2026-10-10T14:59:00.123456789Z UTC; swap players status map retrieved 2026-10-10T15:00:00Z UTC.</p>'
$startSit = $matchup + '<section class="panel recommendation-panel start-sit-assistant">' + $proof + '</section>'
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
    ($startSit.Replace('No Sleeper move was submitted', 'Transaction submitted')),
    ($startSit.Replace('proposed lineup changes have exact player-status checks from Sleeper at the recorded fetch time', 'No lineup change is ready')),
    ($startSit.Replace('2026-10-10T15:00:00Z', 'UNVERIFIED')),
    ($startSit.Replace('2026-10-10T15:00:00Z', '2026-10-10T14:30:00Z')),
    ($startSit.Replace('2026-10-10T15:00:00Z', '2026-10-10T15:04:00Z')),
    ($startSit.Replace('2026-10-10T15:00:00Z', '2026-02-30T15:00:00Z')),
    ($startSit.Replace('2026-10-10T14:59:00.123456789Z', '2026-10-10T12:30:00Z')),
    ($startSit.Replace('2026-10-10T14:59:00.123456789Z', 'yesterday')),
    ($startSit.Replace('</section>', $proof + '</section>')),
    ($startSit.Replace('recorded fetch time', 'review time')),
    ($startSit.Replace('recommendation-panel', 'start-sit-blocker recommendation-panel'))
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
    'Invoke-AppCoreGet -Port $InnerPort -RequestTarget ''/matchup/autofill''',
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
Write-Host 'BF-1074 AUTO-PILOT SOURCED START/SIT: PASS'
Write-Host 'Coverage: exact source/season/week/league, hold, UTC recency, invalid dates, duplicate proof, read-only route and independent waiver.'
