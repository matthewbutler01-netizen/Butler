Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf966-player-search-result-waivers-transform.ps1'
$transformTokens = $null
$transformErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$transformTokens, [ref]$transformErrors)
if (@($transformErrors).Count -ne 0) {
    $summary = (@($transformErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-966 BLOCKED: transform script failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf966-player-search-result-waivers-' + [guid]::NewGuid().ToString('N'))
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
        throw 'BF-966 BLOCKED: staged core has parse errors.'
    }

    $functions = @($coreAst.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-PlayerSearchHtml'
    }, $true))
    if ($functions.Count -ne 1) {
        throw "BF-966 BLOCKED: expected one staged Player Search renderer, found $($functions.Count)."
    }
    $search = $functions[0].Extent.Text

    foreach ($required in @(
        '$resultWaiverPosition = ([string]$player.Position).Trim().ToUpperInvariant()',
        '@("QB", "RB", "WR", "TE") -ccontains $resultWaiverPosition',
        '$resultWaiverHref = [System.Uri]::EscapeDataString($resultWaiverPosition)',
        'href="/waivers?position=',
        'Check '' + (ConvertTo-HtmlText $resultWaiverPosition) + '' waivers',
        'href="/waivers">Check Waiver Board</a>',
        '$resultWaiverAction</div></article>',
        'href=`"/player?id=$hrefId&from=players`">View Player Detail</a>',
        'href=`"/compare?left=$hrefId`">Compare this player</a>',
        'Scout franchise',
        '$searchWaiverAction</div></section>'
    )) {
        if ($search.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-966 BLOCKED: staged Player Search result-card waiver marker is missing: $required"
        }
    }

    if ($search -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-966 BLOCKED: staged Player Search result-card waiver surface introduced provider, backend-read, optimizer, FAAB, or write behavior.'
    }

    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'
    $dashboard = [IO.File]::ReadAllText($dashboardPath)
    foreach ($required in @(
        'function Get-WaiverPositionFocusFromRequestTarget',
        '@("QB", "RB", "WR", "TE") -ccontains $value',
        '-PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])'
    )) {
        if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-966 BLOCKED: staged focused-Waiver contract is missing: $required"
        }
    }

    Write-Host 'BF-966 PLAYER SEARCH RESULT-CARD WAIVERS ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
