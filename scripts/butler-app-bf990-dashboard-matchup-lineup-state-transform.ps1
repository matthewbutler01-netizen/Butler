param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-990 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-990 BLOCKED: $Contract failed PowerShell parse: $summary"
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
        throw "BF-990 BLOCKED: $Contract expected one $Name function, found $($matches.Count)."
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
        throw "BF-990 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$core = [IO.File]::ReadAllText($CorePath)
$ast = Get-ParsedAst -Text $core -Contract 'pre-transform staged core'
$fn = Get-OneFunction -Ast $ast -Name 'Add-DashboardMatchupSummary' -Contract 'Dashboard matchup summary'
$function = $fn.Extent.Text

foreach ($required in @(
    'href="/team/autofill">Review Lineup &rarr;</a>',
    '<strong>Matchup tools</strong>',
    'href="/matchup">Matchup</a>',
    '$opponentTools'
)) {
    if ($function.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-990 BLOCKED: BF-989 Dashboard matchup capability is missing: $required"
    }
}

$stateAnchor = @'
    $title = 'Opponent unavailable'
    $status = 'MATCHUP DATA NEEDED'
    $statusClass = 'warn'
    $opponentHrefId = ''
'@.TrimEnd()

$stateNew = @'
    $title = 'Opponent unavailable'
    $status = 'MATCHUP DATA NEEDED'
    $statusClass = 'warn'
    $opponentHrefId = ''

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
'@.TrimEnd()

$function = Replace-ExactlyOnce -Text $function -Old $stateAnchor -New $stateNew -Contract 'lineup-state matchup action derivation'

$toolsOld = @'
    $opponentTools = if (-not [string]::IsNullOrWhiteSpace($opponentHrefId)) {
        '<div class="week-tools"><strong>Matchup tools</strong><a class="week-tool" href="/matchup">Matchup</a><a class="week-tool" href="/franchise?id=' + (ConvertTo-HtmlText $opponentHrefId) + '">Scout</a><a class="week-tool" href="/trade?opponent=' + (ConvertTo-HtmlText $opponentHrefId) + '">Trade</a></div>'
    }
    else {
        '<div class="week-tools"><strong>Matchup tools</strong><a class="week-tool" href="/matchup">Matchup</a></div>'
    }
'@.TrimEnd()

$toolsNew = @'
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

$function = Replace-ExactlyOnce -Text $function -Old $toolsOld -New $toolsNew -Contract 'duplicate-free matchup secondary tools'

$cardOld = @'
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="/team/autofill">Review Lineup &rarr;</a></div>' + $opponentTools + '</article>'
'@.TrimEnd()

$cardNew = @'
    $card = '<article class="week-glance-card"><div class="week-kind">Matchup</div><div class="week-title">' + (ConvertTo-HtmlText $title) + '</div><div class="status ' + $statusClass + '">' + $status + '</div><div class="week-action"><a href="' + (ConvertTo-HtmlText $matchupPrimaryHref) + '">' + (ConvertTo-HtmlText $matchupPrimaryLabel) + ' &rarr;</a></div>' + $opponentTools + '</article>'
'@.TrimEnd()

$function = Replace-ExactlyOnce -Text $function -Old $cardOld -New $cardNew -Contract 'state-aware matchup primary action'

$core = $core.Substring(0, $fn.Extent.StartOffset) + $function + $core.Substring($fn.Extent.EndOffset)

$finalAst = Get-ParsedAst -Text $core -Contract 'generated staged core'
$installed = Get-OneFunction -Ast $finalAst -Name 'Add-DashboardMatchupSummary' -Contract 'state-aware Dashboard matchup summary'
$installedText = $installed.Extent.Text

foreach ($required in @(
    '$matchupPrimaryHref = ''/team/autofill''',
    '$matchupPrimaryLabel = ''Review Lineup''',
    '$matchupPrimaryHref = ''/matchup''',
    '$matchupPrimaryLabel = ''View Matchup''',
    '''Refresh Lineup''',
    '$matchupTool = if ($matchupPrimaryHref -ceq ''/matchup'')',
    '(ConvertTo-HtmlText $matchupPrimaryHref)',
    '(ConvertTo-HtmlText $matchupPrimaryLabel)'
)) {
    if ($installedText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-990 BLOCKED: state-aware matchup marker is missing: $required"
    }
}

$readCount = [regex]::Matches($installedText, [regex]::Escape('Invoke-ButlerReadOnlyTask')).Count
if ($readCount -ne 1) {
    throw "BF-990 BLOCKED: Dashboard matchup provider-read contract changed; expected one governed read, found $readCount."
}

if ($installedText -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
    throw 'BF-990 BLOCKED: state-aware matchup action introduced optimizer, write, or open-redirect behavior.'
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
Write-Host 'BF-990 Dashboard matchup lineup-state action applied.'
