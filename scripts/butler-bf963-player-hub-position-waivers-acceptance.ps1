Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf963-player-hub-position-waivers-transform.ps1'
$transformTokens = $null
$transformErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$transformTokens, [ref]$transformErrors)
if (@($transformErrors).Count -ne 0) {
    $summary = (@($transformErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-963 BLOCKED: transform script failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf963-player-waivers-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $core = [IO.File]::ReadAllText($corePath)
    $coreTokens = $null
    $coreErrors = $null
    $coreAst = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$coreTokens, [ref]$coreErrors)
    if (@($coreErrors).Count -ne 0) {
        throw 'BF-963 BLOCKED: staged core has parse errors.'
    }

    $hubFunctions = @($coreAst.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Add-PlayerHubPresentation'
    }, $true))
    if ($hubFunctions.Count -ne 1) {
        throw "BF-963 BLOCKED: expected one staged Player Hub presentation function, found $($hubFunctions.Count)."
    }
    $hub = $hubFunctions[0].Extent.Text

    foreach ($required in @(
        '$waiverPosition = ([string]$View.Position).Trim().ToUpperInvariant()',
        '@("QB", "RB", "WR", "TE") -ccontains $waiverPosition',
        'href="/waivers?position=',
        'Check '' + (ConvertTo-HtmlText $waiverPosition) + '' waivers',
        'href="/waivers">Check Waiver Board</a>',
        '$waiverAction'
    )) {
        if ($hub.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-963 BLOCKED: staged Player Hub position-waiver marker is missing: $required"
        }
    }

    if ($hub -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-963 BLOCKED: staged Player Hub position-waiver surface introduced provider, backend-read, optimizer, FAAB, or write behavior.'
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
            throw "BF-963 BLOCKED: staged focused-Waiver contract is missing: $required"
        }
    }

    Write-Host 'BF-963 PLAYER HUB POSITION-AWARE WAIVERS ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
