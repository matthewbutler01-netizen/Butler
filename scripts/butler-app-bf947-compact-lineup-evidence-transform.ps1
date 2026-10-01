param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-947 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-947 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

foreach ($required in @(
    'Projected slot change unavailable; comparable player projections are incomplete.',
    'Recent observed usage',
    'passing attempts are shown separately when available.',
    'NFL opponent and observed defense',
    'Sources and commentary',
    'Back to review queue',
    'Back to Matchup'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-947 BLOCKED: required prior lineup-review capability is missing: $required"
    }
}

$functionStart = $core.IndexOf('function ConvertTo-AutoFillHtml {', [System.StringComparison]::Ordinal)
$functionEnd = $core.IndexOf('function ConvertTo-TeamHtml {', $functionStart, [System.StringComparison]::Ordinal)
if ($functionStart -lt 0 -or $functionEnd -le $functionStart) {
    throw 'BF-947 BLOCKED: final Lineup Advisor renderer boundary is missing.'
}

$function = $core.Substring($functionStart, $functionEnd - $functionStart)
$usageOpen = '<table class=`"swap-usage-table`"><caption>Recent observed usage</caption>'
$usageOpenReplacement = '<details class=`"lineup-evidence-section`"><summary>Recent observed usage</summary><table class=`"swap-usage-table`"><caption>Recent observed usage</caption>'
$usageClose = '<p class=`"meta`">Carries and targets describe rushing and receiving opportunities; passing attempts are shown separately when available.</p>$matchupHtml<details><summary>Sources and commentary</summary>'
$usageCloseReplacement = '<p class=`"meta`">Carries and targets describe rushing and receiving opportunities; passing attempts are shown separately when available.</p></details>$matchupHtml<details><summary>Sources and commentary</summary>'

$function = Replace-ExactlyOnce -Text $function -Old $usageOpen -New $usageOpenReplacement -Contract 'comparison usage disclosure opening'
$function = Replace-ExactlyOnce -Text $function -Old $usageClose -New $usageCloseReplacement -Contract 'comparison usage disclosure closing'
$core = $core.Substring(0, $functionStart) + $function + $core.Substring($functionEnd)

$cssMarker = '/* BF-943 changes-first lineup progressive disclosure. */'
$cssReplacement = @'
/* BF-943 changes-first lineup progressive disclosure. */
/* BF-947 compact lineup comparison evidence. */
.lineup-evidence-section{margin:12px 0;border:1px solid var(--line);border-radius:10px;background:var(--surface-soft);overflow:hidden}.lineup-evidence-section>summary{cursor:pointer;padding:11px 12px;font-weight:800;list-style-position:inside}.lineup-evidence-section[open]>summary{border-bottom:1px solid var(--line)}.lineup-evidence-section .swap-usage-table{margin:0}.lineup-evidence-section>p.meta{padding:0 12px 12px;margin:8px 0 0}
'@
$core = Replace-ExactlyOnce -Text $core -Old $cssMarker -New $cssReplacement.TrimEnd() -Contract 'BF-943 lineup CSS marker'

if ([regex]::Matches($core, [regex]::Escape('class=`"lineup-evidence-section`"')).Count -ne 1) {
    throw 'BF-947 BLOCKED: compact lineup evidence disclosure was not installed exactly once.'
}
if ($core.IndexOf('Invoke-RestMethod', $functionStart, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
    $core.IndexOf('Invoke-RestMethod', $functionStart, [System.StringComparison]::OrdinalIgnoreCase) -lt $functionEnd) {
    throw 'BF-947 BLOCKED: lineup presentation introduced a provider read.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-947 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

Write-Host 'BF-947 Compact lineup comparison evidence applied.'
