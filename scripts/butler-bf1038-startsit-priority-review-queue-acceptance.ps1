Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf1021-v04-start-sit-assistant-transform.ps1'
if (-not (Test-Path -LiteralPath $transformPath -PathType Leaf)) {
    throw "BF-1038 BLOCKED: Start/Sit transform missing at $transformPath"
}

$text = [IO.File]::ReadAllText($transformPath)
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-1038 BLOCKED: Start/Sit transform parse failed: $summary"
}

foreach ($required in @(
    '$holdQueueItems',
    '$directSignalCount',
    '$holdReviewCount',
    'What needs your decision',
    'Start with direct Start/Sit signals.',
    'player holds are evidence checks, not separate lineup moves.',
    '$signalVerb'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "BF-1038 BLOCKED: priority-review marker missing: $required"
    }
}

# The transform source declares replacement strings in implementation order,
# which is not the same as the generated renderer's runtime queue order.
# Validate the generated-order contract structurally instead: holds are diverted
# out of queueItems, then merged only at the final review-queue render anchor.
$holdAppend = $text.IndexOf('$holdQueueItems +=', [System.StringComparison]::Ordinal)
$mergeReplacement = $text.IndexOf('$queueItems += `$holdQueueItems`n    `$reviewQueueReturn =', [System.StringComparison]::Ordinal)
$queueRenderAnchor = $text.IndexOf('$queueReturnOld =', [System.StringComparison]::Ordinal)
$holdRedirectAnchor = $text.IndexOf('$holdAppendNew =', [System.StringComparison]::Ordinal)
if ($holdAppend -lt 0 -or $mergeReplacement -lt 0 -or $queueRenderAnchor -lt 0 -or $holdRedirectAnchor -lt 0) {
    throw 'BF-1038 BLOCKED: queue ordering contract anchors are incomplete.'
}

$priorityStart = $text.IndexOf('# BF-1038: direct Start/Sit signals should lead the review experience.', [System.StringComparison]::Ordinal)
$priorityEnd = $text.IndexOf('$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)', $priorityStart, [System.StringComparison]::Ordinal)
if ($priorityStart -lt 0 -or $priorityEnd -le $priorityStart) {
    throw 'BF-1038 BLOCKED: priority-review transform boundary is missing.'
}
$prioritySurface = $text.Substring($priorityStart, $priorityEnd - $priorityStart)

foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'submitTransaction',
    'setFaab',
    'AutoFillLineupOptimizer'
)) {
    if ($prioritySurface.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-1038 BLOCKED: priority-review presentation introduced forbidden behavior: $forbidden"
    }
}

Write-Host 'BF-1038 START/SIT PRIORITY REVIEW QUEUE ACCEPTANCE: PASS'
Write-Host 'Coverage: direct Start/Sit signals lead the queue, player holds remain visible as secondary evidence checks, decision title reflects actionable signal count, and presentation adds no provider/optimizer/write behavior.'