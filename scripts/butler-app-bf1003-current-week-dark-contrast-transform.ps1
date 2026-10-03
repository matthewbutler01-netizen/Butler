param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($path in @($CorePath, $DashboardPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "BF-1003 BLOCKED: required staged UI source not found at $path"
    }
}

function Add-Bf1003Css {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$StartMarker,
        [Parameter(Mandatory = $true)][string]$NextMarker,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $start = $Text.IndexOf($StartMarker, [System.StringComparison]::Ordinal)
    $next = $Text.IndexOf($NextMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($start -lt 0 -or $next -le $start) {
        throw "BF-1003 BLOCKED: $Contract CSS function boundary is missing."
    }

    $block = $Text.Substring($start, $next - $start)
    if ($block.IndexOf('BF-1003 current-week readability.', [System.StringComparison]::Ordinal) -ge 0) {
        throw "BF-1003 BLOCKED: $Contract contrast CSS is already installed."
    }

    $terminator = $block.LastIndexOf("'@", [System.StringComparison]::Ordinal)
    if ($terminator -lt 0) {
        throw "BF-1003 BLOCKED: $Contract CSS here-string terminator is missing."
    }

    $css = @'
/* BF-1003 current-week readability. */
main .panel a:not(.btn):not(.button):not(.command-button):not(.week-tool){color:var(--turf-deep,#28543D);text-decoration:underline;text-decoration-thickness:1px;text-underline-offset:2px;font-weight:700}
@media(prefers-color-scheme:dark){
main .panel a:not(.btn):not(.button):not(.command-button):not(.week-tool){color:#A8D3B5!important}
main .panel a:not(.btn):not(.button):not(.command-button):not(.week-tool):visited{color:#B9DCC3!important}
main .panel .lede,main .panel .meta,main .panel .technical,main .panel .tech,main .panel .source-note{color:#C1CAC4!important}
main .btn.btn-primary,main a.button,main .command-button:not(.secondary){background:#69A27D!important;color:#111315!important;border-color:#8CBC9A!important}
main .btn.btn-secondary,main .week-tool,main .back-link{color:#A8D3B5!important;border-color:#69A27D!important}
}
'@

    $block = $block.Substring(0, $terminator) + [Environment]::NewLine + $css.TrimEnd() + [Environment]::NewLine + $block.Substring($terminator)
    return $Text.Substring(0, $start) + $block + $Text.Substring($next)
}

$core = [IO.File]::ReadAllText($CorePath)
$dashboard = [IO.File]::ReadAllText($DashboardPath)

$core = Add-Bf1003Css -Text $core -StartMarker 'function Get-AppCss {' -NextMarker 'function Get-AppNav {' -Contract 'app core'
$dashboard = Add-Bf1003Css -Text $dashboard -StartMarker 'function Get-SharedCss {' -NextMarker 'function Get-HeaderHtml {' -Contract 'Dashboard'

foreach ($pair in @(
    [pscustomobject]@{ Label = 'core'; Text = $core },
    [pscustomobject]@{ Label = 'Dashboard'; Text = $dashboard }
)) {
    foreach ($required in @(
        'BF-1003 current-week readability.',
        'color:#A8D3B5!important',
        'color:#B9DCC3!important',
        'background:#69A27D!important',
        'color:#C1CAC4!important'
    )) {
        if ($pair.Text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1003 BLOCKED: $($pair.Label) readability marker is missing: $required"
        }
    }
}

[IO.File]::WriteAllText($CorePath, $core, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($DashboardPath, $dashboard, [Text.UTF8Encoding]::new($false))

foreach ($path in @($CorePath, $DashboardPath)) {
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -gt 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-1003 BLOCKED: generated source failed PowerShell parse: $summary"
    }
}

Write-Host 'BF-1003 current-week readability applied.'
