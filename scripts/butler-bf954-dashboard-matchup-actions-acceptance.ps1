param([string]$HtmlOutputPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf954-dashboard-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $core = [IO.File]::ReadAllText($corePath)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        throw 'BF-954 BLOCKED: staged core has parse errors.'
    }

    $function = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Add-DashboardMatchupSummary'
    }, $true)
    if ($null -eq $function) {
        throw 'BF-954 BLOCKED: staged Dashboard matchup summary helper is missing.'
    }

    $text = $function.Extent.Text
    foreach ($required in @(
        '$opponentHrefId = [System.Uri]::EscapeDataString([string]$matchup.OpponentTeamId)',
        'href="/franchise?id=',
        '>Scout</a>',
        'href="/trade?opponent=',
        '>Trade</a>',
        'Open Weekly Matchup &rarr;',
        '$opponentTools'
    )) {
        if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-954 BLOCKED: staged Dashboard matchup action marker is missing: $required"
        }
    }

    if ($text -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-954 BLOCKED: Dashboard matchup actions introduced write or optimizer behavior.'
    }

    # Execute the final staged renderer with deterministic bundle boundaries.
    # No provider is contacted. The source note must describe only a validated
    # frame and must not convert missing metadata into a freshness claim.
    . ([scriptblock]::Create($text))
    $LeagueId = 'fixture-league'
    $script:bundleReads = 0
    function Invoke-ButlerReadOnlyTask { $script:bundleReads++; return 'fixture' }
    function Get-TeamEvidenceBundleSection { return 'fixture' }
    function ConvertTo-MatchupRosterContextView { return [pscustomobject]@{ProviderLeg='6';ButlerTeamId='team';Season=2026} }
    function ConvertTo-WeeklyMatchupView { return $script:fixtureMatchup }
    function ConvertTo-HtmlText { param($Value) return [Net.WebUtility]::HtmlEncode([string]$Value) }
    $frame = @{LeagueId=$LeagueId;UserTeamId='team';Season=2026;Week=6;UserTeamName='Us';OpponentTeamName='Them';OpponentTeamId='opponent';Source='<script>source & value</script>';AsOf='2026-10-17T06:00:00Z'}
    $script:fixtureMatchup = [pscustomobject]$frame
    $inputCard = '<article class="week-glance-card"><div class="week-kind">Matchup</div>old</article>'
    $rendered = Add-DashboardMatchupSummary -Html $inputCard
    $fixtureCards = @('<h2>Validated frame with escaped source</h2>' + $rendered)
    if ($script:bundleReads -ne 1 -or $rendered.Contains('<script>') -or
        -not $rendered.Contains('&lt;script&gt;source &amp; value&lt;/script&gt;') -or
        -not $rendered.Contains('as-of: 2026-10-17T06:00:00Z') -or
        -not $rendered.Contains('not a live score or player availability check')) {
        throw 'BF-954 BLOCKED: source note escaped incorrectly or changed the read boundary.'
    }
    $frame.Remove('Source'); $frame.Remove('AsOf')
    $script:fixtureMatchup = [pscustomobject]$frame
    $rendered = Add-DashboardMatchupSummary -Html $inputCard
    $fixtureCards += '<h2>Validated frame, metadata unknown</h2>' + $rendered
    if (-not $rendered.Contains('source: unknown; as-of: unknown')) { throw 'Missing source metadata was not explicit.' }
    $frame.Week = 5
    $script:fixtureMatchup = [pscustomobject]$frame
    $rendered = Add-DashboardMatchupSummary -Html $inputCard
    $fixtureCards += '<h2>Wrong-week frame withheld</h2>' + $rendered
    if (-not $rendered.Contains('Matchup source and recorded time are not verified.') -or
        $rendered.Contains('Recorded matchup source:') -or $rendered.Contains('OPPONENT CONFIRMED')) {
        throw 'BF-954 BLOCKED: mismatched matchup frame leaked verified source context.'
    }
    if (-not [string]::IsNullOrWhiteSpace($HtmlOutputPath)) {
        $fixtureHtml = '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Matchup source fixture</title><style>body{font:16px system-ui;margin:24px;max-width:900px;background:#101414;color:#edf3ef}article{padding:18px;border:1px solid #668575;border-radius:12px;overflow-wrap:anywhere}a{color:#b5e6c9;margin-right:12px}.week-title{font-size:22px}.matchup-source-note{line-height:1.5}</style></head><body><h1>Generated source disclosure fixture</h1><p>Synthetic data - not live browser acceptance or player advice.</p>' + ($fixtureCards -join '') + '</body></html>'
        [IO.File]::WriteAllText($HtmlOutputPath, $fixtureHtml, [Text.UTF8Encoding]::new($false))
    }

    Write-Host 'BF-954 DASHBOARD MATCHUP ACTIONS ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
