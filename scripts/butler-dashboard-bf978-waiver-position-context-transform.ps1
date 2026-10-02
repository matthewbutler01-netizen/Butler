param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath,

    [string]$CorePath = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-978 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-978 BLOCKED: $Contract failed PowerShell parse: $summary"
    }
    return $ast
}

function Get-ExactFunctionAst {
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
        throw "BF-978 BLOCKED: $Contract expected exactly one $Name function, found $($matches.Count)."
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
        throw "BF-978 BLOCKED: $Contract expected one match, found $count."
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
    $function = Get-ExactFunctionAst -Ast $ast -Name $Name -Contract $Contract
    $oldBlock = $function.Extent.Text
    $newBlock = & $Mutator $oldBlock
    if ([string]::IsNullOrWhiteSpace($newBlock)) {
        throw "BF-978 BLOCKED: $Contract produced an empty $Name function."
    }
    return $Text.Substring(0, $function.Extent.StartOffset) +
        $newBlock +
        $Text.Substring($function.Extent.EndOffset)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

foreach ($required in @(
    'function Get-WaiverPositionFocusFromRequestTarget',
    'function ConvertTo-WaiverHtml',
    'function ConvertTo-WaiverCandidateDetailHtml',
    'function ConvertTo-WaiverCompareHtml',
    'function ConvertTo-WaiverRosterCompareHtml',
    'BF-962 Waiver comparison mode bridge'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-978 BLOCKED: finalized waiver workflow marker is missing: $required"
    }
}

$positionPrelude = @'
    $normalizedPositionFocus = ([string]$PositionFocus).Trim().ToUpperInvariant()
    if (@("QB", "RB", "WR", "TE") -cnotcontains $normalizedPositionFocus) {
        $normalizedPositionFocus = ""
    }
    $encodedPositionFocus = if ([string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        ""
    }
    else {
        [System.Uri]::EscapeDataString($normalizedPositionFocus)
    }
    $waiverPositionBoardSuffix = if ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) { "" } else { "?position=$encodedPositionFocus" }
    $waiverPositionQuerySuffix = if ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) { "" } else { "&position=$encodedPositionFocus" }

'@

# Waiver Board: preserve focus in all candidate workflow entry points.
$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverHtml' -Contract 'Waiver Board position-context entry links' -Mutator {
    param($block)

    $anchor = '    $displayCandidates = @('
    if ([regex]::Matches($block, [regex]::Escape($anchor)).Count -ne 1) {
        throw 'BF-978 BLOCKED: Waiver Board display-candidate anchor must exist exactly once.'
    }

    $suffixOnlyPrelude = @'
    $encodedPositionFocus = if ([string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        ""
    }
    else {
        [System.Uri]::EscapeDataString($normalizedPositionFocus)
    }
    $waiverPositionBoardSuffix = if ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) { "" } else { "?position=$encodedPositionFocus" }
    $waiverPositionQuerySuffix = if ([string]::IsNullOrWhiteSpace($encodedPositionFocus)) { "" } else { "&position=$encodedPositionFocus" }

'@
    $block = $block.Replace($anchor, $suffixOnlyPrelude + $anchor)

    foreach ($pair in @(
        @(
            'href="/waivers/candidate/$(ConvertTo-HtmlText $candidate.SleeperId)"',
            'href="/waivers/candidate/$(ConvertTo-HtmlText $candidate.SleeperId)$waiverPositionBoardSuffix"'
        ),
        @(
            'href="/waivers/compare?left=$(ConvertTo-HtmlText $candidate.SleeperId)"',
            'href="/waivers/compare?left=$(ConvertTo-HtmlText $candidate.SleeperId)$waiverPositionQuerySuffix"'
        ),
        @(
            'href="/waivers/roster-compare?candidate=$(ConvertTo-HtmlText $candidate.SleeperId)"',
            'href="/waivers/roster-compare?candidate=$(ConvertTo-HtmlText $candidate.SleeperId)$waiverPositionQuerySuffix"'
        )
    )) {
        $old = [string]$pair[0]
        $new = [string]$pair[1]
        if ([regex]::Matches($block, [regex]::Escape($old)).Count -ne 1) {
            throw "BF-978 BLOCKED: Waiver Board focused link expected one match: $old"
        }
        $block = $block.Replace($old, $new)
    }

    $returnStart = $block.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnStart -lt 0) {
        throw 'BF-978 BLOCKED: Waiver Board final return anchor is missing.'
    }

    $quickActionFocus = @'
    if (-not [string]::IsNullOrWhiteSpace($normalizedPositionFocus) -and -not [string]::IsNullOrWhiteSpace($waiverQuickActions)) {
        $waiverQuickActions = $waiverQuickActions.Replace('">Open governed ADD</a>', $waiverPositionBoardSuffix + '">Open governed ADD</a>')
        $waiverQuickActions = $waiverQuickActions.Replace('">Compare ADD to roster</a>', $waiverPositionQuerySuffix + '">Compare ADD to roster</a>')
    }

'@
    $block = $block.Insert($returnStart, $quickActionFocus)
    return $block
}

# Candidate Detail: keep focus when entering either comparison mode or returning.
$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverCandidateDetailHtml' -Contract 'Waiver Candidate Detail position context' -Mutator {
    param($block)

    $paramOld = @'
        [Parameter(Mandatory = $true)]$Candidate,
        [Parameter(Mandatory = $true)][string]$Summary
'@
    $paramNew = @'
        [Parameter(Mandatory = $true)]$Candidate,
        [Parameter(Mandatory = $true)][string]$Summary,
        [string]$PositionFocus = ""
'@
    $block = Replace-ExactlyOnce -Text $block -Old $paramOld.TrimEnd() -New $paramNew.TrimEnd() -Contract 'Candidate Detail PositionFocus parameter'

    $currentAnchor = '    $current = Get-CurrentGovernedAddView -Bundle $Bundle -Summary $Summary'
    $block = Replace-ExactlyOnce -Text $block -Old $currentAnchor -New ($positionPrelude.TrimEnd() + [Environment]::NewLine + $currentAnchor) -Contract 'Candidate Detail position suffix prelude'

    $returnStart = $block.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnStart -lt 0) {
        throw 'BF-978 BLOCKED: Candidate Detail final return anchor is missing.'
    }

    $workflowFocus = @'
    if (-not [string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        $candidateWorkflowActions = $candidateWorkflowActions.Replace('">Compare candidate</a>', $waiverPositionQuerySuffix + '">Compare candidate</a>')
        $candidateWorkflowActions = $candidateWorkflowActions.Replace('">Compare to roster</a>', $waiverPositionQuerySuffix + '">Compare to roster</a>')
        $candidateWorkflowActions = $candidateWorkflowActions.Replace('href="/waivers">Back to Waiver Board</a>', 'href="/waivers' + $waiverPositionBoardSuffix + '">Back to Waiver Board</a>')
    }

'@
    $block = $block.Insert($returnStart, $workflowFocus)
    return $block
}

# Candidate Compare: retain focus through second-player choice, swap, mode bridge, detail links, and board returns.
$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverCompareHtml' -Contract 'Waiver Candidate Compare position context' -Mutator {
    param($block)

    $paramOld = @'
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)]$Request
'@
    $paramNew = @'
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)]$Request,
        [string]$PositionFocus = ""
'@
    $block = Replace-ExactlyOnce -Text $block -Old $paramOld.TrimEnd() -New $paramNew.TrimEnd() -Contract 'Candidate Compare PositionFocus parameter'

    $candidateAnchor = '    $candidates = @(Get-WaiverCandidates -Bundle $Bundle)'
    $block = Replace-ExactlyOnce -Text $block -Old $candidateAnchor -New ($positionPrelude.TrimEnd() + [Environment]::NewLine + $candidateAnchor) -Contract 'Candidate Compare position suffix prelude'

    $leftAnchor = '    $leftHref = [System.Uri]::EscapeDataString([string]$left.SleeperId)'
    $leftNew = @'
    $leftHref = [System.Uri]::EscapeDataString([string]$left.SleeperId)
    if (-not [string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        $leftHtml = $leftHtml.Replace('href="/waivers/candidate/' + $leftHref + '"', 'href="/waivers/candidate/' + $leftHref + $waiverPositionBoardSuffix + '"')
    }
'@
    $block = Replace-ExactlyOnce -Text $block -Old $leftAnchor -New $leftNew.TrimEnd() -Contract 'Candidate Compare left detail focus'

    $rightAnchor = '    $rightHref = [System.Uri]::EscapeDataString([string]$right.SleeperId)'
    $rightNew = @'
    $rightHref = [System.Uri]::EscapeDataString([string]$right.SleeperId)
    if (-not [string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        $rightHtml = $rightHtml.Replace('href="/waivers/candidate/' + $rightHref + '"', 'href="/waivers/candidate/' + $rightHref + $waiverPositionBoardSuffix + '"')
    }
'@
    $block = Replace-ExactlyOnce -Text $block -Old $rightAnchor -New $rightNew.TrimEnd() -Contract 'Candidate Compare right detail focus'

    $swapOld = '    $swapHref = "/waivers/compare?left=$rightHref&right=$leftHref"'
    $swapNew = '    $swapHref = "/waivers/compare?left=$rightHref&right=$leftHref$waiverPositionQuerySuffix"'
    $block = Replace-ExactlyOnce -Text $block -Old $swapOld -New $swapNew -Contract 'Candidate Compare swap focus'

    $replacements = @(
        @('href="/waivers/compare?left=$leftHref&right=$rightHref"', 'href="/waivers/compare?left=$leftHref&right=$rightHref$waiverPositionQuerySuffix"'),
        @('href="/waivers/roster-compare?candidate=$leftHref"', 'href="/waivers/roster-compare?candidate=$leftHref$waiverPositionQuerySuffix"'),
        @('href="/waivers/roster-compare?candidate=$rightHref"', 'href="/waivers/roster-compare?candidate=$rightHref$waiverPositionQuerySuffix"'),
        @('href="/waivers/candidate/$leftHref"', 'href="/waivers/candidate/$leftHref$waiverPositionBoardSuffix"'),
        @('href="/waivers/candidate/$rightHref"', 'href="/waivers/candidate/$rightHref$waiverPositionBoardSuffix"'),
        @('href="/waivers">', 'href="/waivers$waiverPositionBoardSuffix">')
    )
    foreach ($pair in $replacements) {
        $old = [string]$pair[0]
        $new = [string]$pair[1]
        $count = [regex]::Matches($block, [regex]::Escape($old)).Count
        if ($count -lt 1) {
            throw "BF-978 BLOCKED: Candidate Compare focused link is missing: $old"
        }
        $block = $block.Replace($old, $new)
    }
    return $block
}

# Roster Compare: keep the same waiver position context through player choice and mode bridges.
$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'Waiver Roster Compare position context' -Mutator {
    param($block)

    $paramOld = @'
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [Parameter(Mandatory = $true)]$Request
'@
    $paramNew = @'
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [Parameter(Mandatory = $true)]$Request,
        [string]$PositionFocus = ""
'@
    $block = Replace-ExactlyOnce -Text $block -Old $paramOld.TrimEnd() -New $paramNew.TrimEnd() -Contract 'Roster Compare PositionFocus parameter'

    $targetAnchor = '    $target = Assert-WaiverRosterCompareTarget -Bundle $Bundle -RosterContext $RosterContext'
    $block = Replace-ExactlyOnce -Text $block -Old $targetAnchor -New ($positionPrelude.TrimEnd() + [Environment]::NewLine + $targetAnchor) -Contract 'Roster Compare position suffix prelude'

    $candidateAnchor = '    $candidateHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)'
    $candidateNew = @'
    $candidateHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)
    if (-not [string]::IsNullOrWhiteSpace($normalizedPositionFocus)) {
        $candidateHtml = $candidateHtml.Replace('href="/waivers/candidate/' + $candidateHref + '"', 'href="/waivers/candidate/' + $candidateHref + $waiverPositionBoardSuffix + '"')
    }
'@
    $block = Replace-ExactlyOnce -Text $block -Old $candidateAnchor -New $candidateNew.TrimEnd() -Contract 'Roster Compare candidate detail focus'

    $replacements = @(
        @('href="/waivers/roster-compare?candidate=$candidateHref&roster=$rosterHref"', 'href="/waivers/roster-compare?candidate=$candidateHref&roster=$rosterHref$waiverPositionQuerySuffix"'),
        @('href="/waivers/roster-compare?candidate=$candidateHref"', 'href="/waivers/roster-compare?candidate=$candidateHref$waiverPositionQuerySuffix"'),
        @('href="/waivers/compare?left=$candidateHref"', 'href="/waivers/compare?left=$candidateHref$waiverPositionQuerySuffix"'),
        @('href="/waivers/candidate/$candidateHref"', 'href="/waivers/candidate/$candidateHref$waiverPositionBoardSuffix"'),
        @('href="/waivers">', 'href="/waivers$waiverPositionBoardSuffix">')
    )
    foreach ($pair in $replacements) {
        $old = [string]$pair[0]
        $new = [string]$pair[1]
        $count = [regex]::Matches($block, [regex]::Escape($old)).Count
        if ($count -lt 1) {
            throw "BF-978 BLOCKED: Roster Compare focused link is missing: $old"
        }
        $block = $block.Replace($old, $new)
    }
    return $block
}

# Route calls: derive position from the already-governed parser and pass it separately.
$routePairs = @(
    @(
        '                    $html = ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary',
        '                    $html = ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
    ),
    @(
        '                    $html = ConvertTo-WaiverCompareHtml -Bundle $waiverBundle -Request $compareRequest',
        '                    $html = ConvertTo-WaiverCompareHtml -Bundle $waiverBundle -Request $compareRequest -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
    ),
    @(
        '                    $html = ConvertTo-WaiverRosterCompareHtml -Bundle $waiverEvidence.WaiverBoard -RosterContext $waiverEvidence.RosterContext -Request $rosterCompareRequest',
        '                    $html = ConvertTo-WaiverRosterCompareHtml -Bundle $waiverEvidence.WaiverBoard -RosterContext $waiverEvidence.RosterContext -Request $rosterCompareRequest -PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
    )
)
foreach ($pair in $routePairs) {
    $text = Replace-ExactlyOnce -Text $text -Old ([string]$pair[0]) -New ([string]$pair[1]) -Contract 'waiver deep-route position context'
}

$finalAst = Get-ParsedAst -Text $text -Contract 'generated staged Dashboard'
foreach ($name in @(
    'ConvertTo-WaiverHtml',
    'ConvertTo-WaiverCandidateDetailHtml',
    'ConvertTo-WaiverCompareHtml',
    'ConvertTo-WaiverRosterCompareHtml',
    'Get-WaiverPositionFocusFromRequestTarget'
)) {
    [void](Get-ExactFunctionAst -Ast $finalAst -Name $name -Contract 'generated waiver context continuity')
}

foreach ($required in @(
    '$waiverPositionBoardSuffix',
    '$waiverPositionQuerySuffix',
    '-PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])',
    'href="/waivers/candidate/$leftHref$waiverPositionBoardSuffix"',
    'href="/waivers/roster-compare?candidate=$candidateHref$waiverPositionQuerySuffix"',
    'href="/waivers$waiverPositionBoardSuffix">'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-978 BLOCKED: final waiver position-context marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

# App shell must forward the full candidate-detail query instead of stripping it.
if (-not [string]::IsNullOrWhiteSpace($CorePath)) {
    if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
        throw "BF-978 BLOCKED: staged Butler core not found at $CorePath"
    }

    $core = [System.IO.File]::ReadAllText($CorePath)
    $proxyOld = '                $dashboardRequestTarget = if ($path -eq "/waivers" -or $path -eq "/waivers/compare" -or $path -eq "/waivers/roster-compare") { $parts[1] } else { $path }'
    $proxyNew = '                $dashboardRequestTarget = if ($path -eq "/waivers" -or $path -eq "/waivers/compare" -or $path -eq "/waivers/roster-compare" -or $candidate) { $parts[1] } else { $path }'
    $core = Replace-ExactlyOnce -Text $core -Old $proxyOld -New $proxyNew -Contract 'app-shell candidate-detail query preservation'

    [void](Get-ParsedAst -Text $core -Contract 'generated staged app shell')
    if ($core.IndexOf('$path -eq "/waivers/roster-compare" -or $candidate) { $parts[1] }', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-978 BLOCKED: app-shell candidate-detail query preservation marker is missing.'
    }

    [System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
}

$bf978Surface = $positionPrelude + ($routePairs | ForEach-Object { [string]$_[1] }) -join [Environment]::NewLine
if ($bf978Surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-978 BLOCKED: waiver position context introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

Write-Host 'BF-978 waiver position context continuity applied.'
