param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-1005 BLOCKED: staged core not found at $CorePath"
}

function Get-Bf1005Ast {
    param([Parameter(Mandatory = $true)][string]$Text,[Parameter(Mandatory = $true)][string]$Contract)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text,[ref]$tokens,[ref]$errors)
    if (@($errors).Count -gt 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-1005 BLOCKED: $Contract failed PowerShell parse: $summary"
    }
    return $ast
}

function Get-Bf1005Function {
    param([Parameter(Mandatory = $true)]$Ast,[Parameter(Mandatory = $true)][string]$Name,[Parameter(Mandatory = $true)][string]$Contract)
    $matches = @($Ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name
    },$true))
    if ($matches.Count -ne 1) {
        throw "BF-1005 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
    }
    return $matches[0]
}

function Replace-Bf1005ExactlyOnce {
    param([Parameter(Mandatory = $true)][string]$Text,[Parameter(Mandatory = $true)][string]$Old,[Parameter(Mandatory = $true)][string]$New,[Parameter(Mandatory = $true)][string]$Contract)
    $count = [regex]::Matches($Text,[regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-1005 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old,$New)
}

$core = [IO.File]::ReadAllText($CorePath)
$ast = Get-Bf1005Ast -Text $core -Contract 'pre-transform core'
$decisionFn = Get-Bf1005Function -Ast $ast -Name 'Get-MatchupLineupDecisionView' -Contract 'Matchup decision'

$helpers = @'
function ConvertTo-Bf1005TargetKey {
    param([AllowNull()][string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return '' }
    return ([regex]::Replace($Value.Trim(), 's+', ' ')).ToLowerInvariant()
}

function Get-Bf1005SavedLineupReview {
    param(
        [Parameter(Mandatory = $true)][string]$LeagueId,
        [Parameter(Mandatory = $true)]$Roster,
        [Parameter(Mandatory = $true)][int]$Week
    )

    if ($Week -le 0) { return $null }
    $path = Get-Bf808AutoFillSnapshotPath -LeagueKey $LeagueId
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }

    try { $snapshot = ([IO.File]::ReadAllText($path) | ConvertFrom-Json) }
    catch { return $null }

    foreach ($required in @('Schema','LeagueId','TargetHuman','Ready','Source','Season','Week','Scoring','CurrentTotal','RecommendedTotal','Gain','ChangedCount','GeneratedUtc')) {
        if ($null -eq $snapshot.PSObject.Properties[$required]) { return $null }
    }

    if ([string]$snapshot.Schema -cne 'BF-808-1') { return $null }
    if ([string]$snapshot.LeagueId -cne $LeagueId) { return $null }

    $teamName = if (-not [string]::IsNullOrWhiteSpace([string]$Roster.TeamName) -and [string]$Roster.TeamName -cne 'none') { [string]$Roster.TeamName } else { [string]$Roster.ButlerTeamName }
    $expectedTarget = "$($Roster.LeagueName) | $teamName | roster $($Roster.RosterId)"
    if ((ConvertTo-Bf1005TargetKey -Value ([string]$snapshot.TargetHuman)) -cne (ConvertTo-Bf1005TargetKey -Value $expectedTarget)) { return $null }

    $savedWeek = 0
    if (-not [int]::TryParse([string]$snapshot.Week,[ref]$savedWeek) -or $savedWeek -ne $Week) { return $null }

    $generated = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$snapshot.GeneratedUtc,[ref]$generated)) { return $null }
    $ageHours = ([DateTimeOffset]::UtcNow - $generated).TotalHours
    if ($ageHours -lt -0.1 -or $ageHours -gt 6.0) { return $null }

    return $snapshot
}

function ConvertTo-Bf1005SavedLineupReviewHtml {
    param([Parameter(Mandatory = $true)]$Snapshot)

    $week = [string]$Snapshot.Week
    $holds = @(if ($null -ne $Snapshot.PSObject.Properties['ProjectionHolds']) { @($Snapshot.ProjectionHolds) } else { @() })
    $availability = @(if ($null -ne $Snapshot.PSObject.Properties['AvailabilityExclusions']) { @($Snapshot.AvailabilityExclusions) } else { @() })
    $attentionCount = $holds.Count + $availability.Count

    $title = if (-not [bool]$Snapshot.Ready) {
        "Week $week review hit an evidence gap"
    }
    elseif ($attentionCount -gt 0) {
        "Week $week lineup review needs attention"
    }
    elseif ([int]$Snapshot.ChangedCount -gt 0) {
        "Week $week lineup review recommends $($Snapshot.ChangedCount) changes"
    }
    else {
        "Week $week lineup review found no changes"
    }

    $rows = ''
    foreach ($player in @($availability + $holds)) {
        $status = if ($null -ne $player.PSObject.Properties['InjuryStatus'] -and -not [string]::IsNullOrWhiteSpace([string]$player.InjuryStatus) -and [string]$player.InjuryStatus -cne 'none') {
            [string]$player.InjuryStatus
        }
        elseif ($null -ne $player.PSObject.Properties['Status'] -and -not [string]::IsNullOrWhiteSpace([string]$player.Status)) {
            [string]$player.Status
        }
        else {
            'review hold'
        }
        $rows += '<li><strong>' + (ConvertTo-HtmlText ([string]$player.Name)) + '</strong> - ' + (ConvertTo-HtmlText $status) + '</li>'
    }

    $attentionHtml = if ([string]::IsNullOrWhiteSpace($rows)) {
        '<p class="meta">No saved availability exclusions or review holds.</p>'
    }
    else {
        '<div class="callout"><strong>Current review holds</strong><ul>' + $rows + '</ul></div>'
    }

    $reasonHtml = if (-not [bool]$Snapshot.Ready -and $null -ne $Snapshot.PSObject.Properties['Reason'] -and -not [string]::IsNullOrWhiteSpace([string]$Snapshot.Reason)) {
        '<div class="callout callout-danger">' + (ConvertTo-HtmlText ([string]$Snapshot.Reason)) + '</div>'
    }
    else {
        ''
    }

    $statusClass = if ($attentionCount -gt 0 -or -not [bool]$Snapshot.Ready) { 'warn' } else { 'good' }
    $statusText = if ($attentionCount -gt 0) { 'MANUAL REVIEW' } elseif ([bool]$Snapshot.Ready) { 'REVIEWED' } else { 'EVIDENCE GAP' }

    return '<section class="panel recommendation-panel"><div class="manager-head"><div><div class="eyebrow">Lineup advisor - saved current review</div><h2>' +
        (ConvertTo-HtmlText $title) +
        '</h2><p class="lede">Butler loaded the saved read-only Week ' +
        (ConvertTo-HtmlText $week) +
        ' review for this exact roster. No new provider request was made to display it.</p></div><span class="status ' +
        $statusClass + '">' + $statusText +
        '</span></div><div class="stats"><div class="stat"><strong>Current projection</strong><span>' +
        (ConvertTo-HtmlText ([string]$Snapshot.CurrentTotal)) +
        '</span></div><div class="stat"><strong>Reviewed projection</strong><span>' +
        (ConvertTo-HtmlText ([string]$Snapshot.RecommendedTotal)) +
        '</span></div><div class="stat"><strong>Projected change</strong><span>' +
        (ConvertTo-HtmlText ([string]$Snapshot.Gain)) +
        '</span></div><div class="stat"><strong>Proposed changes</strong><span>' +
        (ConvertTo-HtmlText ([string]$Snapshot.ChangedCount)) +
        '</span></div></div>' +
        $reasonHtml + $attentionHtml +
        '<div class="button-row"><a class="btn btn-primary" href="/matchup/autofill">Refresh full Lineup Review</a><a class="btn btn-secondary" href="/team">Open My Team</a></div><p class="meta">Saved ' +
        (ConvertTo-HtmlText ([string]$Snapshot.GeneratedUtc)) +
        ' - projection source: ' +
        (ConvertTo-HtmlText ([string]$Snapshot.Source)) +
        '. The compact saved snapshot does not invent or reconstruct exact move rows that were not persisted.</p></section>'
}

'@

$core = $core.Insert($decisionFn.Extent.StartOffset,$helpers)

$ast = Get-Bf1005Ast -Text $core -Contract 'helper-inserted core'
$decisionFn = Get-Bf1005Function -Ast $ast -Name 'Get-MatchupLineupDecisionView' -Contract 'Matchup decision'
$decision = $decisionFn.Extent.Text
$decision = Replace-Bf1005ExactlyOnce -Text $decision -Old '    param([Parameter(Mandatory = $true)]$AutoFill)' -New @'
    param(
        [Parameter(Mandatory = $true)]$AutoFill,
        [AllowNull()]$SavedReview
    )
'@.TrimEnd() -Contract 'Matchup decision parameters'

$idleAnchor = @'
    if (-not $AutoFill.Requested) {
'@
$savedDecision = @'
    if (-not $AutoFill.Requested -and $null -ne $SavedReview) {
        $week = [string]$SavedReview.Week
        $holds = @(if ($null -ne $SavedReview.PSObject.Properties['ProjectionHolds']) { @($SavedReview.ProjectionHolds) } else { @() })
        $availability = @(if ($null -ne $SavedReview.PSObject.Properties['AvailabilityExclusions']) { @($SavedReview.AvailabilityExclusions) } else { @() })
        $attention = @($availability + $holds)

        if (-not [bool]$SavedReview.Ready) {
            return [pscustomobject]@{
                Title = "Review Week $week lineup evidence"
                Copy = "A saved Week $week Lineup Review exists for this exact roster, but it did not prove a complete recommendation."
                Status = 'EVIDENCE GAP'
                StatusClass = 'warn'
                Detail = if ($null -ne $SavedReview.PSObject.Properties['Reason']) { [string]$SavedReview.Reason } else { 'Saved review evidence is incomplete.' }
                ActionLabel = 'Refresh Lineup Review'
                ActionHref = '/matchup/autofill'
            }
        }

        if ($attention.Count -gt 0) {
            $names = @($attention | ForEach-Object { [string]$_.Name } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            $nameText = if ($names.Count -gt 0) { $names -join ', ' } else { "$($attention.Count) saved review holds" }
            return [pscustomobject]@{
                Title = "Review current Week $week lineup holds"
                Copy = "The saved Week $week Lineup Review is current for this roster and contains manual-review holds."
                Status = 'MANUAL REVIEW'
                StatusClass = 'warn'
                Detail = $nameText
                ActionLabel = 'Refresh Lineup Review'
                ActionHref = '/matchup/autofill'
            }
        }

        if ([int]$SavedReview.ChangedCount -gt 0) {
            return [pscustomobject]@{
                Title = 'Lineup changes are ready to review'
                Copy = "Butler loaded the saved Week $week Lineup Review for this exact roster."
                Status = 'REVIEW'
                StatusClass = 'good'
                Detail = "Saved review: $($SavedReview.ChangedCount) proposed changes; projected change $($SavedReview.Gain) points."
                ActionLabel = 'Refresh Lineup Review'
                ActionHref = '/matchup/autofill'
            }
        }

        return [pscustomobject]@{
            Title = "Week $week lineup review is current"
            Copy = "The saved read-only Week $week review found no proven lineup changes."
            Status = 'REVIEWED'
            StatusClass = 'good'
            Detail = "Current projected starter total: $($SavedReview.CurrentTotal)"
            ActionLabel = 'Refresh Lineup Review'
            ActionHref = '/matchup/autofill'
        }
    }

    if (-not $AutoFill.Requested) {
'@
$decision = Replace-Bf1005ExactlyOnce -Text $decision -Old $idleAnchor.TrimEnd() -New $savedDecision.TrimEnd() -Contract 'saved review decision branch'
$core = $core.Substring(0,$decisionFn.Extent.StartOffset) + $decision + $core.Substring($decisionFn.Extent.EndOffset)

$ast = Get-Bf1005Ast -Text $core -Contract 'decision-hydrated core'
$matchupFn = Get-Bf1005Function -Ast $ast -Name 'ConvertTo-MatchupHtml' -Contract 'Matchup renderer'
$matchup = $matchupFn.Extent.Text

$paramOld = @'
        [Parameter(Mandatory = $true)]$OpponentStrength,
        [Parameter(Mandatory = $true)]$OpponentPressure
    )
'@
$paramNew = @'
        [Parameter(Mandatory = $true)]$OpponentStrength,
        [Parameter(Mandatory = $true)]$OpponentPressure,
        [AllowNull()]$SavedReview
    )
'@
$matchup = Replace-Bf1005ExactlyOnce -Text $matchup -Old $paramOld.TrimEnd() -New $paramNew.TrimEnd() -Contract 'Matchup saved review parameter'
$matchup = Replace-Bf1005ExactlyOnce -Text $matchup -Old '$decision = Get-MatchupLineupDecisionView -AutoFill $AutoFill' -New '$decision = Get-MatchupLineupDecisionView -AutoFill $AutoFill -SavedReview $SavedReview' -Contract 'Matchup saved decision binding'
$matchup = Replace-Bf1005ExactlyOnce -Text $matchup -Old '$autoFillHtml = ConvertTo-MatchupAutoFillHtml -AutoFill $AutoFill' -New @'
$autoFillHtml = if (-not $AutoFill.Requested -and $null -ne $SavedReview) {
        ConvertTo-Bf1005SavedLineupReviewHtml -Snapshot $SavedReview
    }
    else {
        ConvertTo-MatchupAutoFillHtml -AutoFill $AutoFill
    }
'@.TrimEnd() -Contract 'Matchup saved review rendering'
$core = $core.Substring(0,$matchupFn.Extent.StartOffset) + $matchup + $core.Substring($matchupFn.Extent.EndOffset)

$routeOld = '$html = ConvertTo-MatchupHtml -Roster $rosterView -Matchup $matchup -AutoFill $autoFill -OpponentStrength $opponentStrength -OpponentPressure $opponentPressure'
$routeNew = @'
$savedReview = if ($requestAutoFill) {
                            $null
                        }
                        else {
                            Get-Bf1005SavedLineupReview -LeagueId ([string]$LeagueId) -Roster $rosterView -Week ([int]$matchup.Week)
                        }
                        $html = ConvertTo-MatchupHtml -Roster $rosterView -Matchup $matchup -AutoFill $autoFill -OpponentStrength $opponentStrength -OpponentPressure $opponentPressure -SavedReview $savedReview
'@.TrimEnd()
$core = Replace-Bf1005ExactlyOnce -Text $core -Old $routeOld -New $routeNew -Contract 'ordinary Matchup saved review hydration'

$null = Get-Bf1005Ast -Text $core -Contract 'final core'

foreach ($required in @(
    'function Get-Bf1005SavedLineupReview',
    'function ConvertTo-Bf1005SavedLineupReviewHtml',
    'The saved Week $week Lineup Review is current for this roster',
    'Get-Bf1005SavedLineupReview -LeagueId ([string]$LeagueId)',
    '-SavedReview $savedReview',
    'No new provider request was made to display it.',
    'Refresh full Lineup Review'
)) {
    if ($core.IndexOf($required,[System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1005 BLOCKED: saved-review hydration marker is missing: $required"
    }
}

$addedSurface = $helpers + [Environment]::NewLine + $savedDecision
if ($addedSurface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api.sleeper.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-1005 BLOCKED: saved-review hydration introduced provider, optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath,$core,[Text.UTF8Encoding]::new($false))
Write-Host 'BF-1005 Matchup saved Lineup Review hydration applied.'
