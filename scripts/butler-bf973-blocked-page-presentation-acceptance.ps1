Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'

$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-973 BLOCKED: request worker failed PowerShell parse: $summary"
}

$helperFunctions = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Get-ButlerBlockedPageHtml'
}, $true))
if ($helperFunctions.Count -ne 1) {
    throw "BF-973 BLOCKED: expected exactly one blocked-page renderer, found $($helperFunctions.Count)."
}
$helperText = $helperFunctions[0].Extent.Text

function Get-AppCss {
    return ':root{--line:#ccc;--surface-2:#f5f5f5;--ink:#111}.nav{display:flex}.panel{padding:12px}.warn{color:#a60}'
}
function Get-AppNav {
    param([Parameter(Mandatory = $true)][string]$Active)
    $historyClass = if ($Active -ceq 'history') { ' class="active"' } else { '' }
    $tradeClass = if ($Active -ceq 'trade') { ' class="active"' } else { '' }
    $dashboardClass = if ($Active -ceq 'dashboard') { ' class="active"' } else { '' }
    return '<nav class="nav" aria-label="Butler sections"><a' + $dashboardClass + ' href="/">Dashboard</a><a href="/team">My Team</a><a href="/matchup">Matchup</a><a href="/waivers">Waiver Board</a><a href="/players">Player Search</a><a href="/compare">Player Compare</a><a href="/league">League</a><a' + $tradeClass + ' href="/trade">Trade Analyzer</a><a' + $historyClass + ' href="/history?load=1">History</a></nav>'
}
function ConvertTo-HtmlText {
    param([AllowNull()]$Value)
    return [System.Net.WebUtility]::HtmlEncode([string]$Value)
}

Invoke-Expression $helperText

$historyHtml = Get-ButlerBlockedPageHtml -Title 'Decision History blocked' -Message '<history unsafe>' -Active 'history' -PrimaryHref '/history?load=1' -PrimaryLabel 'Return to Decision History'
$tradeHtml = Get-ButlerBlockedPageHtml -Title 'Trade Analyzer blocked' -Message '<trade unsafe>' -Active 'trade' -PrimaryHref '/trade' -PrimaryLabel 'Return to Trade Analyzer'
$appHtml = Get-ButlerBlockedPageHtml -Title 'Butler app blocked' -Message '<app unsafe>' -Active 'dashboard' -PrimaryHref '/' -PrimaryLabel 'Return to Dashboard' -SecondaryHref '/team' -SecondaryLabel 'Review My Team'

foreach ($rendered in @($historyHtml, $tradeHtml, $appHtml)) {
    foreach ($required in @(
        '<div class="brand"><h1>BUTLER</h1>',
        '<div class="target">Request stopped safely</div>',
        '<span class="status warn">STOPPED SAFELY</span>',
        'No Butler or Sleeper write was executed.',
        'href="/players">Player Search</a>',
        'href="/compare">Player Compare</a>',
        'href="/history?load=1">History</a>'
    )) {
        if ($rendered.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-973 BLOCKED: rendered blocked-page marker is missing: $required"
        }
    }
}

if ($historyHtml.IndexOf('&lt;history unsafe&gt;', [System.StringComparison]::Ordinal) -lt 0 -or
    $historyHtml.IndexOf('href="/history?load=1">Return to Decision History</a>', [System.StringComparison]::Ordinal) -lt 0 -or
    $historyHtml.IndexOf('<a class="active" href="/history?load=1">History</a>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-973 BLOCKED: Decision History blocked-page return/escaping contract is incorrect.'
}

if ($tradeHtml.IndexOf('&lt;trade unsafe&gt;', [System.StringComparison]::Ordinal) -lt 0 -or
    $tradeHtml.IndexOf('href="/trade">Return to Trade Analyzer</a>', [System.StringComparison]::Ordinal) -lt 0 -or
    $tradeHtml.IndexOf('<a class="active" href="/trade">Trade Analyzer</a>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-973 BLOCKED: Trade Analyzer blocked-page return/escaping contract is incorrect.'
}

if ($appHtml.IndexOf('&lt;app unsafe&gt;', [System.StringComparison]::Ordinal) -lt 0 -or
    $appHtml.IndexOf('href="/">Return to Dashboard</a>', [System.StringComparison]::Ordinal) -lt 0 -or
    $appHtml.IndexOf('href="/team">Review My Team</a>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-973 BLOCKED: generic app blocked-page return/escaping contract is incorrect.'
}

$source = [IO.File]::ReadAllText($workerPath)
foreach ($required in @(
    '$errorHtml = Get-ButlerBlockedPageHtml -Title ''Decision History blocked'' -Message $_.Exception.Message -Active ''history'' -PrimaryHref ''/history?load=1'' -PrimaryLabel ''Return to Decision History''',
    '$errorHtml = Get-ButlerBlockedPageHtml -Title ''Trade Analyzer blocked'' -Message $_.Exception.Message -Active ''trade'' -PrimaryHref ''/trade'' -PrimaryLabel ''Return to Trade Analyzer''',
    '$errorHtml = Get-ButlerBlockedPageHtml -Title ''Butler app blocked'' -Message $_.Exception.Message -Active ''dashboard'' -PrimaryHref ''/'' -PrimaryLabel ''Return to Dashboard'' -SecondaryHref ''/team'' -SecondaryLabel ''Review My Team'''
)) {
    if ($source.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-973 BLOCKED: worker blocked-page call marker is missing: $required"
    }
}

if ($source.IndexOf('<!doctype html><html><body><h1>Butler Decision History blocked</h1>', [System.StringComparison]::Ordinal) -ge 0 -or
    $source.IndexOf('<!doctype html><html><body><h1>Butler Trade Analyzer blocked</h1>', [System.StringComparison]::Ordinal) -ge 0 -or
    $source.IndexOf('<!doctype html><html><body><h1>Butler app blocked</h1>', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'BF-973 BLOCKED: legacy bare blocked-page HTML remains in the request worker.'
}

$historyStatus = [regex]::Matches($source, "Send-HttpResponse -Stream \$stream -StatusCode 400 -StatusText 'Bad Request' -ContentType 'text/html; charset=utf-8' -Body \$errorHtml").Count
if ($historyStatus -lt 3) {
    throw "BF-973 BLOCKED: expected existing HTML Bad Request semantics to remain; found only $historyStatus matching 400 sends."
}
$genericStatus = [regex]::Matches($source, "Send-HttpResponse -Stream \$stream -StatusCode 500 -StatusText 'Internal Server Error' -ContentType 'text/html; charset=utf-8' -Body \$errorHtml").Count
if ($genericStatus -ne 1) {
    throw "BF-973 BLOCKED: generic app blocked page must remain HTTP 500; found $genericStatus matching sends."
}

if ($helperText -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-973 BLOCKED: blocked-page renderer introduced provider, backend-read, optimizer, FAAB, or write behavior.'
}

Write-Host 'BF-973 BLOCKED-PAGE PRESENTATION PARITY ACCEPTANCE: PASS'
