param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-837 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)
$cssStart = $text.IndexOf('function Get-SharedCss {', [System.StringComparison]::Ordinal)
$cssEnd = $text.IndexOf('function Get-HeaderHtml {', $cssStart, [System.StringComparison]::Ordinal)
if ($cssStart -lt 0 -or $cssEnd -le $cssStart) {
    throw 'BF-837 BLOCKED: dashboard shared CSS function boundary is missing.'
}

$marker = 'BF-837 dashboard-hosted manager visual alignment'
if (-not $text.Contains($marker)) {
    $cssBlock = $text.Substring($cssStart, $cssEnd - $cssStart)
    $cssTerminator = $cssBlock.LastIndexOf("`n'@", [System.StringComparison]::Ordinal)
    if ($cssTerminator -lt 0) {
        throw 'BF-837 BLOCKED: dashboard shared CSS terminator is missing.'
    }

    $managerCss = @'
/* BF-837 dashboard-hosted manager visual alignment: BF-833 language for Waiver Board and shared dashboard chrome. */
:root{--bg:#F3F2EE;--surface:#FFFFFF;--surface-2:#F7F6F2;--line:#D9DCD7;--turf:#376E50;--turf-deep:#28543D;--gold:#A77418;--ink:#1E2521;--muted:#68726B;--brick:#A65245;--radius:10px;--shadow:0 8px 24px rgba(25,35,29,.04)}
html{background:var(--bg)}body{font-family:Inter,Segoe UI,Arial,sans-serif;color:var(--ink);background:#F3F2EE!important;background-image:none!important}.shell{max-width:1160px;padding:28px 28px 72px}
.top{align-items:flex-end;padding:24px 26px 18px;border:1px solid var(--line);border-bottom:1px solid var(--line);border-radius:14px 14px 0 0;background:var(--surface);box-shadow:0 8px 28px rgba(25,35,29,.05);margin-bottom:0}.brand h1{font-family:Inter,Segoe UI,Arial,sans-serif;font-size:30px;font-weight:800;letter-spacing:.16em;line-height:1.1}.brand p{margin-top:6px;color:var(--muted);font-size:13px;line-height:1.5}.target{font-size:12px;color:var(--muted)}
.nav{gap:4px;padding:8px 12px 9px;margin:0 0 24px;border:1px solid var(--line);border-top:0;border-radius:0 0 14px 14px;background:var(--surface);box-shadow:0 8px 28px rgba(25,35,29,.05)}.nav a{font-family:Inter,Segoe UI,Arial,sans-serif;color:var(--muted);font-size:13px;font-weight:700;letter-spacing:0;text-transform:none;padding:9px 12px;border:1px solid transparent;border-radius:8px;background:transparent}.nav a:hover{color:var(--ink);background:var(--surface-2)}.nav a.active{color:var(--turf-deep);background:#26352C;border-color:#35483C}
.panel{background:var(--surface);border-color:var(--line);border-radius:12px;padding:22px 24px;box-shadow:var(--shadow);margin-bottom:18px}.eyebrow{color:var(--turf);font-size:10px;letter-spacing:.1em;font-weight:800}.headline{font-family:Inter,Segoe UI,Arial,sans-serif;font-size:30px;font-weight:800;line-height:1.15;letter-spacing:-.02em;text-transform:none}.lede,.subtle,.boundary,.roster-note,.board-disclaimer{color:var(--muted)}.status{border-radius:999px;padding:5px 9px;font-size:10px;letter-spacing:.04em}.good{color:var(--turf)}.warn{color:var(--gold)}.danger{color:var(--brick)}.done{color:var(--muted)}
.board-stat,.candidate-card,.roster-card,.verify,.fresh,.refresh-step,.next{background:var(--surface-2);border-color:var(--line);border-radius:10px}.board-stat span,.candidate-card .meta,.roster-card .meta,.candidate-facts div,.candidate-facts strong,.market,.market strong,.position-count,.verify small,.fresh strong,.fresh .limit,.lineage-copy span,.refresh-copy-hint,.refresh-purpose{color:var(--muted)}.candidate-card .name,.roster-card .name,.board-stat strong,.fresh .age{color:var(--ink)}
.board-note{border-color:color-mix(in srgb,var(--gold) 45%,var(--line));background:color-mix(in srgb,var(--gold) 10%,var(--surface));color:var(--gold);border-radius:10px}.button{background:var(--turf);color:#fff;border-radius:8px}.button:hover{background:var(--turf-deep)}.current-marker{background:#26352C;color:#A8D3B5}.current-copy,.refresh-step-bf{color:var(--turf-deep)}.current-context,.pair-context{border-color:var(--line);background:var(--surface-2);color:var(--ink)}.pair-context strong{color:var(--ink)}.pair-context .pair-meta{color:var(--muted)}.lane.neutral{background:var(--surface);color:var(--muted)}.market{border-color:var(--line)}details{border-color:var(--line)}summary{color:var(--ink)}.tech{color:var(--muted)}
@media(prefers-color-scheme:dark){:root{--bg:#111315;--surface:#191C1E;--surface-2:#202426;--line:#303639;--turf:#69A27D;--turf-deep:#8CBC9A;--gold:#D4A64B;--ink:#F1F3F1;--muted:#A6AFA9;--brick:#D47A6B;--shadow:none}html,body{background:#111315!important;background-image:none!important;color:#F1F3F1}.top,.nav,.panel{box-shadow:none;background:#191C1E}.nav a.active{color:#A8D3B5;background:#26352C;border-color:#35483C}.board-stat,.candidate-card,.roster-card,.verify,.fresh,.refresh-step,.next{background:#202426}.board-note{background:#2A2418}.current-context,.pair-context{background:#202426}}
@media(max-width:760px){.shell{padding:14px 14px 44px}.top{padding:20px 18px 15px}.brand h1{font-size:26px}.nav{padding:7px 8px}.nav a{font-size:12px;padding:8px 9px}.panel{padding:18px 16px}.headline{font-size:27px}}
'@

    $cssBlock = $cssBlock.Substring(0, $cssTerminator) + "`n" + $managerCss.TrimEnd() + $cssBlock.Substring($cssTerminator)
    $text = $text.Substring(0, $cssStart) + $cssBlock + $text.Substring($cssEnd)
}

$waiverStart = $text.IndexOf('function ConvertTo-WaiverHtml {', [System.StringComparison]::Ordinal)
$waiverEnd = $text.IndexOf('function ConvertTo-WaiverCandidateDetailHtml {', $waiverStart, [System.StringComparison]::Ordinal)
if ($waiverStart -lt 0 -or $waiverEnd -le $waiverStart) {
    throw 'BF-837 BLOCKED: Waiver Board renderer function boundary is missing.'
}
$waiverBlock = $text.Substring($waiverStart, $waiverEnd - $waiverStart)
$waiverMarker = 'BF-837 Waiver Board final visual override'
if (-not $waiverBlock.Contains($waiverMarker)) {
    $styleEnd = $waiverBlock.LastIndexOf('</style>', [System.StringComparison]::Ordinal)
    if ($styleEnd -lt 0) {
        throw 'BF-837 BLOCKED: Waiver Board style terminator is missing.'
    }
    $waiverCss = @'
/* BF-837 Waiver Board final visual override: wins after BF-834 page-local styles. */
.waiver-decision-hero{border-color:var(--line)!important;background:var(--surface)!important}.waiver-state-line,.waiver-action-label,.waiver-action-meta,.waiver-pair-note{color:var(--muted)!important}.waiver-action-card{border-color:var(--line)!important;background:var(--surface-2)!important;border-radius:10px!important}.waiver-action-card.add{border-left:3px solid var(--turf)!important}.waiver-action-card.drop{border-left:3px solid var(--brick)!important}.waiver-next{border-color:var(--line)!important;background:var(--surface-2)!important;border-radius:10px!important}.waiver-next p{color:var(--muted)!important}.waiver-board-head .lede{color:var(--muted)!important}
'@
    $waiverBlock = $waiverBlock.Insert($styleEnd, $waiverCss.TrimEnd() + "`n")
    $text = $text.Substring(0, $waiverStart) + $waiverBlock + $text.Substring($waiverEnd)
}

foreach ($required in @(
    'BF-837 dashboard-hosted manager visual alignment',
    'BF-837 Waiver Board final visual override',
    '--bg:#F3F2EE',
    '--surface:#191C1E',
    'background-image:none',
    '.candidate-card',
    '.waiver-action-card'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-837 BLOCKED: required dashboard-hosted visual marker is missing: $required"
    }
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-837 BLOCKED: generated staged dashboard failed PowerShell parse: $parseSummary"
}
