param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-983 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-983 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-983 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-983 BLOCKED: $Contract expected one match, found $count."
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
        throw "BF-983 BLOCKED: $Contract produced an empty function."
    }
    return $Text.Substring(0, $fn.Extent.StartOffset) + $new + $Text.Substring($fn.Extent.EndOffset)
}

$text = [IO.File]::ReadAllText($DashboardPath)

foreach ($required in @(
    'function ConvertTo-WaiverHtml {',
    '$replacementRosterPlayer = $null',
    'Compare to held starter',
    'Replacement context',
    '$waiverReplacementBoardSuffix'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-983 BLOCKED: BF-982 replacement-context capability is missing: $required"
    }
}

$ast = Get-ParsedAst -Text $text -Contract 'pre-helper staged Dashboard'
$waiverFn = Get-OneFunction -Ast $ast -Name 'ConvertTo-WaiverHtml' -Contract 'Waiver Board'
$helperInsert = $waiverFn.Extent.StartOffset

$helper = @'
function Convert-Bf983ReplacementFocusedCards {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Cards,
        [Parameter(Mandatory = $true)][bool]$ReplacementContextActive
    )

    if (-not $ReplacementContextActive -or [string]::IsNullOrWhiteSpace($Cards)) {
        return $Cards
    }

    $genericRosterCompare = '<a class="button" href="/waivers/roster-compare\?candidate=[^"]*">Compare to roster</a>'
    return [regex]::Replace($Cards, $genericRosterCompare, '')
}

'@

$text = $text.Insert($helperInsert, $helper)

$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverHtml' -Contract 'replacement-mode decision focus' -Mutator {
    param($fn)

    $actionOld = '            $replacementCompareAction = "<a class=`"button`" href=`"/waivers/roster-compare?candidate=$candidateReplacementHref&roster=$replacementRosterHref$waiverPositionQuerySuffix`">Compare to held starter</a>"'
    $actionNew = '            $replacementCompareAction = "<a class=`"button waiver-quick-primary`" href=`"/waivers/roster-compare?candidate=$candidateReplacementHref&roster=$replacementRosterHref$waiverPositionQuerySuffix`">Compare to held starter</a>"'
    $fn = Replace-ExactlyOnce -Text $fn -Old $actionOld -New $actionNew -Contract 'held-starter primary action'

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-983 BLOCKED: Waiver Board return anchor is missing.'
    }

    $focus = @'
    $cards = Convert-Bf983ReplacementFocusedCards -Cards $cards -ReplacementContextActive ($null -ne $replacementRosterPlayer)

'@
    return $fn.Insert($returnPos, $focus)
}

$finalAst = Get-ParsedAst -Text $text -Contract 'generated staged Dashboard'
$helperFn = Get-OneFunction -Ast $finalAst -Name 'Convert-Bf983ReplacementFocusedCards' -Contract 'replacement card focus helper'
$waiverFn = Get-OneFunction -Ast $finalAst -Name 'ConvertTo-WaiverHtml' -Contract 'focused Waiver Board'

foreach ($required in @(
    'Compare to roster</a>',
    'ReplacementContextActive',
    '[regex]::Replace($Cards, $genericRosterCompare, '''')'
)) {
    if ($helperFn.Extent.Text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-983 BLOCKED: replacement card focus helper marker is missing: $required"
    }
}

foreach ($required in @(
    'button waiver-quick-primary',
    'Compare to held starter',
    '$cards = Convert-Bf983ReplacementFocusedCards -Cards $cards -ReplacementContextActive ($null -ne $replacementRosterPlayer)'
)) {
    if ($waiverFn.Extent.Text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-983 BLOCKED: focused Waiver Board marker is missing: $required"
    }
}

$surface = $helper + $waiverFn.Extent.Text
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-983 BLOCKED: replacement decision focus introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($DashboardPath, $text, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-983 Waiver replacement decision focus applied.'
