param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($path in @($CorePath, $DashboardPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "BF-1004 BLOCKED: required staged source not found at $path"
    }
}

function Get-Bf1004Ast {
    param([Parameter(Mandatory = $true)][string]$Text,[Parameter(Mandatory = $true)][string]$Contract)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text,[ref]$tokens,[ref]$errors)
    if (@($errors).Count -gt 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-1004 BLOCKED: $Contract failed PowerShell parse: $summary"
    }
    return $ast
}

function Get-Bf1004Function {
    param([Parameter(Mandatory = $true)]$Ast,[Parameter(Mandatory = $true)][string]$Name,[Parameter(Mandatory = $true)][string]$Contract)
    $matches = @($Ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name
    },$true))
    if ($matches.Count -ne 1) {
        throw "BF-1004 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
    }
    return $matches[0]
}

function Replace-Bf1004ExactlyOnce {
    param([Parameter(Mandatory = $true)][string]$Text,[Parameter(Mandatory = $true)][string]$Old,[Parameter(Mandatory = $true)][string]$New,[Parameter(Mandatory = $true)][string]$Contract)
    $count = [regex]::Matches($Text,[regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-1004 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old,$New)
}

$dashboard = [IO.File]::ReadAllText($DashboardPath)
$dashboardAst = Get-Bf1004Ast -Text $dashboard -Contract 'pre-transform Dashboard'

$dashboardFn = Get-Bf1004Function -Ast $dashboardAst -Name 'ConvertTo-DashboardHtml' -Contract 'Dashboard renderer'
$dashboardFunction = $dashboardFn.Extent.Text
$returnAt = $dashboardFunction.LastIndexOf('    return @"',[System.StringComparison]::Ordinal)
if ($returnAt -lt 0) {
    throw 'BF-1004 BLOCKED: Dashboard final return marker is missing.'
}
$snapshotPrelude = @'
    # BF-1004 exposes only the saved Lineup Review week to the already-existing
    # post-render current-matchup reconciliation. No new read is introduced.
    $bf1004SnapshotWeek = if ($null -ne $lineupSnapshot) { [string]$lineupSnapshot.Week } else { '' }
    $bf1004SnapshotFrame = if ([string]::IsNullOrWhiteSpace($bf1004SnapshotWeek)) {
        ''
    }
    else {
        '<div class="butler-lineup-snapshot-frame" data-lineup-week="' + (ConvertTo-HtmlText $bf1004SnapshotWeek) + '" hidden></div>'
    }

'@
$dashboardFunction = $dashboardFunction.Insert($returnAt,$snapshotPrelude)
$dashboardFunction = Replace-Bf1004ExactlyOnce -Text $dashboardFunction -Old '<div class="butler-refresh-contract" hidden>' -New '$bf1004SnapshotFrame<div class="butler-refresh-contract" hidden>' -Contract 'Dashboard snapshot frame rendering'
$dashboard = $dashboard.Substring(0,$dashboardFn.Extent.StartOffset) + $dashboardFunction + $dashboard.Substring($dashboardFn.Extent.EndOffset)

$dashboardAst = Get-Bf1004Ast -Text $dashboard -Contract 'snapshot-framed Dashboard'
$attentionFn = Get-Bf1004Function -Ast $dashboardAst -Name 'Get-Bf979SnapshotWeeklyAttentionHtml' -Contract 'cached Weekly Attention renderer'
$attentionFunction = $attentionFn.Extent.Text
$attentionFunction = Replace-Bf1004ExactlyOnce -Text $attentionFunction -Old 'return "<section id=`"weekly-attention`" class=`"panel`">' -New 'return "<section id=`"weekly-attention`" class=`"panel dashboard-weekly-attention`">' -Contract 'Dashboard Weekly Attention marker'
$dashboard = $dashboard.Substring(0,$attentionFn.Extent.StartOffset) + $attentionFunction + $dashboard.Substring($attentionFn.Extent.EndOffset)

$core = [IO.File]::ReadAllText($CorePath)
$coreAst = Get-Bf1004Ast -Text $core -Contract 'pre-transform core'
$matchupFn = Get-Bf1004Function -Ast $coreAst -Name 'Add-DashboardMatchupSummary' -Contract 'Dashboard matchup reconciliation'
$matchupFunction = $matchupFn.Extent.Text

$stateOld = @'
    $title = 'Opponent unavailable'
    $copy = 'Matchup evidence is unavailable or stale; open the matchup to review the current frame.'
    $status = 'MATCHUP DATA NEEDED'
    $statusClass = 'warn'
    $opponentHrefId = ''
'@.TrimEnd()
$stateNew = @'
    $title = 'Opponent unavailable'
    $copy = 'Matchup evidence is unavailable or stale; open the matchup to review the current frame.'
    $status = 'MATCHUP DATA NEEDED'
    $statusClass = 'warn'
    $opponentHrefId = ''
    $currentMatchupWeek = 0
'@.TrimEnd()
$matchupFunction = Replace-Bf1004ExactlyOnce -Text $matchupFunction -Old $stateOld -New $stateNew -Contract 'current matchup week state'

$confirmedOld = @'
        $copy = 'Opponent and week are verified for this roster; open the matchup for full context.'
        $status = 'OPPONENT CONFIRMED'
        $statusClass = 'good'
        $opponentHrefId = [System.Uri]::EscapeDataString([string]$matchup.OpponentTeamId)
'@.TrimEnd()
$confirmedNew = @'
        $copy = 'Opponent and week are verified for this roster; open the matchup for full context.'
        $status = 'OPPONENT CONFIRMED'
        $statusClass = 'good'
        $currentMatchupWeek = [int]$matchup.Week
        $opponentHrefId = [System.Uri]::EscapeDataString([string]$matchup.OpponentTeamId)
'@.TrimEnd()
$matchupFunction = Replace-Bf1004ExactlyOnce -Text $matchupFunction -Old $confirmedOld -New $confirmedNew -Contract 'confirmed current matchup week'

$reconcileAnchor = '    $opponentTools = if (-not [string]::IsNullOrWhiteSpace($opponentHrefId)) {'
$reconcileAt = $matchupFunction.IndexOf($reconcileAnchor,[System.StringComparison]::Ordinal)
if ($reconcileAt -lt 0) {
    throw 'BF-1004 BLOCKED: finalized BF-992 opponent-tools reconciliation anchor is missing.'
}

$reconcile = @'
    # BF-1004: the Dashboard is rendered before this function resolves the exact
    # current matchup week. Reconcile any saved Lineup Review only after that
    # existing read proves the current week. Old player holds/recommendations
    # must never carry into a new week as current advice.
    $snapshotFrame = [regex]::Match(
        $Html,
        '<div class="butler-lineup-snapshot-frame" data-lineup-week="(?<week>\d+)" hidden></div>'
    )
    if ($currentMatchupWeek -gt 0 -and $snapshotFrame.Success) {
        $savedLineupWeek = 0
        if ([int]::TryParse([string]$snapshotFrame.Groups['week'].Value,[ref]$savedLineupWeek) -and
            $savedLineupWeek -gt 0 -and
            $savedLineupWeek -ne $currentMatchupWeek) {

            $staleLineupWasPrimary = [regex]::IsMatch(
                $Html,
                '(?is)<article class="manager-decision-card primary">.*?<div class="manager-kind">Lineup</div>.*?</article>'
            )

            $attentionPattern = [regex]::new(
                '(?is)<section id="weekly-attention" class="panel dashboard-weekly-attention">.*?</section>'
            )
            $freshAttention = '<section id="weekly-attention" class="panel dashboard-weekly-attention"><div class="statusrow"><div><div class="eyebrow">Weekly attention</div><h2>Review Week ' +
                (ConvertTo-HtmlText $currentMatchupWeek) +
                ' lineup</h2><p class="lede">The saved Lineup Review is from Week ' +
                (ConvertTo-HtmlText $savedLineupWeek) +
                '. Butler will not carry those player holds or lineup recommendations into Week ' +
                (ConvertTo-HtmlText $currentMatchupWeek) +
                '.</p><p class="meta">Run a new read-only Lineup Review before using weekly lineup advice.</p></div><span class="status warn">NEW WEEK</span></div><div class="button-row"><a class="btn btn-primary" href="/team/autofill">Review Week ' +
                (ConvertTo-HtmlText $currentMatchupWeek) +
                ' lineup</a></div></section>'
            if ($attentionPattern.IsMatch($Html)) {
                $Html = $attentionPattern.Replace(
                    $Html,
                    [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $freshAttention },
                    1
                )
            }

            $lineupCardPattern = [regex]::new(
                '(?is)<article class="week-glance-card"><div class="week-kind">Lineup</div>.*?</article>'
            )
            $currentLineupCard = '<article class="week-glance-card"><div class="week-kind">Lineup</div><div class="week-title">Week ' +
                (ConvertTo-HtmlText $currentMatchupWeek) +
                ' lineup has not been reviewed</div><div class="subtle">The saved Week ' +
                (ConvertTo-HtmlText $savedLineupWeek) +
                ' review is expired for this weekly frame.</div><div class="status done">NOT REVIEWED</div><div class="week-action"><a href="/team/autofill">Review Week ' +
                (ConvertTo-HtmlText $currentMatchupWeek) +
                ' lineup &rarr;</a></div></article>'
            if ($lineupCardPattern.IsMatch($Html)) {
                $Html = $lineupCardPattern.Replace(
                    $Html,
                    [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $currentLineupCard },
                    1
                )
            }

            $lineupDecisionPattern = [regex]::new(
                '(?is)<article class="(?<class>manager-decision-card(?: primary)?)"><div class="manager-priority-index">(?<priority>[^<]+)</div><div class="manager-decision-main"><div class="manager-kind">Lineup</div>.*?</article>'
            )
            $Html = $lineupDecisionPattern.Replace(
                $Html,
                [System.Text.RegularExpressions.MatchEvaluator]{
                    param($m)
                    $cardClass = $m.Groups['class'].Value
                    $priority = $m.Groups['priority'].Value
                    return '<article class="' + $cardClass + '"><div class="manager-priority-index">' +
                        $priority +
                        '</div><div class="manager-decision-main"><div class="manager-kind">Lineup</div><h3>Week ' +
                        (ConvertTo-HtmlText $currentMatchupWeek) +
                        ' lineup needs review</h3><p>The previous Lineup Review was Week ' +
                        (ConvertTo-HtmlText $savedLineupWeek) +
                        '. Butler expired that weekly advice when Sleeper advanced to Week ' +
                        (ConvertTo-HtmlText $currentMatchupWeek) +
                        '.</p><div class="manager-chip-row"><span class="manager-chip ok">Roster verified</span><span class="manager-chip warn">Old week expired</span></div><div class="manager-card-actions"><a class="command-button" href="/team/autofill">Review Lineup</a><a class="command-button secondary" href="/team">My Team</a></div></div><div class="status warn">REVIEW WEEK ' +
                        (ConvertTo-HtmlText $currentMatchupWeek) +
                        '</div></article>'
                }
            )

            if ($staleLineupWasPrimary) {
                $proofPattern = [regex]::new(
                    '(?is)<details id="decision-details" class="proof-mode">.*?</details>'
                )
                $currentProof = '<details id="decision-details" class="proof-mode"><summary>View decision details</summary><div class="proof-body"><div class="proof-grid"><article class="proof-card"><div class="eyebrow">Why Butler says this</div><h3>Weekly frame changed</h3><p>Sleeper is now on Week ' +
                    (ConvertTo-HtmlText $currentMatchupWeek) +
                    ', while the saved lineup review is Week ' +
                    (ConvertTo-HtmlText $savedLineupWeek) +
                    '.</p></article><article class="proof-card"><div class="eyebrow">What changed</div><h3>Old lineup advice expired</h3><p>Butler suppressed the prior holds and recommendations instead of carrying them into a new week.</p></article></div><div class="proof-grid"><article class="proof-card"><div class="eyebrow">What to do next</div><h3>Review the current lineup</h3><p>Run the read-only Lineup Advisor for Week ' +
                    (ConvertTo-HtmlText $currentMatchupWeek) +
                    '.</p><div class="manager-card-actions"><a class="command-button" href="/team/autofill">Review Lineup</a></div></article></div></div></details>'
                if ($proofPattern.IsMatch($Html)) {
                    $Html = $proofPattern.Replace(
                        $Html,
                        [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $currentProof },
                        1
                    )
                }
            }

        }
    }

'@
$matchupFunction = $matchupFunction.Insert($reconcileAt,$reconcile)
$core = $core.Substring(0,$matchupFn.Extent.StartOffset) + $matchupFunction + $core.Substring($matchupFn.Extent.EndOffset)

foreach ($pair in @(
    [pscustomobject]@{ Label = 'Dashboard'; Text = $dashboard },
    [pscustomobject]@{ Label = 'core'; Text = $core }
)) {
    $null = Get-Bf1004Ast -Text $pair.Text -Contract ("generated " + $pair.Label)
}

foreach ($required in @(
    'butler-lineup-snapshot-frame',
    'data-lineup-week=',
    'dashboard-weekly-attention'
)) {
    if ($dashboard.IndexOf($required,[System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1004 BLOCKED: Dashboard week-frame marker is missing: $required"
    }
}

foreach ($required in @(
    '$currentMatchupWeek = [int]$matchup.Week',
    'The saved Lineup Review is from Week ',
    'Butler will not carry those player holds or lineup recommendations into Week ',
    'Old week expired',
    'Old lineup advice expired',
    'href="/matchup">Open Weekly Matchup &rarr;</a>'
)) {
    if ($core.IndexOf($required,[System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1004 BLOCKED: week-rollover reconciliation marker is missing: $required"
    }
}

$installedAst = Get-Bf1004Ast -Text $core -Contract 'final core'
$installedFn = Get-Bf1004Function -Ast $installedAst -Name 'Add-DashboardMatchupSummary' -Contract 'final week reconciliation'
$installedText = $installedFn.Extent.Text
$readCount = [regex]::Matches($installedText,[regex]::Escape('Invoke-ButlerReadOnlyTask')).Count
if ($readCount -ne 1) {
    throw "BF-1004 BLOCKED: Dashboard matchup read contract changed; expected one existing read, found $readCount."
}
if ($installedText -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|Invoke-RestMethod|Invoke-WebRequest|returnUrl|redirectUrl|javascript:') {
    throw 'BF-1004 BLOCKED: week-rollover reconciliation introduced provider, optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath,$core,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($DashboardPath,$dashboard,[Text.UTF8Encoding]::new($false))

Write-Host 'BF-1004 week-rollover Lineup Review reconciliation applied.'
