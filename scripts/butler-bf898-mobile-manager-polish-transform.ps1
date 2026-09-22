param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath,

    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-898 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

function Add-CssOverride {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$StartMarker,
        [Parameter(Mandatory = $true)][string]$NextMarker,
        [Parameter(Mandatory = $true)][string]$Marker,
        [Parameter(Mandatory = $true)][string]$Css,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    if ($Text.Contains($Marker)) {
        return $Text
    }

    $start = $Text.IndexOf($StartMarker, [System.StringComparison]::Ordinal)
    $next = $Text.IndexOf($NextMarker, $start, [System.StringComparison]::Ordinal)
    if ($start -lt 0 -or $next -le $start) {
        throw "BF-898 BLOCKED: $Contract CSS function boundary is missing."
    }

    $block = $Text.Substring($start, $next - $start)
    $terminator = $block.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
    if ($terminator -lt 0) {
        throw "BF-898 BLOCKED: $Contract CSS here-string terminator is missing."
    }

    $block = $block.Substring(0, $terminator) + "`n" + $Css.TrimEnd() + $block.Substring($terminator)
    return $Text.Substring(0, $start) + $block + $Text.Substring($next)
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-898 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$mobileCss = @'
/* BF-898 mobile manager polish: keep all seven manager destinations swipeable without a visible desktop-style scrollbar. */
@media(max-width:760px){
  .nav{display:flex!important;flex-wrap:nowrap!important;max-width:100%;overflow-x:auto;overflow-y:hidden;-webkit-overflow-scrolling:touch;scroll-snap-type:x proximity;scroll-padding-inline:8px;scrollbar-width:none;-ms-overflow-style:none;overscroll-behavior-x:contain;touch-action:pan-x;padding:7px 8px!important;gap:4px!important}
  .nav::-webkit-scrollbar{display:none;width:0;height:0}
  .nav a{display:inline-flex;align-items:center;justify-content:center;flex:0 0 auto;min-height:44px;white-space:nowrap;scroll-snap-align:start;padding:9px 11px!important}
  .nav a.active{font-weight:800}
}
'@

$dashboard = [System.IO.File]::ReadAllText($DashboardPath)
$dashboard = Add-CssOverride -Text $dashboard -StartMarker 'function Get-SharedCss {' -NextMarker 'function Get-HeaderHtml {' -Marker 'BF-898 mobile manager polish' -Css $mobileCss -Contract 'dashboard'

$staleOld = '        "REFRESH AUTOFILL" = @("Your lineup recommendation is out of date", "Your roster or projection data changed since the last lineup review.", "REFRESH", "warn", "/team/autofill", "Refresh Lineup")'
$staleNew = '        "REFRESH AUTOFILL" = @("Lineup needs a fresh review", "Your roster or weekly projection frame changed since the saved lineup review. Refresh it before relying on the recommendation.", "REFRESH", "warn", "/team/autofill", "Refresh Lineup")'
$staleOldCount = [regex]::Matches($dashboard, [regex]::Escape($staleOld)).Count
$staleNewCount = [regex]::Matches($dashboard, [regex]::Escape($staleNew)).Count
if (($staleOldCount + $staleNewCount) -gt 1) {
    throw "BF-898 BLOCKED: stale lineup manager state is ambiguous."
}
$staleCopyExpected = $false
if ($staleOldCount -eq 1) {
    $dashboard = $dashboard.Replace($staleOld, $staleNew)
    $staleCopyExpected = $true
}
elseif ($staleNewCount -eq 1) {
    $staleCopyExpected = $true
}

[System.IO.File]::WriteAllText($DashboardPath, $dashboard, [System.Text.UTF8Encoding]::new($false))

if (-not [string]::IsNullOrWhiteSpace($CorePath)) {
    if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
        throw "BF-898 BLOCKED: staged Butler core not found at $CorePath"
    }

    $core = [System.IO.File]::ReadAllText($CorePath)
    $core = Add-CssOverride -Text $core -StartMarker 'function Get-AppCss {' -NextMarker 'function Get-AppNav {' -Marker 'BF-898 mobile manager polish' -Css $mobileCss -Contract 'core'
    [System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
}

foreach ($path in @($DashboardPath, $CorePath)) {
    if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
        continue
    }

    $tokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$parseErrors)
    if (@($parseErrors).Count -gt 0) {
        $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-898 BLOCKED: generated manager surface failed PowerShell parse: $parseSummary"
    }
}

$installedDashboard = [System.IO.File]::ReadAllText($DashboardPath)
$requiredMarkers = @(
    'BF-898 mobile manager polish',
    'scrollbar-width:none',
    'min-height:44px'
)
if ($staleCopyExpected) {
    $requiredMarkers += 'Lineup needs a fresh review'
    $requiredMarkers += 'Refresh it before relying on the recommendation.'
}
foreach ($required in $requiredMarkers) {
    if ($installedDashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-898 BLOCKED: required mobile manager marker is missing: $required"
    }
}

if ($installedDashboard -match 'Method = "POST"|submitTransaction|setFaab') {
    throw 'BF-898 BLOCKED: mobile manager polish introduced a write-path marker.'
}

Write-Host 'BF-898 mobile manager polish applied.'
