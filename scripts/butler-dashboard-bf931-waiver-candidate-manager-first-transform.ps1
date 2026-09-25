param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-931 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

$candidateStart = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', [System.StringComparison]::Ordinal)
$candidateEnd = $text.IndexOf('function Send-HttpResponse {', $candidateStart, [System.StringComparison]::Ordinal)
if ($candidateStart -lt 0 -or $candidateEnd -le $candidateStart) {
    throw 'BF-931 BLOCKED: Waiver Candidate Detail renderer boundary is missing.'
}

$candidateBlock = $text.Substring($candidateStart, $candidateEnd - $candidateStart)

$currentContextOld = @'
    $currentContext = if ($isCurrent) {
        '<div class="current-context"><strong>Current governed ADD.</strong> This exact Sleeper ID is the already-audited ADD from Butler&apos;s current governed recommendation. The marker is traceability, not a new score or board ranking.</div>'
    } else { "" }
'@
$currentContextNew = @'
    $currentContext = if ($isCurrent) {
        '<div class="current-context"><strong>Current governed ADD.</strong> Butler&apos;s current governed waiver decision already identifies this player as the exact ADD. This marker is traceability, not a new score or board ranking.</div>'
    } else { "" }

    $candidateDecisionCopy = if ($isCurrent) {
        "This player is Butler's current governed ADD. Review the evidence below, then return to Waiver Board for the exact add/drop decision context."
    }
    else {
        "This player is in Butler's authorized waiver review pool. Use this profile as context only; inclusion here is not a recommendation or ranking."
    }
'@

$currentMatches = [regex]::Matches($candidateBlock, [regex]::Escape($currentContextOld.TrimEnd())).Count
if ($currentMatches -ne 1) {
    throw "BF-931 BLOCKED: candidate current-context block expected one match, found $currentMatches."
}
$candidateBlock = $candidateBlock.Replace($currentContextOld.TrimEnd(), $currentContextNew.TrimEnd())

$returnStart = $candidateBlock.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
$returnEnd = $candidateBlock.LastIndexOf('"@', [System.StringComparison]::Ordinal)
if ($returnStart -lt 0 -or $returnEnd -le $returnStart) {
    throw 'BF-931 BLOCKED: Waiver Candidate Detail HTML return is missing.'
}

$managerReturn = @'
    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Waiver Candidate</title><style>$css</style></head><body><main class="shell">
$header
<section class="panel">
  <div class="eyebrow">Waiver candidate</div>
  <div class="statusrow"><div><h1 class="headline">$(ConvertTo-HtmlText $Candidate.Name)</h1><p class="lede">$(ConvertTo-HtmlText $Candidate.Position) &middot; NFL $(ConvertTo-HtmlText $Candidate.Team)</p></div><div class="candidate-badges">$currentBadge<span class="lane $($lane.Class)">$(ConvertTo-HtmlText $lane.Label)</span></div></div>
  <div class="board-note"><span class="not-rank">NOT A RANKING.</span> This page inspects one exact BF-616 authorized candidate without changing Butler's current waiver decision.</div>
  <div class="next"><strong>What this means</strong><p>$(ConvertTo-HtmlText $candidateDecisionCopy)</p></div>
  $currentContext
  <div class="actions" style="margin-top:18px"><a class="button" href="/waivers">Back to Waiver Board</a></div>
</section>
<section class="panel">
  <div class="eyebrow">Player context</div>
  <h2 class="headline">Evidence snapshot</h2>
  <div class="candidate-facts">
    <div><strong>Status</strong>$(ConvertTo-HtmlText $Candidate.Status)</div>
    <div><strong>Injury</strong>$(ConvertTo-HtmlText $injuryText)</div>
    <div><strong>Depth</strong>$(ConvertTo-HtmlText $depthText)</div>
    <div><strong>BF-616 lane</strong>$(ConvertTo-HtmlText $Candidate.Lane)</div>
  </div>
  <div class="market"><strong>Market attention:</strong> add $(ConvertTo-HtmlText $Candidate.MarketAdd) / drop $(ConvertTo-HtmlText $Candidate.MarketDrop) / net $(ConvertTo-HtmlText $Candidate.MarketNet)</div>
  <div class="next"><strong>Why this player is here</strong><p>$(ConvertTo-HtmlText $laneCopy)</p></div>
  <details><summary>Evidence and audit details</summary><div class="tech"><div>Candidate Sleeper ID: $(ConvertTo-HtmlText $Candidate.SleeperId)</div><div>Candidate-supported comparators: $(ConvertTo-HtmlText $Candidate.SupportedComparators)</div><div>Eligible comparators: $(ConvertTo-HtmlText $Candidate.EligibleComparators)</div><div>BF-623 target: $(ConvertTo-HtmlText $target.Human)</div><div>Raw Sleeper league / roster: $(ConvertTo-HtmlText $target.SleeperLeagueId) / $(ConvertTo-HtmlText $target.RosterId)</div><div>Raw comparison identity: $(ConvertTo-HtmlText $target.RawComparison)</div><div>BF-623 target gate: $(ConvertTo-HtmlText $target.Gate)</div><div>BF-623 role: $(ConvertTo-HtmlText $target.Role)</div><div>Current decision state: $(ConvertTo-HtmlText $current.State)</div><div>Current audit ID: $(ConvertTo-HtmlText $current.AuditId)</div><div>Current ADD Sleeper ID: $(ConvertTo-HtmlText $current.SleeperId)</div><div>BF-629 current gate: $(ConvertTo-HtmlText $current.Bf629)</div><div>BF-631 current gate: $(ConvertTo-HtmlText $current.Bf631)</div><div>Audited BF-603 / BF-602: $(ConvertTo-HtmlText $current.AuditedLineageRaw)</div><div>Bundle BF-603 / BF-602: $(ConvertTo-HtmlText $current.BundleLineageRaw)</div><div>BF-614 methodology: $(ConvertTo-HtmlText $methodology)</div><div>BF-603 / BF-602: $(ConvertTo-HtmlText $lineage)</div><div>BF-616: $(ConvertTo-HtmlText $bf616)</div><div>BF-617: $(ConvertTo-HtmlText $bf617)</div></div></details>
</section>
<section class="panel boundary"><span class="lock">READ ONLY &middot; EXACT ID ONLY.</span> Candidate detail resolves only from the current reconciled BF-616 shortlist by exact Sleeper id. A Current governed ADD marker, when present, comes only from the existing audited recommendation after exact target and evidence-lineage reconciliation. It does not use name lookup, rerank candidates, score newcomers, change the recommendation, run BF-641, refresh evidence, set FAAB, or submit a Sleeper transaction.</section>
</main></body></html>
"@
'@

$candidateBlock = $candidateBlock.Substring(0, $returnStart) + $managerReturn.TrimEnd() + [Environment]::NewLine + $candidateBlock.Substring($returnEnd + 2)
$text = $text.Substring(0, $candidateStart) + $candidateBlock + $text.Substring($candidateEnd)

foreach ($required in @(
    'Waiver candidate',
    'What this means',
    'Evidence snapshot',
    'Why this player is here',
    '<details><summary>Evidence and audit details</summary>',
    'Candidate Sleeper ID:',
    'Back to Waiver Board',
    '$candidateDecisionCopy',
    'NOT A RANKING.',
    'READ ONLY &middot; EXACT ID ONLY.'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-931 BLOCKED: required candidate-detail marker is missing: $required"
    }
}

$installedStart = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', [System.StringComparison]::Ordinal)
$installedEnd = $text.IndexOf('function Send-HttpResponse {', $installedStart, [System.StringComparison]::Ordinal)
$installed = $text.Substring($installedStart, $installedEnd - $installedStart)
if ($installed -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-931 BLOCKED: Waiver Candidate Detail polish introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-931 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-931 Waiver Candidate Detail manager-first polish applied.'
