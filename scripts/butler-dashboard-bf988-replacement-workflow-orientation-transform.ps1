param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-988 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-988 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-988 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
    }
    return $matches[0]
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
    $new = & $Mutator $fn.Extent.Text
    if ([string]::IsNullOrWhiteSpace($new)) {
        throw "BF-988 BLOCKED: $Contract produced an empty function."
    }
    return $Text.Substring(0, $fn.Extent.StartOffset) + $new + $Text.Substring($fn.Extent.EndOffset)
}

function Insert-BeforeFinalReturn {
    param(
        [Parameter(Mandatory = $true)][string]$FunctionText,
        [Parameter(Mandatory = $true)][string]$Snippet,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $returnPos = $FunctionText.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw "BF-988 BLOCKED: $Contract return anchor is missing."
    }
    return $FunctionText.Insert($returnPos, $Snippet)
}

$text = [IO.File]::ReadAllText($DashboardPath)

foreach ($required in @(
    'function ConvertTo-WaiverHtml {',
    'function ConvertTo-WaiverCandidateDetailHtml {',
    'function ConvertTo-WaiverRosterCompareHtml {',
    '$replacementRosterPlayer',
    '$encodedRosterFocus',
    '$replacementComparisonActive',
    '$replacementReviewContext',
    'Back to Weekly Attention'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-988 BLOCKED: finalized replacement workflow marker is missing: $required"
    }
}

$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverHtml' -Contract 'replacement workflow step 1' -Mutator {
    param($fn)

    $snippet = @'
    if ($null -ne $replacementRosterPlayer) {
        $replacementContextHtml = '<div class="callout"><div class="eyebrow">Replacement workflow</div><strong>Step 1 of 3: Choose candidate</strong><div class="subtle">Start with the same-position authorized waiver candidates for this held starter.</div></div>' + $replacementContextHtml
    }

'@
    return Insert-BeforeFinalReturn -FunctionText $fn -Snippet $snippet -Contract 'Waiver Board'
}

$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverCandidateDetailHtml' -Contract 'replacement workflow step 2' -Mutator {
    param($fn)

    $snippet = @'
    if (-not [string]::IsNullOrWhiteSpace($encodedRosterFocus)) {
        $candidateWorkflowActions = '<div class="callout"><div class="eyebrow">Replacement workflow</div><strong>Step 2 of 3: Review candidate</strong><div class="subtle">Review this candidate, then compare directly against the held starter.</div></div>' + $candidateWorkflowActions
    }

'@
    return Insert-BeforeFinalReturn -FunctionText $fn -Snippet $snippet -Contract 'Candidate Detail'
}

$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'replacement workflow step 3' -Mutator {
    param($fn)

    $snippet = @'
    if ($replacementComparisonActive) {
        $replacementReviewContext = '<div class="callout"><div class="eyebrow">Replacement workflow</div><strong>Step 3 of 3: Compare to held starter</strong><div class="subtle">Use the evidence below to decide whether to keep the starter or pursue this candidate.</div></div>' + $replacementReviewContext
    }

'@
    return Insert-BeforeFinalReturn -FunctionText $fn -Snippet $snippet -Contract 'Roster Compare'
}

$finalAst = Get-ParsedAst -Text $text -Contract 'generated staged Dashboard'
$board = Get-OneFunction -Ast $finalAst -Name 'ConvertTo-WaiverHtml' -Contract 'Waiver Board'
$candidate = Get-OneFunction -Ast $finalAst -Name 'ConvertTo-WaiverCandidateDetailHtml' -Contract 'Candidate Detail'
$roster = Get-OneFunction -Ast $finalAst -Name 'ConvertTo-WaiverRosterCompareHtml' -Contract 'Roster Compare'

foreach ($spec in @(
    @($board.Extent.Text, 'if ($null -ne $replacementRosterPlayer)', 'Step 1 replacement guard'),
    @($board.Extent.Text, 'Step 1 of 3: Choose candidate', 'Step 1 label'),
    @($candidate.Extent.Text, 'if (-not [string]::IsNullOrWhiteSpace($encodedRosterFocus))', 'Step 2 replacement guard'),
    @($candidate.Extent.Text, 'Step 2 of 3: Review candidate', 'Step 2 label'),
    @($roster.Extent.Text, 'if ($replacementComparisonActive)', 'Step 3 replacement guard'),
    @($roster.Extent.Text, 'Step 3 of 3: Compare to held starter', 'Step 3 label')
)) {
    if ([string]$spec[0] -notmatch [regex]::Escape([string]$spec[1])) {
        throw "BF-988 BLOCKED: $($spec[2]) is missing."
    }
}

$surface = $board.Extent.Text + [Environment]::NewLine + $candidate.Extent.Text + [Environment]::NewLine + $roster.Extent.Text
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-988 BLOCKED: replacement workflow orientation introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($DashboardPath, $text, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-988 replacement workflow orientation applied.'
