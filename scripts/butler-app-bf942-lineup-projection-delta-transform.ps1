param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-942 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-942 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$varsOld = @'
    $promotions = @()
    $availabilityExclusions = @()
    $projectionHolds = @()
    $mode = ''
'@
$varsNew = @'
    $promotions = @()
    $availabilityExclusions = @()
    $projectionHolds = @()
    $projectionDeltas = @{}
    $mode = ''
'@
$core = Replace-ExactlyOnce -Text $core -Old $varsOld -New $varsNew -Contract 'AutoFill projection-delta collection'

$loopOld = @'
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -ceq 'Moves to bench:') { $mode = 'BENCH'; continue }
'@
$loopNew = @'
    foreach ($line in ($Text -split "`r?`n")) {
        $projectionDelta = [regex]::Match(
            $line,
            '^\s{2}#(?<ordinal>\d+)\s+projection_delta\s+\|\s+current=(?<current>UNAVAILABLE|[+-]?\d+(?:\.\d+)?)\s+\|\s+recommended=(?<recommended>[+-]?\d+(?:\.\d+)?)\s+\|\s+gain=(?<gain>UNAVAILABLE|[+-]?\d+(?:\.\d+)?)\s*$')
        if ($projectionDelta.Success) {
            $ordinalKey = $projectionDelta.Groups['ordinal'].Value
            if ($projectionDeltas.ContainsKey($ordinalKey)) {
                throw "BF-942 BLOCKED: duplicate projection delta for starter ordinal $ordinalKey."
            }
            $projectionDeltas[$ordinalKey] = [pscustomobject]@{
                Current = $projectionDelta.Groups['current'].Value
                Recommended = $projectionDelta.Groups['recommended'].Value
                Gain = $projectionDelta.Groups['gain'].Value
            }
            continue
        }

        if ($line -ceq 'Moves to bench:') { $mode = 'BENCH'; continue }
'@
$core = Replace-ExactlyOnce -Text $core -Old $loopOld -New $loopNew -Contract 'AutoFill projection-delta parser'

$validationOld = @'
    if ($assignments.Count -eq 0) {
        throw 'BF-825 BLOCKED: ready AutoFill bundle section contains no recommended lineup rows.'
    }

    return [pscustomobject]@{
'@
$validationNew = @'
    if ($assignments.Count -eq 0) {
        throw 'BF-825 BLOCKED: ready AutoFill bundle section contains no recommended lineup rows.'
    }

    $enrichedAssignments = @()
    foreach ($assignment in @($assignments)) {
        $ordinalKey = [string]$assignment.Ordinal
        if (-not $projectionDeltas.ContainsKey($ordinalKey)) {
            throw "BF-942 BLOCKED: AutoFill assignment #$ordinalKey is missing exact projection-delta evidence."
        }

        $delta = $projectionDeltas[$ordinalKey]
        $recommendedPoints = [decimal]$delta.Recommended
        $assignmentPoints = [decimal]$assignment.Points
        if ($recommendedPoints -ne $assignmentPoints) {
            throw "BF-942 BLOCKED: AutoFill assignment #$ordinalKey recommended projection does not reconcile."
        }

        $currentUnavailable = [string]$delta.Current -ceq 'UNAVAILABLE'
        $gainUnavailable = [string]$delta.Gain -ceq 'UNAVAILABLE'
        if ($currentUnavailable -ne $gainUnavailable) {
            throw "BF-942 BLOCKED: AutoFill assignment #$ordinalKey current projection and slot gain availability do not reconcile."
        }
        if (-not $currentUnavailable) {
            $currentPoints = [decimal]$delta.Current
            $slotGain = [decimal]$delta.Gain
            if (($recommendedPoints - $currentPoints) -ne $slotGain) {
                throw "BF-942 BLOCKED: AutoFill assignment #$ordinalKey slot projection gain does not reconcile."
            }
        }

        $enrichedAssignments += [pscustomobject]@{
            Ordinal = $assignment.Ordinal
            Slot = $assignment.Slot
            Current = $assignment.Current
            CurrentId = $assignment.CurrentId
            Recommended = $assignment.Recommended
            RecommendedId = $assignment.RecommendedId
            Points = $assignment.Points
            CurrentPoints = $delta.Current
            RecommendedPoints = $delta.Recommended
            SlotGain = $delta.Gain
            Changed = $assignment.Changed
        }
    }
    if ($projectionDeltas.Count -ne $enrichedAssignments.Count) {
        throw 'BF-942 BLOCKED: projection-delta evidence count does not match AutoFill assignment count.'
    }

    return [pscustomobject]@{
'@
$core = Replace-ExactlyOnce -Text $core -Old $validationOld -New $validationNew -Contract 'AutoFill projection-delta reconciliation'

$returnOld = '        Assignments = @($assignments | Sort-Object Ordinal)'
$returnNew = '        Assignments = @($enrichedAssignments | Sort-Object Ordinal)'
$core = Replace-ExactlyOnce -Text $core -Old $returnOld -New $returnNew -Contract 'AutoFill enriched assignment binding'

$rowOld = @'
        $rows += "<div class=`"$rowClass`"><div><span class=`"position-chip`">$(ConvertTo-HtmlText $assignment.Slot)</span></div><div class=`"lineup-choice current`"><small>Current</small><strong>$(ConvertTo-HtmlText $assignment.Current)</strong></div><div class=`"lineup-arrow`">&rarr;</div><div class=`"lineup-choice recommended`"><small>Recommended</small><strong>$(ConvertTo-HtmlText $assignment.Recommended)</strong></div><div class=`"projection`">$(ConvertTo-HtmlText $assignment.Points)<span>proj pts</span></div><span class=`"$decisionClass`">$decision</span><div class=`"lineup-swap-action`">$swapCompareAction</div></div>"
'@
$rowNew = @'
        $slotGainClass = if ([string]$assignment.SlotGain -ceq 'UNAVAILABLE') { 'slot-delta unavailable' } elseif ([string]$assignment.SlotGain -match '^-') { 'slot-delta negative' } elseif ([string]$assignment.SlotGain -match '^\+?0(?:\.0+)?$') { 'slot-delta neutral' } else { 'slot-delta positive' }
        $rows += "<div class=`"$rowClass`"><div><span class=`"position-chip`">$(ConvertTo-HtmlText $assignment.Slot)</span></div><div class=`"lineup-choice current`"><small>Current</small><strong>$(ConvertTo-HtmlText $assignment.Current)</strong><span class=`"lineup-player-projection`">$(ConvertTo-HtmlText $assignment.CurrentPoints) proj</span></div><div class=`"lineup-arrow`">&rarr;</div><div class=`"lineup-choice recommended`"><small>Recommended</small><strong>$(ConvertTo-HtmlText $assignment.Recommended)</strong><span class=`"lineup-player-projection`">$(ConvertTo-HtmlText $assignment.RecommendedPoints) proj</span></div><div class=`"$slotGainClass`"><strong>$(ConvertTo-HtmlText $assignment.SlotGain)</strong><span>slot delta</span></div><span class=`"$decisionClass`">$decision</span><div class=`"lineup-swap-action`">$swapCompareAction</div></div>"
'@
$core = Replace-ExactlyOnce -Text $core -Old $rowOld.TrimEnd() -New $rowNew.TrimEnd() -Contract 'Lineup Advisor slot projection delta'

$whyOld = 'Butler is not claiming a separate per-player delta that is not present in the evidence.'
$whyNew = 'Each scoreable row now shows the exact current and recommended weekly projections plus its slot delta. AutoFill still solves the lineup globally, so one slot can decline while coordinated moves improve the overall projected total.'
$core = Replace-ExactlyOnce -Text $core -Old $whyOld -New $whyNew -Contract 'Lineup Advisor slot-delta explanation'

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-942 BLOCKED: final manager CSS boundary is missing.'
}
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-942 BLOCKED: final manager CSS terminator is missing.'
}
$css = @'
/* BF-942 exact weekly projection delta evidence. */
.lineup-player-projection{display:block;margin-top:4px;color:var(--muted);font-size:12px}.slot-delta{display:flex;flex-direction:column;align-items:flex-end;gap:2px;font-weight:800}.slot-delta span{font-size:10px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}.slot-delta.positive strong{color:var(--good)}.slot-delta.negative strong{color:var(--danger)}.slot-delta.neutral strong,.slot-delta.unavailable strong{color:var(--muted)}@media(max-width:900px){.slot-delta{align-items:flex-start}}
'@
$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $css.TrimEnd() + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'projection_delta',
    'CurrentPoints',
    'RecommendedPoints',
    'SlotGain',
    'slot delta',
    'AutoFill still solves the lineup globally',
    'Compare this swap'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-942 BLOCKED: required projection-delta marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-942 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-942 Lineup Projection Delta applied.'
