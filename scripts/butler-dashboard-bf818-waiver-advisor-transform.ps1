param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-818 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $first = $Text.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) {
        throw "BF-818 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-818 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$waiverStart = $text.IndexOf('function ConvertTo-WaiverHtml {', [System.StringComparison]::Ordinal)
$waiverEnd = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $waiverStart, [System.StringComparison]::Ordinal)
if ($waiverStart -lt 0 -or $waiverEnd -le $waiverStart) {
    throw 'BF-818 BLOCKED: Waiver Board renderer function boundary is missing.'
}

$waiverBlock = $text.Substring($waiverStart, $waiverEnd - $waiverStart)

$setupOld = @'
    $css = Get-SharedCss
    $header = Get-HeaderHtml -Target $target.Human -Active "waivers"
    $cards = ""
'@

$setupNew = @'
    $css = Get-SharedCss
    $header = Get-HeaderHtml -Target $target.Human -Active "waivers"

    $decisionState = [string]$current.State
    $decisionTitle = 'Waiver decision unavailable'
    $decisionStatus = 'UNAVAILABLE'
    $decisionClass = 'danger'
    $decisionHtml = 'No waiver action available.'
    $whyCopy = 'Butler does not currently have a supported waiver action for this state.'
    $nextCopy = 'Do not infer a move from the candidate board below. Review the detailed evidence only.'
    $pairHtml = ''

    switch ($decisionState) {
        'CURRENT_AND_ACTIONABLE' {
            if (-not $pair.Active) {
                throw 'BF-818 BLOCKED: actionable waiver state is missing the reconciled BF-652 ADD/DROP pair.'
            }
            $decisionTitle = 'Make this waiver move'
            $decisionStatus = 'MOVE READY'
            $decisionClass = 'good'
            $decisionHtml = "ADD <strong>$(ConvertTo-HtmlText $pair.Add.Name)</strong> / DROP <strong>$(ConvertTo-HtmlText $pair.Drop.Name)</strong>"
            $whyCopy = 'Butler already verified this exact audited ADD/DROP pair against the live roster and latest governed evidence.'
            $nextCopy = 'Review the exact pair and supporting evidence below. If you choose to act, make the transaction manually in Sleeper.'
            $pairHtml = "<div class=`"moves`"><div class=`"move add`"><h2>ADD</h2><div class=`"player-name`">$(ConvertTo-HtmlText $pair.Add.Name)</div><div class=`"player-meta`">$(ConvertTo-HtmlText $pair.Add.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Add.Team)</div></div><div class=`"move drop`"><h2>DROP</h2><div class=`"player-name`">$(ConvertTo-HtmlText $pair.Drop.Name)</div><div class=`"player-meta`">$(ConvertTo-HtmlText $pair.Drop.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Drop.Team)</div></div></div>"
        }
        'CURRENT_REFRESH_RECOMMENDED' {
            if (-not $pair.Active) {
                throw 'BF-818 BLOCKED: refresh-recommended waiver state is missing the reconciled BF-652 ADD/DROP pair.'
            }
            $decisionTitle = 'Refresh before acting'
            $decisionStatus = 'REFRESH FIRST'
            $decisionClass = 'warn'
            $decisionHtml = "Current audited pair: ADD <strong>$(ConvertTo-HtmlText $pair.Add.Name)</strong> / DROP <strong>$(ConvertTo-HtmlText $pair.Drop.Name)</strong>"
            $whyCopy = 'The exact audited move remains live-actionable, but its persisted waiver evidence crossed Butler''s approved warning threshold.'
            $nextCopy = 'Use Butler''s existing governed refresh flow before deciding whether to make this move.'
            $pairHtml = "<div class=`"moves`"><div class=`"move add`"><h2>ADD</h2><div class=`"player-name`">$(ConvertTo-HtmlText $pair.Add.Name)</div><div class=`"player-meta`">$(ConvertTo-HtmlText $pair.Add.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Add.Team)</div></div><div class=`"move drop`"><h2>DROP</h2><div class=`"player-name`">$(ConvertTo-HtmlText $pair.Drop.Name)</div><div class=`"player-meta`">$(ConvertTo-HtmlText $pair.Drop.Position) &middot; NFL $(ConvertTo-HtmlText $pair.Drop.Team)</div></div></div>"
        }
        'NO_TRANSACTION_TO_ACT_ON' {
            $decisionTitle = 'No waiver move proven'
            $decisionStatus = 'NO MOVE'
            $decisionClass = 'done'
            $decisionHtml = 'Hold. Butler has no add/drop move to recommend from this evidence frame.'
            $whyCopy = 'The current waiver review completed without proving one clear add/drop transaction. This is a valid no-action result, not missing recommendation data.'
            $nextCopy = 'No waiver action is needed from this decision. The board below remains available only for governed review context.'
        }
        'TRANSACTION_PENDING_DO_NOT_DUPLICATE' {
            $decisionTitle = 'Waiver move is pending'
            $decisionStatus = 'WAIT'
            $decisionClass = 'warn'
            $decisionHtml = 'Do not submit the same waiver move again.'
            $whyCopy = 'Sleeper is already processing the exact audited transaction, so another submission could duplicate the move.'
            $nextCopy = 'Wait for Sleeper to finish processing the transaction before asking Butler for the next decision.'
        }
        'TRANSACTION_ALREADY_COMPLETE' {
            $decisionTitle = 'Waiver move complete'
            $decisionStatus = 'COMPLETE'
            $decisionClass = 'done'
            $decisionHtml = 'No action needed.'
            $whyCopy = 'Sleeper shows the exact audited waiver transaction as complete.'
            $nextCopy = 'Keep the completed decision for history and wait for the next governed waiver review.'
        }
        'STALE_DO_NOT_ACT' {
            $decisionTitle = 'Waiver decision blocked by stale evidence'
            $decisionStatus = 'DO NOT ACT'
            $decisionClass = 'danger'
            $decisionHtml = 'Do not make a waiver move from this decision.'
            $whyCopy = 'A hard safety gate marked the current decision unsafe to act on with the available evidence.'
            $nextCopy = 'Wait for Butler to produce a new governed decision before taking waiver action.'
        }
        default {
            $decisionTitle = 'Waiver decision unavailable'
            $decisionStatus = 'UNAVAILABLE'
            $decisionClass = 'danger'
            $decisionHtml = 'No waiver action available.'
            $whyCopy = 'Butler does not currently have a supported waiver action for this governed state.'
            $nextCopy = 'Do not infer a move from candidate order or descriptive market data.'
        }
    }

    $waiverAdvisor = @"
<section class="panel">
  <div class="eyebrow">Waiver Advisor</div>
  <div class="statusrow"><div><h1 class="headline">$(ConvertTo-HtmlText $decisionTitle)</h1><p class="lede">Butler leads with the current manager decision; the governed candidate board stays below for supporting context.</p></div><span class="status $decisionClass">$(ConvertTo-HtmlText $decisionStatus)</span></div>
  <div class="moves"><div class="move"><h2>Decision</h2><div class="player-meta">$decisionHtml</div></div><div class="move"><h2>Why</h2><div class="player-meta">$(ConvertTo-HtmlText $whyCopy)</div></div></div>
  $pairHtml
  <div class="next"><strong>What to do next</strong><p>$(ConvertTo-HtmlText $nextCopy)</p></div>
  <div class="subtle" style="margin-top:12px"><strong>Read only:</strong> Butler did not submit, cancel, or replace a Sleeper waiver transaction from this page.</div>
</section>
"@

    $cards = ""
'@

$waiverBlock = Replace-ExactlyOnce -Text $waiverBlock -Old $setupOld.TrimEnd() -New $setupNew.TrimEnd() -Contract 'Waiver Advisor state composition'

$returnOld = @'
$header
<section class="panel">
'@
$returnNew = @'
$header
$waiverAdvisor
<section class="panel">
'@
$waiverBlock = Replace-ExactlyOnce -Text $waiverBlock -Old $returnOld.TrimEnd() -New $returnNew.TrimEnd() -Contract 'Waiver Advisor placement above candidate board'

$text = $text.Substring(0, $waiverStart) + $waiverBlock + $text.Substring($waiverEnd)

foreach ($required in @(
    'Waiver Advisor',
    'Make this waiver move',
    'Refresh before acting',
    'No waiver move proven',
    'Waiver move is pending',
    'Waiver move complete',
    'Waiver decision blocked by stale evidence',
    'MOVE READY',
    'NO MOVE',
    'DO NOT ACT'
)) {
    if ($text -notmatch [regex]::Escape($required)) {
        throw "BF-818 BLOCKED: Waiver Advisor state '$required' is missing."
    }
}

if ($text -notmatch 'Current governed ADD' -or $text -notmatch 'Paired audited DROP' -or $text -notmatch 'NOT A RANKING') {
    throw 'BF-818 BLOCKED: existing Waiver Board traceability or non-ranking boundary regressed.'
}
if ($text -notmatch 'function ConvertTo-WaiverCandidateDetailHtml') {
    throw 'BF-818 BLOCKED: existing waiver candidate detail surface regressed.'
}

if ($setupNew -match 'Invoke-RestMethod|Invoke-ButlerReadOnly|Method = "POST"|sleeperLiveWaiverFinalRecommendationBundle|BF-641') {
    throw 'BF-818 BLOCKED: Waiver Advisor presentation introduced provider, read-source, recommendation, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

# BF-822: after BF-817 has installed the manager Lineup Advisor, replace only the
# raw AutoFill roster-drift failure with a manager-facing path to the existing
# explicit governed refresh confirmation. Standalone dashboard transforms remain valid.
$stagedCore = Join-Path (Split-Path -Parent $DashboardPath) 'butler-app-shell-core-single.ps1'
if (Test-Path -LiteralPath $stagedCore -PathType Leaf) {
    $bf822Transform = Join-Path $PSScriptRoot 'butler-app-bf822-autofill-roster-drift-recovery-transform.ps1'
    if (-not (Test-Path -LiteralPath $bf822Transform -PathType Leaf)) {
        throw "BF-822 BLOCKED: AutoFill roster-drift recovery transform not found at $bf822Transform"
    }
    & $bf822Transform -CorePath $stagedCore
}

# BF-819: after the dedicated Waiver Advisor is installed, simplify the Dashboard into
# manager-first scan mode with the existing governed proof available on demand.
$bf819Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf819-manager-proof-mode-transform.ps1'
if (-not (Test-Path -LiteralPath $bf819Transform -PathType Leaf)) {
    throw "BF-819 BLOCKED: Manager/Proof Mode transform not found at $bf819Transform"
}
& $bf819Transform -DashboardPath $DashboardPath
