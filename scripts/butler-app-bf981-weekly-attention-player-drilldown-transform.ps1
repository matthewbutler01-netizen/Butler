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
        throw "BF-981 BLOCKED: staged Butler file not found at $path"
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
        throw "BF-981 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-981 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-981 BLOCKED: $Contract expected one match, found $count."
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
        throw "BF-981 BLOCKED: $Contract produced an empty function."
    }
    return $Text.Substring(0, $fn.Extent.StartOffset) + $new + $Text.Substring($fn.Extent.EndOffset)
}

$core = [System.IO.File]::ReadAllText($CorePath)
$dashboard = [System.IO.File]::ReadAllText($DashboardPath)

foreach ($required in @(
    'function Get-Bf979WeeklyAttentionHtml {',
    'function Get-Bf979SnapshotWeeklyAttentionHtml {',
    'function Get-Bf980StarterWaiverActionsHtml {',
    'function Get-Bf980SnapshotStarterWaiverActionsHtml {',
    'Add-PlayerDetailCompareReturn -Html $html -CompareContext $compareReturnContext'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-981 BLOCKED: finalized attention/player marker is missing: $required"
    }
}

$core = Replace-FunctionText -Text $core -Name 'Get-Bf979WeeklyAttentionHtml' -Contract 'live Weekly Attention player links' -Mutator {
    param($fn)

    $availabilityOld = '$rows += "<div class=`"callout`"><strong>$(ConvertTo-HtmlText $player.Name)</strong> &middot; unavailable<br><span>$(ConvertTo-HtmlText $status) &middot; $(ConvertTo-HtmlText $injury)</span></div>"'
    $availabilityNew = @'
        $playerHref = [System.Uri]::EscapeDataString([string]$player.Id)
        $rows += "<div class=`"callout`"><strong><a href=`"/player?id=$playerHref`">$(ConvertTo-HtmlText $player.Name)</a></strong> &middot; unavailable<br><span>$(ConvertTo-HtmlText $status) &middot; $(ConvertTo-HtmlText $injury)</span></div>"
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $availabilityOld -New $availabilityNew -Contract 'live unavailable player detail link'

    $holdOld = '$rows += "<div class=`"callout`"><strong>$(ConvertTo-HtmlText $player.Name)</strong> &middot; $(ConvertTo-HtmlText $slot)<br><span>$(ConvertTo-HtmlText $status) &middot; $(ConvertTo-HtmlText $injury)</span></div>"'
    $holdNew = @'
        $playerHref = [System.Uri]::EscapeDataString([string]$player.Id)
        $rows += "<div class=`"callout`"><strong><a href=`"/player?id=$playerHref`">$(ConvertTo-HtmlText $player.Name)</a></strong> &middot; $(ConvertTo-HtmlText $slot)<br><span>$(ConvertTo-HtmlText $status) &middot; $(ConvertTo-HtmlText $injury)</span></div>"
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $holdOld -New $holdNew -Contract 'live hold player detail link'
    return $fn
}

$dashboard = Replace-FunctionText -Text $dashboard -Name 'Get-Bf979SnapshotWeeklyAttentionHtml' -Contract 'cached Weekly Attention player links' -Mutator {
    param($fn)

    $availabilityOld = @'
        if ([string]::IsNullOrWhiteSpace($status) -or $status -ceq 'none') { $status = 'review' }
        $names.Add("$(ConvertTo-HtmlText $player.Name) ($(ConvertTo-HtmlText $status))")
'@.TrimEnd()
    $availabilityNew = @'
        if ([string]::IsNullOrWhiteSpace($status) -or $status -ceq 'none') { $status = 'review' }
        $playerHref = [System.Uri]::EscapeDataString([string]$player.Id)
        $names.Add("<a href=`"/player?id=$playerHref&amp;from=dashboard`">$(ConvertTo-HtmlText $player.Name)</a> ($(ConvertTo-HtmlText $status))")
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $availabilityOld -New $availabilityNew -Contract 'cached unavailable player detail link'

    $holdOld = @'
        if ([string]::IsNullOrWhiteSpace($status) -or $status -ceq 'none') { $status = 'review hold' }
        $names.Add("$(ConvertTo-HtmlText $player.Name) ($(ConvertTo-HtmlText $status))")
'@.TrimEnd()
    $holdNew = @'
        if ([string]::IsNullOrWhiteSpace($status) -or $status -ceq 'none') { $status = 'review hold' }
        $playerHref = [System.Uri]::EscapeDataString([string]$player.Id)
        $names.Add("<a href=`"/player?id=$playerHref&amp;from=dashboard`">$(ConvertTo-HtmlText $player.Name)</a> ($(ConvertTo-HtmlText $status))")
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $holdOld -New $holdNew -Contract 'cached hold player detail link'
    return $fn
}

$helperMarker = 'function Get-PlayerDetailRequestId {'
$helperIndex = $core.IndexOf($helperMarker, [System.StringComparison]::Ordinal)
if ($helperIndex -lt 0) {
    throw 'BF-981 BLOCKED: Player Detail dashboard-return insertion marker is missing.'
}

$helper = @'
function Add-Bf981PlayerDetailDashboardReturn {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][bool]$FromDashboard
    )

    if (-not $FromDashboard) {
        return $Html
    }

    $normal = '<a class="btn btn-secondary" href="/team">Back to My Team</a>'
    $compact = '<a class="btn btn-secondary" href="/team">My Team</a>'
    $normalCount = [regex]::Matches($Html, [regex]::Escape($normal)).Count
    $compactCount = [regex]::Matches($Html, [regex]::Escape($compact)).Count
    if (($normalCount + $compactCount) -ne 1) {
        throw "BF-981 BLOCKED: Dashboard Player Detail return expected one My Team action, found $($normalCount + $compactCount)."
    }

    $dashboardReturn = '<a class="btn btn-secondary" href="/">Back to Dashboard</a><a class="btn btn-secondary" href="/team">My Team</a>'
    if ($normalCount -eq 1) {
        return $Html.Replace($normal, $dashboardReturn)
    }
    return $Html.Replace($compact, $dashboardReturn)
}

'@
$core = $core.Insert($helperIndex, $helper)

$routeAnchor = '                    $html = Add-PlayerDetailCompareReturn -Html $html -CompareContext $compareReturnContext'
$routeExpanded = @'
                    $html = Add-PlayerDetailCompareReturn -Html $html -CompareContext $compareReturnContext
                    $fromDashboard = [regex]::IsMatch($parts[1], '(?:\?|&)from=dashboard(?:&|$)')
                    $html = Add-Bf981PlayerDetailDashboardReturn -Html $html -FromDashboard $fromDashboard
'@
$core = Replace-ExactlyOnce -Text $core -Old $routeAnchor -New $routeExpanded.TrimEnd() -Contract 'Player Detail dashboard return route'

$coreAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'generated staged Dashboard'

foreach ($spec in @(
    @($coreAst, 'Get-Bf979WeeklyAttentionHtml', 'live attention renderer'),
    @($coreAst, 'Add-Bf981PlayerDetailDashboardReturn', 'dashboard return helper'),
    @($dashboardAst, 'Get-Bf979SnapshotWeeklyAttentionHtml', 'cached attention renderer')
)) {
    [void](Get-OneFunction -Ast $spec[0] -Name ([string]$spec[1]) -Contract ([string]$spec[2]))
}

foreach ($required in @(
    'href=`"/player?id=$playerHref`"',
    'Add-Bf981PlayerDetailDashboardReturn -Html $html -FromDashboard $fromDashboard',
    '(?:\?|&)from=dashboard(?:&|$)',
    'Back to Dashboard'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-981 BLOCKED: live/dashboard-return marker is missing: $required"
    }
}

foreach ($required in @(
    'href=`"/player?id=$playerHref&amp;from=dashboard`"',
    '[System.Uri]::EscapeDataString([string]$player.Id)'
)) {
    if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-981 BLOCKED: cached player drill-down marker is missing: $required"
    }
}

$surface = $helper
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-981 BLOCKED: attention player drill-down introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($DashboardPath, $dashboard, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-981 Weekly Attention player drill-down applied.'
