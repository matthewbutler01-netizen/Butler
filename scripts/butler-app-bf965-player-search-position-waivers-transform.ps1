param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-965 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) {
        throw "BF-965 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-965 BLOCKED: staged core failed pre-transform parse: $summary"
}

$functions = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-PlayerSearchHtml'
}, $true))
if ($functions.Count -ne 1) {
    throw "BF-965 BLOCKED: expected exactly one Player Search renderer, found $($functions.Count)."
}

$start = $functions[0].Extent.StartOffset
$end = $functions[0].Extent.EndOffset
$search = $core.Substring($start, $end - $start)

foreach ($required in @(
    '$inputValue = ConvertTo-HtmlText $Query',
    'href="/players?q=QB">QB</a>',
    'href="/players?q=RB">RB</a>',
    'href="/players?q=WR">WR</a>',
    'href="/players?q=TE">TE</a>',
    'href="/waivers">Check Waiver Board</a>'
)) {
    if ($search.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-965 BLOCKED: required Player Search marker is missing: $required"
    }
}

$actionsOld = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/league">Back to League</a><a class="btn btn-secondary" href="/waivers">Check Waiver Board</a></div></section>'
$actionsNew = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/league">Back to League</a>$searchWaiverAction</div></section>'
$search = Replace-ExactlyOnce -Text $search -Old $actionsOld -New $actionsNew -Contract 'Player Search focused Waiver action'

$queryOld = '    $inputValue = ConvertTo-HtmlText $Query'
$queryNew = @'
    $inputValue = ConvertTo-HtmlText $Query
    $searchWaiverPosition = ([string]$Query).Trim().ToUpperInvariant()
    $searchWaiverAction = if (@("QB", "RB", "WR", "TE") -ccontains $searchWaiverPosition) {
        $searchWaiverHref = [System.Uri]::EscapeDataString($searchWaiverPosition)
        '<a class="btn btn-secondary" href="/waivers?position=' + (ConvertTo-HtmlText $searchWaiverHref) + '">Check ' + (ConvertTo-HtmlText $searchWaiverPosition) + ' waivers</a>'
    }
    else {
        '<a class="btn btn-secondary" href="/waivers">Check Waiver Board</a>'
    }
'@
$search = Replace-ExactlyOnce -Text $search -Old $queryOld -New $queryNew.TrimEnd() -Contract 'Player Search waiver-position derivation'

$core = $core.Substring(0, $start) + $search + $core.Substring($end)

$tokens = $null
$parseErrors = $null
$finalAst = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-965 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$installed = @($finalAst.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-PlayerSearchHtml'
}, $true))
if ($installed.Count -ne 1) {
    throw "BF-965 BLOCKED: generated core must contain exactly one Player Search renderer; found $($installed.Count)."
}
$installedSearch = $installed[0].Extent.Text

foreach ($required in @(
    '$searchWaiverPosition = ([string]$Query).Trim().ToUpperInvariant()',
    '@("QB", "RB", "WR", "TE") -ccontains $searchWaiverPosition',
    '$searchWaiverHref = [System.Uri]::EscapeDataString($searchWaiverPosition)',
    'href="/waivers?position=',
    'Check '' + (ConvertTo-HtmlText $searchWaiverPosition) + '' waivers',
    'href="/waivers">Check Waiver Board</a>',
    '$searchWaiverAction</div>'
)) {
    if ($installedSearch.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-965 BLOCKED: installed Player Search focused-waiver marker is missing: $required"
    }
}

if ($installedSearch -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-965 BLOCKED: Player Search focused waiver action introduced provider, backend-read, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-965 Player Search position-aware waivers applied.'

$bf966Transform = Join-Path $PSScriptRoot 'butler-app-bf966-player-search-result-waivers-transform.ps1'
if (-not (Test-Path -LiteralPath $bf966Transform -PathType Leaf)) {
    throw "BF-966 BLOCKED: Player Search result-card waiver transform not found at $bf966Transform"
}
& $bf966Transform -CorePath $CorePath
