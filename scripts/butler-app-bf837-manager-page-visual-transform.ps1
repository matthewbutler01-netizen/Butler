param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-837 BLOCKED: staged Butler core not found at $CorePath"
}

$core = [System.IO.File]::ReadAllText($CorePath)
$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-837 BLOCKED: shared manager-page CSS function boundary is missing.'
}

$oldImport = "@import url('https://fonts.googleapis.com/css2?family=Teko:wght@400;500;600;700&family=Inter:wght@400;500;600;700&display=swap');"
$newImport = "@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap');"
$importCount = [regex]::Matches($core, [regex]::Escape($oldImport)).Count
if ($importCount -ne 1) {
    throw "BF-837 BLOCKED: expected one legacy BF-830 font import, found $importCount."
}
$core = $core.Replace($oldImport, $newImport)

$cssStart = $core.IndexOf('function Get-AppCss {', [System.StringComparison]::Ordinal)
$cssEnd = $core.IndexOf('function Get-AppNav {', $cssStart, [System.StringComparison]::Ordinal)
$cssBlock = $core.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-837 BLOCKED: shared manager-page CSS terminator is missing.'
}

$alignmentCss = @'
/* BF-837 manager-page visual alignment: BF-833 language across manager surfaces. */
:root{--bg:#F3F2EE;--surface:#FFFFFF;--surface-2:#F7F6F2;--line:#D9DCD7;--turf:#376E50;--turf-deep:#28543D;--gold:#A77418;--ink:#1E2521;--muted:#68726B;--brick:#A65245;--font-display:'Inter',Segoe UI,Arial,sans-serif;--font-body:'Inter',Segoe UI,Arial,sans-serif;--radius:10px;--shadow:0 8px 24px rgba(25,35,29,.04)}
body{background:#F3F2EE;background-image:none}
.shell{max-width:1160px;padding:28px 28px 72px}
.top{align-items:flex-end;padding:24px 26px 18px;border:1px solid var(--line);border-bottom:1px solid var(--line);border-radius:14px 14px 0 0;background:var(--surface);box-shadow:0 8px 28px rgba(25,35,29,.05)}
.brand h1{font-family:var(--font-display);font-size:30px;font-weight:800;letter-spacing:.16em;line-height:1.1;text-transform:none}
.brand p{margin-top:6px;font-size:13px;line-height:1.5;letter-spacing:0;text-transform:none}
.target{font-size:12px}
.nav{gap:4px;padding:8px 12px 9px;margin:0 0 24px;border:1px solid var(--line);border-top:0;border-radius:0 0 14px 14px;background:var(--surface);box-shadow:0 8px 28px rgba(25,35,29,.05)}
.nav a{font-family:var(--font-body);font-size:13px;font-weight:700;letter-spacing:0;text-transform:none;padding:9px 12px;border:1px solid transparent;border-radius:8px}
.nav a:hover{color:var(--ink);background:var(--surface-2)}
.nav a.active{color:var(--turf-deep);background:#EDF3EF;border-color:#D4E0D8;border-bottom-color:#D4E0D8}
.panel{padding:22px 24px;border-radius:12px;margin-bottom:18px;box-shadow:var(--shadow)}
.hero-panel{border-top:1px solid var(--line)}
.recommendation-panel{border-left:3px solid var(--turf)}
.eyebrow{font-size:10px;letter-spacing:.1em;color:var(--turf);font-weight:800}
.headline{font-family:var(--font-display);font-size:30px;font-weight:800;line-height:1.15;letter-spacing:-.02em;text-transform:none}
h2,.summary-card h3,.roster-group-head h3{font-family:var(--font-display);font-weight:800;letter-spacing:-.01em}
.status{border-radius:999px;padding:5px 9px;font-size:10px;letter-spacing:.04em}
.stats,.manager-metrics{gap:10px;border:0}
.stat,.metric-card{padding:14px 15px;border:1px solid var(--line);border-radius:10px;background:var(--surface-2)}
.stat strong,.metric-label{font-size:10px;letter-spacing:.04em;text-transform:none}
.stat span,.metric-value{font-family:var(--font-display);font-size:20px;font-weight:800;color:var(--ink)}
.card,.action,.summary-card,.movement-box,.roster-board,.lineup-board{border-radius:10px}
.card,.action,.summary-card,.movement-box{background:var(--surface-2)}
.card .total,.pressure-tier,.projection{font-family:var(--font-display);color:var(--ink)}
.roster-group-head{background:var(--surface-2)}
.position-chip{border-radius:7px;background:var(--turf)}
.empty,.callout,.command{border-radius:9px}
details{border-color:var(--line)}
summary{color:var(--ink)}
@media(prefers-color-scheme:dark){:root{--bg:#111315;--surface:#191C1E;--surface-2:#202426;--line:#303639;--turf:#69A27D;--turf-deep:#8CBC9A;--gold:#D4A64B;--ink:#F1F3F1;--muted:#A6AFA9;--brick:#D47A6B;--shadow:none}body{background:#111315;background-image:none}.top,.nav,.panel{box-shadow:none}.nav a.active{color:#A8D3B5;background:#26352C;border-color:#35483C}.stat,.metric-card,.card,.action,.summary-card,.movement-box{background:#202426}}
@media(max-width:760px){.shell{padding:14px 14px 44px}.top{padding:20px 18px 15px}.brand h1{font-size:26px}.nav{padding:7px 8px}.nav a{font-size:12px;padding:8px 9px}.panel{padding:18px 16px}.headline{font-size:27px}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $alignmentCss.TrimEnd() + $cssBlock.Substring($cssTerminator)
$core = $core.Substring(0, $cssStart) + $cssBlock + $core.Substring($cssEnd)

foreach ($required in @(
    'BF-837 manager-page visual alignment',
    '--bg:#F3F2EE',
    '--surface-2:#F7F6F2',
    '--turf:#376E50',
    "--font-display:'Inter'",
    '--radius:10px',
    '--bg:#111315',
    '--surface:#191C1E',
    '--surface-2:#202426',
    'background-image:none'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-837 BLOCKED: required visual alignment marker is missing: $required"
    }
}

if ($core.Contains("family=Teko")) {
    throw 'BF-837 BLOCKED: legacy Teko font dependency remains in staged manager-page CSS.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-837 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
