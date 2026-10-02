Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformScripts = @(
    'butler-app-bf975-player-search-return-context-transform.ps1',
    'butler-app-bf976-player-workflow-safe-stop-transform.ps1',
    'butler-app-bf977-player-compare-return-context-transform.ps1'
)

foreach ($name in $transformScripts) {
    $path = Join-Path $PSScriptRoot $name
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "PLAYER WORKFLOW BATCH BLOCKED: transform failed PowerShell parse: $name :: $summary"
    }
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-player-workflow-batch-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "PLAYER WORKFLOW BATCH BLOCKED: staged core has parse errors: $summary"
    }

    function Get-OneFunction {
        param([Parameter(Mandatory = $true)]$Ast, [Parameter(Mandatory = $true)][string]$Name)

        $matches = @($Ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $Name
        }, $true))
        if ($matches.Count -ne 1) {
            throw "PLAYER WORKFLOW BATCH BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0]
    }

    $search = (Get-OneFunction -Ast $ast -Name 'ConvertTo-PlayerSearchHtml').Extent.Text
    foreach ($required in @(
        '$searchReturnHref = [System.Uri]::EscapeDataString([string]$Query)',
        'href=`"/player?id=$hrefId&from=players&q=$searchReturnHref`"',
        'Check Waiver Board',
        'Compare this player',
        'Scout franchise'
    )) {
        if ($search.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "PLAYER WORKFLOW BATCH BLOCKED: Player Search context marker is missing: $required"
        }
    }

    $compare = (Get-OneFunction -Ast $ast -Name 'ConvertTo-PlayerCompareHtml').Extent.Text
    foreach ($required in @(
        '$compareDetailSuffix = "&from=compare&left=$leftHref&right=$rightHref"',
        '$leftHtml = $leftHtml.Replace($leftDetailOld, $leftDetailNew)',
        '$rightHtml = $rightHtml.Replace($rightDetailOld, $rightDetailNew)',
        'Butler does not choose a winner'
    )) {
        if ($compare.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "PLAYER WORKFLOW BATCH BLOCKED: Player Compare context marker is missing: $required"
        }
    }

    $core = [IO.File]::ReadAllText($corePath)
    foreach ($required in @(
        'Get-PlayerDetailSearchQueryContext -RequestTarget $parts[1]',
        'Add-PlayerDetailSearchReturnQuery -Html $html -FromPlayers $fromPlayers -SearchQuery $searchReturnQuery',
        'Get-PlayerDetailCompareContext -RequestTarget $parts[1]',
        'Add-PlayerDetailCompareReturn -Html $html -CompareContext $compareReturnContext',
        "Get-PlayerWorkflowBlockedHtml -Title 'Player Detail blocked'",
        "Get-PlayerWorkflowBlockedHtml -Title 'Player Search blocked'",
        "Get-PlayerWorkflowBlockedHtml -Title 'Player Compare blocked'"
    )) {
        if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "PLAYER WORKFLOW BATCH BLOCKED: staged route marker is missing: $required"
        }
    }

    foreach ($legacy in @(
        '<!doctype html><html><body><h1>Butler Player Detail blocked</h1>',
        '<!doctype html><html><body><h1>Butler Player Search blocked</h1>',
        '<!doctype html><html><body><h1>Butler Player Compare blocked</h1>'
    )) {
        if ($core.IndexOf($legacy, [System.StringComparison]::Ordinal) -ge 0) {
            throw "PLAYER WORKFLOW BATCH BLOCKED: legacy player error page remains: $legacy"
        }
    }

    foreach ($name in @(
        'Get-PlayerDetailSearchQueryContext',
        'Add-PlayerDetailSearchReturnQuery',
        'Get-PlayerDetailCompareContext',
        'Add-PlayerDetailCompareReturn',
        'Get-PlayerWorkflowBlockedHtml'
    )) {
        $function = Get-OneFunction -Ast $ast -Name $name
        Invoke-Expression $function.Extent.Text
    }

    $searchQuery = Get-PlayerDetailSearchQueryContext -RequestTarget '/player?id=p1&from=players&q=WR'
    if ($searchQuery -cne 'WR') {
        throw "PLAYER WORKFLOW BATCH BLOCKED: exact Player Search return query was not preserved; got '$searchQuery'."
    }

    $searchHtml = '<div class="button-row"><a class="btn btn-secondary" href="/players">Back to Player Search</a><a class="btn btn-secondary" href="/team">My Team</a><a class="btn btn-secondary" href="/compare?left=p1">Compare this player</a></div>'
    $searchReturn = Add-PlayerDetailSearchReturnQuery -Html $searchHtml -FromPlayers $true -SearchQuery 'WR'
    if ($searchReturn.IndexOf('href="/players?q=WR">Back to Player Search</a>', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'PLAYER WORKFLOW BATCH BLOCKED: Player Search exact return link was not reconstructed.'
    }

    $compareContext = Get-PlayerDetailCompareContext -RequestTarget '/player?id=p1&from=compare&left=p1&right=p2'
    if (-not [bool]$compareContext.FromCompare -or
        [string]$compareContext.Left -cne 'p1' -or
        [string]$compareContext.Right -cne 'p2') {
        throw 'PLAYER WORKFLOW BATCH BLOCKED: exact Player Compare return pair was not preserved.'
    }

    $compareHtml = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/players">Player Search</a><a class="btn btn-secondary" href="/compare?left=p1">Compare this player</a></div>'
    $compareReturn = Add-PlayerDetailCompareReturn -Html $compareHtml -CompareContext $compareContext
    if ($compareReturn.IndexOf('href="/compare?left=p1&right=p2">Back to Player Compare</a>', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'PLAYER WORKFLOW BATCH BLOCKED: exact Player Compare return link was not reconstructed.'
    }

    function Get-AppCss { return ':root{--line:#ccc}.panel{padding:12px}' }
    function Get-AppNav {
        param([Parameter(Mandatory = $true)][string]$Active)
        $class = if ($Active -ceq 'players') { ' class="active"' } else { '' }
        return '<nav class="nav" aria-label="Butler sections"><a' + $class + ' href="/players">Player Search</a><a href="/compare">Player Compare</a></nav>'
    }
    function ConvertTo-HtmlText {
        param([AllowNull()]$Value)
        return [System.Net.WebUtility]::HtmlEncode([string]$Value)
    }

    $blocked = Get-PlayerWorkflowBlockedHtml -Title 'Player Search blocked' -Message '<unsafe>' -Active 'players' -PrimaryHref '/players' -PrimaryLabel 'Back to Player Search'
    foreach ($required in @(
        '<h1>BUTLER</h1>',
        'STOPPED SAFELY',
        '&lt;unsafe&gt;',
        'href="/players">Back to Player Search</a>',
        'No provider refresh, Butler write, or Sleeper write was executed.'
    )) {
        if ($blocked.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "PLAYER WORKFLOW BATCH BLOCKED: safe-stop renderer marker is missing: $required"
        }
    }

    $batchSurface = (
        (Get-OneFunction -Ast $ast -Name 'Get-PlayerDetailSearchQueryContext').Extent.Text +
        (Get-OneFunction -Ast $ast -Name 'Add-PlayerDetailSearchReturnQuery').Extent.Text +
        (Get-OneFunction -Ast $ast -Name 'Get-PlayerDetailCompareContext').Extent.Text +
        (Get-OneFunction -Ast $ast -Name 'Add-PlayerDetailCompareReturn').Extent.Text +
        (Get-OneFunction -Ast $ast -Name 'Get-PlayerWorkflowBlockedHtml').Extent.Text
    )
    if ($batchSurface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|Method = "POST"|submitTransaction|setFaab|returnUrl|redirectUrl|javascript:') {
        throw 'PLAYER WORKFLOW BATCH BLOCKED: context/safe-stop helpers introduced provider, backend-read, write, or open-redirect behavior.'
    }

    Write-Host 'BUTLER PLAYER WORKFLOW CONTEXT BATCH ACCEPTANCE: PASS'
    Write-Host 'Checks passed: BF-975 search return, BF-976 safe-stop pages, BF-977 compare return'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
