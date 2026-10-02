param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-984 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-984 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-984 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
    $old = $fn.Extent.Text
    $new = & $Mutator $old
    if ([string]::IsNullOrWhiteSpace($new)) {
        throw "BF-984 BLOCKED: $Contract produced an empty function."
    }
    return $Text.Substring(0, $fn.Extent.StartOffset) + $new + $Text.Substring($fn.Extent.EndOffset)
}

$text = [IO.File]::ReadAllText($DashboardPath)

foreach ($required in @(
    'function ConvertTo-WaiverCandidateDetailHtml {',
    'Compare to replacement context',
    '$encodedRosterFocus',
    '$candidateWorkflowActions'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-984 BLOCKED: BF-982 Candidate Detail replacement capability is missing: $required"
    }
}

$ast = Get-ParsedAst -Text $text -Contract 'pre-helper staged Dashboard'
$candidateFn = Get-OneFunction -Ast $ast -Name 'ConvertTo-WaiverCandidateDetailHtml' -Contract 'Candidate Detail'
$helperInsert = $candidateFn.Extent.StartOffset

$helper = @'
function Convert-Bf984ReplacementCandidateActions {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Actions,
        [Parameter(Mandatory = $true)][bool]$ReplacementContextActive
    )

    if (-not $ReplacementContextActive -or [string]::IsNullOrWhiteSpace($Actions)) {
        return $Actions
    }

    $focused = [regex]::Replace(
        $Actions,
        '<a class="button" href="/waivers/compare\?left=[^"]*">Compare candidate</a>',
        ''
    )
    $focused = $focused.Replace(
        '<a class="button" href="/waivers/roster-compare',
        '<a class="button waiver-quick-primary" href="/waivers/roster-compare'
    )
    return $focused.Replace('>Compare to replacement context</a>', '>Compare to held starter</a>')
}

'@

$text = $text.Insert($helperInsert, $helper)

$text = Replace-FunctionText -Text $text -Name 'ConvertTo-WaiverCandidateDetailHtml' -Contract 'replacement-mode candidate focus' -Mutator {
    param($fn)

    $returnPos = $fn.LastIndexOf('    return @"', [System.StringComparison]::Ordinal)
    if ($returnPos -lt 0) {
        throw 'BF-984 BLOCKED: Candidate Detail return anchor is missing.'
    }

    $focus = @'
    $candidateWorkflowActions = Convert-Bf984ReplacementCandidateActions -Actions $candidateWorkflowActions -ReplacementContextActive (-not [string]::IsNullOrWhiteSpace($encodedRosterFocus))

'@
    return $fn.Insert($returnPos, $focus)
}

$finalAst = Get-ParsedAst -Text $text -Contract 'generated staged Dashboard'
$helperFn = Get-OneFunction -Ast $finalAst -Name 'Convert-Bf984ReplacementCandidateActions' -Contract 'candidate action focus helper'
$candidateFn = Get-OneFunction -Ast $finalAst -Name 'ConvertTo-WaiverCandidateDetailHtml' -Contract 'focused Candidate Detail'

foreach ($required in @(
    'Compare candidate</a>',
    'button waiver-quick-primary',
    'Compare to held starter'
)) {
    if ($helperFn.Extent.Text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-984 BLOCKED: candidate action focus helper marker is missing: $required"
    }
}

if ($candidateFn.Extent.Text.IndexOf(
    '$candidateWorkflowActions = Convert-Bf984ReplacementCandidateActions -Actions $candidateWorkflowActions -ReplacementContextActive (-not [string]::IsNullOrWhiteSpace($encodedRosterFocus))',
    [System.StringComparison]::Ordinal
) -lt 0) {
    throw 'BF-984 BLOCKED: Candidate Detail does not apply replacement action focus.'
}

$surface = $helper + $candidateFn.Extent.Text
if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-984 BLOCKED: Candidate Detail replacement focus introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($DashboardPath, $text, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-984 Candidate Detail replacement decision focus applied.'
