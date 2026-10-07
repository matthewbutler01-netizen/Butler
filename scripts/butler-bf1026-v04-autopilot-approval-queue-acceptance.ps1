Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
if (-not (Test-Path -LiteralPath $workerPath -PathType Leaf)) {
    throw "BF-1026 BLOCKED: request worker missing at $workerPath"
}

$text = [IO.File]::ReadAllText($workerPath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1026 BLOCKED: request worker parse failed: $summary"
}

foreach ($functionName in @('Get-V04AutoPilotApprovalPolicy','Get-V04AutoPilotApprovalQueue','Get-V04AutoPilotHtml')) {
    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-1026 BLOCKED: expected one $functionName function, found $($matches.Count)."
    }
    . ([scriptblock]::Create($matches[0].Extent.Text))
}

function Get-AppCss { return '' }

$policy = Get-V04AutoPilotApprovalPolicy
$watch = [pscustomobject]@{
    Ready = $true
    Attention = '2 NEED ATTENTION'
    StartSit = 'REFRESH'
    Waivers = 'DO NOT ACT'
    Roster = 'Hard(CORE)-Dynasty | nuke the whales | roster 6'
}
$queue = Get-V04AutoPilotApprovalQueue -WatchState $watch -ApprovalPolicy $policy

if ($queue.StartSitSignal -cne 'REFRESH') {
    throw 'BF-1026 BLOCKED: Start/Sit queue signal mismatch.'
}
if ($queue.StartSitPolicy -cne 'PREPARE ONLY') {
    throw 'BF-1026 BLOCKED: Start/Sit queue policy mismatch.'
}
if ($queue.StartSitNext -notmatch 'Refresh or resolve') {
    throw 'BF-1026 BLOCKED: REFRESH signal did not produce an evidence-resolution next step.'
}
if ($queue.WaiverSignal -cne 'DO NOT ACT') {
    throw 'BF-1026 BLOCKED: waiver queue signal mismatch.'
}
if ($queue.WaiverPolicy -cne 'RECOMMEND ONLY') {
    throw 'BF-1026 BLOCKED: waiver queue policy mismatch.'
}
if ($queue.WaiverNext -notmatch 'No waiver action should be taken') {
    throw 'BF-1026 BLOCKED: DO NOT ACT waiver signal did not stay fail-closed.'
}
if ($queue.TradePolicy -cne 'NEVER AUTO-EXECUTE') {
    throw 'BF-1026 BLOCKED: trade queue policy mismatch.'
}

$html = Get-V04AutoPilotHtml -WatchState $watch -ApprovalPolicy $policy -ApprovalQueue $queue
foreach ($required in @(
    'APPROVAL QUEUE',
    'What needs your decision',
    'NOTHING AUTO-EXECUTES',
    'Start/Sit signal',
    'Waiver signal',
    'Allowed',
    'REFRESH',
    'PREPARE ONLY',
    'DO NOT ACT',
    'RECOMMEND ONLY',
    'NEVER AUTO-EXECUTE',
    'Refresh or resolve the current evidence state',
    'No waiver action should be taken'
)) {
    if ($html.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1026 BLOCKED: rendered approval-queue marker missing: $required"
    }
}

$blockedWatch = [pscustomobject]@{
    Ready = $false
    Attention = 'UNAVAILABLE'
    StartSit = 'UNAVAILABLE'
    Waivers = 'UNAVAILABLE'
    Roster = 'Manager tools'
}
$blockedQueue = Get-V04AutoPilotApprovalQueue -WatchState $blockedWatch -ApprovalPolicy $policy
if ($blockedQueue.StartSitNext -notmatch '^Blocked until') {
    throw 'BF-1026 BLOCKED: incomplete watch did not block Start/Sit queue.'
}
if ($blockedQueue.WaiverNext -notmatch '^Blocked until') {
    throw 'BF-1026 BLOCKED: incomplete watch did not block waiver queue.'
}

foreach ($required in @(
    'Get-V04AutoPilotApprovalQueue -WatchState $watchState -ApprovalPolicy $approvalPolicy',
    'Get-V04AutoPilotHtml -WatchState $watchState -ApprovalPolicy $approvalPolicy -ApprovalQueue $approvalQueue'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1026 BLOCKED: Auto-Pilot approval-queue route marker missing: $required"
    }
}

$start = $text.IndexOf('function Get-V04AutoPilotApprovalQueue {', [System.StringComparison]::Ordinal)
$end = $text.IndexOf('function Send-HttpResponse {', $start, [System.StringComparison]::Ordinal)
$surface = $text.Substring($start, $end - $start)
if ($surface -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|https://api\.sleeper\.app|Invoke-RestMethod|Invoke-WebRequest') {
    throw 'BF-1026 BLOCKED: approval queue introduced provider, optimizer, or write behavior.'
}

Write-Host 'BF-1026 V0.4 AUTO-PILOT APPROVAL QUEUE ACCEPTANCE: PASS'
Write-Host 'Coverage: policy-aware Start/Sit and waiver next steps, trade manual-only boundary, incomplete-watch blocking, rendered approval queue, and no auto-execution.'
