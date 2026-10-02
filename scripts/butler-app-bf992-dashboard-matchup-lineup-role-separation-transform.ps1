param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-992 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-992 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-992 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-992 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [IO.File]::ReadAllText($CorePath)
$ast = Get-ParsedAst -Text $core -Contract 'pre-transform staged core'
$fn = Get-OneFunction -Ast $ast -Name 'Add-DashboardMatchupSummary' -Contract 'Dashboard matchup summary'
$function = $fn.Extent.Text

foreach ($required in @(
    '$matchupPrimaryHref = ''/team/autofill''',
    '$matchupPrimaryLabel = ''Review Lineup''',
    '$lineupCardMatch = [regex]::Match(',
    '$matchupTool = if ($matchupPrimaryHref -ceq ''/matchup'')',
    '(ConvertTo-HtmlText $matchupPrimaryHref)',
    '(ConvertTo-HtmlText $matchupPrimaryLabel)',
    'href="/franchise?id=',
    'href="/trade?opponent='
)) {
    if ($function.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-992 BLOCKED: BF-990 state-aware matchup capability is missing: $required"
    }
}

$stateAwareBlock = @'
    # Reuse the already-rendered Dashboard Lineup card. This is presentation-only:
    # no extra Butler/provider task is invoked to choose the matchup card action.
    $matchupPrimaryHref = '/team/autofill'
    $matchupPrimaryLabel = 'Review Lineup'
    $lineupCardMatch = [regex]::Match(
        $Html,
        '(?is)<article class="week-glance-card"><div class="week-kind">Lineup</div>.*?</article>'
    )
    if ($lineupCardMatch.Success) {
        $lineupCardHtml = [string]$lineupCardMatch.Value
        if ($lineupCardHtml -match 'href="/matchup"') {
            $matchupPrimaryHref = '/matchup'
            $matchupPrimaryLabel = 'View Matchup'
        }
        elseif ($lineupCardHtml -match 'href="/team/autofill"') {
            $matchupPrimaryHref = '/team/autofill'
            $matchupPrimaryLabel = if ($lineupCardHtml -match '>Refresh Lineup\s*&rarr;</a>') { 'Refresh Lineup' } else { 'Review Lineup' }
        }
    }

'@

$function = Replace-ExactlyOnce -Text $function -Old $stateAwareBlock -New '' -Contract 'remove duplicated lineup-state derivation'

$toolsOld = @'
    $matchupTool = if ($matchupPrimaryHref -ceq '/matchup') {
        ''
    }
    else {
        '<a class="week-tool" href="/matchup">Matchup</a>'
    }

    $opponentTools = if (-not [string]::IsNullOrWhiteSpace($opponentHrefId)) {
        '<div class="week-tools"><strong>Matchup tools</strong>' + $matchupTool + '<a class="week-tool" href="/franchise?id=' + (ConvertTo-HtmlText $opponentHrefId) + '">Scout</a><a class="week-tool" href="/trade?opponent=' + (ConvertTo-HtmlText $opponentHrefId) + '">Trade</a></div>'
    }
    elseif (-not [string]::IsNullOrWhiteSpace($matchupTool)) {
        '<div class="week-tools"><strong>Matchup tools</strong>' + $matchupTool + '</div>'
    }
    else {
        ''
    }
'@.TrimEnd()

$toolsNew = @'
    $opponentTools = if (-not [string]::IsNullOrWhiteSpace($opponentHrefId)) {
        '<div class="week-tools"><strong>Opponent tools</strong><a class="week-tool" href="/franchise?id=' + (ConvertTo-HtmlText $opponentHrefId) + '">Scout</a><a class="week-tool" href="/trade?opponent=' + (ConvertTo-HtmlText $opponentHrefId) + '">Trade</a></div>'
    }
    else {
        ''
    }
'@.TrimEnd()

$function = Replace-ExactlyOnce -Text $function -Old $toolsOld -New $toolsNew -Contract 'matchup card role separation tools'

$cardOld = @'
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="' + (ConvertTo-HtmlText $matchupPrimaryHref) + '">' + (ConvertTo-HtmlText $matchupPrimaryLabel) + ' &rarr;</a></div>' + $opponentTools + '</article>'
'@.TrimEnd()

$cardNew = @'
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="/matchup">Open Weekly Matchup &rarr;</a></div>' + $opponentTools + '</article>'
'@.TrimEnd()

$function = Replace-ExactlyOnce -Text $function -Old $cardOld -New $cardNew -Contract 'matchup card primary ownership'

$core = $core.Substring(0, $fn.Extent.StartOffset) + $function + $core.Substring($fn.Extent.EndOffset)

$finalAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$installed = Get-OneFunction -Ast $finalAst -Name 'Add-DashboardMatchupSummary' -Contract 'role-separated Dashboard matchup summary'
$installedText = $installed.Extent.Text

foreach ($required in @(
    'href="/matchup">Open Weekly Matchup &rarr;</a>',
    '<strong>Opponent tools</strong>',
    'href="/franchise?id=',
    'href="/trade?opponent='
)) {
    if ($installedText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-992 BLOCKED: Dashboard matchup role marker is missing: $required"
    }
}

foreach ($forbidden in @(
    '$matchupPrimaryHref',
    '$matchupPrimaryLabel',
    '$lineupCardMatch',
    '$lineupCardHtml',
    '$matchupTool',
    'href="/team/autofill">Review Lineup &rarr;</a>',
    '<strong>Matchup tools</strong>'
)) {
    if ($installedText.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
        throw "BF-992 BLOCKED: duplicated lineup/matchup behavior remains: $forbidden"
    }
}

$readCount = [regex]::Matches($installedText, [regex]::Escape('Invoke-ButlerReadOnlyTask')).Count
if ($readCount -ne 1) {
    throw "BF-992 BLOCKED: Dashboard matchup provider-read contract changed; expected one governed read, found $readCount."
}

if ($installedText -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-992 BLOCKED: matchup/lineup role separation introduced optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-992 Dashboard matchup/lineup role separation applied.'
