param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-873 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-873 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$waiverStart = $text.IndexOf('function ConvertTo-WaiverHtml {', [System.StringComparison]::Ordinal)
$waiverEnd = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $waiverStart, [System.StringComparison]::Ordinal)
if ($waiverStart -lt 0 -or $waiverEnd -le $waiverStart) {
    throw 'BF-873 BLOCKED: Waiver Board renderer function boundary is missing.'
}
$waiverBlock = $text.Substring($waiverStart, $waiverEnd - $waiverStart)

# Keep the exact governed pair visible while removing raw provider IDs from the
# BF-834 first-scan pair only. Older supporting variables can retain similar strings.
$pairBlockStart = $waiverBlock.IndexOf('    $waiverPairHtml = ""', [System.StringComparison]::Ordinal)
$pairBlockEnd = $waiverBlock.IndexOf('    $waiverNextActionCopy = switch', $pairBlockStart, [System.StringComparison]::Ordinal)
if ($pairBlockStart -lt 0 -or $pairBlockEnd -le $pairBlockStart) {
    throw 'BF-873 BLOCKED: BF-834 governed pair presentation block is missing.'
}
$pairBlock = $waiverBlock.Substring($pairBlockStart, $pairBlockEnd - $pairBlockStart)

$addMetaOld = '$(ConvertTo-HtmlText $pair.Add.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Add.Team) &middot; Sleeper $(ConvertTo-HtmlText $pair.Add.SleeperId)'
$addMetaNew = '$(ConvertTo-HtmlText $pair.Add.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Add.Team)'
$pairBlock = Replace-ExactlyOnce -Text $pairBlock -Old $addMetaOld -New $addMetaNew -Contract 'ADD first-scan metadata'

$dropMetaOld = '$(ConvertTo-HtmlText $pair.Drop.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Drop.Team) &middot; Sleeper $(ConvertTo-HtmlText $pair.Drop.SleeperId)'
$dropMetaNew = '$(ConvertTo-HtmlText $pair.Drop.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Drop.Team)'
$pairBlock = Replace-ExactlyOnce -Text $pairBlock -Old $dropMetaOld -New $dropMetaNew -Contract 'DROP first-scan metadata'

$pairNoteOld = 'This is Butler''s already-audited exact pair; it is not inferred from board order.'
$pairNoteNew = 'This is Butler''s exact governed pair; board order does not create this decision.'
$pairBlock = Replace-ExactlyOnce -Text $pairBlock -Old $pairNoteOld -New $pairNoteNew -Contract 'pair first-scan copy'

$waiverBlock = $waiverBlock.Substring(0, $pairBlockStart) + $pairBlock + $waiverBlock.Substring($pairBlockEnd)

$returnStart = $waiverBlock.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
if ($returnStart -lt 0) {
    throw 'BF-873 BLOCKED: Waiver Board HTML return is missing.'
}

$decisionSupportPrelude = @'
    # BF-873 is presentation-only. History is contextual, not primary navigation;
    # BF-870 already provides the global manager navigation.
    $waiverHistoryStates = @(
        "CURRENT_AND_ACTIONABLE",
        "CURRENT_REFRESH_RECOMMENDED",
        "TRANSACTION_ALREADY_COMPLETE",
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE",
        "STALE_DO_NOT_ACT",
        "NO_TRANSACTION_TO_ACT_ON"
    )
    $waiverHistoryLink = if ($waiverHistoryStates -ccontains [string]$current.State) {
        '<a class="waiver-history-link" href="/history">Decision History</a>'
    }
    else {
        ''
    }

    $waiverPairDetailHtml = if ($pair.Active) {
        "<div>Pair ADD Sleeper ID: $(ConvertTo-HtmlText $pair.AddSleeperId)</div><div>Pair DROP Sleeper ID: $(ConvertTo-HtmlText $pair.DropSleeperId)</div>"
    }
    else {
        ""
    }

'@
$waiverBlock = $waiverBlock.Insert($returnStart, $decisionSupportPrelude)

$heroOld = @'
<section class="panel waiver-decision-hero">
  <div class="eyebrow">Butler waiver decision</div>
  <div class="waiver-decision-head"><div><h1 class="headline">$(ConvertTo-HtmlText $waiverDecisionTitle)</h1><p class="lede">$(ConvertTo-HtmlText $waiverDecisionCopy)</p><div class="waiver-state-line">Governed state: $(ConvertTo-HtmlText $current.State) &middot; authorized review pool: $($counts.Total)</div></div><div class="status $waiverDecisionClass">$(ConvertTo-HtmlText $waiverDecisionStatus)</div></div>
  $waiverPairHtml
  <div class="waiver-next"><strong>What to do now</strong><p>$(ConvertTo-HtmlText $waiverNextActionCopy)</p></div>
  <div class="actions" style="margin-top:15px"><a class="button" href="/history">Decision History</a><a class="button" href="/team">My Team</a><a class="button" href="/">Dashboard</a></div>
</section>
'@

$heroNew = @'
<section class="panel waiver-decision-hero">
  <div class="eyebrow">Butler waiver decision</div>
  <div class="waiver-decision-head"><div><h1 class="headline">$(ConvertTo-HtmlText $waiverDecisionTitle)</h1><p class="lede">$(ConvertTo-HtmlText $waiverDecisionCopy)</p></div><div class="status $waiverDecisionClass">$(ConvertTo-HtmlText $waiverDecisionStatus)</div></div>
  $waiverPairHtml
  <div class="waiver-next"><strong>Next step</strong><p>$(ConvertTo-HtmlText $waiverNextActionCopy)</p>$waiverHistoryLink</div>
  <details class="waiver-decision-details"><summary>Decision details</summary><div class="tech"><div>Governed state: $(ConvertTo-HtmlText $current.State)</div><div>Authorized review pool: $($counts.Total)</div><div>Current audit ID: $(ConvertTo-HtmlText $current.AuditId)</div>$waiverPairDetailHtml</div></details>
</section>
'@
$waiverBlock = Replace-ExactlyOnce -Text $waiverBlock -Old $heroOld.TrimEnd() -New $heroNew.TrimEnd() -Contract 'decision-first Waiver hero'

$styleEnd = $waiverBlock.LastIndexOf('</style>', [System.StringComparison]::Ordinal)
if ($styleEnd -lt 0) {
    throw 'BF-873 BLOCKED: Waiver Board style terminator is missing.'
}
$polishCss = @'
/* BF-873 Waiver Board decision-first polish. */
.waiver-next{display:grid;gap:6px}.waiver-history-link{display:inline-flex;width:max-content;margin-top:5px;color:var(--turf-deep);font-size:12px;font-weight:700;text-decoration:none}.waiver-history-link:hover{text-decoration:underline}.waiver-decision-details{margin-top:12px}.waiver-decision-details .tech{display:grid;gap:3px}.waiver-pair-note{max-width:72ch}
'@
$waiverBlock = $waiverBlock.Insert($styleEnd, $polishCss.TrimEnd() + "`n")

$text = $text.Substring(0, $waiverStart) + $waiverBlock + $text.Substring($waiverEnd)

foreach ($required in @(
    'BF-873 is presentation-only',
    'Next step',
    'Decision details',
    '$waiverHistoryLink',
    '$waiverPairDetailHtml',
    'Butler''s exact governed pair; board order does not create this decision.',
    'Authorized review pool',
    'NOT A RANKING.',
    'Technical and audit details'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-873 BLOCKED: required Waiver polish marker is missing: $required"
    }
}

$installedWaiverEnd = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $waiverStart, [System.StringComparison]::Ordinal)
$installedWaiver = $text.Substring($waiverStart, $installedWaiverEnd - $waiverStart)
if ($installedWaiver -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-873 BLOCKED: Waiver polish introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-873 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-873 Waiver Board decision-first polish applied.'
