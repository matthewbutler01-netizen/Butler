param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-833 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$cssStart = $text.IndexOf('function Get-SharedCss {', [System.StringComparison]::Ordinal)
$cssEnd = $text.IndexOf('function Get-HeaderHtml {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-833 BLOCKED: shared dashboard CSS function boundary is missing.'
}

$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-833 BLOCKED: Dashboard renderer function boundary is missing.'
}
$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)
foreach ($liveClass in @('manager-decision-card','manager-priority-index','manager-kind','manager-card-actions')) {
    if ($dashboardBlock.IndexOf($liveClass, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-833 BLOCKED: live Dashboard manager queue contract is missing: $liveClass"
    }
}

$cssBlock = $text.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-833 BLOCKED: shared dashboard CSS terminator is missing.'
}

$refinementCss = @'
/* BF-833 final Dashboard visual refinement: target the live manager queue contract. */
body.dashboard-page{--bg:#F3F2EE;--surface:#FFFFFF;--surface-2:#F7F6F2;--line:#D9DCD7;--turf:#376E50;--turf-deep:#28543D;--gold:#A77418;--ink:#1E2521;--muted:#68726B;--brick:#A65245;--font-display:'Inter',Segoe UI,Arial,sans-serif;--font-body:'Inter',Segoe UI,Arial,sans-serif;--radius:10px;background:#F3F2EE;background-image:none}
.dashboard-command-center{max-width:1160px;padding-top:28px}
.dashboard-command-center .top{padding:24px 26px 18px;border-bottom:1px solid #D9DCD7;border-radius:14px 14px 0 0;box-shadow:0 8px 28px rgba(25,35,29,.05)}
.dashboard-command-center .brand h1{font-family:var(--font-display);font-size:30px;font-weight:800;letter-spacing:.16em;line-height:1.1}
.dashboard-command-center .brand p{font-size:13px;line-height:1.5}
.dashboard-command-center .nav{gap:4px;padding:8px 12px 9px;margin-bottom:24px;border-radius:0 0 14px 14px;box-shadow:0 8px 28px rgba(25,35,29,.05)}
.dashboard-command-center .nav a{font-family:var(--font-body);font-size:13px;font-weight:700;letter-spacing:0;padding:9px 12px;border:1px solid transparent;border-radius:8px}
.dashboard-command-center .nav a:hover{background:#F7F6F2}
.dashboard-command-center .nav a.active{color:#28543D;background:#EDF3EF;border-color:#D4E0D8}
.dashboard-command-center .panel{padding:22px 24px;border-radius:12px;margin-bottom:18px;box-shadow:0 8px 24px rgba(25,35,29,.04)}
.dashboard-command-center .command-title,.dashboard-command-center h2{font-family:var(--font-display);font-weight:800;letter-spacing:-.02em}
.dashboard-command-center .command-title{font-size:30px;line-height:1.15;margin:7px 0}
.dashboard-command-center h2{font-size:23px;line-height:1.2;margin:7px 0 6px}
.dashboard-command-center .command-meta{display:flex;gap:8px;flex-wrap:wrap;margin-top:18px}
.dashboard-command-center .command-meta span{display:inline-flex;align-items:center;padding:5px 9px;border:1px solid #D9DCD7;border-radius:999px;background:#F7F6F2;color:#68726B;font-size:11px;font-weight:700}
.dashboard-command-center .manager-decision-stack{display:grid;gap:12px;margin-top:18px}
.dashboard-command-center .manager-decision-card{display:grid;grid-template-columns:40px minmax(0,1fr) auto;gap:16px;align-items:flex-start;padding:18px 19px;border:1px solid #D9DCD7!important;border-radius:10px;background:#F7F6F2!important;box-shadow:none!important}
.dashboard-command-center .manager-decision-card.primary{background:#FFFFFF!important;border-color:#D9DCD7!important;border-left:3px solid #376E50!important;box-shadow:0 5px 16px rgba(25,35,29,.05)!important}
.dashboard-command-center .manager-priority-index{width:32px;height:32px;display:flex;align-items:center;justify-content:center;border-radius:8px;background:#E7F0EA!important;color:#28543D!important;font-size:12px;font-weight:800}
.dashboard-command-center .manager-kind{font-size:10px;text-transform:uppercase;letter-spacing:.1em;color:#68726B!important;font-weight:800}
.dashboard-command-center .manager-decision-main h3{margin:4px 0 0;color:#1E2521!important;font-size:17px;line-height:1.35}
.dashboard-command-center .manager-decision-main p{margin:6px 0 0;color:#68726B!important;font-size:13px;line-height:1.55}
.dashboard-command-center .manager-chip-row{display:flex;gap:7px;flex-wrap:wrap;margin-top:11px}
.dashboard-command-center .manager-chip{background:#FFFFFF!important;border-color:#D9DCD7!important;color:#68726B!important}
.dashboard-command-center .manager-chip.ok{color:#376E50!important}.dashboard-command-center .manager-chip.warn{color:#A77418!important}
.dashboard-command-center .manager-card-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:13px}
.dashboard-command-center .manager-decision-card .status{background:#FFFFFF!important;border-color:#D9DCD7!important}
.dashboard-command-center details{background:#F7F6F2!important;border:1px solid #D9DCD7!important;border-radius:8px!important;padding:11px 13px!important}
.dashboard-command-center summary{color:#1E2521!important}
.dashboard-command-center .boundary{background:transparent;box-shadow:none}
@media(prefers-color-scheme:dark){body.dashboard-page{--bg:#111315;--surface:#191C1E;--surface-2:#202426;--line:#303639;--turf:#69A27D;--turf-deep:#8CBC9A;--gold:#D4A64B;--ink:#F1F3F1;--muted:#A6AFA9;--brick:#D47A6B;background:#111315;background-image:none}.dashboard-command-center .top,.dashboard-command-center .nav,.dashboard-command-center .panel{box-shadow:none}.dashboard-command-center .command-meta span{background:#202426;border-color:#303639;color:#A6AFA9}html body.dashboard-page main.dashboard-command-center .manager-decision-stack>article.manager-decision-card{background:#1B1E20!important;border-color:#303639!important;box-shadow:none!important}html body.dashboard-page main.dashboard-command-center .manager-decision-stack>article.manager-decision-card.primary{background:#202426!important;border-color:#303639!important;border-left:3px solid #69A27D!important;box-shadow:none!important}html body.dashboard-page main.dashboard-command-center .manager-priority-index{background:#26352C!important;color:#A8D3B5!important}html body.dashboard-page main.dashboard-command-center .manager-kind{color:#A6AFA9!important}html body.dashboard-page main.dashboard-command-center .manager-decision-main h3{color:#F1F3F1!important}html body.dashboard-page main.dashboard-command-center .manager-decision-main p{color:#A6AFA9!important}html body.dashboard-page main.dashboard-command-center .manager-chip{background:#202426!important;border-color:#303639!important;color:#A6AFA9!important}html body.dashboard-page main.dashboard-command-center .manager-decision-card .status{background:#202426!important;border-color:#303639!important}.dashboard-command-center details{background:#202426!important;border-color:#303639!important}.dashboard-command-center summary{color:#F1F3F1!important}}
@media(max-width:760px){.dashboard-command-center{padding-top:14px}.dashboard-command-center .top{padding:20px 18px 15px}.dashboard-command-center .brand h1{font-size:26px}.dashboard-command-center .nav{padding:7px 8px}.dashboard-command-center .nav a{font-size:12px;padding:8px 9px}.dashboard-command-center .panel{padding:18px 16px}.dashboard-command-center .manager-decision-card{grid-template-columns:36px minmax(0,1fr);padding:15px 14px;gap:12px}.dashboard-command-center .manager-decision-card>.status{grid-column:2;justify-self:start}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $refinementCss.TrimEnd() + $cssBlock.Substring($cssTerminator)
$text = $text.Substring(0, $cssStart) + $cssBlock + $text.Substring($cssEnd)

foreach ($required in @(
    'BF-833 final Dashboard visual refinement',
    "--font-display:'Inter'",
    'manager-decision-card',
    'manager-priority-index',
    'manager-kind',
    'manager-card-actions',
    'background:#1B1E20!important',
    'background:#202426!important',
    'background-image:none'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-833 BLOCKED: required visual refinement marker is missing: $required"
    }
}

if ($dashboardBlock -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-833 BLOCKED: visual refinement introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    $parseSummary = ($parseErrors | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-833 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}
