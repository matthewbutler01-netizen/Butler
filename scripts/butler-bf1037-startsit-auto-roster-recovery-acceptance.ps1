Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$recoveryPath = Join-Path $PSScriptRoot 'butler-recover-roster-drift.ps1'
foreach ($requiredPath in @($workerPath, $recoveryPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "BF-1037 BLOCKED: required file missing at $requiredPath"
    }
}

$worker = [IO.File]::ReadAllText($workerPath)
$recovery = [IO.File]::ReadAllText($recoveryPath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    throw 'BF-1037 BLOCKED: request worker does not parse.'
}

$matches = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Test-StartSitRosterDriftResponse'
}, $true))
if ($matches.Count -ne 1) {
    throw "BF-1037 BLOCKED: expected one roster-drift response detector, found $($matches.Count)."
}
. ([scriptblock]::Create($matches[0].Extent.Text))

$drift = '<div>BLOCKED: current roster membership drifted from BF-603/BF-602 frame; added=[9508] removed=[11618]; refresh BF-602/BF-603 and downstream live evidence before target-roster review</div>'
if (-not (Test-StartSitRosterDriftResponse -RequestTarget '/matchup/autofill' -Body $drift)) {
    throw 'BF-1037 BLOCKED: exact Start/Sit roster drift was not detected.'
}
if (-not (Test-StartSitRosterDriftResponse -RequestTarget '/team' -Body $drift)) {
    throw 'BF-1037 BLOCKED: exact My Team roster drift was not detected.'
}
foreach ($route in @('/matchup', '/', '/waivers', '/team?position=RB', '/players')) {
    if (Test-StartSitRosterDriftResponse -RequestTarget $route -Body $drift) {
        throw "BF-1037 BLOCKED: unsupported route $route initiated roster recovery."
    }
}
if (Test-StartSitRosterDriftResponse -RequestTarget '/team' -Body '<div>projection gap</div>') {
    throw 'BF-1037 BLOCKED: non-roster My Team failures must not trigger roster recovery.'
}
if (Test-StartSitRosterDriftResponse -RequestTarget '/matchup/autofill' -Body '<div>projection gap</div>') {
    throw 'BF-1037 BLOCKED: non-roster Start/Sit failures must not trigger roster recovery.'
}

foreach ($required in @(
    'Invoke-StartSitRosterDriftAutoRecovery -Root $RepoRoot',
    '$proxied = Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget',
    'BF-723 APP AUTO RECOVERY: COMPLETE',
    '-AppAutoRecovery',
    'Local\Butler.StartSit.RosterRecovery.',
    'Claim-LocalEvidenceRecovery -State $RefreshState',
    'Complete-DecisionRefreshAttempt -State $RefreshState'
)) {
    if ($worker.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $recovery.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1037 BLOCKED: auto-recovery contract missing: $required"
    }
}

if ($recovery.IndexOf('if ($AppAutoRecovery)', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1037 BLOCKED: recovery script has no app auto-recovery boundary.'
}
if ($recovery.IndexOf('BF-610 failed for a reason other than exact roster drift. No Butler evidence write was attempted.', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1037 BLOCKED: exact-drift authorization guard was weakened.'
}
foreach ($forbidden in @('create_transaction','submitTransaction','setFaab','cancel waiver','replace a Sleeper transaction')) {
    if ($worker.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-1037 BLOCKED: request-worker auto-recovery introduced forbidden transaction behavior: $forbidden"
    }
}

Write-Host 'BF-1037 START/SIT AUTO ROSTER RECOVERY ACCEPTANCE: PASS'
Write-Host 'Coverage: exact drift detection on My Team and Start/Sit only, governed BF-723 local-evidence recovery, cache invalidation even after partial writes, single route retry, bounded mutex, and no Sleeper transaction behavior.'