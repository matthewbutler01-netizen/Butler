param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-829 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-FunctionBlock {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$StartMarker,
        [Parameter(Mandatory = $true)][string]$NextMarker,
        [Parameter(Mandatory = $true)][string]$Replacement
    )

    $start = $Text.IndexOf($StartMarker, [System.StringComparison]::Ordinal)
    if ($start -lt 0) {
        throw "BF-829 BLOCKED: app CSS start marker is missing."
    }
    if ($Text.IndexOf($StartMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal) -ge 0) {
        throw "BF-829 BLOCKED: app CSS start marker is ambiguous."
    }
    $next = $Text.IndexOf($NextMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($next -lt 0) {
        throw "BF-829 BLOCKED: app navigation marker is missing."
    }

    return $Text.Substring(0, $start) + $Replacement.TrimEnd() + "`r`n`r`n" + $Text.Substring($next)
}

$core = [System.IO.File]::ReadAllText($CorePath)

# BF-830: the uploaded command-center prototype is now the visual source of truth.
# This remains a presentation-only transform; generated HTML and governed decision logic stay intact.
$cssReplacement = @"
function Get-AppCss {
    return @'
@import url('https://fonts.googleapis.com/css2?family=Teko:wght@400;500;600;700&family=Inter:wght@400;500;600;700&display=swap');
:root{font-family:'Inter',Segoe UI,Arial,sans-serif;color:#16201A;background:#F4F2EA;line-height:1.45;--bg:#F4F2EA;--surface:#FFFFFF;--surface-2:#ECE9DD;--line:#D8D4C4;--turf:#2E6B47;--turf-deep:#1F4D33;--gold:#C98A1F;--ink:#16201A;--muted:#5B6459;--brick:#A8452F;--font-display:'Teko',Impact,'Arial Narrow',sans-serif;--font-body:'Inter',Segoe UI,Arial,sans-serif;--radius:3px;--shadow:0 12px 30px rgba(22,32,26,.06)}@media(prefers-color-scheme:dark){:root{--bg:#101a14;--surface:#16221a;--surface-2:#1c2b21;--line:#2c3d31;--turf:#4fa571;--turf-deep:#2e6b47;--gold:#e0ab4a;--ink:#eef1ea;--muted:#97a596;--brick:#d97a63;--shadow:none}}*{box-sizing:border-box}html{background:var(--bg)}body{margin:0;min-height:100vh;background-color:var(--bg);background-image:repeating-linear-gradient(to bottom,transparent,transparent 79px,var(--line) 79px,var(--line) 80px);color:var(--ink);font-family:var(--font-body);-webkit-font-smoothing:antialiased}a{color:inherit}.shell{max-width:1180px;margin:0 auto;padding:22px 28px 80px}.top{display:flex;justify-content:space-between;gap:24px;align-items:baseline;flex-wrap:wrap;background:var(--surface);padding:22px 28px 16px;border:1px solid var(--line);border-bottom:3px solid var(--turf-deep);border-radius:var(--radius) var(--radius) 0 0}.brand h1{font-family:var(--font-display);font-weight:600;font-size:42px;letter-spacing:.5px;line-height:1;margin:0;color:var(--ink)}.brand p{margin:5px 0 0;color:var(--muted);font-size:12px}.target{font-size:13px;color:var(--muted);text-align:right}.nav{display:flex;gap:2px;margin:0 0 28px;overflow-x:auto;padding:0 14px;background:var(--surface);border:1px solid var(--line);border-top:0;border-radius:0 0 var(--radius) var(--radius)}.nav a{font-family:var(--font-display);font-size:19px;letter-spacing:.3px;color:var(--muted);text-decoration:none;padding:10px 14px 12px;border-bottom:3px solid transparent;white-space:nowrap}.nav a:hover{color:var(--ink)}.nav a.active{color:var(--turf);border-bottom-color:var(--turf)}.panel{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);padding:18px 20px;box-shadow:none;margin-bottom:16px}.hero-panel{background:var(--surface);border-top:3px solid var(--turf-deep)}.recommendation-panel{border-left:4px solid var(--turf);background:var(--surface)}.section-head,.statusrow,.manager-head{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;flex-wrap:wrap}.eyebrow{font-size:11px;text-transform:uppercase;letter-spacing:.08em;color:var(--turf-deep);font-weight:700}.headline{font-family:var(--font-display);font-weight:600;font-size:32px;line-height:1;margin:4px 0 6px;letter-spacing:.2px}.lede{color:var(--muted);margin:0;max-width:62ch;font-size:14px}.status{font-size:11px;color:var(--turf-deep);background:color-mix(in srgb,var(--turf) 14%,transparent);border:1px solid color-mix(in srgb,var(--turf) 40%,transparent);border-radius:20px;padding:3px 10px;white-space:nowrap;font-weight:700}.good{color:var(--turf-deep)}.warn{color:var(--gold);background:color-mix(in srgb,var(--gold) 14%,transparent);border-color:color-mix(in srgb,var(--gold) 42%,transparent)}.danger{color:var(--brick);background:color-mix(in srgb,var(--brick) 12%,transparent);border-color:color-mix(in srgb,var(--brick) 38%,transparent)}.done{color:var(--muted);background:var(--surface-2);border-color:var(--line)}.stats,.manager-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-top:18px}.stat,.metric-card{padding:14px 15px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface)}.stat strong,.metric-label{display:block;color:var(--muted);font-size:11px;font-weight:600}.stat span,.metric-value{display:block;font-family:var(--font-display);font-size:28px;font-weight:600;line-height:1;margin-top:5px;color:var(--turf-deep)}.metric-positive{color:var(--turf)}.metric-negative{color:var(--brick)}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-top:16px}.grid.four{grid-template-columns:repeat(4,minmax(0,1fr))}.card,.action,.summary-card,.movement-box{padding:16px 18px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface)}.card .rank{color:var(--muted);font-size:11px;font-weight:600;text-transform:uppercase}.card .name{font-size:15px;font-weight:700;margin-top:4px}.card .total{font-family:var(--font-display);font-size:30px;line-height:1;color:var(--turf-deep);margin-top:8px}.card .meta,.meta{color:var(--muted);font-size:12px;margin-top:6px}.pressure-tier{font-family:var(--font-display);font-size:28px;line-height:1;color:var(--turf-deep);margin-top:7px}.position-card{min-height:112px}.movers,.actions,.season-list{display:grid;gap:0;margin-top:12px}.mover,.season-row{padding:10px 2px;border:0;border-bottom:1px solid var(--line);border-radius:0;background:transparent;font-size:13px;color:var(--ink)}.action-head{display:flex;gap:7px;align-items:center;flex-wrap:wrap}.pill,.chip,.state-chip,.decision-chip,.position-chip{display:inline-flex;align-items:center;justify-content:center;font-size:11px;font-weight:700}.pill,.chip{padding:4px 9px;border-radius:20px;border:1px solid var(--line);background:var(--surface);color:var(--muted)}.required{color:var(--gold)}.optional{color:var(--muted)}.action .kind{font-weight:700;color:var(--ink)}.action p{margin:7px 0 0;color:var(--muted);font-size:13px}.command{display:block;width:100%;margin-top:10px;padding:10px 12px;border-radius:var(--radius);background:var(--surface-2);border:1px solid var(--line);color:var(--ink);font-family:Consolas,monospace;font-size:12px}.empty,.callout{margin-top:12px;padding:14px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface-2);color:var(--muted)}.callout-danger{border-color:color-mix(in srgb,var(--brick) 45%,var(--line));background:color-mix(in srgb,var(--brick) 9%,var(--surface));color:var(--brick)}.boundary{font-size:12px;color:var(--muted);background:var(--surface);box-shadow:none;border-style:dashed}.lock{font-weight:700;color:var(--turf-deep)}.btn{display:inline-flex;align-items:center;justify-content:center;padding:8px 14px;border-radius:var(--radius);text-decoration:none;font-weight:600;font-size:13px;border:1px solid var(--turf);cursor:pointer}.btn-primary{background:var(--turf);color:#fff}.btn-primary:hover{background:var(--turf-deep);border-color:var(--turf-deep)}.btn-secondary{background:transparent;color:var(--turf-deep);border-color:var(--turf)}.btn-secondary:hover{background:color-mix(in srgb,var(--turf) 9%,transparent)}.button-row{display:flex;gap:8px;flex-wrap:wrap;margin-top:14px}.manager-summary{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(280px,.7fr);gap:14px;margin-top:16px}.summary-card h3{font-family:var(--font-display);font-size:20px;font-weight:600;line-height:1;margin:0 0 4px}.summary-card p{margin:0;color:var(--muted);font-size:13px}.roster-board{margin-top:16px;border:1px solid var(--line);border-radius:var(--radius);overflow:hidden;background:var(--surface)}.roster-group+.roster-group{border-top:2px solid var(--line)}.roster-group-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 12px;background:var(--surface-2)}.roster-group-head h3{margin:0;font-family:var(--font-display);font-size:17px;font-weight:600;letter-spacing:.2px}.roster-group-head span{font-size:11px;color:var(--muted)}.player-row{display:grid;grid-template-columns:90px minmax(0,1fr) 96px;gap:12px;align-items:center;padding:10px 12px;border-top:1px solid var(--line);background:var(--surface)}.player-row:first-child{border-top:0}.player-row:hover{background:var(--surface-2)}.slot-marker{font-family:var(--font-display);font-size:14px;font-weight:600;letter-spacing:.2px;color:var(--muted)}.player-primary strong{display:block;font-size:13px}.player-primary span{display:block;color:var(--muted);font-size:11px;margin-top:2px}.state-chip{justify-self:end;padding:2px 8px;border-radius:10px}.state-start{background:color-mix(in srgb,var(--turf) 18%,transparent);color:var(--turf-deep)}.state-bench{background:var(--surface-2);color:var(--muted)}.state-reserve{background:color-mix(in srgb,var(--gold) 16%,transparent);color:var(--gold)}.autofill-summary{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:16px}.lineup-board{margin-top:16px;border:1px solid var(--line);border-radius:var(--radius);overflow:hidden}.lineup-row{display:grid;grid-template-columns:90px minmax(0,1fr) 34px minmax(0,1fr) 84px 86px;gap:10px;align-items:center;padding:10px 12px;border-top:1px solid var(--line);background:var(--surface)}.lineup-row:first-child{border-top:0}.lineup-row.changed{background:color-mix(in srgb,var(--gold) 10%,var(--surface));border-left:3px solid var(--gold)}.position-chip{padding:1px 7px;border-radius:3px;background:var(--turf);color:#fff;font-family:var(--font-display);font-size:13px}.lineup-choice small{display:block;color:var(--muted);font-size:10px;font-weight:600}.lineup-choice strong{display:block;margin-top:2px;font-size:13px}.lineup-arrow{text-align:center;color:var(--muted);font-size:17px}.projection{text-align:right;font-family:var(--font-display);font-size:20px;line-height:1;color:var(--turf-deep)}.projection span{display:block;color:var(--muted);font-family:var(--font-body);font-size:9px;font-weight:600;text-transform:uppercase}.decision-chip{justify-self:end;padding:2px 8px;border-radius:10px}.decision-keep{background:color-mix(in srgb,var(--turf) 18%,transparent);color:var(--turf-deep)}.decision-change{background:color-mix(in srgb,var(--gold) 18%,transparent);color:var(--gold)}.movement-strip{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:12px}.movement-box strong{display:block;font-family:var(--font-display);font-size:16px;font-weight:600;color:var(--ink)}.movement-box div{display:flex;gap:6px;flex-wrap:wrap;margin-top:7px}.source-note{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:14px;padding-top:12px;border-top:1px solid var(--line);color:var(--muted);font-size:11px}details{margin-top:12px;border-top:1px solid var(--line);padding-top:10px}summary{cursor:pointer;color:var(--turf-deep);font-weight:700;font-size:12px}.technical{margin-top:8px;color:var(--muted);font-size:11px;line-height:1.55}@media(max-width:960px){.grid.four{grid-template-columns:repeat(2,minmax(0,1fr))}.stats,.manager-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.lineup-row{grid-template-columns:72px minmax(0,1fr) 28px minmax(0,1fr) 70px}.decision-chip{grid-column:2/6;justify-self:start}}@media(max-width:760px){.shell{padding:14px 14px 44px}.top,.statusrow,.manager-head,.source-note{display:block}.top{padding:18px 18px 12px}.target{text-align:left;margin-top:8px}.brand h1{font-size:36px}.nav{padding:0 8px;margin-bottom:22px}.nav a{font-size:17px;padding:9px 10px 10px}.panel{padding:15px 14px}.headline{font-size:29px}.status{display:inline-flex;margin-top:10px}.stats,.manager-metrics,.grid,.grid.four,.manager-summary,.autofill-summary,.movement-strip{grid-template-columns:1fr}.player-row{grid-template-columns:70px minmax(0,1fr) 80px}.lineup-row{grid-template-columns:66px minmax(0,1fr) 70px;padding:10px}.lineup-arrow{display:none}.lineup-choice.current{grid-column:2}.lineup-choice.recommended{grid-column:2}.projection{grid-column:3;grid-row:1/3}.decision-chip{grid-column:2/4}.source-note .button-row{margin-top:8px}}
'@
}
"@

$core = Replace-FunctionBlock -Text $core -StartMarker 'function Get-AppCss {' -NextMarker 'function Get-AppNav {' -Replacement $cssReplacement

foreach ($required in @(
    '--bg:#F4F2EA',
    '--surface:#FFFFFF',
    '--turf:#2E6B47',
    '--turf-deep:#1F4D33',
    '--gold:#C98A1F',
    '--brick:#A8452F',
    "--font-display:'Teko'",
    "--font-body:'Inter'",
    'background-image:repeating-linear-gradient',
    '.nav a.active{color:var(--turf);border-bottom-color:var(--turf)',
    '.panel{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius)',
    '@media(prefers-color-scheme:dark)',
    '@media(max-width:760px)'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-830 BLOCKED: required command-center visual marker is missing: $required"
    }
}

if ($cssReplacement -match 'Invoke-RestMethod|Invoke-WebRequest|Method = "POST"|https://api\.|SleeperClient|FantasyPros') {
    throw 'BF-830 BLOCKED: command-center visual alignment introduced provider, API, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
