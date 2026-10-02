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
        throw "BF-979 BLOCKED: staged Butler file not found at $path"
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
        throw "BF-979 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-979 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
    }
    return $matches[0]
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
        throw "BF-979 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

function Replace-FunctionText {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Mutator,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $ast = Get-ParsedAst -Text $Text -Contract "$Contract pre-transform"
    $fn = Get-OneFunction -Ast $ast -Name $Name -Contract $Contract
    $old = $fn.Extent.Text
    $new = & $Mutator $old
    if ([string]::IsNullOrWhiteSpace($new)) {
        throw "BF-979 BLOCKED: $Contract produced an empty function."
    }
    return $Text.Substring(0, $fn.Extent.StartOffset) + $new + $Text.Substring($fn.Extent.EndOffset)
}

$core = [System.IO.File]::ReadAllText($CorePath)

foreach ($required in @(
    'function Save-Bf808AutoFillSnapshot {',
    'function ConvertTo-TeamHtml {',
    'ProjectionHolds',
    'AvailabilityExclusions',
    'id="team-lineup"',
    '$autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill -Roster $Roster'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-979 BLOCKED: final My Team/AutoFill capability is missing: $required"
    }
}

$core = Replace-FunctionText -Text $core -Name 'Save-Bf808AutoFillSnapshot' -Contract 'AutoFill snapshot attention persistence' -Mutator {
    param($fn)

    $snapshotAnchor = '    $snapshot = [ordered]@{'
    $snapshotSetup = @'
    $availabilitySnapshot = @()
    if ($null -ne $AutoFill.PSObject.Properties['AvailabilityExclusions']) {
        $availabilitySnapshot = @($AutoFill.AvailabilityExclusions | ForEach-Object {
            [ordered]@{
                Name = [string]$_.Name
                Id = [string]$_.Id
                Status = [string]$_.Status
                InjuryStatus = [string]$_.InjuryStatus
                Reason = [string]$_.Reason
            }
        })
    }

    $holdSnapshot = @()
    if ($null -ne $AutoFill.PSObject.Properties['ProjectionHolds']) {
        $holdSnapshot = @($AutoFill.ProjectionHolds | ForEach-Object {
            [ordered]@{
                Name = [string]$_.Name
                Id = [string]$_.Id
                RosterSlot = [string]$_.RosterSlot
                LineupSlot = [string]$_.LineupSlot
                Status = [string]$_.Status
                InjuryStatus = [string]$_.InjuryStatus
                Reason = [string]$_.Reason
            }
        })
    }

    $snapshot = [ordered]@{
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $snapshotAnchor -New $snapshotSetup.TrimEnd() -Contract 'snapshot attention setup'

    $fieldAnchor = '        AssignmentCount = [int](@($AutoFill.Assignments).Count)'
    $fieldReplacement = @'
        AssignmentCount = [int](@($AutoFill.Assignments).Count)
        AvailabilityExclusions = @($availabilitySnapshot)
        ProjectionHolds = @($holdSnapshot)
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $fieldAnchor -New $fieldReplacement.TrimEnd() -Contract 'snapshot attention fields'
    return $fn
}

$coreHelperMarker = 'function ConvertTo-TeamHtml {'
$coreHelperIndex = $core.IndexOf($coreHelperMarker, [System.StringComparison]::Ordinal)
if ($coreHelperIndex -lt 0) {
    throw 'BF-979 BLOCKED: My Team helper insertion marker is missing.'
}

$coreHelper = @'
function Get-Bf979WeeklyAttentionHtml {
    param([Parameter(Mandatory = $true)]$AutoFill)

    if (-not [bool]$AutoFill.Requested -or -not [bool]$AutoFill.Ready) {
        return ''
    }

    $availability = if ($null -ne $AutoFill.PSObject.Properties['AvailabilityExclusions']) { @($AutoFill.AvailabilityExclusions) } else { @() }
    $holds = if ($null -ne $AutoFill.PSObject.Properties['ProjectionHolds']) { @($AutoFill.ProjectionHolds) } else { @() }
    if ($availability.Count -eq 0 -and $holds.Count -eq 0) {
        return ''
    }

    $starterHolds = @($holds | Where-Object { [string]$_.RosterSlot -ceq 'STARTER' })
    $title = if ($starterHolds.Count -gt 0) {
        $noun = if ($starterHolds.Count -eq 1) { 'starter needs' } else { 'starters need' }
        "$($starterHolds.Count) $noun weekly review"
    }
    elseif ($availability.Count -gt 0) {
        $noun = if ($availability.Count -eq 1) { 'player is' } else { 'players are' }
        "$($availability.Count) unavailable roster $noun excluded"
    }
    else {
        $noun = if ($holds.Count -eq 1) { 'player is' } else { 'players are' }
        "$($holds.Count) $noun held for manual review"
    }

    $rows = ''
    foreach ($player in $availability) {
        $status = if ([string]::IsNullOrWhiteSpace([string]$player.Status) -or [string]$player.Status -ceq 'none') { 'status not reported' } else { [string]$player.Status }
        $injury = if ([string]::IsNullOrWhiteSpace([string]$player.InjuryStatus) -or [string]$player.InjuryStatus -ceq 'none') { 'injury status not reported' } else { [string]$player.InjuryStatus }
        $rows += "<div class=`"callout`"><strong>$(ConvertTo-HtmlText $player.Name)</strong> &middot; unavailable<br><span>$(ConvertTo-HtmlText $status) &middot; $(ConvertTo-HtmlText $injury)</span></div>"
    }
    foreach ($player in $holds) {
        $slot = if ([string]$player.RosterSlot -ceq 'STARTER') { 'starter hold' } else { 'review hold' }
        $status = if ([string]::IsNullOrWhiteSpace([string]$player.Status) -or [string]$player.Status -ceq 'none') { 'status not reported' } else { [string]$player.Status }
        $injury = if ([string]::IsNullOrWhiteSpace([string]$player.InjuryStatus) -or [string]$player.InjuryStatus -ceq 'none') { 'injury status not reported' } else { [string]$player.InjuryStatus }
        $rows += "<div class=`"callout`"><strong>$(ConvertTo-HtmlText $player.Name)</strong> &middot; $(ConvertTo-HtmlText $slot)<br><span>$(ConvertTo-HtmlText $status) &middot; $(ConvertTo-HtmlText $injury)</span></div>"
    }

    return "<section class=`"panel weekly-attention`"><div class=`"manager-head`"><div><div class=`"eyebrow`">Weekly attention</div><h2>$(ConvertTo-HtmlText $title)</h2><p class=`"lede`">These are the exact availability exclusions and review holds already used by Butler's current Lineup Review.</p></div><span class=`"status warn`">CHECK LINEUP</span></div>$rows<div class=`"button-row`"><a class=`"btn btn-primary`" href=`"/team/autofill`">Review Lineup</a></div><p class=`"meta`">Holds preserve the current lineup state; Butler does not invent replacement points or submit a lineup.</p></section>"
}

'@
$core = $core.Insert($coreHelperIndex, $coreHelper)

$core = Replace-FunctionText -Text $core -Name 'ConvertTo-TeamHtml' -Contract 'My Team weekly attention composition' -Mutator {
    param($fn)

    $anchor = @'
    $autoFillHtml = $autoFillHtml.Replace('<section class="panel recommendation-panel"', '<section id="team-lineup" class="panel recommendation-panel team-section-target" tabindex="-1"')
'@
    $replacement = @'
    $autoFillHtml = $autoFillHtml.Replace('<section class="panel recommendation-panel"', '<section id="team-lineup" class="panel recommendation-panel team-section-target" tabindex="-1"')
    $weeklyAttentionHtml = Get-Bf979WeeklyAttentionHtml -AutoFill $AutoFill
    $autoFillHtml = $weeklyAttentionHtml + $autoFillHtml
'@
    return Replace-ExactlyOnce -Text $fn -Old $anchor.TrimEnd() -New $replacement.TrimEnd() -Contract 'My Team attention prefix'
}

$dashboard = [System.IO.File]::ReadAllText($DashboardPath)
foreach ($required in @(
    'function ConvertTo-DashboardHtml {',
    'function Read-Bf809AutoFillSnapshotFile {',
    '$lineupSnapshot = Get-Bf809AutoFillSnapshot',
    '$bf907WeekGlanceHtml',
    'Week at a glance'
)) {
    if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-979 BLOCKED: final Dashboard snapshot/Decision Center capability is missing: $required"
    }
}

$dashboardHelperMarker = 'function ConvertTo-DashboardHtml {'
$dashboardHelperIndex = $dashboard.IndexOf($dashboardHelperMarker, [System.StringComparison]::Ordinal)
if ($dashboardHelperIndex -lt 0) {
    throw 'BF-979 BLOCKED: Dashboard helper insertion marker is missing.'
}

$dashboardHelper = @'
function Get-Bf979SnapshotWeeklyAttentionHtml {
    param(
        [AllowNull()]$Snapshot,
        [AllowEmptyString()][string]$LineupSignalStatus
    )

    if ($null -eq $Snapshot -or -not [bool]$Snapshot.Ready) {
        return ''
    }
    if ($LineupSignalStatus -in @('REFRESH AUTOFILL', 'EVIDENCE GAP', 'NOT REVIEWED')) {
        return ''
    }

    $availability = if ($null -ne $Snapshot.PSObject.Properties['AvailabilityExclusions']) { @($Snapshot.AvailabilityExclusions) } else { @() }
    $holds = if ($null -ne $Snapshot.PSObject.Properties['ProjectionHolds']) { @($Snapshot.ProjectionHolds) } else { @() }
    if ($availability.Count -eq 0 -and $holds.Count -eq 0) {
        return ''
    }

    $starterHolds = @($holds | Where-Object { [string]$_.RosterSlot -ceq 'STARTER' })
    $title = if ($starterHolds.Count -gt 0) {
        $noun = if ($starterHolds.Count -eq 1) { 'starter needs' } else { 'starters need' }
        "$($starterHolds.Count) $noun weekly review"
    }
    elseif ($availability.Count -gt 0) {
        $noun = if ($availability.Count -eq 1) { 'player is' } else { 'players are' }
        "$($availability.Count) unavailable roster $noun excluded"
    }
    else {
        $noun = if ($holds.Count -eq 1) { 'player is' } else { 'players are' }
        "$($holds.Count) $noun held for manual review"
    }

    $names = New-Object System.Collections.Generic.List[string]
    foreach ($player in $availability) {
        $status = if ([string]::IsNullOrWhiteSpace([string]$player.InjuryStatus) -or [string]$player.InjuryStatus -ceq 'none') { [string]$player.Status } else { [string]$player.InjuryStatus }
        if ([string]::IsNullOrWhiteSpace($status) -or $status -ceq 'none') { $status = 'review' }
        $names.Add("$(ConvertTo-HtmlText $player.Name) ($(ConvertTo-HtmlText $status))")
    }
    foreach ($player in $holds) {
        $status = if ([string]::IsNullOrWhiteSpace([string]$player.InjuryStatus) -or [string]$player.InjuryStatus -ceq 'none') { [string]$player.Status } else { [string]$player.InjuryStatus }
        if ([string]::IsNullOrWhiteSpace($status) -or $status -ceq 'none') { $status = 'review hold' }
        $names.Add("$(ConvertTo-HtmlText $player.Name) ($(ConvertTo-HtmlText $status))")
    }
    $nameText = $names -join ' &middot; '

    return "<section class=`"panel`"><div class=`"statusrow`"><div><div class=`"eyebrow`">Weekly attention</div><h2>$(ConvertTo-HtmlText $title)</h2><p class=`"lede`">$nameText</p><p class=`"meta`">Saved from the latest governed Lineup Review; Dashboard does not make a new provider request for this alert.</p></div><span class=`"status warn`">CHECK LINEUP</span></div><div class=`"button-row`"><a class=`"btn btn-primary`" href=`"/team/autofill`">Review Lineup</a></div></section>"
}

'@
$dashboard = $dashboard.Insert($dashboardHelperIndex, $dashboardHelper)

$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-DashboardHtml' -Contract 'Dashboard weekly attention composition' -Mutator {
    param($fn)

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-979 BLOCKED: final Dashboard return marker is missing.'
    }

    $setup = @'
    $bf979WeeklyAttentionHtml = Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $lineupSnapshot -LineupSignalStatus ([string]$lineupSignalStatus)
    $bf907WeekGlanceHtml = $bf979WeeklyAttentionHtml + $bf907WeekGlanceHtml

'@
    return $fn.Insert($returnPos, $setup)
}

$coreAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'generated staged Dashboard'
foreach ($spec in @(
    @($coreAst, 'Save-Bf808AutoFillSnapshot', 'core snapshot'),
    @($coreAst, 'Get-Bf979WeeklyAttentionHtml', 'core attention renderer'),
    @($coreAst, 'ConvertTo-TeamHtml', 'My Team'),
    @($dashboardAst, 'Read-Bf809AutoFillSnapshotFile', 'snapshot reader'),
    @($dashboardAst, 'Get-Bf979SnapshotWeeklyAttentionHtml', 'Dashboard attention renderer'),
    @($dashboardAst, 'ConvertTo-DashboardHtml', 'Dashboard')
)) {
    [void](Get-OneFunction -Ast $spec[0] -Name ([string]$spec[1]) -Contract ([string]$spec[2]))
}

foreach ($required in @(
    'AvailabilityExclusions = @($availabilitySnapshot)',
    'ProjectionHolds = @($holdSnapshot)',
    'Get-Bf979WeeklyAttentionHtml -AutoFill $AutoFill',
    '$autoFillHtml = $weeklyAttentionHtml + $autoFillHtml'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-979 BLOCKED: final core weekly-attention marker is missing: $required"
    }
}

foreach ($required in @(
    'Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $lineupSnapshot',
    '$bf907WeekGlanceHtml = $bf979WeeklyAttentionHtml + $bf907WeekGlanceHtml',
    'Dashboard does not make a new provider request for this alert.'
)) {
    if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-979 BLOCKED: final Dashboard weekly-attention marker is missing: $required"
    }
}

# BF-809 keeps the original BF-808-1 fields mandatory; BF-979 fields are optional
# so older snapshots continue to load until the next explicit Lineup Review.
$readerFn = (Get-OneFunction -Ast $dashboardAst -Name 'Read-Bf809AutoFillSnapshotFile' -Contract 'BF-809 compatibility').Extent.Text
if ($readerFn.IndexOf("'AvailabilityExclusions'", [System.StringComparison]::Ordinal) -ge 0 -or
    $readerFn.IndexOf("'ProjectionHolds'", [System.StringComparison]::Ordinal) -ge 0) {
    throw 'BF-979 BLOCKED: optional weekly-attention fields were made mandatory for older snapshots.'
}

$attentionSurface = $coreHelper + $dashboardHelper
if ($attentionSurface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-979 BLOCKED: Weekly Attention presentation introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($DashboardPath, $dashboard, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-979 Weekly Attention applied.'
