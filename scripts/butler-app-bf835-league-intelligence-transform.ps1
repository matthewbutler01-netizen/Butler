param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-835 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)
$leagueStart = $core.IndexOf('function ConvertTo-LeagueHtml {', [System.StringComparison]::Ordinal)
$leagueEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $leagueStart, [System.StringComparison]::Ordinal)
if ($leagueStart -lt 0 -or $leagueEnd -le $leagueStart) {
    throw 'BF-835 BLOCKED: League renderer function boundary is missing.'
}
$leagueBlock = $core.Substring($leagueStart, $leagueEnd - $leagueStart)

$oldLeader = '$leadersHtml += "<article class=`"card`"><div class=`"rank`">Rank $(ConvertTo-HtmlText $leader.Rank)</div><div class=`"name`">$(ConvertTo-HtmlText $leader.Name)</div><div class=`"total`">$(ConvertTo-HtmlText $leader.Total)</div><div class=`"meta`">Players $(ConvertTo-HtmlText $leader.Players) &middot; Picks $(ConvertTo-HtmlText $leader.Picks)</div><div class=`"meta`">Team ID $(ConvertTo-HtmlText $leader.TeamId)</div></article>"'
$newLeader = '$leadersHtml += "<article class=`"card`"><div class=`"rank`">Rank $(ConvertTo-HtmlText $leader.Rank)</div><div class=`"name`">$(ConvertTo-HtmlText $leader.Name)</div><div class=`"total`">$(ConvertTo-HtmlText $leader.Total)</div><div class=`"meta`">Players $(ConvertTo-HtmlText $leader.Players) &middot; Picks $(ConvertTo-HtmlText $leader.Picks)</div><details><summary>Franchise identity</summary><div class=`"technical`">Team ID $(ConvertTo-HtmlText $leader.TeamId)</div></details></article>"'
$leaderMatches = [regex]::Matches($leagueBlock, [regex]::Escape($oldLeader)).Count
if ($leaderMatches -ne 1) {
    throw "BF-835 BLOCKED: franchise leader presentation contract expected one match, found $leaderMatches."
}
$leagueBlock = $leagueBlock.Replace($oldLeader, $newLeader)

$oldUnavailable = @'
        $leadersHtml = '<div class="empty">Franchise rankings are unavailable until Butler reports current asset coverage as READY. No leader is inferred.</div>'
'@
$newUnavailable = @'
        $leadersHtml = '<div class="empty">League comparison is waiting on complete current asset coverage. Butler will not infer a leader until that governed evidence is ready.</div>'
'@
if ([regex]::Matches($leagueBlock, [regex]::Escape($oldUnavailable.Trim())).Count -ne 1) {
    throw 'BF-835 BLOCKED: franchise-unavailable copy contract drifted.'
}
$leagueBlock = $leagueBlock.Replace($oldUnavailable.Trim(), $newUnavailable.Trim())

$oldMovementUnavailable = @'
        $movementHtml = '<div class="empty">Value movement is unavailable until comparable provider snapshots exist. Butler does not manufacture a trend.</div>'
'@
$newMovementUnavailable = @'
        $movementHtml = '<div class="empty">No comparable history is available yet. Butler will not manufacture a trend from unmatched evidence.</div>'
'@
if ([regex]::Matches($leagueBlock, [regex]::Escape($oldMovementUnavailable.Trim())).Count -ne 1) {
    throw 'BF-835 BLOCKED: movement-unavailable copy contract drifted.'
}
$leagueBlock = $leagueBlock.Replace($oldMovementUnavailable.Trim(), $newMovementUnavailable.Trim())

$oldNoActions = @'
        $actionsHtml = '<div class="empty">No governed next action is currently required.</div>'
'@
$newNoActions = @'
        $actionsHtml = '<div class="empty">Nothing requires your attention from the current governed league-health frame.</div>'
'@
if ([regex]::Matches($leagueBlock, [regex]::Escape($oldNoActions.Trim())).Count -ne 1) {
    throw 'BF-835 BLOCKED: no-actions copy contract drifted.'
}
$leagueBlock = $leagueBlock.Replace($oldNoActions.Trim(), $newNoActions.Trim())

$oldCommand = '$commandHtml = if ([string]::IsNullOrWhiteSpace($action.Command)) { "" } else { "<input class=`"command`" readonly value=`"$(ConvertTo-HtmlText $action.Command)`">" }'
$newCommand = '$commandHtml = if ([string]::IsNullOrWhiteSpace($action.Command)) { "" } else { "<details><summary>Manual technical command</summary><input class=`"command`" readonly value=`"$(ConvertTo-HtmlText $action.Command)`"></details>" }'
$commandMatches = [regex]::Matches($leagueBlock, [regex]::Escape($oldCommand)).Count
if ($commandMatches -ne 1) {
    throw "BF-835 BLOCKED: League manual-command presentation contract expected one match, found $commandMatches."
}
$leagueBlock = $leagueBlock.Replace($oldCommand, $newCommand)

$oldState = @'
    $attentionText = if ($View.RequiresAttention) { "Needs attention" } else { "No required blocker" }
    $coreText = if ($View.CoreReady) { "Ready" } else { "Not ready" }
'@
$newState = @'
    $attentionText = if ($View.RequiresAttention) { "Review needed" } else { "No required blocker" }
    $coreText = if ($View.CoreReady) { "Ready" } else { "Coverage incomplete" }
    $leagueStateTitle = if ($View.RequiresAttention) {
        "League data needs attention"
    }
    elseif ($View.CoreReady) {
        "League intelligence is ready"
    }
    else {
        "League intelligence is incomplete"
    }
    $leagueStateCopy = if ($View.RequiresAttention) {
        "Butler found at least one governed league-health item that needs review. Start with What deserves attention below."
    }
    elseif ($View.CoreReady) {
        "Current governed league evidence is ready. Use League landscape and What changed to understand the current frame."
    }
    else {
        "Some governed league evidence is incomplete. Butler shows only the sections that pass their existing readiness gates and will not fill gaps with inferred data."
    }
'@
$stateMatches = [regex]::Matches($leagueBlock, [regex]::Escape($oldState.Trim())).Count
if ($stateMatches -ne 1) {
    throw "BF-835 BLOCKED: League state presentation contract expected one match, found $stateMatches."
}
$leagueBlock = $leagueBlock.Replace($oldState.Trim(), $newState.Trim())

$oldPanels = @'
<section class="panel"><div class="eyebrow">League intelligence</div><div class="statusrow"><div><h1 class="headline">$(ConvertTo-HtmlText $View.LeagueName)</h1><p class="lede">Butler's existing governed league overview, presented as an app view without adding a new ranking or strategy model.</p></div><div class="status $statusClass">$(ConvertTo-HtmlText $View.Status)</div></div><div class="stats"><div class="stat"><strong>Core analysis</strong><span>$(ConvertTo-HtmlText $coreText)</span></div><div class="stat"><strong>Source</strong><span>$(ConvertTo-HtmlText $View.Source)</span></div><div class="stat"><strong>Attention</strong><span>$(ConvertTo-HtmlText $attentionText)</span></div></div></section>
<section class="panel"><div class="eyebrow">Safe franchise context</div><h2>Franchise leaders</h2><p class="lede">Shown only when Butler's existing franchise-readiness gate authorizes rankings.</p><div class="grid">$leadersHtml</div></section>
<section class="panel"><div class="eyebrow">Comparable history</div><h2>Value movement</h2><p class="lede">$movementSummary</p><div class="movers">$movementHtml</div></section>
<section class="panel"><div class="eyebrow">Governed guidance</div><h2>Next actions</h2><p class="lede">These are Butler's existing deterministic league-health actions. Commands are displayed for manual use only and are never executed by this page.</p><div class="actions">$actionsHtml</div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> BF-667 presents the existing governed league overview. It does not rerank franchises, create strategy labels, refresh values, mutate evidence, run waiver decisions, set FAAB, execute trades, or submit Sleeper transactions.</section>
'@
$newPanels = @'
<section class="panel"><div class="eyebrow">League status</div><div class="statusrow"><div><h1 class="headline">$(ConvertTo-HtmlText $leagueStateTitle)</h1><p class="lede">$(ConvertTo-HtmlText $leagueStateCopy)</p></div><div class="status $statusClass">$(ConvertTo-HtmlText $View.Status)</div></div><div class="stats"><div class="stat"><strong>League</strong><span>$(ConvertTo-HtmlText $View.LeagueName)</span></div><div class="stat"><strong>Evidence</strong><span>$(ConvertTo-HtmlText $coreText)</span></div><div class="stat"><strong>Attention</strong><span>$(ConvertTo-HtmlText $attentionText)</span></div></div><details><summary>Source details</summary><div class="technical">$(ConvertTo-HtmlText $View.Source) &middot; league $(ConvertTo-HtmlText $View.LeagueId)</div></details></section>
<section class="panel"><div class="eyebrow">What deserves attention</div><h2>Next steps</h2><p class="lede">Butler's existing deterministic league-health guidance is shown here in manager language. Any raw manual command is secondary technical detail and is never executed by this page.</p><div class="actions">$actionsHtml</div></section>
<section class="panel"><div class="eyebrow">League landscape</div><h2>Authorized franchise leaders</h2><p class="lede">Shown only when Butler's existing franchise-readiness gate has enough current asset coverage. This is the existing governed context, not a new ranking formula.</p><div class="grid">$leadersHtml</div></section>
<section class="panel"><div class="eyebrow">What changed</div><h2>Value movement</h2><p class="lede">$movementSummary</p><div class="movers">$movementHtml</div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> League Intelligence presents Butler's existing governed league state, authorized franchise context, comparable movement, and deterministic guidance. It does not create a new ranking model, predict outcomes, refresh values automatically, execute commands, or submit Sleeper transactions.</section>
'@
$panelMatches = [regex]::Matches($leagueBlock, [regex]::Escape($oldPanels.Trim())).Count
if ($panelMatches -ne 1) {
    throw "BF-835 BLOCKED: League panel hierarchy contract expected one match, found $panelMatches."
}
$leagueBlock = $leagueBlock.Replace($oldPanels.Trim(), $newPanels.Trim())

$core = $core.Substring(0, $leagueStart) + $leagueBlock + $core.Substring($leagueEnd)

foreach ($required in @(
    'League data needs attention',
    'League intelligence is ready',
    'League intelligence is incomplete',
    'League landscape',
    'Authorized franchise leaders',
    'What changed',
    'What deserves attention',
    'Manual technical command',
    'will not fill gaps with inferred data',
    'does not create a new ranking model'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-835 BLOCKED: required League Intelligence marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function ConvertTo-LeagueHtml {', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installedBlock = $core.Substring($installedStart, $installedEnd - $installedStart)
if ($installedBlock -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-835 BLOCKED: League Intelligence presentation introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    $parseSummary = ($parseErrors | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-835 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
