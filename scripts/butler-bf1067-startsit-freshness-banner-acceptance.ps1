Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$path = Join-Path $PSScriptRoot 'butler-app-bf1021-v04-start-sit-assistant-transform.ps1'
if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    throw 'BF-1067 BLOCKED: Start/Sit presentation transform is missing.'
}
$source = [IO.File]::ReadAllText($path)
$tokens = $null
$errors = $null
[void][Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) {
    throw 'BF-1067 BLOCKED: presentation transform has Windows PowerShell parse errors.'
}
$start = $source.IndexOf('    # BF-1067: report what this exact preview did and did not verify.', [StringComparison]::Ordinal)
$end = $source.IndexOf('    $decisionActionCopy = $decisionTitle', $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $end -le $start) {
    throw 'BF-1067 BLOCKED: unique source-check summary not found.'
}
$proof = $source.Substring($start, $end - $start)
$renderer = $source.IndexOf('<p class=`"meta butler-startsit-source-proof`" role=`"status`">$(ConvertTo-HtmlText $statusProofCopy)</p>', [StringComparison]::Ordinal)
if ($renderer -lt 0 -or
    $source.IndexOf('<p class=`"meta butler-startsit-source-proof`"', $renderer + 1, [StringComparison]::Ordinal) -ge 0) {
    throw 'BF-1067 BLOCKED: Start/Sit did not render exactly one accessible, escaped source proof.'
}
# BF-1105: the one safely escaped source proof must be readable in a
# dedicated block rather than hidden as muted decision-card microcopy.
$renderBinding = @($source -split "[\r\n]+" | Where-Object {
    $_.StartsWith('$function = $function.Replace($summaryOld, ', [StringComparison]::Ordinal)
})
if ($renderBinding.Count -ne 1) {
    throw 'BF-1105 BLOCKED: unique Start/Sit summary binding missing.'
}
$display = [string]$renderBinding[0]
foreach ($required in @(
    '<div class=`"startsit-source-context`">',
    '<strong class=`"startsit-source-heading`">EVIDENCE BEHIND THIS ADVICE</strong>',
    '<p class=`"meta butler-startsit-source-proof`" role=`"status`">$(ConvertTo-HtmlText $statusProofCopy)</p>',
    '<p class=`"startsit-source-caution`">Retrieval times are not injury clearance.'
)) {
    if ($display.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1105 BLOCKED: manager-facing source evidence missing: $required"
    }
}
foreach ($css in @(
    '.start-sit-assistant .startsit-source-context{margin-top:14px;padding:16px',
    '.start-sit-assistant .startsit-source-heading{display:block;color:var(--ink);font-weight:800;font-size:14px',
    '.start-sit-assistant .butler-startsit-source-proof{display:block;margin-top:9px;padding:0;',
    'color:var(--ink);line-height:1.65;font-size:14px',
    '.start-sit-assistant .lineup-choice small{font-size:12px;font-weight:700}',
    '.start-sit-assistant .lineup-swap-compare{font-size:14px;line-height:1.55}',
    '.start-sit-assistant .review-queue li{font-size:14px;line-height:1.6'
)) {
    if ($source.IndexOf($css, [StringComparison]::Ordinal) -lt 0) {
        throw "BF-1105 BLOCKED: readable Start/Sit styling missing: $css"
    }
}

if ($proof -match 'Invoke-RestMethod|Invoke-WebRequest|submitTransaction|setFaab|https://api|Method\s*=\s*POST') {
    throw 'BF-1067 BLOCKED: view-only proof introduced provider or transaction behavior.'
}

function Test-FreshnessPreview {
    param([object[]]$Assignments,[object[]]$Holds,[string]$ProjectionCoverage,
        [string]$ProjectionFetchedAt = 'UNVERIFIED', [string]$SwapStatusFetchedAt = 'UNVERIFIED')
    $AutoFill = [pscustomobject]@{
        Assignments = @($Assignments)
        ProjectionHolds = @($Holds)
        ProjectionCoverage = $ProjectionCoverage
        ProjectionFetchedAt = $ProjectionFetchedAt
        SwapStatusFetchedAt = $SwapStatusFetchedAt
    }
    $statusProofCopy = ''
    . ([scriptblock]::Create($proof))
    return [string]$statusProofCopy
}

$changed = [pscustomobject]@{ Changed = $true }
$keep = [pscustomobject]@{ Changed = $false }
$verified = Test-FreshnessPreview -Assignments @($changed,$keep) -Holds @() -ProjectionCoverage 'FULL' -ProjectionFetchedAt '2026-10-10T08:00:00Z' -SwapStatusFetchedAt '2026-10-10T08:01:00Z'
if ($verified -notmatch '^1 proposed lineup changes have exact player-status checks' -or
    $verified -notmatch 'Full scoreable projection coverage' -or
    $verified -notmatch '0 player holds' -or
    $verified -notmatch 'not availability clearance' -or
    $verified -notmatch 'projection snapshot retrieved 2026-10-10T08:00:00Z UTC' -or
    $verified -notmatch 'swap players status map retrieved 2026-10-10T08:01:00Z UTC' -or
    $verified -notmatch 'no Sleeper move was submitted') {
    throw 'BF-1067 BLOCKED: a prepared proposal lost bounded source-check disclaimers.'
}
$held = Test-FreshnessPreview -Assignments @($keep) -Holds @('hold 1','hold 2') -ProjectionCoverage 'PARTIAL'
if ($held -notmatch '^No lineup change is ready' -or
    $held -notmatch 'Partial projection coverage' -or
    $held -notmatch '2 player holds' -or
    $held -notmatch 'projection fetch time not supplied' -or
    $held -notmatch 'exact swap status fetch time not verified' -or
    $held -match 'exact player-status checks') {
    throw 'BF-1067 BLOCKED: unchanged lineup was incorrectly labeled as sourced swap.'
}
$partial = Test-FreshnessPreview -Assignments @($changed) -Holds @('hold 1') -ProjectionCoverage 'PARTIAL'
if ($partial -notmatch '1 player hold' -or
    $partial -notmatch 'status check time is UNVERIFIED' -or
    $partial -notmatch 'Do not act on this proposal' -or
    $partial -match 'status checks from Sleeper at the recorded fetch time') {
    throw 'BF-1067 BLOCKED: partial-source proposal lost explicit hold or pre-kickoff review warning.'
}

Write-Host 'BF-1067 START/SIT STATUS SOURCE PROOF: PASS'
Write-Host 'Coverage: changed versus unchanged previews, FULL/PARTIAL projection coverage, live-status limitations, hold counts, escaped accessible display and no Sleeper writes.'
