Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf965-player-search-position-waivers-transform.ps1'
$transformTokens = $null
$transformErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$transformTokens, [ref]$transformErrors)
if (@($transformErrors).Count -ne 0) {
    $summary = (@($transformErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-965 BLOCKED: transform script failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf965-player-search-waivers-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $coreTokens = $null
    $coreErrors = $null
    $coreAst = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$coreTokens, [ref]$coreErrors)
    if (@($coreErrors).Count -ne 0) {
        throw 'BF-965 BLOCKED: staged core has parse errors.'
    }

    $functions = @($coreAst.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-PlayerSearchHtml'
    }, $true))
    if ($functions.Count -ne 1) {
        throw "BF-965 BLOCKED: expected one staged Player Search renderer, found $($functions.Count)."
    }
    $search = $functions[0].Extent.Text

    foreach ($required in @(
        '$searchWaiverPosition = ([string]$Query).Trim().ToUpperInvariant()',
        '@("QB", "RB", "WR", "TE") -ccontains $searchWaiverPosition',
        '$searchWaiverHref = [System.Uri]::EscapeDataString($searchWaiverPosition)',
        'href="/waivers?position=',
        'Check '' + (ConvertTo-HtmlText $searchWaiverPosition) + '' waivers',
        'href="/waivers">Check Waiver Board</a>',
        '$searchWaiverAction</div>',
        'href="/players?q=QB">QB</a>',
        'href="/players?q=RB">RB</a>',
        'href="/players?q=WR">WR</a>',
        'href="/players?q=TE">TE</a>'
    )) {
        if ($search.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-965 BLOCKED: staged Player Search position-waiver marker is missing: $required"
        }
    }

    if ($search -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-965 BLOCKED: staged Player Search position-waiver surface introduced provider, backend-read, optimizer, FAAB, or write behavior.'
    }

    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'
    $dashboard = [IO.File]::ReadAllText($dashboardPath)
    foreach ($required in @(
        'function Get-WaiverPositionFocusFromRequestTarget',
        '@("QB", "RB", "WR", "TE") -ccontains $value',
        '-PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])',
        'href="/waivers?position=QB">QB</a>',
        'href="/waivers?position=RB">RB</a>',
        'href="/waivers?position=WR">WR</a>',
        'href="/waivers?position=TE">TE</a>'
    )) {
        if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-965 BLOCKED: staged focused-Waiver contract is missing: $required"
        }
    }

    Write-Host 'BF-965 PLAYER SEARCH POSITION-AWARE WAIVERS ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
