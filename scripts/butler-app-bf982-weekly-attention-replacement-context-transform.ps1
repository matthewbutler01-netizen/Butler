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
        throw "BF-982 BLOCKED: staged Butler file not found at $path"
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
        throw "BF-982 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-982 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-982 BLOCKED: $Contract expected one match, found $count."
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
        throw "BF-982 BLOCKED: $Contract produced an empty function."
    }
    return $Text.Substring(0, $fn.Extent.StartOffset) + $new + $Text.Substring($fn.Extent.EndOffset)
}

$core = [IO.File]::ReadAllText($CorePath)
$dashboard = [IO.File]::ReadAllText($DashboardPath)

foreach ($required in @(
    'function Get-Bf979WeeklyAttentionHtml {',
    'function Get-Bf980StarterWaiverActionsHtml {',
    'function ConvertTo-WaiverHtml {',
    'function Resolve-WaiverRosterPlayerById {',
    'function Get-WaiverPositionFocusFromRequestTarget {',
    'function ConvertTo-WaiverCandidateDetailHtml {',
    'function ConvertTo-WaiverRosterCompareHtml {'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-982 BLOCKED: finalized weekly-attention/waiver marker is missing: $required"
    }
}

# Live Weekly Attention: add exact held-starter replacement entry point.
$core = Replace-FunctionText -Text $core -Name 'Get-Bf979WeeklyAttentionHtml' -Contract 'live held-starter replacement link' -Mutator {
    param($fn)

    $slotAnchor = '        $slot = if ([string]$player.RosterSlot -ceq ''STARTER'') { ''starter hold'' } else { ''review hold'' }'
    $slotReplacement = @'
        $slot = if ([string]$player.RosterSlot -ceq 'STARTER') { 'starter hold' } else { 'review hold' }
        $replacementAction = ''
        $replacementPosition = ([string]$player.LineupSlot).Trim().ToUpperInvariant()
        $replacementId = ([string]$player.Id).Trim()
        if ([string]$player.RosterSlot -ceq 'STARTER' -and
            @('QB', 'RB', 'WR', 'TE') -ccontains $replacementPosition -and
            $replacementId -match '^[0-9]+$') {
            $replacementPositionHref = [System.Uri]::EscapeDataString($replacementPosition)
            $replacementIdHref = [System.Uri]::EscapeDataString($replacementId)
            $replacementAction = "<div class=`"button-row`" style=`"margin-top:8px`"><a class=`"btn btn-secondary`" href=`"/waivers?position=$replacementPositionHref&amp;roster=$replacementIdHref`">Find $(ConvertTo-HtmlText $replacementPosition) replacement</a></div>"
        }
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $slotAnchor -New $slotReplacement.TrimEnd() -Contract 'live replacement-link derivation'

    $rowOld = '$rows += "<div class=`"callout`"><strong><a href=`"/player?id=$playerHref`">$(ConvertTo-HtmlText $player.Name)</a></strong> &middot; $(ConvertTo-HtmlText $slot)<br><span>$(ConvertTo-HtmlText $status) &middot; $(ConvertTo-HtmlText $injury)</span></div>"'
    $rowNew = '$rows += "<div class=`"callout`"><strong><a href=`"/player?id=$playerHref`">$(ConvertTo-HtmlText $player.Name)</a></strong> &middot; $(ConvertTo-HtmlText $slot)<br><span>$(ConvertTo-HtmlText $status) &middot; $(ConvertTo-HtmlText $injury)</span>$replacementAction</div>"'
    return Replace-ExactlyOnce -Text $fn -Old $rowOld -New $rowNew -Contract 'live replacement-link render'
}

# Cached Dashboard Weekly Attention: same exact held-starter context.
$dashboard = Replace-FunctionText -Text $dashboard -Name 'Get-Bf979SnapshotWeeklyAttentionHtml' -Contract 'cached held-starter replacement link' -Mutator {
    param($fn)

    $holdAnchor = @'
        if ([string]::IsNullOrWhiteSpace($status) -or $status -ceq 'none') { $status = 'review hold' }
        $playerHref = [System.Uri]::EscapeDataString([string]$player.Id)
'@.TrimEnd()
    $holdReplacement = @'
        if ([string]::IsNullOrWhiteSpace($status) -or $status -ceq 'none') { $status = 'review hold' }
        $playerHref = [System.Uri]::EscapeDataString([string]$player.Id)
        $replacementAction = ''
        $replacementPosition = ([string]$player.LineupSlot).Trim().ToUpperInvariant()
        $replacementId = ([string]$player.Id).Trim()
        if ([string]$player.RosterSlot -ceq 'STARTER' -and
            @('QB', 'RB', 'WR', 'TE') -ccontains $replacementPosition -and
            $replacementId -match '^[0-9]+$') {
            $replacementPositionHref = [System.Uri]::EscapeDataString($replacementPosition)
            $replacementIdHref = [System.Uri]::EscapeDataString($replacementId)
            $replacementAction = " <a href=`"/waivers?position=$replacementPositionHref&amp;roster=$replacementIdHref`">Find $(ConvertTo-HtmlText $replacementPosition) replacement</a>"
        }
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $holdAnchor -New $holdReplacement -Contract 'cached replacement-link derivation'

    $nameOld = @'
        if ([string]::IsNullOrWhiteSpace($status) -or $status -ceq 'none') { $status = 'review hold' }
        $playerHref = [System.Uri]::EscapeDataString([string]$player.Id)
        $replacementAction = ''
        $replacementPosition = ([string]$player.LineupSlot).Trim().ToUpperInvariant()
        $replacementId = ([string]$player.Id).Trim()
        if ([string]$player.RosterSlot -ceq 'STARTER' -and
            @('QB', 'RB', 'WR', 'TE') -ccontains $replacementPosition -and
            $replacementId -match '^[0-9]+}

$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'pre-helper staged Dashboard'
$positionFn = Get-OneFunction -Ast $dashboardAst -Name 'Get-WaiverPositionFocusFromRequestTarget' -Contract 'position parser'
$helperInsert = $positionFn.Extent.StartOffset

$rosterFocusHelper = @'
function Get-WaiverReplacementRosterFocusFromRequestTarget {
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
        if ($key -cne 'roster') { continue }

        $value = [System.Uri]::UnescapeDataString($pair.Substring($equals + 1).Replace('+', ' ')).Trim()
        $values += $value
    }

    if ($values.Count -ne 1) {
        return ''
    }
    $rosterId = [string]$values[0]
    if ($rosterId -notmatch '^[0-9]+$') {
        return ''
    }
    return $rosterId
}

'@
$dashboard = $dashboard.Insert($helperInsert, $rosterFocusHelper)

# Waiver Board: validate the replacement target against the exact verified roster.
$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverHtml' -Contract 'Waiver Board held-starter context' -Mutator {
    param($fn)

    $paramOld = @'
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [string]$PositionFocus = ""
'@.TrimEnd()
    $paramNew = @'
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [string]$PositionFocus = "",
        [string]$RosterFocus = ""
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $paramOld -New $paramNew -Contract 'Waiver Board RosterFocus parameter'

    $suffixAnchor = '    $waiverPositionQuerySuffix = if ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) { "" } else { "&position=$encodedPositionFocus" }'
    $contextPrelude = @'
    $waiverPositionQuerySuffix = if ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) { "" } else { "&position=$encodedPositionFocus" }

    $replacementRosterPlayer = $null
    $replacementRosterHref = ''
    $waiverReplacementBoardSuffix = $waiverPositionBoardSuffix
    $replacementContextHtml = ''
    $normalizedRosterFocus = ([string]$RosterFocus).Trim()

    if ($normalizedRosterFocus -match '^[0-9]+$' -and -not [string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        $candidateRosterPlayer = Resolve-WaiverRosterPlayerById -RosterContext $RosterContext -SleeperId $normalizedRosterFocus
        if ($null -ne $candidateRosterPlayer -and
            [string]$candidateRosterPlayer.RosterSlot -ceq 'STARTER' -and
            ([string]$candidateRosterPlayer.Position).Trim().ToUpperInvariant() -ceq $normalizedPositionFocus) {
            $replacementRosterPlayer = $candidateRosterPlayer
            $replacementRosterHref = [System.Uri]::EscapeDataString([string]$candidateRosterPlayer.SleeperId)
            $waiverReplacementBoardSuffix = "?position=$encodedPositionFocus&roster=$replacementRosterHref"
            $replacementContextHtml = "<div class=`"callout`"><div class=`"eyebrow`">Replacement context</div><strong>$(ConvertTo-HtmlText $candidateRosterPlayer.Name)</strong> &middot; $(ConvertTo-HtmlText $candidateRosterPlayer.Position) starter<div class=`"subtle`">Candidates remain Butler's existing authorized pool and source order. No replacement has been selected.</div></div>"
        }
    }
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $suffixAnchor -New $contextPrelude.TrimEnd() -Contract 'Waiver Board replacement context prelude'

    $currentCopyAnchor = '        $currentCopy = if ($isCurrent) {'
    $currentCopyIndex = $fn.IndexOf($currentCopyAnchor, [System.StringComparison]::Ordinal)
    if ($currentCopyIndex -lt 0) {
        throw 'BF-982 BLOCKED: Waiver Board current-copy anchor is missing.'
    }
    $cardsAnchor = '        $cards += @"'
    $cardsIndex = $fn.IndexOf($cardsAnchor, $currentCopyIndex, [System.StringComparison]::Ordinal)
    if ($cardsIndex -lt 0) {
        throw 'BF-982 BLOCKED: Waiver Board card-build anchor is missing.'
    }

    $comparePrelude = @'
        $replacementCompareAction = ''
        if ($null -ne $replacementRosterPlayer) {
            $candidateReplacementHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)
            $replacementCompareAction = "<a class=`"button`" href=`"/waivers/roster-compare?candidate=$candidateReplacementHref&roster=$replacementRosterHref$waiverPositionQuerySuffix`">Compare to held starter</a>"
        }

'@
    $fn = $fn.Insert($cardsIndex, $comparePrelude)

    $actionOld = '>Compare to roster</a></div>'
    $actionNew = '>Compare to roster</a>$replacementCompareAction</div>'
    $fn = Replace-ExactlyOnce -Text $fn -Old $actionOld -New $actionNew -Contract 'Waiver Board direct held-starter compare action'

    $detailContext = @'
    if ($null -ne $replacementRosterPlayer) {
        $cards = $cards.Replace($waiverPositionBoardSuffix + '">View governed details</a>', $waiverReplacementBoardSuffix + '">View governed details</a>')
    }

'@
    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-982 BLOCKED: Waiver Board return anchor is missing.'
    }
    $fn = $fn.Insert($returnPos, $detailContext)

    $gridOld = '$waiverFocusHtml<div class="board-grid">$cards</div>'
    $gridNew = '$waiverFocusHtml$replacementContextHtml<div class="board-grid">$cards</div>'
    $fn = Replace-ExactlyOnce -Text $fn -Old $gridOld -New $gridNew -Contract 'Waiver Board replacement context display'
    return $fn
}

# Candidate Detail: keep the exact replacement context through compare and board return.
$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverCandidateDetailHtml' -Contract 'Candidate Detail replacement context' -Mutator {
    param($fn)

    $paramOld = @'
        [Parameter(Mandatory = $true)][string]$Summary,
        [string]$PositionFocus = ""
'@.TrimEnd()
    $paramNew = @'
        [Parameter(Mandatory = $true)][string]$Summary,
        [string]$PositionFocus = "",
        [string]$RosterFocus = ""
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $paramOld -New $paramNew -Contract 'Candidate Detail RosterFocus parameter'

    $currentAnchor = '    $current = Get-CurrentGovernedAddView -Bundle $Bundle -Summary $Summary'
    $contextPrelude = @'
    $normalizedRosterFocus = ([string]$RosterFocus).Trim()
    $encodedRosterFocus = if ($normalizedRosterFocus -match '^[0-9]+$') { [System.Uri]::EscapeDataString($normalizedRosterFocus) } else { '' }
    $waiverReplacementBoardSuffix = if ([string]::IsNullOrWhiteSpace($encodedRosterFocus)) {
        $waiverPositionBoardSuffix
    }
    elseif ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) {
        "?roster=$encodedRosterFocus"
    }
    else {
        "?position=$encodedPositionFocus&roster=$encodedRosterFocus"
    }

    $current = Get-CurrentGovernedAddView -Bundle $Bundle -Summary $Summary
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $currentAnchor -New $contextPrelude.TrimEnd() -Contract 'Candidate Detail replacement suffix prelude'

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-982 BLOCKED: Candidate Detail return anchor is missing.'
    }

    $workflow = @'
    if (-not [string]::IsNullOrWhiteSpace($encodedRosterFocus)) {
        if (-not [string]::IsNullOrWhiteSpace($waiverPositionQuerySuffix)) {
            $candidateWorkflowActions = $candidateWorkflowActions.Replace($waiverPositionQuerySuffix + '">Compare to roster</a>', "&roster=$encodedRosterFocus$waiverPositionQuerySuffix" + '">Compare to replacement context</a>')
        }
        else {
            $candidateWorkflowActions = $candidateWorkflowActions.Replace('">Compare to roster</a>', "&roster=$encodedRosterFocus" + '">Compare to replacement context</a>')
        }

        $candidateWorkflowActions = $candidateWorkflowActions.Replace('href="/waivers' + $waiverPositionBoardSuffix + '">Back to Waiver Board</a>', 'href="/waivers' + $waiverReplacementBoardSuffix + '">Back to Waiver Board</a>')
    }

'@
    return $fn.Insert($returnPos, $workflow)
}

# Completed roster compare: return to the same held-starter Board context.
$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'Roster Compare replacement-context return' -Mutator {
    param($fn)

    $candidateAnchor = '    $candidateHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)'
    $candidateReplacement = @'
    $candidateHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)
    $waiverReplacementBoardSuffix = $waiverPositionBoardSuffix
    if (-not [string]::IsNullOrWhiteSpace([string]$Request.RosterId) -and [string]$Request.RosterId -match '^[0-9]+$') {
        $returnRosterHref = [System.Uri]::EscapeDataString([string]$Request.RosterId)
        if (-not [string]::IsNullOrWhiteSpace($encodedPositionFocus)) {
            $waiverReplacementBoardSuffix = "?position=$encodedPositionFocus&roster=$returnRosterHref"
        }
        else {
            $waiverReplacementBoardSuffix = "?roster=$returnRosterHref"
        }
    }
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $candidateAnchor -New $candidateReplacement.TrimEnd() -Contract 'Roster Compare replacement Board suffix'

    $backOld = 'href="/waivers$waiverPositionBoardSuffix">'
    $backNew = 'href="/waivers$waiverReplacementBoardSuffix">'
    $count = [regex]::Matches($fn, [regex]::Escape($backOld)).Count
    if ($count -lt 1) {
        throw 'BF-982 BLOCKED: Roster Compare Board-return anchor is missing.'
    }
    return $fn.Replace($backOld, $backNew)
}

# Route calls preserve exact roster context separately from position.
$boardRouteOld = '                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
$boardRouteNew = '                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1]) -RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])'
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $boardRouteOld -New $boardRouteNew -Contract 'Waiver Board replacement route context'

$candidateRouteOld = '                    $html = ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
$candidateRouteNew = '                    $html = ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1]) -RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])'
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $candidateRouteOld -New $candidateRouteNew -Contract 'Candidate Detail replacement route context'

$coreAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'generated staged Dashboard'

foreach ($spec in @(
    @($coreAst, 'Get-Bf979WeeklyAttentionHtml', 'live attention renderer'),
    @($dashboardAst, 'Get-Bf979SnapshotWeeklyAttentionHtml', 'cached attention renderer'),
    @($dashboardAst, 'Get-WaiverReplacementRosterFocusFromRequestTarget', 'replacement roster parser'),
    @($dashboardAst, 'ConvertTo-WaiverHtml', 'Waiver Board'),
    @($dashboardAst, 'ConvertTo-WaiverCandidateDetailHtml', 'Candidate Detail'),
    @($dashboardAst, 'ConvertTo-WaiverRosterCompareHtml', 'Roster Compare')
)) {
    [void](Get-OneFunction -Ast $spec[0] -Name ([string]$spec[1]) -Contract ([string]$spec[2]))
}

foreach ($required in @(
    'Find $(ConvertTo-HtmlText $replacementPosition) replacement',
    '&amp;roster=$replacementIdHref'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-982 BLOCKED: Weekly Attention replacement-link marker is missing: $required"
    }
}

foreach ($required in @(
    'function Get-WaiverReplacementRosterFocusFromRequestTarget',
    '[string]$RosterFocus = ""',
    'Compare to held starter',
    'Replacement context',
    'No replacement has been selected.',
    'Compare to replacement context',
    '-RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])',
    '$waiverReplacementBoardSuffix'
)) {
    if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-982 BLOCKED: replacement-context marker is missing: $required"
    }
}

$surface = $rosterFocusHelper
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-982 BLOCKED: replacement context introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($DashboardPath, $dashboard, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-982 Weekly Attention replacement context applied.'
) {
            $replacementPositionHref = [System.Uri]::EscapeDataString($replacementPosition)
            $replacementIdHref = [System.Uri]::EscapeDataString($replacementId)
            $replacementAction = " <a href=`"/waivers?position=$replacementPositionHref&amp;roster=$replacementIdHref`">Find $(ConvertTo-HtmlText $replacementPosition) replacement</a>"
        }
        $names.Add("<a href=`"/player?id=$playerHref&amp;from=dashboard`">$(ConvertTo-HtmlText $player.Name)</a> ($(ConvertTo-HtmlText $status))")
'@.TrimEnd()
    $nameNew = @'
        if ([string]::IsNullOrWhiteSpace($status) -or $status -ceq 'none') { $status = 'review hold' }
        $playerHref = [System.Uri]::EscapeDataString([string]$player.Id)
        $replacementAction = ''
        $replacementPosition = ([string]$player.LineupSlot).Trim().ToUpperInvariant()
        $replacementId = ([string]$player.Id).Trim()
        if ([string]$player.RosterSlot -ceq 'STARTER' -and
            @('QB', 'RB', 'WR', 'TE') -ccontains $replacementPosition -and
            $replacementId -match '^[0-9]+}

$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'pre-helper staged Dashboard'
$positionFn = Get-OneFunction -Ast $dashboardAst -Name 'Get-WaiverPositionFocusFromRequestTarget' -Contract 'position parser'
$helperInsert = $positionFn.Extent.StartOffset

$rosterFocusHelper = @'
function Get-WaiverReplacementRosterFocusFromRequestTarget {
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
        if ($key -cne 'roster') { continue }

        $value = [System.Uri]::UnescapeDataString($pair.Substring($equals + 1).Replace('+', ' ')).Trim()
        $values += $value
    }

    if ($values.Count -ne 1) {
        return ''
    }
    $rosterId = [string]$values[0]
    if ($rosterId -notmatch '^[0-9]+$') {
        return ''
    }
    return $rosterId
}

'@
$dashboard = $dashboard.Insert($helperInsert, $rosterFocusHelper)

# Waiver Board: validate the replacement target against the exact verified roster.
$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverHtml' -Contract 'Waiver Board held-starter context' -Mutator {
    param($fn)

    $paramOld = @'
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [string]$PositionFocus = ""
'@.TrimEnd()
    $paramNew = @'
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [string]$PositionFocus = "",
        [string]$RosterFocus = ""
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $paramOld -New $paramNew -Contract 'Waiver Board RosterFocus parameter'

    $suffixAnchor = '    $waiverPositionQuerySuffix = if ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) { "" } else { "&position=$encodedPositionFocus" }'
    $contextPrelude = @'
    $waiverPositionQuerySuffix = if ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) { "" } else { "&position=$encodedPositionFocus" }

    $replacementRosterPlayer = $null
    $replacementRosterHref = ''
    $waiverReplacementBoardSuffix = $waiverPositionBoardSuffix
    $replacementContextHtml = ''
    $normalizedRosterFocus = ([string]$RosterFocus).Trim()

    if ($normalizedRosterFocus -match '^[0-9]+$' -and -not [string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        $candidateRosterPlayer = Resolve-WaiverRosterPlayerById -RosterContext $RosterContext -SleeperId $normalizedRosterFocus
        if ($null -ne $candidateRosterPlayer -and
            [string]$candidateRosterPlayer.RosterSlot -ceq 'STARTER' -and
            ([string]$candidateRosterPlayer.Position).Trim().ToUpperInvariant() -ceq $normalizedPositionFocus) {
            $replacementRosterPlayer = $candidateRosterPlayer
            $replacementRosterHref = [System.Uri]::EscapeDataString([string]$candidateRosterPlayer.SleeperId)
            $waiverReplacementBoardSuffix = "?position=$encodedPositionFocus&roster=$replacementRosterHref"
            $replacementContextHtml = "<div class=`"callout`"><div class=`"eyebrow`">Replacement context</div><strong>$(ConvertTo-HtmlText $candidateRosterPlayer.Name)</strong> &middot; $(ConvertTo-HtmlText $candidateRosterPlayer.Position) starter<div class=`"subtle`">Candidates remain Butler's existing authorized pool and source order. No replacement has been selected.</div></div>"
        }
    }
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $suffixAnchor -New $contextPrelude.TrimEnd() -Contract 'Waiver Board replacement context prelude'

    $currentCopyAnchor = '        $currentCopy = if ($isCurrent) {'
    $currentCopyIndex = $fn.IndexOf($currentCopyAnchor, [System.StringComparison]::Ordinal)
    if ($currentCopyIndex -lt 0) {
        throw 'BF-982 BLOCKED: Waiver Board current-copy anchor is missing.'
    }
    $cardsAnchor = '        $cards += @"'
    $cardsIndex = $fn.IndexOf($cardsAnchor, $currentCopyIndex, [System.StringComparison]::Ordinal)
    if ($cardsIndex -lt 0) {
        throw 'BF-982 BLOCKED: Waiver Board card-build anchor is missing.'
    }

    $comparePrelude = @'
        $replacementCompareAction = ''
        if ($null -ne $replacementRosterPlayer) {
            $candidateReplacementHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)
            $replacementCompareAction = "<a class=`"button`" href=`"/waivers/roster-compare?candidate=$candidateReplacementHref&roster=$replacementRosterHref$waiverPositionQuerySuffix`">Compare to held starter</a>"
        }

'@
    $fn = $fn.Insert($cardsIndex, $comparePrelude)

    $actionOld = '>Compare to roster</a></div>'
    $actionNew = '>Compare to roster</a>$replacementCompareAction</div>'
    $fn = Replace-ExactlyOnce -Text $fn -Old $actionOld -New $actionNew -Contract 'Waiver Board direct held-starter compare action'

    $detailContext = @'
    if ($null -ne $replacementRosterPlayer) {
        $cards = $cards.Replace($waiverPositionBoardSuffix + '">View governed details</a>', $waiverReplacementBoardSuffix + '">View governed details</a>')
    }

'@
    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-982 BLOCKED: Waiver Board return anchor is missing.'
    }
    $fn = $fn.Insert($returnPos, $detailContext)

    $gridOld = '$waiverFocusHtml<div class="board-grid">$cards</div>'
    $gridNew = '$waiverFocusHtml$replacementContextHtml<div class="board-grid">$cards</div>'
    $fn = Replace-ExactlyOnce -Text $fn -Old $gridOld -New $gridNew -Contract 'Waiver Board replacement context display'
    return $fn
}

# Candidate Detail: keep the exact replacement context through compare and board return.
$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverCandidateDetailHtml' -Contract 'Candidate Detail replacement context' -Mutator {
    param($fn)

    $paramOld = @'
        [Parameter(Mandatory = $true)][string]$Summary,
        [string]$PositionFocus = ""
'@.TrimEnd()
    $paramNew = @'
        [Parameter(Mandatory = $true)][string]$Summary,
        [string]$PositionFocus = "",
        [string]$RosterFocus = ""
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $paramOld -New $paramNew -Contract 'Candidate Detail RosterFocus parameter'

    $currentAnchor = '    $current = Get-CurrentGovernedAddView -Bundle $Bundle -Summary $Summary'
    $contextPrelude = @'
    $normalizedRosterFocus = ([string]$RosterFocus).Trim()
    $encodedRosterFocus = if ($normalizedRosterFocus -match '^[0-9]+$') { [System.Uri]::EscapeDataString($normalizedRosterFocus) } else { '' }
    $waiverReplacementBoardSuffix = if ([string]::IsNullOrWhiteSpace($encodedRosterFocus)) {
        $waiverPositionBoardSuffix
    }
    elseif ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) {
        "?roster=$encodedRosterFocus"
    }
    else {
        "?position=$encodedPositionFocus&roster=$encodedRosterFocus"
    }

    $current = Get-CurrentGovernedAddView -Bundle $Bundle -Summary $Summary
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $currentAnchor -New $contextPrelude.TrimEnd() -Contract 'Candidate Detail replacement suffix prelude'

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-982 BLOCKED: Candidate Detail return anchor is missing.'
    }

    $workflow = @'
    if (-not [string]::IsNullOrWhiteSpace($encodedRosterFocus)) {
        if (-not [string]::IsNullOrWhiteSpace($waiverPositionQuerySuffix)) {
            $candidateWorkflowActions = $candidateWorkflowActions.Replace($waiverPositionQuerySuffix + '">Compare to roster</a>', "&roster=$encodedRosterFocus$waiverPositionQuerySuffix" + '">Compare to replacement context</a>')
        }
        else {
            $candidateWorkflowActions = $candidateWorkflowActions.Replace('">Compare to roster</a>', "&roster=$encodedRosterFocus" + '">Compare to replacement context</a>')
        }

        $candidateWorkflowActions = $candidateWorkflowActions.Replace('href="/waivers' + $waiverPositionBoardSuffix + '">Back to Waiver Board</a>', 'href="/waivers' + $waiverReplacementBoardSuffix + '">Back to Waiver Board</a>')
    }

'@
    return $fn.Insert($returnPos, $workflow)
}

# Completed roster compare: return to the same held-starter Board context.
$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'Roster Compare replacement-context return' -Mutator {
    param($fn)

    $candidateAnchor = '    $candidateHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)'
    $candidateReplacement = @'
    $candidateHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)
    $waiverReplacementBoardSuffix = $waiverPositionBoardSuffix
    if (-not [string]::IsNullOrWhiteSpace([string]$Request.RosterId) -and [string]$Request.RosterId -match '^[0-9]+$') {
        $returnRosterHref = [System.Uri]::EscapeDataString([string]$Request.RosterId)
        if (-not [string]::IsNullOrWhiteSpace($encodedPositionFocus)) {
            $waiverReplacementBoardSuffix = "?position=$encodedPositionFocus&roster=$returnRosterHref"
        }
        else {
            $waiverReplacementBoardSuffix = "?roster=$returnRosterHref"
        }
    }
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $candidateAnchor -New $candidateReplacement.TrimEnd() -Contract 'Roster Compare replacement Board suffix'

    $backOld = 'href="/waivers$waiverPositionBoardSuffix">'
    $backNew = 'href="/waivers$waiverReplacementBoardSuffix">'
    $count = [regex]::Matches($fn, [regex]::Escape($backOld)).Count
    if ($count -lt 1) {
        throw 'BF-982 BLOCKED: Roster Compare Board-return anchor is missing.'
    }
    return $fn.Replace($backOld, $backNew)
}

# Route calls preserve exact roster context separately from position.
$boardRouteOld = '                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
$boardRouteNew = '                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1]) -RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])'
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $boardRouteOld -New $boardRouteNew -Contract 'Waiver Board replacement route context'

$candidateRouteOld = '                    $html = ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
$candidateRouteNew = '                    $html = ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1]) -RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])'
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $candidateRouteOld -New $candidateRouteNew -Contract 'Candidate Detail replacement route context'

$coreAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'generated staged Dashboard'

foreach ($spec in @(
    @($coreAst, 'Get-Bf979WeeklyAttentionHtml', 'live attention renderer'),
    @($dashboardAst, 'Get-Bf979SnapshotWeeklyAttentionHtml', 'cached attention renderer'),
    @($dashboardAst, 'Get-WaiverReplacementRosterFocusFromRequestTarget', 'replacement roster parser'),
    @($dashboardAst, 'ConvertTo-WaiverHtml', 'Waiver Board'),
    @($dashboardAst, 'ConvertTo-WaiverCandidateDetailHtml', 'Candidate Detail'),
    @($dashboardAst, 'ConvertTo-WaiverRosterCompareHtml', 'Roster Compare')
)) {
    [void](Get-OneFunction -Ast $spec[0] -Name ([string]$spec[1]) -Contract ([string]$spec[2]))
}

foreach ($required in @(
    'Find $(ConvertTo-HtmlText $replacementPosition) replacement',
    '&amp;roster=$replacementIdHref'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-982 BLOCKED: Weekly Attention replacement-link marker is missing: $required"
    }
}

foreach ($required in @(
    'function Get-WaiverReplacementRosterFocusFromRequestTarget',
    '[string]$RosterFocus = ""',
    'Compare to held starter',
    'Replacement context',
    'No replacement has been selected.',
    'Compare to replacement context',
    '-RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])',
    '$waiverReplacementBoardSuffix'
)) {
    if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-982 BLOCKED: replacement-context marker is missing: $required"
    }
}

$surface = $rosterFocusHelper
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-982 BLOCKED: replacement context introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($DashboardPath, $dashboard, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-982 Weekly Attention replacement context applied.'
) {
            $replacementPositionHref = [System.Uri]::EscapeDataString($replacementPosition)
            $replacementIdHref = [System.Uri]::EscapeDataString($replacementId)
            $replacementAction = " <a href=`"/waivers?position=$replacementPositionHref&amp;roster=$replacementIdHref`">Find $(ConvertTo-HtmlText $replacementPosition) replacement</a>"
        }
        $names.Add("<a href=`"/player?id=$playerHref&amp;from=dashboard`">$(ConvertTo-HtmlText $player.Name)</a> ($(ConvertTo-HtmlText $status))$replacementAction")
'@.TrimEnd()
    return Replace-ExactlyOnce -Text $fn -Old $nameOld -New $nameNew -Contract 'cached replacement-link render'
}

$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'pre-helper staged Dashboard'
$positionFn = Get-OneFunction -Ast $dashboardAst -Name 'Get-WaiverPositionFocusFromRequestTarget' -Contract 'position parser'
$helperInsert = $positionFn.Extent.StartOffset

$rosterFocusHelper = @'
function Get-WaiverReplacementRosterFocusFromRequestTarget {
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
        if ($key -cne 'roster') { continue }

        $value = [System.Uri]::UnescapeDataString($pair.Substring($equals + 1).Replace('+', ' ')).Trim()
        $values += $value
    }

    if ($values.Count -ne 1) {
        return ''
    }
    $rosterId = [string]$values[0]
    if ($rosterId -notmatch '^[0-9]+$') {
        return ''
    }
    return $rosterId
}

'@
$dashboard = $dashboard.Insert($helperInsert, $rosterFocusHelper)

# Waiver Board: validate the replacement target against the exact verified roster.
$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverHtml' -Contract 'Waiver Board held-starter context' -Mutator {
    param($fn)

    $paramOld = @'
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [string]$PositionFocus = ""
'@.TrimEnd()
    $paramNew = @'
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [string]$PositionFocus = "",
        [string]$RosterFocus = ""
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $paramOld -New $paramNew -Contract 'Waiver Board RosterFocus parameter'

    $suffixAnchor = '    $waiverPositionQuerySuffix = if ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) { "" } else { "&position=$encodedPositionFocus" }'
    $contextPrelude = @'
    $waiverPositionQuerySuffix = if ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) { "" } else { "&position=$encodedPositionFocus" }

    $replacementRosterPlayer = $null
    $replacementRosterHref = ''
    $waiverReplacementBoardSuffix = $waiverPositionBoardSuffix
    $replacementContextHtml = ''
    $normalizedRosterFocus = ([string]$RosterFocus).Trim()

    if ($normalizedRosterFocus -match '^[0-9]+$' -and -not [string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        $candidateRosterPlayer = Resolve-WaiverRosterPlayerById -RosterContext $RosterContext -SleeperId $normalizedRosterFocus
        if ($null -ne $candidateRosterPlayer -and
            [string]$candidateRosterPlayer.RosterSlot -ceq 'STARTER' -and
            ([string]$candidateRosterPlayer.Position).Trim().ToUpperInvariant() -ceq $normalizedPositionFocus) {
            $replacementRosterPlayer = $candidateRosterPlayer
            $replacementRosterHref = [System.Uri]::EscapeDataString([string]$candidateRosterPlayer.SleeperId)
            $waiverReplacementBoardSuffix = "?position=$encodedPositionFocus&roster=$replacementRosterHref"
            $replacementContextHtml = "<div class=`"callout`"><div class=`"eyebrow`">Replacement context</div><strong>$(ConvertTo-HtmlText $candidateRosterPlayer.Name)</strong> &middot; $(ConvertTo-HtmlText $candidateRosterPlayer.Position) starter<div class=`"subtle`">Candidates remain Butler's existing authorized pool and source order. No replacement has been selected.</div></div>"
        }
    }
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $suffixAnchor -New $contextPrelude.TrimEnd() -Contract 'Waiver Board replacement context prelude'

    $currentCopyAnchor = '        $currentCopy = if ($isCurrent) {'
    $currentCopyIndex = $fn.IndexOf($currentCopyAnchor, [System.StringComparison]::Ordinal)
    if ($currentCopyIndex -lt 0) {
        throw 'BF-982 BLOCKED: Waiver Board current-copy anchor is missing.'
    }
    $cardsAnchor = '        $cards += @"'
    $cardsIndex = $fn.IndexOf($cardsAnchor, $currentCopyIndex, [System.StringComparison]::Ordinal)
    if ($cardsIndex -lt 0) {
        throw 'BF-982 BLOCKED: Waiver Board card-build anchor is missing.'
    }

    $comparePrelude = @'
        $replacementCompareAction = ''
        if ($null -ne $replacementRosterPlayer) {
            $candidateReplacementHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)
            $replacementCompareAction = "<a class=`"button`" href=`"/waivers/roster-compare?candidate=$candidateReplacementHref&roster=$replacementRosterHref$waiverPositionQuerySuffix`">Compare to held starter</a>"
        }

'@
    $fn = $fn.Insert($cardsIndex, $comparePrelude)

    $actionOld = '>Compare to roster</a></div>'
    $actionNew = '>Compare to roster</a>$replacementCompareAction</div>'
    $fn = Replace-ExactlyOnce -Text $fn -Old $actionOld -New $actionNew -Contract 'Waiver Board direct held-starter compare action'

    $detailContext = @'
    if ($null -ne $replacementRosterPlayer) {
        $cards = $cards.Replace($waiverPositionBoardSuffix + '">View governed details</a>', $waiverReplacementBoardSuffix + '">View governed details</a>')
    }

'@
    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-982 BLOCKED: Waiver Board return anchor is missing.'
    }
    $fn = $fn.Insert($returnPos, $detailContext)

    $gridOld = '$waiverFocusHtml<div class="board-grid">$cards</div>'
    $gridNew = '$waiverFocusHtml$replacementContextHtml<div class="board-grid">$cards</div>'
    $fn = Replace-ExactlyOnce -Text $fn -Old $gridOld -New $gridNew -Contract 'Waiver Board replacement context display'
    return $fn
}

# Candidate Detail: keep the exact replacement context through compare and board return.
$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverCandidateDetailHtml' -Contract 'Candidate Detail replacement context' -Mutator {
    param($fn)

    $paramOld = @'
        [Parameter(Mandatory = $true)][string]$Summary,
        [string]$PositionFocus = ""
'@.TrimEnd()
    $paramNew = @'
        [Parameter(Mandatory = $true)][string]$Summary,
        [string]$PositionFocus = "",
        [string]$RosterFocus = ""
'@.TrimEnd()
    $fn = Replace-ExactlyOnce -Text $fn -Old $paramOld -New $paramNew -Contract 'Candidate Detail RosterFocus parameter'

    $currentAnchor = '    $current = Get-CurrentGovernedAddView -Bundle $Bundle -Summary $Summary'
    $contextPrelude = @'
    $normalizedRosterFocus = ([string]$RosterFocus).Trim()
    $encodedRosterFocus = if ($normalizedRosterFocus -match '^[0-9]+$') { [System.Uri]::EscapeDataString($normalizedRosterFocus) } else { '' }
    $waiverReplacementBoardSuffix = if ([string]::IsNullOrWhiteSpace($encodedRosterFocus)) {
        $waiverPositionBoardSuffix
    }
    elseif ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) {
        "?roster=$encodedRosterFocus"
    }
    else {
        "?position=$encodedPositionFocus&roster=$encodedRosterFocus"
    }

    $current = Get-CurrentGovernedAddView -Bundle $Bundle -Summary $Summary
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $currentAnchor -New $contextPrelude.TrimEnd() -Contract 'Candidate Detail replacement suffix prelude'

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-982 BLOCKED: Candidate Detail return anchor is missing.'
    }

    $workflow = @'
    if (-not [string]::IsNullOrWhiteSpace($encodedRosterFocus)) {
        if (-not [string]::IsNullOrWhiteSpace($waiverPositionQuerySuffix)) {
            $candidateWorkflowActions = $candidateWorkflowActions.Replace($waiverPositionQuerySuffix + '">Compare to roster</a>', "&roster=$encodedRosterFocus$waiverPositionQuerySuffix" + '">Compare to replacement context</a>')
        }
        else {
            $candidateWorkflowActions = $candidateWorkflowActions.Replace('">Compare to roster</a>', "&roster=$encodedRosterFocus" + '">Compare to replacement context</a>')
        }

        $candidateWorkflowActions = $candidateWorkflowActions.Replace('href="/waivers' + $waiverPositionBoardSuffix + '">Back to Waiver Board</a>', 'href="/waivers' + $waiverReplacementBoardSuffix + '">Back to Waiver Board</a>')
    }

'@
    return $fn.Insert($returnPos, $workflow)
}

# Completed roster compare: return to the same held-starter Board context.
$dashboard = Replace-FunctionText -Text $dashboard -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'Roster Compare replacement-context return' -Mutator {
    param($fn)

    $candidateAnchor = '    $candidateHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)'
    $candidateReplacement = @'
    $candidateHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)
    $waiverReplacementBoardSuffix = $waiverPositionBoardSuffix
    if (-not [string]::IsNullOrWhiteSpace([string]$Request.RosterId) -and [string]$Request.RosterId -match '^[0-9]+$') {
        $returnRosterHref = [System.Uri]::EscapeDataString([string]$Request.RosterId)
        if (-not [string]::IsNullOrWhiteSpace($encodedPositionFocus)) {
            $waiverReplacementBoardSuffix = "?position=$encodedPositionFocus&roster=$returnRosterHref"
        }
        else {
            $waiverReplacementBoardSuffix = "?roster=$returnRosterHref"
        }
    }
'@
    $fn = Replace-ExactlyOnce -Text $fn -Old $candidateAnchor -New $candidateReplacement.TrimEnd() -Contract 'Roster Compare replacement Board suffix'

    $backOld = 'href="/waivers$waiverPositionBoardSuffix">'
    $backNew = 'href="/waivers$waiverReplacementBoardSuffix">'
    $count = [regex]::Matches($fn, [regex]::Escape($backOld)).Count
    if ($count -lt 1) {
        throw 'BF-982 BLOCKED: Roster Compare Board-return anchor is missing.'
    }
    return $fn.Replace($backOld, $backNew)
}

# Route calls preserve exact roster context separately from position.
$boardRouteOld = '                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
$boardRouteNew = '                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1]) -RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])'
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $boardRouteOld -New $boardRouteNew -Contract 'Waiver Board replacement route context'

$candidateRouteOld = '                    $html = ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
$candidateRouteNew = '                    $html = ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1]) -RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])'
$dashboard = Replace-ExactlyOnce -Text $dashboard -Old $candidateRouteOld -New $candidateRouteNew -Contract 'Candidate Detail replacement route context'

$coreAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$dashboardAst = Get-ParsedAst -Text $dashboard -Contract 'generated staged Dashboard'

foreach ($spec in @(
    @($coreAst, 'Get-Bf979WeeklyAttentionHtml', 'live attention renderer'),
    @($dashboardAst, 'Get-Bf979SnapshotWeeklyAttentionHtml', 'cached attention renderer'),
    @($dashboardAst, 'Get-WaiverReplacementRosterFocusFromRequestTarget', 'replacement roster parser'),
    @($dashboardAst, 'ConvertTo-WaiverHtml', 'Waiver Board'),
    @($dashboardAst, 'ConvertTo-WaiverCandidateDetailHtml', 'Candidate Detail'),
    @($dashboardAst, 'ConvertTo-WaiverRosterCompareHtml', 'Roster Compare')
)) {
    [void](Get-OneFunction -Ast $spec[0] -Name ([string]$spec[1]) -Contract ([string]$spec[2]))
}

foreach ($required in @(
    'Find $(ConvertTo-HtmlText $replacementPosition) replacement',
    '&amp;roster=$replacementIdHref'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
        $dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-982 BLOCKED: Weekly Attention replacement-link marker is missing: $required"
    }
}

foreach ($required in @(
    'function Get-WaiverReplacementRosterFocusFromRequestTarget',
    '[string]$RosterFocus = ""',
    'Compare to held starter',
    'Replacement context',
    'No replacement has been selected.',
    'Compare to replacement context',
    '-RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])',
    '$waiverReplacementBoardSuffix'
)) {
    if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-982 BLOCKED: replacement-context marker is missing: $required"
    }
}

$surface = $rosterFocusHelper
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-982 BLOCKED: replacement context introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($DashboardPath, $dashboard, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-982 Weekly Attention replacement context applied.'
