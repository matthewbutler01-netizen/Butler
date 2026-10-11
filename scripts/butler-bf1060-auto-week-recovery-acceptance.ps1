Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$runnerPath = Join-Path $PSScriptRoot 'butler-current-week-onopen-recovery.ps1'
if (-not (Test-Path -LiteralPath $workerPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
    throw 'BF-1060 BLOCKED: worker or on-open week recovery runner missing.'
}

$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) { throw 'BF-1060 BLOCKED: Windows worker parser errors.' }
foreach ($name in @('Test-AutomaticWeekRecoveryCandidate', 'Claim-AutomaticWeekRecovery')) {
    $matches = @($ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name
    }, $true))
    if ($matches.Count -ne 1) { throw "BF-1060 BLOCKED: required unique function missing: $name" }
    . ([scriptblock]::Create($matches[0].Extent.Text))
}

$held = '<html><main><section class="panel butler-live-week-status" role="status" data-butler-week-state="MISMATCH"><strong>SAVED MATCHUP OUTDATED</strong></section><h1>Saved matchup not usable</h1><span>DO NOT ACT</span></main></html>'
if (-not (Test-AutomaticWeekRecoveryCandidate -RequestTarget '/matchup' -Body $held)) {
    throw 'BF-1060 BLOCKED: exact proof-backed mismatch was not eligible for repair.'
}
$assistant = $held.Replace('Saved matchup not usable', 'Start/Sit review held')
if (-not (Test-AutomaticWeekRecoveryCandidate -RequestTarget '/matchup/autofill' -Body $assistant)) {
    throw 'BF-1060 BLOCKED: exact held Start/Sit page was not eligible for local repair.'
}
foreach ($other in @('/', '/team', '/waivers', '/autopilot', '/matchup?refresh=1', '/matchup/autofill?refresh=1')) {
    if (Test-AutomaticWeekRecoveryCandidate -RequestTarget $other -Body $held) {
        throw 'BF-1060 BLOCKED: non-exact route initiated a local week write.'
    }
}
foreach ($invalid in @(
    '',
    ($held.Replace('MISMATCH', 'MATCH').Replace('SAVED MATCHUP OUTDATED', 'WEEK MATCHES SLEEPER')),
    ($held.Replace('MISMATCH', 'UNVERIFIED').Replace('SAVED MATCHUP OUTDATED', 'WEEK NOT VERIFIED')),
    ($held.Replace('SAVED MATCHUP OUTDATED', 'FAKE CURRENT')),
    ($held + $held),
    ($held.Replace(' data-butler-week-state="MISMATCH"', '')),
    ($held.Replace('Saved matchup not usable', 'Read-only page only')),
    ($held.Replace('DO NOT ACT', 'START PLAYER'))
)) {
    if (Test-AutomaticWeekRecoveryCandidate -RequestTarget '/matchup' -Body $invalid) {
        throw 'BF-1060 BLOCKED: unsupported, forged, duplicate or actionable page authorized a DB write.'
    }
}
if (Test-AutomaticWeekRecoveryCandidate -RequestTarget '/matchup' -Body $held -StatusCode 503) {
    throw 'BF-1060 BLOCKED: failed HTTP page authorized local recovery.'
}

$state = @{ SyncRoot = (New-Object object); InProgress = $false; EvidenceGeneration = [long]0 }
if (-not (Claim-AutomaticWeekRecovery -State $state) -or
    -not $state.InProgress -or -not $state.ContainsKey('AutoWeekRetryAfterUtcTicks')) {
    throw 'BF-1060 BLOCKED: first guarded writer claim failed.'
}
if (Claim-AutomaticWeekRecovery -State $state) {
    throw 'BF-1060 BLOCKED: simultaneous recovery obtained a second writer claim.'
}
$state.InProgress = $false
if (Claim-AutomaticWeekRecovery -State $state) {
    throw 'BF-1060 BLOCKED: same-week repeated navigation ignored the cooldown.'
}
$state.AutoWeekRetryAfterUtcTicks = [long]0
if (-not (Claim-AutomaticWeekRecovery -State $state)) {
    throw 'BF-1060 BLOCKED: expired cooldown did not permit a new governed attempt.'
}

$worker = [IO.File]::ReadAllText($workerPath)
$runner = [IO.File]::ReadAllText($runnerPath)
$start = $worker.IndexOf('if (Test-AutomaticWeekRecoveryCandidate -RequestTarget $requestTarget', [StringComparison]::Ordinal)
$end = $worker.IndexOf('        $bf856Timings = $null', $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) { throw 'BF-1060 BLOCKED: exact bounded GET recovery flow missing.' }
$region = $worker.Substring($start, $end - $start)
foreach ($needle in @(
    'Claim-AutomaticWeekRecovery -State $RefreshState',
    'Invoke-AutomaticWeekRecovery -Root $RepoRoot -League $LeagueId',
    'Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget',
    'Complete-DecisionRefreshAttempt -State $RefreshState'
)) {
    if (-not $region.Contains($needle)) {
        throw "BF-1060 BLOCKED: route lost required writer isolation/retry/cache invalidation: $needle"
    }
}
if ($region -match 'Sleeper.*(submit|waiver|transaction)|Set-Faab|Method\s*=\s*POST') {
    throw 'BF-1060 BLOCKED: local week recovery added Sleeper transaction behavior.'
}
# BF-1069 must work with the default BF-723 governed install path.
# An unset override is normal, not evidence of a missing runtime.
foreach ($requiredDefault in @(
    "if ([string]::IsNullOrWhiteSpace(`$rawData))",
    "`$rawData = Join-Path `$localData 'Butler\data'",
    "elseif (-not [IO.Path]::IsPathRooted(`$rawData))",
    "Join-Path `$dataDir 'butler.db'",
    "BF-1069 BLOCKED: runtime data directory override must be absolute."
)) {
    if ($runner.IndexOf($requiredDefault, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1069 BLOCKED: default external install data-path guard missing: $requiredDefault"
    }
}

foreach ($required in @(
    '[guid]::TryParse($LeagueId',
    'app-league.txt',
    '$configured -cne $LeagueId',
    'BUTLER_APP_DATA_DIR',
    'butler.db',
    'io.butler.bet.cli.ButlerSleeperCurrentWeekMatchupSyncCli',
    'BF-840 current weekly matchup pairing synchronized.',
    'BF-1060 WEEK RECOVERY: COMPLETE'
)) {
    if (-not $runner.Contains($required)) { throw "BF-1060 BLOCKED: runtime guard missing: $required" }
}

Write-Host 'BF-1060 AUTOMATIC WEEK RECOVERY: PASS'
Write-Host 'Coverage: exact stale-week route gating, no duplicate/forged source proof, one writer, bounded cooldown, evidence-cache invalidation, read-only retry and no Sleeper transactions.'
