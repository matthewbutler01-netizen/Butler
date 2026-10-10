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
if ($partial -notmatch '1 player hold' -or $partial -notmatch 'Recheck injury updates before kickoff') {
    throw 'BF-1067 BLOCKED: partial-source proposal lost explicit hold or pre-kickoff review warning.'
}

Write-Host 'BF-1067 START/SIT STATUS SOURCE PROOF: PASS'
Write-Host 'Coverage: changed versus unchanged previews, FULL/PARTIAL projection coverage, live-status limitations, hold counts, escaped accessible display and no Sleeper writes.'
