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
        throw "BF-980 BLOCKED: staged Butler file not found at $path"
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
        throw "BF-980 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-980 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-980 BLOCKED: $Contract expected one match, found $count."
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
        throw "BF-980 BLOCKED: $Contract produced an empty function."
    }
    return $Text.Substring(0, $fn.Extent.StartOffset) + $new + $Text.Substring($fn.Extent.EndOffset)
}

$core = [System.IO.File]::ReadAllText($CorePath)
$dashboard = [System.IO.File]::ReadAllText($DashboardPath)

foreach ($required in @(
    'function Get-Bf979WeeklyAttentionHtml {',
    'function Get-Bf979SnapshotWeeklyAttentionHtml {',
    'ProjectionHolds',
    'Weekly attention',
    'href="/team/autofill">Review Lineup</a>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-980 BLOCKED: BF-979 finalized attention marker is missing: $required"
    }
}

$coreHelperMarker = 'function Get-Bf979WeeklyAttentionHtml {'
$coreHelperIndex = $core.IndexOf($coreHelperMarker, [System.StringComparison]::Ordinal)
if ($coreHelperIndex -lt 0) {
    throw 'BF-980 BLOCKED: live Weekly Attention helper insertion marker is missing.'
}

$coreHelper = @'
function Get-Bf980StarterWaiverActionsHtml {
    param([AllowNull()]$Holds)

    $holdItems = @()
    if ($null -ne $Holds) {
        $holdItems = @($Holds)
    }

    $positions = @(
        $holdItems |
            Where-Object { $null -ne $_ -and [string]$_.RosterSlot -ceq 'STARTER' } |
            ForEach-Object {
                $position = ([string]$_.LineupSlot).Trim().ToUpperInvariant()
                if (@('QB', 'RB', 'WR', 'TE') -ccontains $position) {
                    $position
                }
            } |
            Sort-Object -Unique
    )

    if ($positions.Count -eq 0) {
        return ''
    }

    $links = ''
    foreach ($position in $positions) {
        $encodedPosition = [System.Uri]::EscapeDataString($position)
        $links += "<a class=`"btn btn-secondary`" href=`"/waivers?position=$encodedPosition`">Check $(ConvertTo-HtmlText $position) waivers</a>"
    }
    return $links
}

'@
$core = $core.Insert($coreHelperIndex, $coreHelper)

$core = Replace-FunctionText -Text $core -Name 'Get-Bf979WeeklyAttentionHtml' -Contract 'live Weekly Attention waiver actions' -Mutator {
    param($fn)

    $holdAnchor = '    $starterHolds = @($holds | Where-Object { [string]$_.RosterSlot -ceq ''STARTER'' })'
    $holdReplacement = @'
    $starterHolds = @($holds | Where-Object { [string]$_.RosterSlot -ceq 'STARTER' })
    $waiverActionHtml = Get-Bf980StarterWaiverActionsHtml -Holds $holds
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $holdAnchor -New $holdReplacement.TrimEnd() -Contract 'live waiver-action derivation'

    $buttonOld = '<div class=`"button-row`"><a class=`"btn btn-primary`" href=`"/team/autofill`">Review Lineup</a></div>'
    $buttonNew = '<div class=`"button-row`"><a class=`"btn btn-primary`" href=`"/team/autofill`">Review Lineup</a>$waiverActionHtml</div>'
    $fn = Replace-ExactlyOnce -Text $fn -Old $buttonOld -New $buttonNew -Contract 'live focused-waiver actions'

    $metaOld = 'Holds preserve the current lineup state; Butler does not invent replacement points or submit a lineup.'
    $metaNew = 'Holds preserve the current lineup state; Butler does not invent replacement points or submit a lineup. Position-focused waiver links filter the board only; they do not choose a replacement.'
    $fn = Replace-ExactlyOnce -Text $fn -Old $metaOld -New $metaNew -Contract 'live waiver-action boundary'
    return $fn
}

$dashboardHelperMarker = 'function Get-Bf979SnapshotWeeklyAttentionHtml {'
$dashboardHelperIndex = $dashboard.IndexOf($dashboardHelperMarker, [System.StringComparison]::Ordinal)
if ($dashboardHelperIndex -lt 0) {
    throw 'BF-980 BLOCKED: cached Weekly Attention helper insertion marker is missing.'
}

$dashboardHelper = @'
function Get-Bf980SnapshotStarterWaiverActionsHtml {
    param([AllowNull()]$Holds)

    $holdItems = @()
    if ($null -ne $Holds) {
        $holdItems = @($Holds)
    }

    $positions = @(
        $holdItems |
            Where-Object { $null -ne $_ -and [string]$_.RosterSlot -ceq 'STARTER' } |
            ForEach-Object {
                $position = ([string]$_.LineupSlot).Trim().ToUpperInvariant()
                if (@('QB', 'RB', 'WR', 'TE') -ccontains $position) {
                    $position
                }
            } |
            Sort-Object -Unique
    )

    if ($positions.Count -eq 0) {
        return ''
    }

    $links = ''
    foreach ($position in $positions) {
        $encodedPosition = [System.Uri]::EscapeDataString($position)
        $links += "<a class=`"btn btn-secondary`" href=`"/waivers?position=$encodedPosition`">Check $(ConvertTo-HtmlText $position) waivers</a>"
    }
    return $links
}

'@
$dashboard = $dashboard.Insert($dashboardHelperIndex, $dashboardHelper)

$dashboard = Replace-FunctionText -Text $dashboard -Name 'Get-Bf979SnapshotWeeklyAttentionHtml' -Contract 'cached Weekly Attention waiver actions' -Mutator {
    param($fn)

    $holdAnchor = '    $starterHolds = @($holds | Where-Object { [string]$_.RosterSlot -ceq ''STARTER'' })'
    $holdReplacement = @'
    $starterHolds = @($holds | Where-Object { [string]$_.RosterSlot -ceq 'STARTER' })
    $waiverActionHtml = Get-Bf980SnapshotStarterWaiverActionsHtml -Holds $holds
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $holdAnchor -New $holdReplacement.TrimEnd() -Contract 'cached waiver-action derivation'

    $buttonOld = '<div class=`"button-row`"><a class=`"btn btn-primary`" href=`"/team/autofill`">Review Lineup</a></div>'
    $buttonNew = '<div class=`"button-row`"><a class=`"btn btn-primary`" href=`"/team/autofill`">Review Lineup</a>$waiverActionHtml</div>'
    $fn = Replace-ExactlyOnce -Text $fn -Old $buttonOld -New $buttonNew -Contract 'cached focused-waiver actions'

    $metaOld = 'Saved from the latest governed Lineup Review; Dashboard does not make a new provider request for this alert.'
    $metaNew = 'Saved from the latest governed Lineup Review; Dashboard does not make a new provider request for this alert. Position-focused waiver links filter the board only; they do not choose a replacement.'
    $fn = Replace-ExactlyOnce -Text $fn -Old $metaOld -New $metaNew -Contract 'cached waiver-action boundary'
    return $fn
}

$coreAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'generated staged Dashboard'

foreach ($spec in @(
    @($coreAst, 'Get-Bf980StarterWaiverActionsHtml', 'live waiver helper'),
    @($coreAst, 'Get-Bf979WeeklyAttentionHtml', 'live attention renderer'),
    @($dashboardAst, 'Get-Bf980SnapshotStarterWaiverActionsHtml', 'cached waiver helper'),
    @($dashboardAst, 'Get-Bf979SnapshotWeeklyAttentionHtml', 'cached attention renderer')
)) {
    [void](Get-OneFunction -Ast $spec[0] -Name ([string]$spec[1]) -Contract ([string]$spec[2]))
}

foreach ($required in @(
    'Get-Bf980StarterWaiverActionsHtml -Holds $holds',
    'href=`"/waivers?position=$encodedPosition`"',
    'Position-focused waiver links filter the board only; they do not choose a replacement.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-980 BLOCKED: live focused-waiver marker is missing: $required"
    }
}

foreach ($required in @(
    'Get-Bf980SnapshotStarterWaiverActionsHtml -Holds $holds',
    'href=`"/waivers?position=$encodedPosition`"',
    'Position-focused waiver links filter the board only; they do not choose a replacement.'
)) {
    if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-980 BLOCKED: cached focused-waiver marker is missing: $required"
    }
}

$surface = $coreHelper + [Environment]::NewLine + $dashboardHelper
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-980 BLOCKED: focused waiver actions introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($DashboardPath, $dashboard, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-980 Weekly Attention focused waiver actions applied.'
