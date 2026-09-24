param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-924 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-924 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$loopOld = @'
        foreach ($leader in $View.Leaders) {
            $franchiseHtml += @"
'@
$loopNew = @'
        foreach ($leader in $View.Leaders) {
            $leaderHrefId = [System.Uri]::EscapeDataString([string]$leader.TeamId)
            $franchiseHtml += @"
'@
$core = Replace-ExactlyOnce -Text $core -Old $loopOld.TrimEnd() -New $loopNew.TrimEnd() -Contract 'League Hub leader href'

$cardOld = @'
  <p class="meta">Governed franchise rank and values from Butler's current league evidence. Open the franchise name for neutral team detail.</p>
</article>
'@
$cardNew = @'
  <p class="meta">Governed franchise rank and values from Butler's current league evidence. Open the franchise name or use the actions below for the next workflow.</p>
  <div class="button-row" style="margin-top:12px"><a class="btn btn-primary" href="/franchise?id=$leaderHrefId">Scout franchise</a><a class="btn btn-secondary" href="/trade">Open Trade Analyzer</a></div>
</article>
'@
$core = Replace-ExactlyOnce -Text $core -Old $cardOld.TrimEnd() -New $cardNew.TrimEnd() -Contract 'League Hub franchise actions'

foreach ($required in @(
    '$leaderHrefId = [System.Uri]::EscapeDataString([string]$leader.TeamId)',
    'href="/franchise?id=$leaderHrefId">Scout franchise</a>',
    'href="/trade">Open Trade Analyzer</a>',
    'Open the franchise name or use the actions below for the next workflow.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-924 BLOCKED: required League Hub action marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-924 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-924 League Hub franchise actions applied.'
