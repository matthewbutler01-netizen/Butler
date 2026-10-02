param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($path in @($DashboardPath, $CorePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "BF-993 BLOCKED: staged Butler file not found at $path"
    }
}

function Get-ParsedAst {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -gt 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-993 BLOCKED: $Contract failed PowerShell parse: $summary"
    }
    return $ast
}

function Get-OneFunction {
    param(
        [Parameter(Mandatory = $true)]$Ast,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = @($Ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq $Name
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-993 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
    }
    return $matches[0]
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-993 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

# Dashboard renderer: add one concise state/context line per glance card.
$dashboard = [IO.File]::ReadAllText($DashboardPath)
$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'pre-transform staged Dashboard'
$dashboardFn = Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-DashboardHtml' -Contract 'Dashboard renderer'
$dashboardFunction = $dashboardFn.Extent.Text

$matchupObjectOld = @'
        [pscustomobject]@{
            Kind = "Matchup"
            Title = "Review this week's matchup"
            Status = "VIEW MATCHUP"
            StatusClass = "done"
            ActionHref = "/matchup"
            ActionLabel = "Open Weekly Matchup"
        },
'@
$matchupObjectNew = @'
        [pscustomobject]@{
            Kind = "Matchup"
            Title = "Review this week's matchup"
            Copy = "Opponent, week, and matchup context for the current roster."
            Status = "VIEW MATCHUP"
            StatusClass = "done"
            ActionHref = "/matchup"
            ActionLabel = "Open Weekly Matchup"
        },
'@
$dashboardFunction = Replace-ExactlyOnce -Text $dashboardFunction -Old $matchupObjectOld -New $matchupObjectNew -Contract 'Matchup glance copy'

$lineupObjectOld = @'
        [pscustomobject]@{
            Kind = "Lineup"
            Title = [string]$bf907LineupView[0]
            Status = [string]$bf907LineupView[2]
'@
$lineupObjectNew = @'
        [pscustomobject]@{
            Kind = "Lineup"
            Title = [string]$bf907LineupView[0]
            Copy = [string]$bf907LineupView[1]
            Status = [string]$bf907LineupView[2]
'@
$dashboardFunction = Replace-ExactlyOnce -Text $dashboardFunction -Old $lineupObjectOld -New $lineupObjectNew -Contract 'Lineup glance state copy'

$waiverObjectOld = @'
        [pscustomobject]@{
            Kind = "Waivers"
            Title = [string]$bf907WaiverView[0]
            Status = [string]$bf907WaiverView[2]
'@
$waiverObjectNew = @'
        [pscustomobject]@{
            Kind = "Waivers"
            Title = [string]$bf907WaiverView[0]
            Copy = [string]$bf907WaiverView[1]
            Status = [string]$bf907WaiverView[2]
'@
$dashboardFunction = Replace-ExactlyOnce -Text $dashboardFunction -Old $waiverObjectOld -New $waiverObjectNew -Contract 'Waiver glance state copy'

$cardOld = '<article class="week-glance-card"><div class="week-kind">$(ConvertTo-HtmlText $bf907Item.Kind)</div><div class="week-title">$(ConvertTo-HtmlText $bf907Item.Title)</div><div class="status $($bf907Item.StatusClass)">$(ConvertTo-HtmlText $bf907Item.Status)</div><div class="week-action"><a href="$(ConvertTo-HtmlText $bf907Item.ActionHref)">$(ConvertTo-HtmlText $bf907Item.ActionLabel) &rarr;</a></div></article>'
$cardNew = '<article class="week-glance-card"><div class="week-kind">$(ConvertTo-HtmlText $bf907Item.Kind)</div><div class="week-title">$(ConvertTo-HtmlText $bf907Item.Title)</div><div class="subtle">$(ConvertTo-HtmlText $bf907Item.Copy)</div><div class="status $($bf907Item.StatusClass)">$(ConvertTo-HtmlText $bf907Item.Status)</div><div class="week-action"><a href="$(ConvertTo-HtmlText $bf907Item.ActionHref)">$(ConvertTo-HtmlText $bf907Item.ActionLabel) &rarr;</a></div></article>'
$dashboardFunction = Replace-ExactlyOnce -Text $dashboardFunction -Old $cardOld -New $cardNew -Contract 'glance-card context line'

$dashboard = $dashboard.Substring(0, $dashboardFn.Extent.StartOffset) + $dashboardFunction + $dashboard.Substring($dashboardFn.Extent.EndOffset)

# Core response decorator: preserve the context line when the Matchup card is
# replaced with verified live matchup data.
$core = [IO.File]::ReadAllText($CorePath)
$coreAst = Get-ParsedAst -Text $core -Contract 'pre-transform staged core'
$coreFn = Get-OneFunction -Ast $coreAst -Name 'Add-DashboardMatchupSummary' -Contract 'Dashboard matchup summary'
$coreFunction = $coreFn.Extent.Text

$stateOld = @'
    $title = 'Opponent unavailable'
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
'@.TrimEnd()
$coreFunction = Replace-ExactlyOnce -Text $coreFunction -Old $stateOld -New $stateNew -Contract 'default Matchup context copy'

$successOld = @'
        $title = "Week $($matchup.Week): $($matchup.UserTeamName) vs. $($matchup.OpponentTeamName)"
        $status = 'OPPONENT CONFIRMED'
        $statusClass = 'good'
'@.TrimEnd()
$successNew = @'
        $title = "Week $($matchup.Week): $($matchup.UserTeamName) vs. $($matchup.OpponentTeamName)"
        $copy = 'Opponent and week are verified for this roster; open the matchup for full context.'
        $status = 'OPPONENT CONFIRMED'
        $statusClass = 'good'
'@.TrimEnd()
$coreFunction = Replace-ExactlyOnce -Text $coreFunction -Old $successOld -New $successNew -Contract 'verified Matchup context copy'

$coreCardOld = @'
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="/matchup">Open Weekly Matchup &rarr;</a></div>' + $opponentTools + '</article>'
'@.TrimEnd()
$coreCardNew = @'
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="subtle">' + (ConvertTo-HtmlText $copy) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="/matchup">Open Weekly Matchup &rarr;</a></div>' + $opponentTools + '</article>'
'@.TrimEnd()
$coreFunction = Replace-ExactlyOnce -Text $coreFunction -Old $coreCardOld -New $coreCardNew -Contract 'decorated Matchup glance context line'

$core = $core.Substring(0, $coreFn.Extent.StartOffset) + $coreFunction + $core.Substring($coreFn.Extent.EndOffset)

$finalDashboardAst = Get-ParsedAst -Text $dashboard -Contract 'generated staged Dashboard'
$finalCoreAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$installedDashboard = Get-OneFunction -Ast $finalDashboardAst -Name 'ConvertTo-DashboardHtml' -Contract 'context-rich Dashboard renderer'
$installedCore = Get-OneFunction -Ast $finalCoreAst -Name 'Add-DashboardMatchupSummary' -Contract 'context-rich Matchup summary'

foreach ($required in @(
    'Copy = "Opponent, week, and matchup context for the current roster."',
    'Copy = [string]$bf907LineupView[1]',
    'Copy = [string]$bf907WaiverView[1]',
    '<div class="subtle">$(ConvertTo-HtmlText $bf907Item.Copy)</div>'
)) {
    if ($installedDashboard.Extent.Text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-993 BLOCKED: Dashboard glance context marker is missing: $required"
    }
}

foreach ($required in @(
    '$copy = ''Matchup evidence is unavailable or stale; open the matchup to review the current frame.''',
    '$copy = ''Opponent and week are verified for this roster; open the matchup for full context.''',
    '<div class="subtle">'' + (ConvertTo-HtmlText $copy) + ''</div>'
)) {
    if ($installedCore.Extent.Text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-993 BLOCKED: Matchup context marker is missing: $required"
    }
}

$surface = $installedDashboard.Extent.Text + [Environment]::NewLine + $installedCore.Extent.Text
if ($surface -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-993 BLOCKED: Dashboard scanability batch introduced optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($DashboardPath, $dashboard, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-993 Dashboard glance scanability batch applied.'
