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

$cssBlock = $text.Substring($cssStart, $cssEnd - $cssStart)
$cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
if ($cssTerminator -lt 0) {
    throw 'BF-833 BLOCKED: shared dashboard CSS terminator is missing.'
}

$refinementCss = @'
/* BF-833 final Dashboard visual refinement: calmer product UI, same structure and semantics. */
body.dashboard-page{--bg:#F3F2EE;--surface:#FFFFFF;--surface-2:#F7F6F2;--line:#D9DCD7;--turf:#376E50;--turf-deep:#28543D;--gold:#A77418;--ink:#1E2521;--muted:#68726B;--brick:#A65245;--font-display:'Inter',Segoe UI,Arial,sans-serif;--font-body:'Inter',Segoe UI,Arial,sans-serif;--radius:10px;background:var(--bg);background-image:none}
.dashboard-command-center{max-width:1160px;padding-top:28px}
.dashboard-command-center .top{padding:24px 26px 18px;border-bottom:1px solid var(--line);border-radius:14px 14px 0 0;box-shadow:0 8px 28px rgba(25,35,29,.05)}
.dashboard-command-center .brand h1{font-family:var(--font-display);font-size:30px;font-weight:800;letter-spacing:.16em;line-height:1.1}
.dashboard-command-center .brand p{font-size:13px;line-height:1.5}
.dashboard-command-center .nav{gap:4px;padding:8px 12px 9px;margin-bottom:24px;border-radius:0 0 14px 14px;box-shadow:0 8px 28px rgba(25,35,29,.05)}
.dashboard-command-center .nav a{font-family:var(--font-body);font-size:13px;font-weight:700;letter-spacing:0;padding:9px 12px;border:1px solid transparent;border-radius:8px}
.dashboard-command-center .nav a:hover{background:var(--surface-2)}
.dashboard-command-center .nav a.active{color:var(--turf-deep);background:color-mix(in srgb,var(--turf) 10%,var(--surface));border-color:color-mix(in srgb,var(--turf) 20%,var(--line));border-bottom-color:color-mix(in srgb,var(--turf) 20%,var(--line))}
.dashboard-command-center .panel{padding:22px 24px;border-radius:12px;margin-bottom:18px;box-shadow:0 8px 24px rgba(25,35,29,.04)}
.dashboard-command-center .command-hero{border-top:1px solid var(--line)}
.dashboard-command-center .command-kicker,.dashboard-command-center .eyebrow{font-size:10px;letter-spacing:.12em;color:var(--muted)}
.dashboard-command-center .command-title{font-family:var(--font-display);font-size:30px;font-weight:800;line-height:1.15;letter-spacing:-.02em;margin:7px 0 7px}
.dashboard-command-center h2{font-family:var(--font-display);font-size:23px;font-weight:800;line-height:1.2;letter-spacing:-.01em;margin:7px 0 6px}
.dashboard-command-center .command-copy,.dashboard-command-center .meta{font-size:14px;line-height:1.55}
.dashboard-command-center .command-summary{gap:8px;margin-top:18px}
.dashboard-command-center .summary-chip{background:var(--surface);font-size:11px;padding:5px 9px}
.dashboard-command-center .status{font-size:10px;font-weight:800;padding:5px 9px;background:var(--surface);border-color:var(--line)}
.dashboard-command-center .good{color:var(--turf)}
.dashboard-command-center .warn{color:var(--gold)}
.dashboard-command-center .danger{color:var(--brick)}
.dashboard-command-center .done{color:var(--muted);background:var(--surface-2)}
.dashboard-command-center .priority-stack{gap:12px;margin-top:18px}
.dashboard-command-center .priority-card,.dashboard-command-center article.bf833-priority-card{grid-template-columns:40px minmax(0,1fr) auto;gap:16px;padding:18px 19px;border-radius:10px;background:#F7F6F2!important;border-color:#D9DCD7!important;box-shadow:none!important}
.dashboard-command-center .priority-card.primary,.dashboard-command-center article.bf833-priority-card.primary{background:#FFFFFF!important;border-color:#D9DCD7!important;border-left:3px solid #376E50!important;box-shadow:0 5px 16px rgba(25,35,29,.05)!important}
.dashboard-command-center .priority-index{width:32px;height:32px;border-radius:8px;background:#E7F0EA!important;color:#28543D!important;font-family:var(--font-body);font-size:12px;font-weight:800}
.dashboard-command-center .priority-type{font-size:10px;color:#68726B!important;letter-spacing:.1em}
.dashboard-command-center .priority-title{font-size:17px;font-weight:800;line-height:1.35;margin-top:4px;color:#1E2521!important}
.dashboard-command-center .priority-copy{font-size:13px;line-height:1.55;margin-top:6px;color:#68726B!important}
.dashboard-command-center .priority-card .status,.dashboard-command-center article.bf833-priority-card .status{background:#FFFFFF!important;border-color:#D9DCD7!important}
.dashboard-command-center .priority-player{border-radius:8px;background:#FFFFFF!important;border-color:#D9DCD7!important}
.dashboard-command-center .command-button{border-radius:8px;padding:8px 13px}
.dashboard-command-center .command-button.secondary{background:var(--surface)}
.dashboard-command-center .why-card{border-radius:8px;background:var(--surface-2)}
.dashboard-command-center .evidence-card{border-radius:8px;background:var(--surface-2)}
.dashboard-command-center .evidence-card .evidence-value{font-family:var(--font-body);font-size:18px;font-weight:800;color:var(--ink)}
.dashboard-command-center details{background:var(--surface-2)!important;border:1px solid var(--line)!important;border-radius:8px!important;padding:11px 13px!important}
.dashboard-command-center summary{color:var(--ink)!important}
.dashboard-command-center .boundary{background:transparent;box-shadow:none}
@media(prefers-color-scheme:dark){body.dashboard-page{--bg:#111315;--surface:#191C1E;--surface-2:#202426;--line:#303639;--turf:#69A27D;--turf-deep:#8CBC9A;--gold:#D4A64B;--ink:#F1F3F1;--muted:#A6AFA9;--brick:#D47A6B;background:#111315;background-image:none}.dashboard-command-center .top,.dashboard-command-center .nav,.dashboard-command-center .panel,.dashboard-command-center .priority-card.primary{box-shadow:none}html body.dashboard-page main.dashboard-command-center .priority-stack>article.bf833-priority-card{background:#1B1E20!important;border-color:#303639!important;box-shadow:none!important}html body.dashboard-page main.dashboard-command-center .priority-stack>article.bf833-priority-card.primary{background:#202426!important;border-color:#303639!important;border-left:3px solid #69A27D!important;box-shadow:none!important}html body.dashboard-page main.dashboard-command-center article.bf833-priority-card .priority-index{background:#26352C!important;color:#A8D3B5!important}html body.dashboard-page main.dashboard-command-center article.bf833-priority-card .priority-type{color:#A6AFA9!important}html body.dashboard-page main.dashboard-command-center article.bf833-priority-card .priority-title{color:#F1F3F1!important}html body.dashboard-page main.dashboard-command-center article.bf833-priority-card .priority-copy{color:#A6AFA9!important}html body.dashboard-page main.dashboard-command-center article.bf833-priority-card .status{background:#202426!important;border-color:#303639!important}.dashboard-command-center .nav a.active{background:#222D26;border-color:#33483A}.dashboard-command-center .summary-chip,.dashboard-command-center .status,.dashboard-command-center .command-button.secondary{background:#1D2123}.dashboard-command-center .evidence-card,.dashboard-command-center .why-card,.dashboard-command-center .priority-player,.dashboard-command-center details{background:#202426!important;border-color:#303639!important}}
@media(max-width:760px){.dashboard-command-center{padding-top:14px}.dashboard-command-center .top{padding:20px 18px 15px}.dashboard-command-center .brand h1{font-size:26px}.dashboard-command-center .nav{padding:7px 8px}.dashboard-command-center .nav a{font-size:12px;padding:8px 9px}.dashboard-command-center .panel{padding:18px 16px}.dashboard-command-center .command-title{font-size:27px}.dashboard-command-center h2{font-size:21px}.dashboard-command-center .priority-card{padding:15px 14px;gap:12px}}
'@

$cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $refinementCss.TrimEnd() + $cssBlock.Substring($cssTerminator)
$text = $text.Substring(0, $cssStart) + $cssBlock + $text.Substring($cssEnd)

foreach ($required in @(
    'BF-833 final Dashboard visual refinement',
    "--font-display:'Inter'",
    '--bg:#F3F2EE',
    '--bg:#111315',
    'background-image:none',
    'article.bf833-priority-card',
    'background:#1B1E20!important',
    'background:#202426!important',
    '.dashboard-command-center details',
    '.dashboard-command-center .command-title',
    '.dashboard-command-center .nav a.active'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-833 BLOCKED: required visual refinement marker is missing: $required"
    }
}

$dashboardStart = $text.IndexOf('function ConvertTo-DashboardHtml {', [System.StringComparison]::Ordinal)
$dashboardEnd = $text.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [System.StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-833 BLOCKED: Dashboard renderer function boundary is missing.'
}
$dashboardBlock = $text.Substring($dashboardStart, $dashboardEnd - $dashboardStart)
$cardClassAnchor = '$cardClass = if ($priorityIndex -eq 0) { "priority-card primary" } else { "priority-card" }'
$cardClassMatches = [regex]::Matches($dashboardBlock, [regex]::Escape($cardClassAnchor)).Count
if ($cardClassMatches -ne 1) {
    throw "BF-833 BLOCKED: priority-card class contract expected one match, found $cardClassMatches."
}
$dashboardBlock = $dashboardBlock.Replace(
    $cardClassAnchor,
    '$cardClass = if ($priorityIndex -eq 0) { "priority-card primary bf833-priority-card" } else { "priority-card bf833-priority-card" }'
)
if ($dashboardBlock -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-833 BLOCKED: visual refinement introduced provider, optimizer, FAAB, or write behavior.'
}
$text = $text.Substring(0, $dashboardStart) + $dashboardBlock + $text.Substring($dashboardEnd)

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    $parseSummary = ($parseErrors | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-833 BLOCKED: generated Dashboard failed PowerShell parse: $parseSummary"
}
