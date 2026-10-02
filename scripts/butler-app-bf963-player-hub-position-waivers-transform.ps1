param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-963 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-963 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-963 BLOCKED: staged core failed pre-transform parse: $summary"
}

$functions = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Add-PlayerHubPresentation'
}, $true))
if ($functions.Count -ne 1) {
    throw "BF-963 BLOCKED: expected exactly one Player Hub presentation function, found $($functions.Count)."
}

$start = $functions[0].Extent.StartOffset
$end = $functions[0].Extent.EndOffset
$hub = $core.Substring($start, $end - $start)

foreach ($required in @(
    '$positionHref = [System.Uri]::EscapeDataString([string]$View.Position)',
    'href="/waivers">Check Waiver Board</a>',
    'href="/players?q=$positionHref">Find more $(ConvertTo-HtmlText $View.Position)</a>'
)) {
    if ($hub.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-963 BLOCKED: required Player Hub marker is missing: $required"
    }
}

$positionOld = '    $positionHref = [System.Uri]::EscapeDataString([string]$View.Position)'
$positionNew = @'
    $positionHref = [System.Uri]::EscapeDataString([string]$View.Position)
    $waiverPosition = ([string]$View.Position).Trim().ToUpperInvariant()
    $waiverAction = if (@("QB", "RB", "WR", "TE") -ccontains $waiverPosition) {
        '<a class="btn btn-secondary" href="/waivers?position=' + (ConvertTo-HtmlText $positionHref) + '">Check ' + (ConvertTo-HtmlText $waiverPosition) + ' waivers</a>'
    }
    else {
        '<a class="btn btn-secondary" href="/waivers">Check Waiver Board</a>'
    }
'@
$hub = Replace-ExactlyOnce -Text $hub -Old $positionOld -New $positionNew.TrimEnd() -Contract 'Player Hub waiver-position derivation'

$genericWaiver = '<a class="btn btn-secondary" href="/waivers">Check Waiver Board</a>'
$hub = Replace-ExactlyOnce -Text $hub -Old $genericWaiver -New '$waiverAction' -Contract 'Player Hub focused Waiver action'

$core = $core.Substring(0, $start) + $hub + $core.Substring($end)

$tokens = $null
$parseErrors = $null
$finalAst = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-963 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$installed = @($finalAst.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Add-PlayerHubPresentation'
}, $true))
if ($installed.Count -ne 1) {
    throw "BF-963 BLOCKED: generated core must contain exactly one Player Hub presentation function; found $($installed.Count)."
}
$installedHub = $installed[0].Extent.Text

foreach ($required in @(
    '$waiverPosition = ([string]$View.Position).Trim().ToUpperInvariant()',
    '@("QB", "RB", "WR", "TE") -ccontains $waiverPosition',
    'href="/waivers?position=',
    'Check '' + (ConvertTo-HtmlText $waiverPosition) + '' waivers',
    'href="/waivers">Check Waiver Board</a>',
    '$waiverAction'
)) {
    if ($installedHub.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-963 BLOCKED: installed Player Hub focused-waiver marker is missing: $required"
    }
}

if ($installedHub -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-963 BLOCKED: Player Hub focused waiver action introduced provider, backend-read, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-963 Player Hub position-aware waivers applied.'
