param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-975 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-975 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-975 BLOCKED: staged core failed pre-transform parse: $summary"
}

$searchFunctions = @($ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-PlayerSearchHtml'
}, $true))
if ($searchFunctions.Count -ne 1) {
    throw "BF-975 BLOCKED: expected exactly one Player Search renderer, found $($searchFunctions.Count)."
}

$searchStart = $searchFunctions[0].Extent.StartOffset
$searchEnd = $searchFunctions[0].Extent.EndOffset
$search = $core.Substring($searchStart, $searchEnd - $searchStart)

$inputOld = '    $inputValue = ConvertTo-HtmlText $Query'
$inputNew = @'
    $inputValue = ConvertTo-HtmlText $Query
    $searchReturnHref = [System.Uri]::EscapeDataString([string]$Query)
'@
$search = Replace-ExactlyOnce -Text $search -Old $inputOld -New $inputNew.TrimEnd() -Contract 'Player Search return-query encoding'

$linkOld = 'href=`"/player?id=$hrefId&from=players`"'
$linkNew = 'href=`"/player?id=$hrefId&from=players&q=$searchReturnHref`"'
$search = Replace-ExactlyOnce -Text $search -Old $linkOld -New $linkNew -Contract 'Player Search detail return context'

$core = $core.Substring(0, $searchStart) + $search + $core.Substring($searchEnd)

$helperMarker = 'function Get-PlayerDetailRequestId {'
$helperIndex = $core.IndexOf($helperMarker, [System.StringComparison]::Ordinal)
if ($helperIndex -lt 0) {
    throw 'BF-975 BLOCKED: Player Detail request helper insertion marker is missing.'
}

$helpers = @'
function Get-PlayerDetailSearchQueryContext {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $question = $RequestTarget.IndexOf('?')
    if ($question -lt 0 -or $question + 1 -ge $RequestTarget.Length) { return '' }

    $queries = @()
    foreach ($part in @($RequestTarget.Substring($question + 1) -split '&')) {
        if ([string]::IsNullOrWhiteSpace($part)) { continue }

        $separator = $part.IndexOf('=')
        if ($separator -lt 0) {
            $rawName = $part
            $rawValue = ''
        }
        else {
            $rawName = $part.Substring(0, $separator)
            $rawValue = $part.Substring($separator + 1)
        }

        $name = [System.Uri]::UnescapeDataString($rawName.Replace('+', ' '))
        if ($name -ceq 'q') {
            $queries += [System.Uri]::UnescapeDataString($rawValue.Replace('+', ' '))
        }
    }

    if ($queries.Count -eq 0) { return '' }
    if ($queries.Count -ne 1) {
        throw 'BF-975 BLOCKED: Player Detail search context requires at most one q query parameter.'
    }

    $query = [regex]::Replace(([string]$queries[0]).Trim(), '\s+', ' ')
    if ([string]::IsNullOrWhiteSpace($query)) { return '' }
    if ($query.Length -gt 80) {
        throw 'BF-975 BLOCKED: Player Detail search context must be 80 characters or fewer.'
    }
    if ($query -notmatch '^[A-Za-z0-9 ._''-]+$') {
        throw 'BF-975 BLOCKED: Player Detail search context contains unsupported characters.'
    }
    return $query
}

function Add-PlayerDetailSearchReturnQuery {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][bool]$FromPlayers,
        [AllowEmptyString()][string]$SearchQuery
    )

    if (-not $FromPlayers -or [string]::IsNullOrWhiteSpace($SearchQuery)) {
        return $Html
    }

    $encodedQuery = [System.Uri]::EscapeDataString($SearchQuery)
    $old = 'href="/players">Back to Player Search</a>'
    $new = 'href="/players?q=' + $encodedQuery + '">Back to Player Search</a>'
    $matches = [regex]::Matches($Html, [regex]::Escape($old)).Count
    if ($matches -ne 1) {
        throw "BF-975 BLOCKED: Player Detail exact search return expected one Back to Player Search action, found $matches."
    }
    return $Html.Replace($old, $new)
}

'@

$core = $core.Insert($helperIndex, $helpers)

$routeAnchor = '                    $html = Add-PlayerHubPresentation -Html $html -View $playerDetail'
$routeExpanded = @'
                    $html = Add-PlayerHubPresentation -Html $html -View $playerDetail
                    $searchReturnQuery = Get-PlayerDetailSearchQueryContext -RequestTarget $parts[1]
                    $html = Add-PlayerDetailSearchReturnQuery -Html $html -FromPlayers $fromPlayers -SearchQuery $searchReturnQuery
'@
$core = Replace-ExactlyOnce -Text $core -Old $routeAnchor -New $routeExpanded.TrimEnd() -Contract 'Player Detail exact search-return route'

$tokens = $null
$parseErrors = $null
$finalAst = [System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-975 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$installedSearch = @($finalAst.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'ConvertTo-PlayerSearchHtml'
}, $true))
if ($installedSearch.Count -ne 1) {
    throw "BF-975 BLOCKED: generated core must contain exactly one Player Search renderer; found $($installedSearch.Count)."
}

$searchText = $installedSearch[0].Extent.Text
foreach ($required in @(
    '$searchReturnHref = [System.Uri]::EscapeDataString([string]$Query)',
    'href=`"/player?id=$hrefId&from=players&q=$searchReturnHref`"'
)) {
    if ($searchText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-975 BLOCKED: installed Player Search return-context marker is missing: $required"
    }
}

foreach ($functionName in @('Get-PlayerDetailSearchQueryContext', 'Add-PlayerDetailSearchReturnQuery')) {
    $matches = @($finalAst.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq $functionName
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-975 BLOCKED: expected exactly one installed $functionName function, found $($matches.Count)."
    }
}

$bf975Surface = $helpers + $inputNew + $linkNew
if ($bf975Surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api.sleeper.app|Method = "POST"|submitTransaction|setFaab|returnUrl|redirectUrl|javascript:') {
    throw 'BF-975 BLOCKED: Player Search return context introduced provider, backend-read, write, or open-redirect behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-975 Player Search exact return context applied.'

$bf977Transform = Join-Path $PSScriptRoot 'butler-app-bf977-player-compare-return-context-transform.ps1'
if (-not (Test-Path -LiteralPath $bf977Transform -PathType Leaf)) {
    throw "BF-977 BLOCKED: Player Compare return-context transform not found at $bf977Transform"
}
& $bf977Transform -CorePath $CorePath
