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
        throw "BF-987 BLOCKED: staged Butler file not found at $path"
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
        throw "BF-987 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-987 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-987 BLOCKED: $Contract expected one match, found $count."
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
        throw "BF-987 BLOCKED: $Contract produced an empty function."
    }
    return $Text.Substring(0, $fn.Extent.StartOffset) + $new + $Text.Substring($fn.Extent.EndOffset)
}

$core = [IO.File]::ReadAllText($CorePath)
$dashboard = [IO.File]::ReadAllText($DashboardPath)

foreach ($required in @(
    'function Get-Bf979WeeklyAttentionHtml {',
    'function Get-Bf979SnapshotWeeklyAttentionHtml {',
    'function Get-WaiverReplacementRosterFocusFromRequestTarget {',
    'function ConvertTo-WaiverHtml {',
    'function ConvertTo-WaiverCandidateDetailHtml {',
    'function ConvertTo-WaiverRosterCompareHtml {',
    'Held starter replacement review'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-987 BLOCKED: finalized replacement-workflow marker is missing: $required"
    }
}

$core = Replace-FunctionText -Text $core -Name 'Get-Bf979WeeklyAttentionHtml' -Contract 'My Team Weekly Attention origin' -Mutator {
    param($fn)

    $fn = Replace-ExactlyOnce -Text $fn -Old 'return "<section class=`"panel weekly-attention`">' -New 'return "<section id=`"weekly-attention`" class=`"panel weekly-attention`">' -Contract 'My Team Weekly Attention anchor'
    $fn = Replace-ExactlyOnce -Text $fn -Old '&amp;roster=$replacementIdHref`">Find $(ConvertTo-HtmlText $replacementPosition) replacement</a>' -New '&amp;roster=$replacementIdHref&amp;from=team`">Find $(ConvertTo-HtmlText $replacementPosition) replacement</a>' -Contract 'My Team replacement origin token'
    return $fn
}

$dashboard = Replace-FunctionText -Text $dashboard -Name 'Get-Bf979SnapshotWeeklyAttentionHtml' -Contract 'Dashboard Weekly Attention origin' -Mutator {
    param($fn)

    $fn = Replace-ExactlyOnce -Text $fn -Old 'return "<section class=`"panel`">' -New 'return "<section id=`"weekly-attention`" class=`"panel`">' -Contract 'Dashboard Weekly Attention anchor'
    $fn = Replace-ExactlyOnce -Text $fn -Old '&amp;roster=$replacementIdHref`">Find $(ConvertTo-HtmlText $replacementPosition) replacement</a>' -New '&amp;roster=$replacementIdHref&amp;from=dashboard`">Find $(ConvertTo-HtmlText $replacementPosition) replacement</a>' -Contract 'Dashboard replacement origin token'
    return $fn
}

$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'pre-origin-helper staged Dashboard'
$rosterParser = Get-OneFunction -Ast $dashboardAst -Name 'Get-WaiverReplacementRosterFocusFromRequestTarget' -Contract 'replacement roster parser'
$helperInsert = $rosterParser.Extent.StartOffset

$originHelper = @'
function Get-Bf987WeeklyAttentionOriginFromRequestTarget {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $question = $RequestTarget.IndexOf('?')
    if ($question -lt 0 -or $question + 1 -ge $RequestTarget.Length) {
        return ''
    }

    $values = @()
    foreach ($pair in @($RequestTarget.Substring($question + 1) -split '&')) {
        if ([string]::IsNullOrWhiteSpace($pair)) { continue }
        $equals = $pair.IndexOf('=')
        if ($equals -lt 0) { continue }

        $key = [System.Uri]::UnescapeDataString($pair.Substring(0, $equals).Replace('+', ' ')).Trim()
        if ($key -cne 'from') { continue }

        $value = [System.Uri]::UnescapeDataString($pair.Substring($equals + 1).Replace('+', ' ')).Trim().ToLowerInvariant()
        $values += $value
    }

    if ($values.Count -ne 1) {
        return ''
    }

    $origin = [string]$values[0]
    if (@('dashboard', 'team') -cnotcontains $origin) {
        return ''
    }
    return $origin
}

'@

$dashboard = $dashboard.Insert($helperInsert, $originHelper)

$originPrelude = @'
    $normalizedAttentionOrigin = ([string]$AttentionOrigin).Trim().ToLowerInvariant()
    $attentionOriginQuerySuffix = ''
    $attentionReturnHref = ''
    if ($normalizedAttentionOrigin -ceq 'dashboard') {
        $attentionOriginQuerySuffix = '&from=dashboard'
        $attentionReturnHref = '/#weekly-attention'
    }
    elseif ($normalizedAttentionOrigin -ceq 'team') {
        $attentionOriginQuerySuffix = '&from=team'
        $attentionReturnHref = '/team#weekly-attention'
    }

'@

$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverHtml' -Contract 'Waiver Board Weekly Attention return' -Mutator {
    param($fn)

    $paramOld = @'
        [string]$PositionFocus = "",
        [string]$RosterFocus = ""
'@.TrimEnd()
    $paramNew = @'
        [string]$PositionFocus = "",
        [string]$RosterFocus = "",
        [string]$AttentionOrigin = ""
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $paramOld -New $paramNew -Contract 'Waiver Board AttentionOrigin parameter'

    $anchor = '    $replacementRosterPlayer = $null'
    $fn = Replace-ExactlyOnce -Text $fn -Old $anchor -New ($originPrelude.TrimEnd() + [Environment]::NewLine + $anchor) -Contract 'Waiver Board origin prelude'

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-987 BLOCKED: Waiver Board return anchor is missing.'
    }

    $preserve = @'
    if ($null -ne $replacementRosterPlayer -and -not [string]::IsNullOrWhiteSpace($attentionReturnHref)) {
        $cards = $cards.Replace(
            $waiverReplacementBoardSuffix + '">View governed details</a>',
            $waiverReplacementBoardSuffix + $attentionOriginQuerySuffix + '">View governed details</a>'
        )
        $cards = $cards.Replace(
            '">Compare to held starter</a>',
            $attentionOriginQuerySuffix + '">Compare to held starter</a>'
        )
        $replacementContextHtml += '<div class="actions" style="margin-top:10px"><a class="button" href="' + (ConvertTo-HtmlText $attentionReturnHref) + '">Back to Weekly Attention</a></div>'
    }

'@
    return $fn.Insert($returnPos, $preserve)
}

$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverCandidateDetailHtml' -Contract 'Candidate Detail Weekly Attention return' -Mutator {
    param($fn)

    $paramOld = @'
        [string]$PositionFocus = "",
        [string]$RosterFocus = ""
'@.TrimEnd()
    $paramNew = @'
        [string]$PositionFocus = "",
        [string]$RosterFocus = "",
        [string]$AttentionOrigin = ""
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $paramOld -New $paramNew -Contract 'Candidate Detail AttentionOrigin parameter'

    $anchor = '    $normalizedRosterFocus = ([string]$RosterFocus).Trim()'
    $fn = Replace-ExactlyOnce -Text $fn -Old $anchor -New ($originPrelude.TrimEnd() + [Environment]::NewLine + $anchor) -Contract 'Candidate Detail origin prelude'

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-987 BLOCKED: Candidate Detail return anchor is missing.'
    }

    $preserve = @'
    if (-not [string]::IsNullOrWhiteSpace($encodedRosterFocus) -and -not [string]::IsNullOrWhiteSpace($attentionReturnHref)) {
        $candidateWorkflowActions = $candidateWorkflowActions.Replace(
            '">Compare to held starter</a>',
            $attentionOriginQuerySuffix + '">Compare to held starter</a>'
        )
        $candidateWorkflowActions = $candidateWorkflowActions.Replace(
            'href="/waivers' + $waiverReplacementBoardSuffix + '">Back to Waiver Board</a>',
            'href="/waivers' + $waiverReplacementBoardSuffix + $attentionOriginQuerySuffix + '">Back to Waiver Board</a>'
        )
        $candidateWorkflowActions = $candidateWorkflowActions.Replace(
            '</div>',
            '<a class="button" href="' + (ConvertTo-HtmlText $attentionReturnHref) + '">Back to Weekly Attention</a></div>'
        )
    }

'@
    return $fn.Insert($returnPos, $preserve)
}

$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'Roster Compare Weekly Attention return' -Mutator {
    param($fn)

    $paramOld = @'
        [Parameter(Mandatory = $true)]$Request,
        [string]$PositionFocus = ""
'@.TrimEnd()
    $paramNew = @'
        [Parameter(Mandatory = $true)]$Request,
        [string]$PositionFocus = "",
        [string]$AttentionOrigin = ""
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $paramOld -New $paramNew -Contract 'Roster Compare AttentionOrigin parameter'

    $anchor = '    $target = Assert-WaiverRosterCompareTarget -Bundle $Bundle -RosterContext $RosterContext'
    $fn = Replace-ExactlyOnce -Text $fn -Old $anchor -New ($originPrelude.TrimEnd() + [Environment]::NewLine + $anchor) -Contract 'Roster Compare origin prelude'

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-987 BLOCKED: Roster Compare return anchor is missing.'
    }

    $preserve = @'
    if ($replacementComparisonActive -and -not [string]::IsNullOrWhiteSpace($attentionReturnHref)) {
        $replacementComparisonActions = $replacementComparisonActions.Replace(
            'href="/waivers/candidate/$candidateHref$waiverReplacementBoardSuffix">',
            'href="/waivers/candidate/$candidateHref$waiverReplacementBoardSuffix$attentionOriginQuerySuffix">'
        )
        $replacementComparisonActions = $replacementComparisonActions.Replace(
            'href="/waivers$waiverReplacementBoardSuffix">',
            'href="/waivers$waiverReplacementBoardSuffix$attentionOriginQuerySuffix">'
        )
        $replacementComparisonActions = $replacementComparisonActions.Replace(
            '</div>',
            '<a class="button" href="' + (ConvertTo-HtmlText $attentionReturnHref) + '">Back to Weekly Attention</a></div>'
        )
    }

'@
    return $fn.Insert($returnPos, $preserve)
}

$boardRouteOld = '                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1]) -RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])'
$boardRouteNew = $boardRouteOld + ' -AttentionOrigin (Get-Bf987WeeklyAttentionOriginFromRequestTarget -RequestTarget $parts[1])'
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $boardRouteOld -New $boardRouteNew -Contract 'Waiver Board origin route context'

$candidateRouteOld = '                    $html = ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1]) -RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])'
$candidateRouteNew = $candidateRouteOld + ' -AttentionOrigin (Get-Bf987WeeklyAttentionOriginFromRequestTarget -RequestTarget $parts[1])'
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $candidateRouteOld -New $candidateRouteNew -Contract 'Candidate Detail origin route context'

$rosterRouteOld = '                    $html = ConvertTo-WaiverRosterCompareHtml -Bundle $waiverEvidence.WaiverBoard -RosterContext $waiverEvidence.RosterContext -Request $rosterCompareRequest -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
$rosterRouteNew = $rosterRouteOld + ' -AttentionOrigin (Get-Bf987WeeklyAttentionOriginFromRequestTarget -RequestTarget $parts[1])'
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $rosterRouteOld -New $rosterRouteNew -Contract 'Roster Compare origin route context'

$coreAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'generated staged Dashboard'

foreach ($spec in @(
    @($coreAst, 'Get-Bf979WeeklyAttentionHtml', 'My Team Weekly Attention'),
    @($dashboardAst, 'Get-Bf979SnapshotWeeklyAttentionHtml', 'Dashboard Weekly Attention'),
    @($dashboardAst, 'Get-Bf987WeeklyAttentionOriginFromRequestTarget', 'origin parser'),
    @($dashboardAst, 'ConvertTo-WaiverHtml', 'Waiver Board'),
    @($dashboardAst, 'ConvertTo-WaiverCandidateDetailHtml', 'Candidate Detail'),
    @($dashboardAst, 'ConvertTo-WaiverRosterCompareHtml', 'Roster Compare')
)) {
    [void](Get-OneFunction -Ast $spec[0] -Name ([string]$spec[1]) -Contract ([string]$spec[2]))
}

foreach ($required in @(
    'id=`"weekly-attention`"',
    '&amp;from=team',
    '&amp;from=dashboard'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-987 BLOCKED: Weekly Attention return-loop marker is missing: $required"
    }
}

foreach ($required in @(
    'function Get-Bf987WeeklyAttentionOriginFromRequestTarget',
    "@('dashboard', 'team') -cnotcontains",
    '[string]$AttentionOrigin = ""',
    'Back to Weekly Attention',
    '/#weekly-attention',
    '/team#weekly-attention',
    '-AttentionOrigin (Get-Bf987WeeklyAttentionOriginFromRequestTarget -RequestTarget $parts[1])'
)) {
    if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-987 BLOCKED: replacement origin continuity marker is missing: $required"
    }
}

$surface = $originHelper + $originPrelude
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:|https?://') {
    throw 'BF-987 BLOCKED: Weekly Attention return loop introduced provider, optimizer, FAAB, write, open-redirect, or external URL behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($DashboardPath, $dashboard, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-987 Weekly Attention replacement return loop applied.'
