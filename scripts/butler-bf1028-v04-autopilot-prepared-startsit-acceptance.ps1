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

$readyWatch = [pscustomobject]@{
    Ready = $true
    Attention = 'READY'
    StartSit = 'START 1 / SIT 1'
    Waivers = 'NO MOVE'
    Roster = 'Hard(CORE)-Dynasty | nuke the whales | roster 6'
}
$readyQueue = Get-V04AutoPilotApprovalQueue -WatchState $readyWatch -ApprovalPolicy $policy
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
