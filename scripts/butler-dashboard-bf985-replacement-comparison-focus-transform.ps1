param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-985 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-985 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-985 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-985 BLOCKED: $Contract expected one match, found $count."
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
        throw "BF-985 BLOCKED: $Contract produced an empty function."
    }
    return $Text.Substring(0, $fn.Extent.StartOffset) + $new + $Text.Substring($fn.Extent.EndOffset)
}

$text = [IO.File]::ReadAllText($DashboardPath)

foreach ($required in @(
    'function ConvertTo-WaiverRosterCompareHtml {',
    'Compare another roster player',
    'Compare with waiver candidate',
    '$waiverReplacementBoardSuffix',
    'Resolve-WaiverRosterPlayerById'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-985 BLOCKED: finalized roster-comparison capability is missing: $required"
    }
}

$ast = Get-ParsedAst -Text $text -Contract 'pre-helper staged Dashboard'
$rosterFn = Get-OneFunction -Ast $ast -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'Roster Compare'
$helperInsert = $rosterFn.Extent.StartOffset

$helper = @'
function Convert-Bf985ReplacementComparisonActions {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Actions,
        [Parameter(Mandatory = $true)][bool]$ReplacementContextActive
    )

    if (-not $ReplacementContextActive -or [string]::IsNullOrWhiteSpace($Actions)) {
        return $Actions
    }

    $focused = [regex]::Replace(
        $Actions,
        '<a class="button" href="/waivers/roster-compare\?candidate=[^"]*">Compare another roster player</a>',
        ''
    )
    $focused = [regex]::Replace(
        $focused,
        '<a class="button" href="/waivers/compare\?left=[^"]*">Compare with waiver candidate</a>',
        ''
    )
    return [regex]::Replace(
        $focused,
        '<a class="button" href="(/waivers(?:\?[^"]*)?)">Back to Waiver Board</a>',
        '<a class="button waiver-quick-primary" href="$1">Back to replacement candidates</a>'
    )
}

'@

$text = $text.Insert($helperInsert, $helper)

$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'completed replacement comparison focus' -Mutator {
    param($fn)

    $actionsOld = '<div class="actions" style="margin-top:14px"><a class="button" href="/waivers/roster-compare?candidate=$candidateHref$waiverPositionQuerySuffix">Compare another roster player</a><a class="button" href="/waivers/compare?left=$candidateHref$waiverPositionQuerySuffix">Compare with waiver candidate</a><a class="button" href="/waivers/candidate/$candidateHref$waiverPositionBoardSuffix">Back to candidate</a><a class="button" href="/waivers$waiverReplacementBoardSuffix">Back to Waiver Board</a></div>'

    if ([regex]::Matches($fn, [regex]::Escape($actionsOld)).Count -ne 1) {
        throw 'BF-985 BLOCKED: completed Roster Compare action contract is missing.'
    }

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-985 BLOCKED: completed Roster Compare return anchor is missing.'
    }

    $focus = @'
    $replacementComparisonActive = $false
    if (-not [string]::IsNullOrWhiteSpace([string]$Request.RosterId) -and
        [string]$Request.RosterId -match '^[0-9]+$' -and
        -not [string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        $replacementComparedRoster = Resolve-WaiverRosterPlayerById -RosterContext $RosterContext -SleeperId ([string]$Request.RosterId)
        if ($null -ne $replacementComparedRoster -and
            [string]$replacementComparedRoster.RosterSlot -ceq 'STARTER' -and
            ([string]$replacementComparedRoster.Position).Trim().ToUpperInvariant() -ceq $normalizedPositionFocus) {
            $replacementComparisonActive = $true
        }
    }

    $replacementComparisonActions = '<div class="actions" style="margin-top:14px"><a class="button" href="/waivers/roster-compare?candidate=$candidateHref$waiverPositionQuerySuffix">Compare another roster player</a><a class="button" href="/waivers/compare?left=$candidateHref$waiverPositionQuerySuffix">Compare with waiver candidate</a><a class="button" href="/waivers/candidate/$candidateHref$waiverPositionBoardSuffix">Back to candidate</a><a class="button" href="/waivers$waiverReplacementBoardSuffix">Back to Waiver Board</a></div>'
    if ($replacementComparisonActive) {
        $replacementComparisonActions = $replacementComparisonActions.Replace(
            'href="/waivers/candidate/$candidateHref$waiverPositionBoardSuffix">',
            'href="/waivers/candidate/$candidateHref$waiverReplacementBoardSuffix">'
        )
    }
    $replacementComparisonActions = Convert-Bf985ReplacementComparisonActions -Actions $replacementComparisonActions -ReplacementContextActive $replacementComparisonActive

'@

    $fn = $fn.Insert($returnPos, $focus)
    return Replace-ExactlyOnce -Text $fn -Old $actionsOld -New '$replacementComparisonActions' -Contract 'completed replacement comparison actions'
}

$finalAst = Get-ParsedAst -Text $text -Contract 'generated staged Dashboard'
$helperFn = Get-OneFunction -Ast $finalAst -Name 'Convert-Bf985ReplacementComparisonActions' -Contract 'replacement comparison action helper'
$rosterFn = Get-OneFunction -Ast $finalAst -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'focused Roster Compare'

foreach ($required in @(
    'Compare another roster player</a>',
    'Compare with waiver candidate</a>',
    'Back to replacement candidates'
)) {
    if ($helperFn.Extent.Text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-985 BLOCKED: replacement comparison helper marker is missing: $required"
    }
}

foreach ($required in @(
    '$replacementComparisonActive = $false',
    '[string]$replacementComparedRoster.RosterSlot -ceq ''STARTER''',
    '$replacementComparisonActions = Convert-Bf985ReplacementComparisonActions',
    'href="/waivers/candidate/$candidateHref$waiverReplacementBoardSuffix">'
)) {
    if ($rosterFn.Extent.Text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-985 BLOCKED: focused Roster Compare marker is missing: $required"
    }
}

$surface = $helper + $rosterFn.Extent.Text
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-985 BLOCKED: replacement comparison focus introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($DashboardPath, $text, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-985 completed replacement comparison focus applied.'
