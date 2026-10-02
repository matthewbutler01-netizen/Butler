param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-991 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-991 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-991 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-991 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [IO.File]::ReadAllText($CorePath)
$ast = Get-ParsedAst -Text $core -Contract 'pre-transform staged core'
$fn = Get-OneFunction -Ast $ast -Name 'ConvertTo-AutoFillHtml' -Contract 'Lineup Review renderer'
$function = $fn.Extent.Text

foreach ($required in @(
    'Back to Matchup',
    'Back to My Team',
    'Refresh projection',
    'Review queue'
)) {
    if ($function.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-991 BLOCKED: finalized Lineup Review capability is missing: $required"
    }
}

$old = '<a class=`"btn btn-secondary`" href=`"/matchup`">Back to Matchup</a><a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a>'
$new = '<a class=`"btn btn-secondary`" href=`"/`">Back to Dashboard</a><a class=`"btn btn-secondary`" href=`"/matchup`">Back to Matchup</a><a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a>'

$function = Replace-ExactlyOnce -Text $function -Old $old -New $new -Contract 'completed Lineup Review manager returns'
$core = $core.Substring(0, $fn.Extent.StartOffset) + $function + $core.Substring($fn.Extent.EndOffset)

$finalAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$installed = Get-OneFunction -Ast $finalAst -Name 'ConvertTo-AutoFillHtml' -Contract 'manager-return Lineup Review'
$installedText = $installed.Extent.Text

foreach ($required in @(
    'href=`"/`">Back to Dashboard</a>',
    'href=`"/matchup`">Back to Matchup</a>',
    'href=`"/team`">Back to My Team</a>',
    'href=`"/team/autofill`">Refresh projection</a>'
)) {
    if ($installedText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-991 BLOCKED: completed Lineup Review return marker is missing: $required"
    }
}

if ([regex]::Matches($installedText, [regex]::Escape('Back to Dashboard')).Count -ne 1) {
    throw 'BF-991 BLOCKED: completed Lineup Review must expose exactly one Back to Dashboard action.'
}

if ($installedText -match 'Invoke-RestMethod|Invoke-WebRequest|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|window\.history|javascript:') {
    throw 'BF-991 BLOCKED: Dashboard return polish introduced provider, optimizer, write, history, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-991 Lineup Review manager return navigation applied.'
