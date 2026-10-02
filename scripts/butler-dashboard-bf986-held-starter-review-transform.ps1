param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-986 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-986 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-986 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-986 BLOCKED: $Contract expected one match, found $count."
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
        throw "BF-986 BLOCKED: $Contract produced an empty function."
    }
    return $Text.Substring(0, $fn.Extent.StartOffset) + $new + $Text.Substring($fn.Extent.EndOffset)
}

$text = [IO.File]::ReadAllText($DashboardPath)

foreach ($required in @(
    'function ConvertTo-WaiverRosterCompareHtml {',
    '$replacementComparisonActive = $false',
    '$replacementComparedRoster',
    '$replacementComparisonActions',
    'Candidate vs roster context',
    'No score, winner, preference, recommendation'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-986 BLOCKED: finalized BF-985 comparison capability is missing: $required"
    }
}

$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'replacement comparison presentation' -Mutator {
    param($fn)

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-986 BLOCKED: completed Roster Compare return anchor is missing.'
    }

    $presentation = @'
    $replacementReviewEyebrow = 'Waiver Roster Compare'
    $replacementReviewHeadline = 'Candidate vs roster context'
    $replacementReviewLede = 'Neutral evidence for one exact authorized waiver candidate and one exact verified roster player. Butler does not select a winner or turn this into a drop recommendation.'
    $replacementReviewContext = ''

    if ($replacementComparisonActive -and $null -ne $replacementComparedRoster) {
        $replacementReviewEyebrow = 'Held starter replacement review'
        $replacementReviewHeadline = 'Candidate vs held starter'
        $replacementReviewLede = 'Review one exact authorized waiver candidate against the exact verified starter carried from Weekly Attention. Butler does not select a winner or turn this into a drop recommendation.'
        $replacementReviewContext = "<div class=`"callout`"><div class=`"eyebrow`">Held starter</div><strong>$(ConvertTo-HtmlText $replacementComparedRoster.Name)</strong> &middot; $(ConvertTo-HtmlText $replacementComparedRoster.Position) starter<div class=`"subtle`">This exact starter is the replacement context carried into this comparison. No replacement has been selected.</div></div>"
    }

'@
    $fn = $fn.Insert($returnPos, $presentation)

    $headerOld = '<section class="panel"><div class="eyebrow">Waiver Roster Compare</div><div class="statusrow"><div><h1 class="headline">Candidate vs roster context</h1><p class="lede">Neutral evidence for one exact authorized waiver candidate and one exact verified roster player. Butler does not select a winner or turn this into a drop recommendation.</p></div><span class="status done">NOT A RANKING</span></div>$replacementComparisonActions</section>'
    $headerNew = '<section class="panel"><div class="eyebrow">$replacementReviewEyebrow</div><div class="statusrow"><div><h1 class="headline">$replacementReviewHeadline</h1><p class="lede">$replacementReviewLede</p></div><span class="status done">NOT A RANKING</span></div>$replacementReviewContext$replacementComparisonActions</section>'

    return Replace-ExactlyOnce -Text $fn -Old $headerOld -New $headerNew -Contract 'completed replacement review heading'
}

$finalAst = Get-ParsedAst -Text $text -Contract 'generated staged Dashboard'
$rosterFn = Get-OneFunction -Ast $finalAst -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'replacement-labeled Roster Compare'

foreach ($required in @(
    '$replacementReviewEyebrow = ''Waiver Roster Compare''',
    '$replacementReviewHeadline = ''Candidate vs roster context''',
    'Held starter replacement review',
    'Candidate vs held starter',
    'This exact starter is the replacement context carried into this comparison. No replacement has been selected.',
    '$replacementReviewContext$replacementComparisonActions'
)) {
    if ($rosterFn.Extent.Text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-986 BLOCKED: replacement review presentation marker is missing: $required"
    }
}

$surface = $rosterFn.Extent.Text
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-986 BLOCKED: replacement review presentation introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($DashboardPath, $text, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-986 held-starter replacement review presentation applied.'
