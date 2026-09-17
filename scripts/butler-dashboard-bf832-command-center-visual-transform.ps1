param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-832 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

# BF-832 is presentation-only. Scope the new visual system to the Dashboard page so
# existing Waiver Board and other dashboard-hosted routes keep their current contracts.
$cssStart = $text.IndexOf('function Get-SharedCss {', [System.StringComparison]::Ordinal)
$cssEnd = $text.IndexOf('function Get-HeaderHtml {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-832 BLOCKED: shared dashboard CSS function boundary is missing.'
}
$cssBlock = $text.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-832 BLOCKED: shared dashboard CSS terminator is missing.'
}

$dashboardCss = @'
@import url('https://fonts.googleapis.com/css2?family=Teko:wght@400;500;600;700&family=Inter:wght@400;500;600;700&display=swap');
body.dashboard-page{--bg:#F4F2EA;--surface:#FFFFFF;--surface-2:#ECE9DD;--line:#D8D4C4;--turf:#2E6B47;--turf-deep:#1F4D33;--gold:#C98A1F;--ink:#16201A;--muted:#5B6459;--brick:#A8452F;--font-display:'Teko',Impact,'Arial Narrow',sans-serif;--font-body:'Inter',Segoe UI,Arial,sans-serif;--radius:3px;margin:0;min-height:100vh;background-color:var(--bg);background-image:repeating-linear-gradient(to bottom,transparent,transparent 79px,var(--line) 79px,var(--line) 80px);color:var(--ink);font-family:var(--font-body);-webkit-font-smoothing:antialiased}
.dashboard-command-center{max-width:1180px;margin:0 auto;padding:22px 28px 80px;color:var(--ink)}
.dashboard-command-center .top{display:flex;justify-content:space-between;gap:24px;align-items:baseline;flex-wrap:wrap;margin:0;background:var(--surface);padding:22px 28px 16px;border:1px solid var(--line);border-bottom:3px solid var(--turf-deep);border-radius:var(--radius) var(--radius) 0 0}
.dashboard-command-center .brand h1{font-family:var(--font-display);font-weight:600;font-size:42px;letter-spacing:.5px;line-height:1;margin:0;color:var(--ink)}
.dashboard-command-center .brand p{margin:5px 0 0;color:var(--muted);font-size:12px}
.dashboard-command-center .target{font-size:13px;color:var(--muted);text-align:right}
.dashboard-command-center .nav{display:flex;gap:2px;margin:0 0 28px;overflow-x:auto;flex-wrap:nowrap;padding:0 14px;background:var(--surface);border:1px solid var(--line);border-top:0;border-radius:0 0 var(--radius) var(--radius)}
.dashboard-command-center .nav a{font-family:var(--font-display);font-size:19px;letter-spacing:.3px;color:var(--muted);text-decoration:none;padding:10px 14px 12px;border:0;border-bottom:3px solid transparent;border-radius:0;background:transparent;white-space:nowrap;font-weight:600}
.dashboard-command-center .nav a:hover{color:var(--ink)}
.dashboard-command-center .nav a.active{color:var(--turf);border-bottom-color:var(--turf);background:transparent}
.dashboard-command-center .panel{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);padding:18px 20px;box-shadow:none;margin-bottom:16px;color:var(--ink)}
.dashboard-command-center .command-hero{background:var(--surface);border-color:var(--line);border-top:3px solid var(--turf-deep)}
.dashboard-command-center .command-kicker,.dashboard-command-center .eyebrow{font-size:11px;text-transform:uppercase;letter-spacing:.08em;color:var(--turf-deep);font-weight:700}
.dashboard-command-center .command-head,.dashboard-command-center .section-head,.dashboard-command-center .decision-strip{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;flex-wrap:wrap}
.dashboard-command-center .command-title{font-family:var(--font-display);font-weight:600;font-size:34px;line-height:1;margin:5px 0 6px;letter-spacing:.2px;color:var(--ink)}
.dashboard-command-center .command-copy,.dashboard-command-center .meta{color:var(--muted);margin:0;max-width:70ch;font-size:13px}
.dashboard-command-center .command-summary{display:flex;gap:7px;flex-wrap:wrap;margin-top:16px}
.dashboard-command-center .summary-chip{display:inline-flex;align-items:center;padding:4px 9px;border:1px solid var(--line);border-radius:20px;background:var(--surface-2);color:var(--muted);font-size:11px;font-weight:700}
.dashboard-command-center h2{font-family:var(--font-display);font-size:27px;font-weight:600;line-height:1.05;margin:5px 0 5px;color:var(--ink);letter-spacing:.2px}
.dashboard-command-center .status{display:inline-flex;align-items:center;justify-content:center;font-size:10px;font-weight:800;padding:4px 9px;border-radius:20px;white-space:nowrap;border:1px solid currentColor;background:transparent;letter-spacing:.03em}
.dashboard-command-center .good{color:var(--turf-deep)}
.dashboard-command-center .warn{color:#8A6319}
.dashboard-command-center .danger{color:var(--brick)}
.dashboard-command-center .done{color:var(--muted);border-color:var(--line);background:var(--surface-2)}
.dashboard-command-center .priority-panel{border-color:var(--line)}
.dashboard-command-center .priority-stack{display:grid;gap:10px;margin-top:16px}
.dashboard-command-center .priority-card{display:grid;grid-template-columns:42px minmax(0,1fr) auto;gap:14px;align-items:flex-start;padding:16px 16px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface)}
.dashboard-command-center .priority-card.primary{border-left:4px solid var(--turf);background:color-mix(in srgb,var(--turf) 5%,var(--surface))}
.dashboard-command-center .priority-index{width:34px;height:34px;display:flex;align-items:center;justify-content:center;border-radius:var(--radius);background:var(--turf);color:#fff;font-family:var(--font-display);font-size:16px;font-weight:600;letter-spacing:.04em}
.dashboard-command-center .priority-type{font-size:10px;text-transform:uppercase;letter-spacing:.09em;color:var(--turf-deep);font-weight:800}
.dashboard-command-center .priority-title{font-size:16px;font-weight:750;margin-top:3px;color:var(--ink)}
.dashboard-command-center .priority-copy{margin-top:5px;color:var(--muted);font-size:13px;max-width:780px}
.dashboard-command-center .priority-move{display:grid;grid-template-columns:1fr 1fr;gap:9px;margin-top:12px}
.dashboard-command-center .priority-player{padding:11px 12px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface-2)}
.dashboard-command-center .priority-player.add{border-left:3px solid var(--turf)}
.dashboard-command-center .priority-player.drop{border-left:3px solid var(--brick)}
.dashboard-command-center .priority-player strong{display:block;font-size:10px;letter-spacing:.08em;color:var(--muted)}
.dashboard-command-center .priority-player span{display:block;margin-top:3px;font-size:14px;font-weight:750;color:var(--ink)}
.dashboard-command-center .priority-player small{display:block;margin-top:2px;color:var(--muted)}
.dashboard-command-center .priority-actions,.dashboard-command-center .manager-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:13px}
.dashboard-command-center .command-button{display:inline-flex;align-items:center;justify-content:center;text-decoration:none;padding:8px 13px;border-radius:var(--radius);font-size:12px;font-weight:700;border:1px solid var(--turf);background:var(--turf);color:#fff}
.dashboard-command-center .command-button:hover{background:var(--turf-deep);border-color:var(--turf-deep)}
.dashboard-command-center .command-button.secondary{background:transparent;color:var(--turf-deep);border-color:var(--turf)}
.dashboard-command-center .command-button.secondary:hover{background:color-mix(in srgb,var(--turf) 9%,transparent)}
.dashboard-command-center .evidence-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-top:15px}
.dashboard-command-center .evidence-card{padding:14px 15px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface)}
.dashboard-command-center .evidence-card strong{display:block;color:var(--muted);font-size:10px;text-transform:uppercase;letter-spacing:.07em}
.dashboard-command-center .evidence-card .evidence-value{font-family:var(--font-display);font-size:20px;font-weight:600;line-height:1.05;margin-top:5px;color:var(--turf-deep)}
.dashboard-command-center .evidence-card .evidence-note{font-size:11px;color:var(--muted);margin-top:5px;line-height:1.45}
.dashboard-command-center .why-card{margin-top:12px;padding:14px 15px;border:1px solid var(--line);border-left:4px solid var(--turf);border-radius:var(--radius);background:var(--surface-2);color:var(--ink);font-size:13px}
.dashboard-command-center .decision-strip strong{display:block;color:var(--ink)}
.dashboard-command-center .decision-strip span{display:block;color:var(--muted);font-size:12px;margin-top:3px;max-width:80ch}
.dashboard-command-center .boundary{font-size:12px;color:var(--muted);background:var(--surface);border-style:dashed}
.dashboard-command-center .lock{font-weight:700;color:var(--turf-deep)}
.dashboard-command-center details{border-top:1px solid var(--line)}
.dashboard-command-center summary{color:var(--turf-deep)}
@media(prefers-color-scheme:dark){body.dashboard-page{--bg:#101A14;--surface:#16221A;--surface-2:#1C2B21;--line:#2C3D31;--turf:#4FA571;--turf-deep:#77B58B;--gold:#E0AB4A;--ink:#EEF1EA;--muted:#97A596;--brick:#D97A63}}
@media(max-width:960px){.dashboard-command-center .evidence-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.dashboard-command-center .priority-card{grid-template-columns:42px minmax(0,1fr)}.dashboard-command-center .priority-card>.status{grid-column:2;justify-self:start}}
@media(max-width:760px){.dashboard-command-center{padding:14px 14px 44px}.dashboard-command-center .top,.dashboard-command-center .command-head,.dashboard-command-center .section-head,.dashboard-command-center .decision-strip{display:block}.dashboard-command-center .top{padding:18px 18px 12px}.dashboard-command-center .target{text-align:left;margin-top:8px}.dashboard-command-center .brand h1{font-size:36px}.dashboard-command-center .nav{padding:0 8px;margin-bottom:22px}.dashboard-command-center .nav a{font-size:17px;padding:9px 10px 10px}.dashboard-command-center .panel{padding:15px 14px}.dashboard-command-center .command-title{font-size:30px}.dashboard-command-center .status{margin-top:9px}.dashboard-command-center .evidence-grid,.dashboard-command-center .priority-move{grid-template-columns:1fr}.dashboard-command-center .priority-card{grid-template-columns:36px minmax(0,1fr);padding:14px}.dashboard-command-center .priority-card>.status{grid-column:2}.dashboard-command-center .manager-actions{margin-top:12px}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $dashboardCss.TrimEnd() + $cssBlock.Substring($cssTerminator)
$text = $text.Substring(0, $cssStart) + $cssBlock + $text.Substring($cssEnd)

$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-832 BLOCKED: dashboard renderer function boundary is missing.'
}
$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)

$bodyAnchor = '<body><main class="shell">'
$bodyMatches = [regex]::Matches($dashboardBlock, [regex]::Escape($bodyAnchor)).Count
if ($bodyMatches -ne 1) {
    throw "BF-832 BLOCKED: Dashboard body scope contract expected one match, found $bodyMatches."
}
$dashboardBlock = $dashboardBlock.Replace($bodyAnchor, '<body class="dashboard-page"><main class="shell dashboard-command-center">')
$dashboardBlock = $dashboardBlock.Replace('Trade Lab', 'Trade Analyzer')
$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

$headerStart = $text.IndexOf('function Get-HeaderHtml {', [System.StringComparison]::Ordinal)
$headerEnd = $text.IndexOf('function ConvertTo-DashboardHtml {', $headerStart, [System.StringComparison]::Ordinal)
if ($headerStart -lt 0 -or $headerEnd -le $headerStart) {
    throw 'BF-832 BLOCKED: dashboard header function boundary is missing.'
}
$headerBlock = $text.Substring($headerStart, $headerEnd - $headerStart)
$tradeNavOld = 'href="/trade">Trade Lab</a>'
$tradeNavMatches = [regex]::Matches($headerBlock, [regex]::Escape($tradeNavOld)).Count
if ($tradeNavMatches -ne 1) {
    throw "BF-832 BLOCKED: Trade Analyzer navigation contract expected one match, found $tradeNavMatches."
}
$headerBlock = $headerBlock.Replace($tradeNavOld, 'href="/trade">Trade Analyzer</a>')
$text = $text.Substring(0, $headerStart) + $headerBlock + $text.Substring($headerEnd)

foreach ($required in @(
    'body class="dashboard-page"',
    'shell dashboard-command-center',
    '--bg:#F4F2EA',
    '--surface:#FFFFFF',
    '--surface-2:#ECE9DD',
    '--line:#D8D4C4',
    '--turf:#2E6B47',
    '--turf-deep:#1F4D33',
    '--gold:#C98A1F',
    '--ink:#16201A',
    '--muted:#5B6459',
    '--brick:#A8452F',
    "--font-display:'Teko'",
    'repeating-linear-gradient',
    '@media(prefers-color-scheme:dark)',
    'href="/trade">Trade Analyzer</a>',
    'What matters now',
    'Your decision queue'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-832 BLOCKED: required Dashboard visual marker is missing: $required"
    }
}

if ($dashboardBlock -match 'https://api\.sleeper\.app|Invoke-RestMethod|Invoke-WebRequest|Method = "POST"|submitTransaction|AutoFillLineupOptimizer') {
    throw 'BF-832 BLOCKED: Dashboard visual alignment introduced provider, optimizer, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$parseTokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$parseTokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $messages = (@($parseErrors) | ForEach-Object { $_.Message }) -join '; '
    throw "BF-832 BLOCKED: generated Dashboard failed PowerShell parse: $messages"
}

# BF-833: refine the Dashboard visual language only after BF-832 has installed the
# command-center structure and visual scope. This is a presentation-only override.
$bf833Transform = Join-Path $PSScriptRoot 'butler-dashboard-bf833-visual-language-refinement-transform.ps1'
if (-not (Test-Path -LiteralPath $bf833Transform -PathType Leaf)) {
    throw "BF-833 BLOCKED: visual-language refinement transform not found at $bf833Transform"
}
& $bf833Transform -DashboardPath $DashboardPath
