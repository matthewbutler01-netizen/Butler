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

$cssReplacement = @"
function Get-AppCss {
    return @'
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#1f1d19;background:#eee8de;line-height:1.5;--paper:#f8f4ec;--paper-2:#eee7dc;--paper-3:#e5dccf;--ink:#1f1d19;--ink-2:#33302a;--muted:#6d675f;--muted-2:#887f74;--line:#c9beaf;--line-dark:#a99d8d;--accent:#9b3f28;--accent-dark:#72301f;--green:#255b45;--amber:#855d18;--red:#8d3431;--shadow:0 16px 40px rgba(42,35,27,.08)}*{box-sizing:border-box}html{background:#eee8de}body{margin:0;min-height:100vh;color:var(--ink);background:linear-gradient(180deg,#f5f0e7 0,#eee8de 38%,#e9e1d6 100%)}a{color:var(--accent-dark)}h1,h2,h3{font-family:Georgia,'Times New Roman',serif;color:var(--ink);font-weight:700}.shell{max-width:1320px;margin:0 auto;padding:30px 30px 72px}.top{display:flex;justify-content:space-between;gap:28px;align-items:flex-end;padding:8px 0 24px;border-bottom:1px solid var(--ink)}.brand h1{font-family:Inter,Segoe UI,Arial,sans-serif;font-size:34px;line-height:1;letter-spacing:.22em;margin:0;font-weight:900}.brand p{margin:8px 0 0;color:var(--muted);font-size:12px;letter-spacing:.08em;text-transform:uppercase}.target{font-size:12px;color:var(--muted);text-align:right;max-width:420px}.nav{display:flex;gap:24px;margin:0 0 34px;flex-wrap:wrap;padding:14px 0;border-bottom:1px solid var(--line-dark);background:transparent}.nav a{position:relative;color:var(--muted);text-decoration:none;padding:3px 0;font-weight:800;font-size:11px;letter-spacing:.1em;text-transform:uppercase}.nav a:hover{color:var(--ink)}.nav a.active{color:var(--ink)}.nav a.active:after{content:'';position:absolute;left:0;right:0;bottom:-15px;height:3px;background:var(--accent)}.panel{background:rgba(248,244,236,.94);border:1px solid var(--line);border-radius:2px;padding:28px 30px;box-shadow:none;margin-bottom:20px}.hero-panel{padding:42px 38px;background:var(--ink);border-color:var(--ink);color:#f8f4ec}.hero-panel h1,.hero-panel h2,.hero-panel h3{color:#f8f4ec}.hero-panel .lede,.hero-panel .meta{color:#d7cec1}.recommendation-panel{border-top:5px solid var(--accent);background:#fbf8f2}.section-head,.statusrow,.manager-head{display:flex;align-items:flex-start;justify-content:space-between;gap:26px}.eyebrow{font-size:10px;text-transform:uppercase;letter-spacing:.19em;color:var(--accent);font-weight:900}.headline{font-family:Georgia,'Times New Roman',serif;font-size:42px;line-height:1.04;margin:9px 0 13px;letter-spacing:-.025em}.lede{color:var(--muted);margin:0;max-width:850px;font-size:14px}.status{display:inline-flex;align-items:center;font-weight:900;padding:7px 9px;border-radius:2px;font-size:9px;white-space:nowrap;letter-spacing:.11em;border:1px solid currentColor;background:transparent}.good{color:var(--green)}.warn{color:var(--amber)}.danger{color:var(--red)}.done{color:#555047}.stats,.manager-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:0;margin-top:26px;border-top:1px solid var(--line);border-left:1px solid var(--line)}.stat,.metric-card{padding:20px;border:0;border-right:1px solid var(--line);border-bottom:1px solid var(--line);border-radius:0;background:rgba(255,252,246,.72)}.stat strong,.metric-label{display:block;color:var(--muted);font-size:9px;text-transform:uppercase;letter-spacing:.13em;font-weight:900}.stat span,.metric-value{display:block;font-family:Georgia,'Times New Roman',serif;font-size:26px;font-weight:700;margin-top:8px;line-height:1.08}.metric-positive{color:var(--green)}.metric-negative{color:var(--red)}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-top:20px}.grid.four{grid-template-columns:repeat(4,minmax(0,1fr))}.card,.action,.summary-card,.movement-box{padding:19px;border:1px solid var(--line);border-radius:2px;background:#fffaf3}.card .rank{color:var(--accent);font-size:9px;font-weight:900;text-transform:uppercase;letter-spacing:.12em}.card .name{font-family:Georgia,'Times New Roman',serif;font-size:19px;font-weight:700;margin-top:7px}.card .total{font-family:Georgia,'Times New Roman',serif;font-size:28px;font-weight:700;margin-top:11px}.card .meta,.meta{color:var(--muted);font-size:11px;margin-top:7px}.pressure-tier{font-family:Georgia,'Times New Roman',serif;font-size:23px;font-weight:700;margin-top:9px}.position-card{min-height:126px}.movers,.actions,.season-list{display:grid;gap:10px;margin-top:16px}.mover,.season-row{padding:13px 0;border:0;border-bottom:1px solid var(--line);border-radius:0;background:transparent;font-size:12px;color:var(--ink-2)}.action-head{display:flex;gap:9px;align-items:center;flex-wrap:wrap}.pill,.chip,.state-chip,.decision-chip,.position-chip{display:inline-flex;align-items:center;justify-content:center;border-radius:2px;font-size:9px;font-weight:900;letter-spacing:.08em;text-transform:uppercase}.pill,.chip{padding:5px 7px;border:1px solid var(--line-dark);background:transparent}.required{color:var(--amber)}.optional{color:#555047}.action .kind{font-weight:900;color:var(--ink)}.action p{margin:10px 0 0;color:var(--muted)}.command{display:block;width:100%;margin-top:12px;padding:12px 13px;border-radius:2px;background:#25231f;border:1px solid #25231f;color:#f7f1e8;font-family:Consolas,monospace;font-size:11px}.empty,.callout{margin-top:16px;padding:17px;border:1px solid var(--line);border-radius:2px;background:#f2ece3;color:var(--muted)}.callout-danger{border-color:#b98a84;background:#f4e4e1;color:#6f2927}.boundary{font-size:11px;color:var(--muted);background:transparent;box-shadow:none;border-style:dashed}.lock{font-weight:900;color:var(--ink)}.btn{display:inline-flex;align-items:center;justify-content:center;padding:11px 15px;border-radius:2px;text-decoration:none;font-weight:900;font-size:10px;letter-spacing:.09em;text-transform:uppercase;border:1px solid var(--ink);transition:background .15s ease,color .15s ease}.btn-primary{background:var(--ink);color:#f8f4ec}.btn-primary:hover{background:var(--accent);border-color:var(--accent)}.btn-secondary{background:transparent;color:var(--ink);border-color:var(--line-dark)}.btn-secondary:hover{border-color:var(--ink)}.button-row{display:flex;gap:9px;flex-wrap:wrap;margin-top:18px}.manager-summary{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(280px,.7fr);gap:14px;margin-top:22px}.summary-card h3{margin:0 0 7px;font-size:18px}.summary-card p{margin:0;color:var(--muted);font-size:12px}.roster-board{margin-top:20px;border:1px solid var(--line);border-radius:2px;overflow:hidden;background:#fffaf3}.roster-group+.roster-group{border-top:2px solid var(--ink)}.roster-group-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 15px;background:var(--paper-2)}.roster-group-head h3{margin:0;font-family:Inter,Segoe UI,Arial,sans-serif;font-size:10px;text-transform:uppercase;letter-spacing:.12em}.roster-group-head span{font-size:10px;color:var(--muted)}.player-row{display:grid;grid-template-columns:90px minmax(0,1fr) 96px;gap:12px;align-items:center;padding:13px 15px;border-top:1px solid var(--line);background:#fffaf3}.player-row:first-child{border-top:0}.player-row:hover{background:#f5eee4}.slot-marker{font-size:9px;font-weight:900;letter-spacing:.09em;color:var(--accent)}.player-primary strong{display:block;font-size:13px}.player-primary span{display:block;color:var(--muted);font-size:10px;margin-top:3px}.state-chip{justify-self:end;padding:5px 7px;border:1px solid currentColor;background:transparent}.state-start{color:var(--green)}.state-bench{color:#625c54}.state-reserve{color:#765d80}.autofill-summary{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0;margin-top:22px;border-top:1px solid var(--line);border-left:1px solid var(--line)}.lineup-board{margin-top:20px;border:1px solid var(--line);border-radius:2px;overflow:hidden}.lineup-row{display:grid;grid-template-columns:90px minmax(0,1fr) 34px minmax(0,1fr) 84px 86px;gap:10px;align-items:center;padding:13px 15px;border-top:1px solid var(--line);background:#fffaf3}.lineup-row:first-child{border-top:0}.lineup-row.changed{background:#f1e4d5;border-left:4px solid var(--accent)}.position-chip{padding:5px 7px;border:1px solid var(--line-dark);color:var(--ink);background:transparent}.lineup-choice small{display:block;color:var(--muted-2);text-transform:uppercase;letter-spacing:.1em;font-size:8px;font-weight:900}.lineup-choice strong{display:block;margin-top:3px;font-size:12px}.lineup-arrow{text-align:center;color:var(--accent);font-size:18px}.projection{text-align:right;font-family:Georgia,'Times New Roman',serif;font-weight:700;font-size:16px}.projection span{display:block;color:var(--muted-2);font-family:Inter,Segoe UI,Arial,sans-serif;font-weight:800;font-size:8px;text-transform:uppercase;letter-spacing:.08em}.decision-chip{justify-self:end;padding:5px 7px;border:1px solid currentColor;background:transparent}.decision-keep{color:var(--green)}.decision-change{color:var(--amber)}.movement-strip{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:14px}.movement-box strong{display:block;font-size:9px;text-transform:uppercase;letter-spacing:.1em;color:var(--muted)}.movement-box div{display:flex;gap:6px;flex-wrap:wrap;margin-top:9px}.chip{color:var(--ink)}.source-note{display:flex;align-items:center;justify-content:space-between;gap:14px;margin-top:16px;padding-top:14px;border-top:1px solid var(--line);color:var(--muted);font-size:10px}details{margin-top:15px;border-top:1px solid var(--line);padding-top:12px}summary{cursor:pointer;color:var(--ink);font-weight:900;font-size:10px;letter-spacing:.06em;text-transform:uppercase}.technical{margin-top:10px;color:var(--muted);font-size:10px;line-height:1.6}@media(max-width:960px){.grid.four{grid-template-columns:repeat(2,minmax(0,1fr))}.stats,.manager-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.lineup-row{grid-template-columns:72px minmax(0,1fr) 28px minmax(0,1fr) 70px}.decision-chip{grid-column:2/6;justify-self:start}}@media(max-width:760px){.shell{padding:18px 15px 44px}.top,.statusrow,.manager-head,.source-note{display:block}.top{padding-bottom:18px}.target{text-align:left;margin-top:12px}.brand h1{font-size:27px}.nav{gap:17px;margin-bottom:24px}.nav a.active:after{bottom:-15px}.panel{padding:22px 18px}.hero-panel{padding:30px 20px}.headline{font-size:34px}.status{margin-top:13px}.stats,.manager-metrics,.grid,.grid.four,.manager-summary,.autofill-summary,.movement-strip{grid-template-columns:1fr}.player-row{grid-template-columns:70px minmax(0,1fr) 80px}.lineup-row{grid-template-columns:66px minmax(0,1fr) 70px;padding:11px}.lineup-arrow{display:none}.lineup-choice.current{grid-column:2}.lineup-choice.recommended{grid-column:2}.projection{grid-column:3;grid-row:1/3}.decision-chip{grid-column:2/4}.source-note .button-row{margin-top:10px}}
'@
}
"@

$core = Replace-FunctionBlock -Text $core -StartMarker 'function Get-AppCss {' -NextMarker 'function Get-AppNav {' -Replacement $cssReplacement

foreach ($required in @(
    '--paper:#f8f4ec',
    '--accent:#9b3f28',
    "font-family:Georgia,'Times New Roman',serif",
    '.nav a.active:after',
    '.recommendation-panel{border-top:5px solid var(--accent)',
    '.panel{background:rgba(248,244,236,.94);border:1px solid var(--line);border-radius:2px',
    '.btn-primary{background:var(--ink);color:#f8f4ec',
    '@media(max-width:760px)'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-829 BLOCKED: required editorial visual marker is missing: $required"
    }
}

if ($cssReplacement -match 'Invoke-RestMethod|Invoke-WebRequest|Method = "POST"|https://api\.|SleeperClient|FantasyPros') {
    throw 'BF-829 BLOCKED: editorial visual refresh introduced provider, network, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
