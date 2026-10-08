Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1021-start-sit-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-1021 BLOCKED: staged core has parse errors: $summary"
    }

    $functions = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-AutoFillHtml'
    }, $true))
    if ($functions.Count -ne 1) {
        throw "BF-1021 BLOCKED: expected one staged Start/Sit renderer, found $($functions.Count)."
    }

    $surface = $functions[0].Extent.Text
    foreach ($required in @(
        'Start/Sit Assistant',
        'What should I change?',
        '<h3>START</h3>',
        '<h3>SIT</h3>',
        'Current starter',
        'Recommended starter',
        'Projected difference',
        'Compare this swap',
        'What needs your decision',
        'Projection hold'
    )) {
        if ($surface.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1021 BLOCKED: staged Start/Sit marker is missing: $required"
        }
    }

    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-1021 BLOCKED: Start/Sit Assistant introduced provider, optimizer, or write behavior.'
    }

    $all = [IO.File]::ReadAllText($corePath)
    foreach ($required in @(
        'BF-1021 v0.4 Start/Sit Assistant',
        '.start-sit-assistant{padding:22px}',
        '.lineup-row.changed{border-color:',
        '.grid.four>.summary-card:nth-child(3)',
        '.grid.four>.summary-card:nth-child(4)'
    )) {
        if ($all.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1021 BLOCKED: staged Start/Sit visual marker is missing: $required"
        }
    }

    $workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
    $workerTokens = $null
    $workerErrors = $null
    $workerAst = [System.Management.Automation.Language.Parser]::ParseFile($workerPath, [ref]$workerTokens, [ref]$workerErrors)
    if (@($workerErrors).Count -ne 0) {
        throw 'BF-1021 BLOCKED: request worker has parse errors.'
    }

    foreach ($functionName in @('ConvertTo-V04StartSitRouteHtml', 'Add-ButlerAccessibility')) {
        $workerFunction = @($workerAst.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $functionName
        }, $true))
        if ($workerFunction.Count -ne 1) {
            throw "BF-1021 BLOCKED: expected one $functionName worker function, found $($workerFunction.Count)."
        }
        . ([scriptblock]::Create($workerFunction[0].Extent.Text))
    }

    $routeFixture = '<!doctype html><html><head><title>Butler - Weekly Matchup</title></head><body><main class="shell"><header class="top"><div class="target">Hard(CORE)-Dynasty &middot; Week 4</div></header><nav class="nav" aria-label="Butler sections"><a href="/">Dashboard</a><a class="active" href="/matchup">Matchup</a></nav><section class="panel hero-panel"><h1>Duplicate matchup decision</h1></section><section class="panel recommendation-panel start-sit-assistant"><div class="eyebrow">Start/Sit Assistant</div><h2>Lineup decision</h2></section><details class="manager-disclosure matchup-context-details"><summary>View opponent context</summary></details><section class="panel boundary">Weekly Matchup leads with the existing Start/Sit Assistant decision, then shows confirmed-opponent context.</section></main></body></html>'
    $routeHtml = ConvertTo-V04StartSitRouteHtml -Html $routeFixture -RequestTarget '/matchup/autofill'
    if ($routeHtml.IndexOf('Duplicate matchup decision', [System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-1021 BLOCKED: explicit Start/Sit route still renders the duplicate Matchup hero.'
    }
    if ($routeHtml.IndexOf('<title>Butler - Start/Sit Assistant</title>', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-1021 BLOCKED: explicit Start/Sit route title is not dedicated.'
    }
    if ($routeHtml.IndexOf('View opponent context', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-1021 BLOCKED: secondary opponent context disappeared from Start/Sit.'
    }

    $script:ButlerPublicRequestTarget = '/matchup/autofill'
    try {
        $publicHtml = Add-ButlerAccessibility -Html $routeHtml
    }
    finally {
        $script:ButlerPublicRequestTarget = ''
    }

    if ($publicHtml.IndexOf('class="playbook-start-sit active" aria-current="page" href="/matchup/autofill">Start/Sit Assistant</a>', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-1021 BLOCKED: Start/Sit Assistant is not the exact current Playbook destination.'
    }
    $currentLinks = [regex]::Matches($publicHtml, '<a\b[^>]*aria-current="page"[^>]*>([^<]+)</a>')
    if ($currentLinks.Count -ne 1 -or $currentLinks[0].Groups[1].Value -cne 'Start/Sit Assistant') {
        throw 'BF-1021 BLOCKED: Start/Sit route must announce exactly one current page.'
    }

    Write-Host 'BF-1021 V0.4 START/SIT ASSISTANT ACCEPTANCE: PASS'
    Write-Host 'Coverage: dedicated Start/Sit route and current-page state, no duplicate Matchup hero, What-should-I-change summary, START/SIT cards, priority decision queue, current/recommended starter labels, projection difference, compare actions, holds, and read-only safety.'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
