param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-943 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)

$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-943 BLOCKED: final Lineup Advisor function boundary is missing.'
}

$function = $core.Substring($functionStart, $functionEnd - $functionStart)
foreach ($requiredPrior in @(
    'Compare this swap',
    'CurrentPoints',
    'RecommendedPoints',
    'SlotGain'
)) {
    if ($function.IndexOf($requiredPrior, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-943 BLOCKED: required prior Lineup Advisor capability is missing: $requiredPrior"
    }
}

$returnMarker = '    return "<section class=`"panel recommendation-panel`">'
$returnPos = $function.IndexOf($returnMarker, [System.StringComparison]::Ordinal)
if ($returnPos -lt 0) {
    throw 'BF-943 BLOCKED: final Lineup Advisor ready-state return is missing.'
}

$disclosureSetup = @'
    $unchangedCount = @($AutoFill.Assignments | Where-Object { -not $_.Changed }).Count
    $lineupFocusHtml = if ($changedCount -gt 0) {
        $changeWord = if ($changedCount -eq 1) { 'change' } else { 'changes' }
        "<div class=`"lineup-focus`"><div class=`"lineup-focus-head`"><div><span class=`"eyebrow`">Actionable lineup</span><h3>$changedCount $changeWord to review</h3></div><span class=`"status good`">CHANGES FIRST</span></div><div class=`"lineup-board`">$rows</div></div>"
    }
    else {
        '<div class="lineup-focus lineup-clear"><div class="lineup-focus-head"><div><span class="eyebrow">Actionable lineup</span><h3>No lineup changes to review</h3></div><span class="status done">ALL KEEP</span></div><p class="meta">Butler found no proven START/SIT change in the scoreable weekly frame. The full unchanged lineup remains available below.</p></div>'
    }

    $unchangedDisclosure = if ($unchangedCount -gt 0) {
        $slotWord = if ($unchangedCount -eq 1) { 'slot' } else { 'slots' }
        "<details class=`"lineup-unchanged`"><summary>View $unchangedCount unchanged lineup $slotWord</summary><div class=`"lineup-board`">$rows</div></details>"
    }
    else {
        ''
    }

'@

$oldBoard = '<div class=`"lineup-board`">$rows</div>'
$boardCount = [regex]::Matches($function, [regex]::Escape($oldBoard)).Count
if ($boardCount -ne 1) {
    throw "BF-943 BLOCKED: expected one primary lineup-board binding, found $boardCount."
}
$function = $function.Replace($oldBoard, '$lineupFocusHtml$unchangedDisclosure')
$function = $function.Substring(0, $returnPos) + $disclosureSetup + $function.Substring($returnPos)

$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-943 BLOCKED: final manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-943 BLOCKED: final manager CSS terminator is missing.'
}
$css = @'
/* BF-943 changes-first lineup progressive disclosure. */
.lineup-focus{margin-top:18px}.lineup-focus-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:10px}.lineup-focus-head h3{margin:2px 0 0}.lineup-focus .lineup-row:not(.changed){display:none}.lineup-clear{padding:16px;border:1px solid var(--line);border-radius:14px;background:var(--surface-soft)}.lineup-unchanged{margin-top:14px;border:1px solid var(--line);border-radius:14px;background:var(--surface-soft);overflow:hidden}.lineup-unchanged>summary{cursor:pointer;padding:14px 16px;font-weight:800;list-style-position:inside}.lineup-unchanged[open]>summary{border-bottom:1px solid var(--line)}.lineup-unchanged .lineup-row.changed{display:none}.lineup-unchanged .lineup-board{padding:10px 12px 12px}@media(max-width:900px){.lineup-focus-head{flex-direction:column;align-items:flex-start}.lineup-unchanged .lineup-board{padding:8px}}
'@
$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $css.TrimEnd() + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'View $unchangedCount unchanged lineup $slotWord',
    'CHANGES FIRST',
    'ALL KEEP',
    '$lineupFocusHtml',
    '$unchangedDisclosure',
    'Compare this swap',
    'CurrentPoints',
    'SlotGain',
    'lineup-unchanged'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-943 BLOCKED: required changes-first marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installed = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'submitTransaction',
    'setFaab'
)) {
    if ($installed.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-943 BLOCKED: changes-first lineup introduced forbidden behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-943 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-943 Changes-First Lineup applied.'
