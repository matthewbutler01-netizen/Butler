param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-883 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-883 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$playerSearchStart = $core.IndexOf('function ConvertTo-PlayerSearchHtml {', [System.StringComparison]::Ordinal)
$playerSearchEnd = $core.IndexOf('function Get-PlayerDetailRequestId {', $playerSearchStart, [System.StringComparison]::Ordinal)
if ($playerSearchStart -lt 0 -or $playerSearchEnd -le $playerSearchStart) {
    throw 'BF-883 BLOCKED: Player Search renderer boundary is missing.'
}
$playerSearchBlock = $core.Substring($playerSearchStart, $playerSearchEnd - $playerSearchStart)

$searchResultOld = 'href=`"/player?id=$hrefId`"'
$searchResultNew = 'href=`"/player?id=$hrefId&from=players`"'
$playerSearchBlock = Replace-ExactlyOnce -Text $playerSearchBlock -Old $searchResultOld -New $searchResultNew -Contract 'Player Search contextual Player Detail link'

$searchHeroOld = '</div>$form</section>'
$searchHeroNew = '</div>$form<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/league">Back to League</a></div></section>'
$playerSearchBlock = Replace-ExactlyOnce -Text $playerSearchBlock -Old $searchHeroOld -New $searchHeroNew -Contract 'Player Search safe return actions'

$core = $core.Substring(0, $playerSearchStart) + $playerSearchBlock + $core.Substring($playerSearchEnd)

$contextFunction = @'
function Add-PlayerDetailContextNavigation {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][bool]$FromPlayers
    )

    $current = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/players">Find another player</a></div>'
    if ($FromPlayers) {
        $replacement = '<div class="button-row"><a class="btn btn-secondary" href="/players">Back to Player Search</a><a class="btn btn-secondary" href="/team">My Team</a></div>'
    }
    else {
        $replacement = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/players">Player Search</a></div>'
    }

    return Replace-ExactlyOnce -Text $Html -Old $current -New $replacement -Contract 'Player Detail contextual return actions'
}

'@

$playerMarker = 'function Get-PlayerDetailRequestId {'
$playerIndex = $core.IndexOf($playerMarker, [System.StringComparison]::Ordinal)
if ($playerIndex -lt 0) {
    throw 'BF-883 BLOCKED: Player Detail insertion marker is missing.'
}
$core = $core.Insert($playerIndex, $contextFunction)

$routeOld = @'
                    $html = ConvertTo-PlayerDetailHtml -View $playerDetail
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
'@
$routeNew = @'
                    $html = ConvertTo-PlayerDetailHtml -View $playerDetail
                    $fromPlayers = [regex]::IsMatch($parts[1], '(?:\?|&)from=players(?:&|$)')
                    $html = Add-PlayerDetailContextNavigation -Html $html -FromPlayers $fromPlayers
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
'@
$core = Replace-ExactlyOnce -Text $core -Old $routeOld.TrimEnd() -New $routeNew.TrimEnd() -Contract 'Player Detail contextual route presentation'

foreach ($required in @(
    'href=`"/player?id=$hrefId&from=players`"',
    'Back to Player Search',
    'Back to My Team',
    'Back to League',
    'function Add-PlayerDetailContextNavigation',
    '(?:\?|&)from=players(?:&|$)',
    '<a class="btn btn-secondary" href="/league">Back to League</a>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-883 BLOCKED: required contextual-navigation marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function Add-PlayerDetailContextNavigation', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function Get-PlayerDetailRequestId', $installedStart, [System.StringComparison]::Ordinal)
$installed = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'https://api.sleeper.app',
    'submitTransaction',
    'setFaab',
    'returnUrl',
    'redirectUrl',
    'window.history',
    'javascript:'
)) {
    if ($installed.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-883 BLOCKED: contextual navigation introduced forbidden behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-883 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
