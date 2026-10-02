param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-989 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-989 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-989 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-989 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [IO.File]::ReadAllText($CorePath)
$ast = Get-ParsedAst -Text $core -Contract 'pre-transform staged core'
$fn = Get-OneFunction -Ast $ast -Name 'Add-DashboardMatchupSummary' -Contract 'Dashboard matchup summary'
$function = $fn.Extent.Text

foreach ($required in @(
    'Open Weekly Matchup &rarr;',
    '$opponentTools',
    'href="/franchise?id=',
    'href="/trade?opponent=',
    "Invoke-ButlerReadOnlyTask -Task ':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit'"
)) {
    if ($function.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-989 BLOCKED: finalized Dashboard matchup capability is missing: $required"
    }
}

$toolsOld = @'
    $opponentTools = if (-not [string]::IsNullOrWhiteSpace($opponentHrefId)) {
        '<div class="week-tools"><strong>Opponent</strong><a class="week-tool" href="/franchise?id=' + (ConvertTo-HtmlText $opponentHrefId) + '">Scout</a><a class="week-tool" href="/trade?opponent=' + (ConvertTo-HtmlText $opponentHrefId) + '">Trade</a></div>'
    }
    else {
        ''
    }
'@.TrimEnd()

$toolsNew = @'
    $opponentTools = if (-not [string]::IsNullOrWhiteSpace($opponentHrefId)) {
        '<div class="week-tools"><strong>Matchup tools</strong><a class="week-tool" href="/matchup">Matchup</a><a class="week-tool" href="/franchise?id=' + (ConvertTo-HtmlText $opponentHrefId) + '">Scout</a><a class="week-tool" href="/trade?opponent=' + (ConvertTo-HtmlText $opponentHrefId) + '">Trade</a></div>'
    }
    else {
        '<div class="week-tools"><strong>Matchup tools</strong><a class="week-tool" href="/matchup">Matchup</a></div>'
    }
'@.TrimEnd()

$function = Replace-ExactlyOnce -Text $function -Old $toolsOld -New $toolsNew -Contract 'Dashboard matchup secondary tools'

$cardOld = @'
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="/matchup">Open Weekly Matchup &rarr;</a></div>' + $opponentTools + '</article>'
'@.TrimEnd()

$cardNew = @'
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="/team/autofill">Review Lineup &rarr;</a></div>' + $opponentTools + '</article>'
'@.TrimEnd()

$function = Replace-ExactlyOnce -Text $function -Old $cardOld -New $cardNew -Contract 'Dashboard matchup primary lineup action'

$core = $core.Substring(0, $fn.Extent.StartOffset) + $function + $core.Substring($fn.Extent.EndOffset)

$finalAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$installed = Get-OneFunction -Ast $finalAst -Name 'Add-DashboardMatchupSummary' -Contract 'decision-first Dashboard matchup summary'
$installedText = $installed.Extent.Text

foreach ($required in @(
    'href="/team/autofill">Review Lineup &rarr;</a>',
    '<strong>Matchup tools</strong>',
    'href="/matchup">Matchup</a>',
    'href="/franchise?id=',
    'href="/trade?opponent='
)) {
    if ($installedText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-989 BLOCKED: Dashboard matchup decision marker is missing: $required"
    }
}

$readCount = [regex]::Matches($installedText, [regex]::Escape('Invoke-ButlerReadOnlyTask')).Count
if ($readCount -ne 1) {
    throw "BF-989 BLOCKED: Dashboard matchup provider-read contract changed; expected one governed read, found $readCount."
}

if ($installedText -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-989 BLOCKED: Dashboard matchup decision actions introduced optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-989 Dashboard matchup decision actions applied.'
