Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
if (-not (Test-Path -LiteralPath $workerPath -PathType Leaf)) {
    throw "BF-1025 BLOCKED: request worker missing at $workerPath"
}

$text = [IO.File]::ReadAllText($workerPath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1025 BLOCKED: request worker parse failed: $summary"
}

foreach ($functionName in @('Get-V04AutoPilotApprovalPolicy','Get-V04AutoPilotApprovalQueue','Get-V04AutoPilotHtml')) {
    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-1025 BLOCKED: expected one $functionName function, found $($matches.Count)."
    }
    . ([scriptblock]::Create($matches[0].Extent.Text))
}

function Get-AppCss { return '' }

$policy = Get-V04AutoPilotApprovalPolicy
if ($policy.Mode -cne 'MANAGER APPROVAL REQUIRED') {
    throw 'BF-1025 BLOCKED: manager approval is not the default Auto-Pilot mode.'
}
if ($policy.StartSit -cne 'PREPARE ONLY') {
    throw 'BF-1025 BLOCKED: Start/Sit policy must remain PREPARE ONLY.'
}
if ($policy.Waivers -cne 'RECOMMEND ONLY') {
    throw 'BF-1025 BLOCKED: waiver policy must remain RECOMMEND ONLY.'
}
if ($policy.Trades -cne 'NEVER AUTO-EXECUTE') {
    throw 'BF-1025 BLOCKED: trade policy must remain NEVER AUTO-EXECUTE.'
}

$requiredBlockers = @(
    'Evidence gap',
    'Stale or incomplete weekly data',
    'Roster drift or identity mismatch',
    'Unverified kickoff or game-lock state'
)
foreach ($blocker in $requiredBlockers) {
    if (@($policy.HardBlockers) -notcontains $blocker) {
        throw "BF-1025 BLOCKED: required hard blocker is missing: $blocker"
    }
}

$watch = [pscustomobject]@{
    Ready = $true
    Attention = '2 NEED ATTENTION'
    StartSit = 'REFRESH'
    Waivers = 'DO NOT ACT'
    Roster = 'Hard(CORE)-Dynasty | nuke the whales | roster 6'
}

$queue = Get-V04AutoPilotApprovalQueue -WatchState $watch -ApprovalPolicy $policy
$html = Get-V04AutoPilotHtml -WatchState $watch -ApprovalPolicy $policy -ApprovalQueue $queue
foreach ($required in @(
    'APPROVAL POLICY',
    'What Auto-Pilot is allowed to do',
    'MANAGER APPROVAL REQUIRED',
    'PREPARE ONLY',
    'RECOMMEND ONLY',
    'NEVER AUTO-EXECUTE',
    'Hard blockers always stop action',
    'Evidence gap',
    'Stale or incomplete weekly data',
    'Roster drift or identity mismatch',
    'Unverified kickoff or game-lock state'
)) {
    if ($html.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1025 BLOCKED: rendered approval-policy marker missing: $required"
    }
}

foreach ($required in @(
    'Get-V04AutoPilotApprovalPolicy',
    'Get-V04AutoPilotApprovalQueue -WatchState $watchState -ApprovalPolicy $approvalPolicy',
    'Get-V04AutoPilotHtml -WatchState $watchState -ApprovalPolicy $approvalPolicy -ApprovalQueue $approvalQueue'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1025 BLOCKED: Auto-Pilot approval route marker missing: $required"
    }
}

$start = $text.IndexOf('function Get-V04AutoPilotApprovalPolicy {', [System.StringComparison]::Ordinal)
$end = $text.IndexOf('function Send-HttpResponse {', $start, [System.StringComparison]::Ordinal)
$surface = $text.Substring($start, $end - $start)
if ($surface -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|https://api\.sleeper\.app|Invoke-RestMethod|Invoke-WebRequest') {
    throw 'BF-1025 BLOCKED: approval-policy surface introduced provider, optimizer, or write behavior.'
}

Write-Host 'BF-1025 V0.4 AUTO-PILOT APPROVAL POLICY ACCEPTANCE: PASS'
Write-Host 'Coverage: manager approval default, Start/Sit prepare-only, waivers recommend-only, trades never auto-execute, hard blockers, and no new write behavior.'
