Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
if (-not (Test-Path -LiteralPath $workerPath -PathType Leaf)) {
    throw "BF-1027 BLOCKED: request worker missing at $workerPath"
}

$text = [IO.File]::ReadAllText($workerPath)
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1027 BLOCKED: request worker parse failed: $summary"
}

foreach ($functionName in @('Get-V04AutoPilotApprovalPolicy','Get-V04AutoPilotApprovalQueue','Get-V04AutoPilotHtml')) {
    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-1027 BLOCKED: expected one $functionName function, found $($matches.Count)."
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
$html = Get-V04AutoPilotHtml -WatchState $watch -ApprovalPolicy $policy -ApprovalQueue $queue

foreach ($required in @(
    'class="autopilot-queue-action" href="/matchup/autofill">Review lineup</a>',
    'class="autopilot-queue-action" href="/waivers">Review waivers</a>',
    'class="autopilot-queue-action" href="/trade">Open Trade Analyzer</a>',
    '.autopilot-queue-action{display:inline-flex',
    'NOTHING AUTO-EXECUTES'
)) {
    if ($html.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1027 BLOCKED: approval-queue review action missing: $required"
    }
}

if ($html -match '<form|type="submit"|data-action=|onclick=') {
    throw 'BF-1027 BLOCKED: review links must not become execution controls.'
}

$start = $text.IndexOf('function Get-V04AutoPilotApprovalQueue {', [System.StringComparison]::Ordinal)
$end = $text.IndexOf('function Send-HttpResponse {', $start, [System.StringComparison]::Ordinal)
$surface = $text.Substring($start, $end - $start)
if ($surface -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|https://api\.sleeper\.app|Invoke-RestMethod|Invoke-WebRequest') {
    throw 'BF-1027 BLOCKED: Auto-Pilot review actions introduced provider, optimizer, or write behavior.'
}

Write-Host 'BF-1027 V0.4 AUTO-PILOT REVIEW ACTIONS ACCEPTANCE: PASS'
Write-Host 'Coverage: direct Start/Sit, waiver, and trade review links; responsive queue action styling; no submit controls; and no auto-execution behavior.'
