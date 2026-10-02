Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$tradePath = Join-Path $PSScriptRoot 'butler-trade-lab.ps1'
if (-not (Test-Path -LiteralPath $tradePath -PathType Leaf)) {
    throw "BF-995 BLOCKED: Trade Analyzer script not found at $tradePath"
}

$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($tradePath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-995 BLOCKED: Trade Analyzer failed PowerShell parse: $summary"
}

$matches = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-TradeLabHtml'
}, $true))
if ($matches.Count -ne 1) {
    throw "BF-995 BLOCKED: expected one ConvertTo-TradeLabHtml function, found $($matches.Count)."
}

$trade = $matches[0].Extent.Text

foreach ($required in @(
    '$workflowStep = if ($null -ne $Evaluation) { 3 } elseif ($null -ne $Opponent) { 2 } else { 1 }',
    "Label = 'Choose partner'",
    "Label = 'Build exact deal'",
    "Label = 'Review result'",
    'aria-label=`"Trade workflow`"',
    '$partnerControlsHtml =',
    'Evaluated partner',
    'This recommendation is locked to this exact league opponent.',
    'href=`"/trade`">Change partner</a>',
    '$giveAssetLabel = if (@($Give).Count -eq 1)',
    '$receiveAssetLabel = if (@($Receive).Count -eq 1)',
    'href="#edit-trade">Edit this deal</a>',
    'New deal with this partner',
    '<details id="edit-trade" class="edit-deal">',
    '$workflowHtml$partnerControlsHtml'
)) {
    if ($trade.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-995 BLOCKED: Trade Analyzer decision-flow marker is missing: $required"
    }
}

foreach ($required in @(
    '.trade-workflow{',
    '.trade-step.current{',
    '.trade-partner-lock{',
    '.trade-deal-actions{',
    '.trade-workflow{grid-template-columns:1fr}',
    '.trade-partner-lock{align-items:flex-start;flex-direction:column}'
)) {
    if ($trade.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-995 BLOCKED: Trade Analyzer responsive workflow style is missing: $required"
    }
}

$evaluatedLock = '$partnerControlsHtml = "<div class=`"trade-partner-lock`"'
if ([regex]::Matches($trade, [regex]::Escape($evaluatedLock)).Count -ne 1) {
    throw 'BF-995 BLOCKED: evaluated partner lock must be installed exactly once.'
}

$editTarget = '<details id="edit-trade" class="edit-deal">'
if ([regex]::Matches($trade, [regex]::Escape($editTarget)).Count -ne 1) {
    throw 'BF-995 BLOCKED: evaluated deal edit target must be installed exactly once.'
}

foreach ($preserved in @(
    'Get Butler recommendation',
    'Build Counteroffer',
    'Scout franchise',
    'Back to League',
    'Why Butler says this',
    'Raw decision record'
)) {
    if ($trade.IndexOf($preserved, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-995 BLOCKED: existing Trade Analyzer capability regressed: $preserved"
    }
}

if ($trade -match 'Method = "POST"|method="post"|submitTransaction|setFaab|AutoFillLineupOptimizer|javascript:|window\.location') {
    throw 'BF-995 BLOCKED: Trade Analyzer decision-flow batch introduced write, optimizer, or scripted navigation behavior.'
}

if ($trade -match 'Invoke-ButlerReadOnly|Invoke-RestMethod|Invoke-WebRequest') {
    throw 'BF-995 BLOCKED: presentation-only Trade Analyzer renderer introduced a new backend/provider read.'
}

Write-Host 'BF-995 TRADE ANALYZER DECISION-FLOW ACCEPTANCE: PASS'
Write-Host 'Coverage: three-step workflow, evaluated-partner lock, exact asset counts, and edit/new-deal actions with existing read-only recommendation and counter flows preserved'
