Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$worker = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
if (-not (Test-Path -LiteralPath $worker -PathType Leaf)) {
    throw "BF-1023 BLOCKED: request worker missing at $worker"
}

$text = [IO.File]::ReadAllText($worker)
$start = $text.IndexOf('function Get-V04AutoPilotHtml {', [System.StringComparison]::Ordinal)
$end = $text.IndexOf('function Send-HttpResponse {', $start, [System.StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) {
    throw 'BF-1023 BLOCKED: Auto-Pilot function boundary is missing.'
}

$surface = $text.Substring($start, $end - $start)
foreach ($required in @(
    'PREVIEW ONLY',
    'AUTOMATION OFF',
    'No background job',
    'Start/Sit changes',
    'Approval rules',
    'Open Start/Sit Assistant',
    'Review My Team',
    'View Matchup',
    'READ ONLY PREVIEW',
    '.autopilot-actions',
    '.autopilot-actions .primary',
    'class="target"'
)) {
    if ($surface.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1023 BLOCKED: Auto-Pilot marker missing: $required"
    }
}

# BF-1024 legitimately evolves the second watch card from the static
# Availability preview into a real Waiver-attention watch. Either label
# satisfies the BF-1023 cockpit contract on descendant branches.
if ($surface.IndexOf('Availability changes', [System.StringComparison]::Ordinal) -lt 0 -and
    $surface.IndexOf('Waiver attention', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1023 BLOCKED: Auto-Pilot secondary watch card is missing.'
}

if ($surface -match 'currently monitors|background monitoring is active|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|https://api\.sleeper\.app') {
    throw 'BF-1023 BLOCKED: Auto-Pilot preview overclaims automation or introduces write/provider behavior.'
}

$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($worker, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1023 BLOCKED: request worker parse failed: $summary"
}

Write-Host 'BF-1023 V0.4 AUTO-PILOT COCKPIT ACCEPTANCE: PASS'
Write-Host 'Coverage: explicit preview/off state, no background-automation overclaim, Start/Sit plus secondary watch/control cards, separated action buttons, manager context, and no write behavior.'
