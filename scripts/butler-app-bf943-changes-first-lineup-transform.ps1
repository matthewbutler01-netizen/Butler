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

$evidenceAnchor = '        SourceSurface = $sourceSurface.Groups[''value''].Value.Trim()'
if (-not $core.Contains($evidenceAnchor)) {
    throw 'BF-943 BLOCKED: lineup decision evidence parser anchor is missing.'
}
$core = $core.Replace($evidenceAnchor, $evidenceAnchor + "`n" + '        DecisionEvidence = @([regex]::Matches($Text, ''(?m)^Decision evidence: (?<value>.+)$'') | ForEach-Object { $_.Groups[''value''].Value.Trim() })')

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
# Earlier returns render blocked and incomplete-evidence states, before counts exist.
# Only the final ready-state return may receive assignment disclosure setup.
$returnPos = $function.LastIndexOf($returnMarker, [System.StringComparison]::Ordinal)
if ($returnPos -lt 0) {
    throw 'BF-943 BLOCKED: final Lineup Advisor ready-state return is missing.'
}

$disclosureSetup = @'
    $holdEvidenceHtml = '<p class="meta">Projection-based proposals after availability and usage review holds. Sharp verified drops in snaps and workload prevent automatic promotion. Review the sources and gaps below before changing your lineup; expert start/sit picks and NFL defensive matchup evidence remain unverified.</p>'
    if ($null -ne $AutoFill.PSObject.Properties['DecisionEvidence']) {
        foreach ($evidence in @($AutoFill.DecisionEvidence)) {
            $holdEvidenceHtml += "<details><summary>Why this projected change needs review</summary><p>$(ConvertTo-HtmlText $evidence)</p></details>"
        }
    }
    foreach ($hold in @($AutoFill.ProjectionHolds)) {
        if ($null -ne $hold.PSObject.Properties['Reason']) {
            $holdEvidenceHtml += "<div class=`"callout`"><strong>$(ConvertTo-HtmlText $hold.Name): review before starting</strong><p>$(ConvertTo-HtmlText $hold.Reason)</p></div>"
        }
    }
    $unchangedCount = @($AutoFill.Assignments | Where-Object { -not $_.Changed }).Count
    $lineupFocusHtml = if ($changedCount -gt 0) {
        $changeWord = if ($changedCount -eq 1) { 'change' } else { 'changes' }
        "<div class=`"lineup-focus`"><div class=`"lineup-focus-head`"><div><span class=`"eyebrow`">Actionable lineup</span><h3>$changedCount $changeWord to review</h3></div><span class=`"status good`">CHANGES FIRST</span></div><div class=`"lineup-board`">$rows</div></div>"
    }
    else {
        '<div class="lineup-focus lineup-clear"><div class="lineup-focus-head"><div><span class="eyebrow">Lineup proposals</span><h3>No projected changes after holds</h3></div><span class="status done">NO PROPOSALS</span></div><p class="meta">No higher projected lineup was found among the evaluated players. Review holds and evidence gaps before treating this as a complete lineup assessment. The unchanged lineup remains available below.</p></div>'
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
$function = $function.Replace($oldBoard, '$holdEvidenceHtml$lineupFocusHtml$unchangedDisclosure')
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
/* Respond to the advisor panel width, including narrow desktop sidebars. */
.lineup-board{container-type:inline-size;container-name:lineup-board}.lineup-row>*{min-width:0}.lineup-choice strong{overflow-wrap:anywhere}.lineup-row .lineup-swap-action{grid-column:1/-1;justify-content:flex-start;min-width:0}.lineup-row .lineup-swap-compare{max-width:100%;white-space:normal}
@container lineup-board (max-width:650px){.lineup-row{grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:12px;padding:14px}.lineup-row>div:first-child{grid-column:1/-1;grid-row:auto}.lineup-row .lineup-choice.current{grid-column:1;grid-row:auto}.lineup-row .lineup-choice.recommended{grid-column:2;grid-row:auto}.lineup-row .lineup-arrow{display:none}.lineup-row .projection,.lineup-row .slot-delta{grid-column:1;grid-row:auto}.lineup-row .decision-chip{grid-column:2;grid-row:auto;justify-self:end;align-self:center}.lineup-row .lineup-swap-action{grid-column:1/-1;grid-row:auto}.lineup-row .lineup-choice small,.lineup-row .lineup-choice strong,.lineup-row .lineup-player-projection{display:block}}

'@
$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $css.TrimEnd() + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'View $unchangedCount unchanged lineup $slotWord',
    'CHANGES FIRST',
    'NO PROPOSALS',
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
