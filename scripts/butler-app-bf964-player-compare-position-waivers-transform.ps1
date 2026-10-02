param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-964 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-964 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-964 BLOCKED: staged core failed pre-transform parse: $summary"
}

$functions = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-PlayerCompareCardHtml'
}, $true))
if ($functions.Count -ne 1) {
    throw "BF-964 BLOCKED: expected exactly one Player Compare card renderer, found $($functions.Count)."
}

$start = $functions[0].Extent.StartOffset
$end = $functions[0].Extent.EndOffset
$card = $core.Substring($start, $end - $start)

foreach ($required in @(
    '$positionHref = [System.Uri]::EscapeDataString([string]$Player.Position)',
    'href="/compare?left=$hrefId&q=$positionHref">Compare with another $(ConvertTo-HtmlText $Player.Position)</a>',
    'href="/player?id=$hrefId">View Player Detail</a>',
    'href="/franchise?id=$teamHrefId">Scout franchise</a>'
)) {
    if ($card.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-964 BLOCKED: required Player Compare card marker is missing: $required"
    }
}

$actionsOld = '<div class="button-row"><a class="btn btn-primary" href="/compare?left=$hrefId&q=$positionHref">Compare with another $(ConvertTo-HtmlText $Player.Position)</a><a class="btn btn-secondary" href="/player?id=$hrefId">View Player Detail</a><a class="btn btn-secondary" href="/franchise?id=$teamHrefId">Scout franchise</a></div>'
$actionsNew = '<div class="button-row"><a class="btn btn-primary" href="/compare?left=$hrefId&q=$positionHref">Compare with another $(ConvertTo-HtmlText $Player.Position)</a><a class="btn btn-secondary" href="/player?id=$hrefId">View Player Detail</a><a class="btn btn-secondary" href="/franchise?id=$teamHrefId">Scout franchise</a>$waiverAction</div>'
$card = Replace-ExactlyOnce -Text $card -Old $actionsOld -New $actionsNew -Contract 'Player Compare focused Waiver action'

$positionOld = '    $positionHref = [System.Uri]::EscapeDataString([string]$Player.Position)'
$positionNew = @'
    $positionHref = [System.Uri]::EscapeDataString([string]$Player.Position)
    $waiverPosition = ([string]$Player.Position).Trim().ToUpperInvariant()
    $waiverAction = if (@("QB", "RB", "WR", "TE") -ccontains $waiverPosition) {
        '<a class="btn btn-secondary" href="/waivers?position=' + (ConvertTo-HtmlText $positionHref) + '">Check ' + (ConvertTo-HtmlText $waiverPosition) + ' waivers</a>'
    }
    else {
        '<a class="btn btn-secondary" href="/waivers">Check Waiver Board</a>'
    }
'@
$card = Replace-ExactlyOnce -Text $card -Old $positionOld -New $positionNew.TrimEnd() -Contract 'Player Compare waiver-position derivation'

$core = $core.Substring(0, $start) + $card + $core.Substring($end)

$tokens = $null
$parseErrors = $null
$finalAst = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-964 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$installed = @($finalAst.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-PlayerCompareCardHtml'
}, $true))
if ($installed.Count -ne 1) {
    throw "BF-964 BLOCKED: generated core must contain exactly one Player Compare card renderer; found $($installed.Count)."
}
$installedCard = $installed[0].Extent.Text

foreach ($required in @(
    '$waiverPosition = ([string]$Player.Position).Trim().ToUpperInvariant()',
    '@("QB", "RB", "WR", "TE") -ccontains $waiverPosition',
    'href="/waivers?position=',
    'Check '' + (ConvertTo-HtmlText $waiverPosition) + '' waivers',
    'href="/waivers">Check Waiver Board</a>',
    '$waiverAction</div>'
)) {
    if ($installedCard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-964 BLOCKED: installed Player Compare focused-waiver marker is missing: $required"
    }
}

if ($installedCard -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-964 BLOCKED: Player Compare focused waiver action introduced provider, backend-read, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-964 Player Compare position-aware waivers applied.'
