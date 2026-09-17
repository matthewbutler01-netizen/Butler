param(
    [Parameter(Mandatory = $true)]
    [string]$CorePath,

    [string]$TradeLabPath = (Join-Path $PSScriptRoot 'butler-trade-lab.ps1'),
    [string]$TradeHostPath = (Join-Path $PSScriptRoot 'butler-trade-lab-host.ps1')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($requiredPath in @($CorePath, $TradeLabPath, $TradeHostPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "BF-831 required file is missing: $requiredPath"
    }
}

function Replace-Block {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$StartMarker,
        [Parameter(Mandatory = $true)][string]$NextMarker,
        [Parameter(Mandatory = $true)][string]$Replacement,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $start = $Text.IndexOf($StartMarker, [System.StringComparison]::Ordinal)
    if ($start -lt 0) {
        throw "BF-831 could not find start marker for $Label"
    }
    $next = $Text.IndexOf($NextMarker, $start, [System.StringComparison]::Ordinal)
    if ($next -lt 0) {
        throw "BF-831 could not find next marker for $Label"
    }
    return $Text.Substring(0, $start) + $Replacement + "`r`n" + $Text.Substring($next)
}

$core = [System.IO.File]::ReadAllText($CorePath)
if (-not $core.Contains('--turf:#2E6B47')) {
    throw 'BF-831 requires the BF-830 command-center visual baseline.'
}

$host = [System.IO.File]::ReadAllText($TradeHostPath)
$hostCssReplacement = @"
function Get-AppCss {
    return @'
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#16201A;background:#F4F2EA;line-height:1.45;--bg:#F4F2EA;--surface:#FFFFFF;--surface-2:#ECE9DD;--line:#D8D4C4;--turf:#2E6B47;--turf-deep:#1F4D33;--gold:#C98A1F;--ink:#16201A;--muted:#5B6459;--brick:#A8452F;--font-display:'Teko',Impact,sans-serif;--font-body:'Inter',Segoe UI,Arial,sans-serif;--radius:3px}*{box-sizing:border-box}html{background:var(--bg)}body{font-family:var(--font-body);margin:0;min-height:100vh;color:var(--ink);background-color:var(--bg);background-image:repeating-linear-gradient(90deg,transparent 0,transparent 79px,color-mix(in srgb,var(--line) 28%,transparent) 80px)}a{color:var(--turf-deep)}h1,h2,h3{color:var(--ink)}.shell{max-width:1320px;margin:0 auto;padding:28px 30px 64px}.top{display:flex;justify-content:space-between;gap:28px;align-items:flex-end;padding:8px 0 20px;border-bottom:1px solid var(--ink);margin-bottom:0}.brand h1{font-family:var(--font-display);font-weight:600;font-size:42px;line-height:.9;letter-spacing:.03em;margin:0;text-transform:uppercase}.brand p{margin:8px 0 0;color:var(--muted);font-size:11px;letter-spacing:.08em;text-transform:uppercase}.target{font-size:11px;color:var(--muted);text-align:right;max-width:420px}.nav{display:flex;gap:22px;margin:0 0 30px;flex-wrap:wrap;padding:13px 0;border-bottom:1px solid var(--line)}.nav a{color:var(--muted);text-decoration:none;padding:4px 0 9px;font-weight:800;font-size:10px;letter-spacing:.09em;text-transform:uppercase;border:0;border-bottom:3px solid transparent;background:transparent;border-radius:0}.nav a:hover{color:var(--ink)}.nav a.active{color:var(--turf);border-bottom-color:var(--turf);background:transparent}.panel{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);padding:22px 24px;box-shadow:none;margin-bottom:18px}.eyebrow{font-size:10px;text-transform:uppercase;letter-spacing:.13em;color:var(--turf);font-weight:900}.statusrow{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.headline{font-family:var(--font-display);font-size:34px;line-height:1;margin:7px 0 7px;text-transform:uppercase}.lede{color:var(--muted);margin:0;max-width:860px}.status{display:inline-flex;align-items:center;font-weight:900;padding:6px 8px;border-radius:var(--radius);font-size:9px;white-space:nowrap;letter-spacing:.08em;text-transform:uppercase;border:1px solid currentColor;background:transparent}.good{color:var(--turf)}.warn{color:#8a6319}.danger{color:var(--brick)}.done{color:var(--muted)}.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0;margin-top:18px;border-top:1px solid var(--line);border-left:1px solid var(--line)}.stat{padding:14px;border-right:1px solid var(--line);border-bottom:1px solid var(--line);background:var(--surface)}.stat strong{display:block;color:var(--muted);font-size:9px;text-transform:uppercase;letter-spacing:.08em}.stat span{display:block;font-size:18px;font-weight:800;margin-top:5px}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:16px}.card,.action,.roster-card{padding:16px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface)}.card .meta,.meta{color:var(--muted);font-size:12px;margin-top:6px}.empty{margin-top:14px;padding:14px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface-2);color:var(--muted)}.boundary{font-size:12px;color:var(--muted);border-style:dashed}.lock{font-weight:900;color:var(--ink)}details{margin-top:14px;border-top:1px solid var(--line);padding-top:12px}summary{cursor:pointer;color:var(--turf-deep);font-weight:800}.trade-loading-shell{min-height:55vh;display:grid;place-items:center}.trade-loading-card{max-width:520px;text-align:center}.trade-loading-spinner{width:42px;height:42px;margin:0 auto 16px;border:3px solid var(--line);border-top-color:var(--turf);border-radius:50%;animation:trade-spin .8s linear infinite}@keyframes trade-spin{to{transform:rotate(360deg)}}@media(prefers-color-scheme:dark){:root{--bg:#111713;--surface:#18201b;--surface-2:#202b24;--line:#344137;--turf:#77b58b;--turf-deep:#9ec7aa;--gold:#e1ad52;--ink:#edf4ef;--muted:#aab7ad;--brick:#d77a62}}@media(max-width:760px){.top,.statusrow{display:block}.target{text-align:left;margin-top:12px}.brand h1{font-size:36px}.status{margin-top:12px}.stats,.grid{grid-template-columns:1fr}.shell{padding:20px 16px 46px}.nav{gap:14px}}
'@
}
"@

if (-not $host.Contains('--turf:#2E6B47')) {
    $host = Replace-Block -Text $host -StartMarker 'function Get-AppCss {' -NextMarker 'function Get-AppNav {' -Replacement $hostCssReplacement -Label 'trade host command-center CSS'
}
$host = $host.Replace('Trade Lab', 'Trade Analyzer')

$lab = [System.IO.File]::ReadAllText($TradeLabPath)
$tradeCssStart = '$tradeCss = @' + "'"
$tradeCssNext = '$opponentOptions ='
$tradeCssReplacement = @"
`$tradeCss = @'
.trade-setup{display:grid;grid-template-columns:1fr auto;gap:12px;align-items:end;margin-top:16px}.field label{display:block;color:var(--muted);font-size:10px;font-weight:900;letter-spacing:.07em;text-transform:uppercase;margin-bottom:6px}.field select,.trade-button{font:inherit;border-radius:var(--radius);border:1px solid var(--line);background:var(--surface);color:var(--ink);padding:10px 12px}.field select{width:100%}.trade-button{cursor:pointer;background:var(--turf);border-color:var(--turf);color:#fff;font-weight:900;text-transform:uppercase;letter-spacing:.05em}.trade-button:hover{background:var(--turf-deep);border-color:var(--turf-deep)}.trade-columns{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-top:16px}.asset-list{display:grid;gap:8px;margin-top:12px}.asset-option{display:flex;gap:10px;align-items:flex-start;padding:12px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface);cursor:pointer}.asset-option:hover{background:var(--surface-2)}.asset-option input{margin-top:4px}.asset-option strong{display:block}.asset-option small{display:block;color:var(--muted);margin-top:3px}.asset-value{color:var(--turf)}.asset-missing{color:#8a6319}.trade-submit{margin-top:16px}.trade-result{border-left:4px solid var(--turf)}.trade-result>.empty{font-size:14px;color:var(--ink)}.gate-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:0;margin-top:14px;border-top:1px solid var(--line);border-left:1px solid var(--line)}.gate{padding:10px;border-right:1px solid var(--line);border-bottom:1px solid var(--line);background:var(--surface);font-size:12px}.gate strong{display:block;color:var(--muted);font-size:9px;text-transform:uppercase;letter-spacing:.06em}.gate span{display:block;margin-top:4px;font-weight:900}.raw-output{white-space:pre-wrap;word-break:break-word;background:var(--surface-2);border:1px solid var(--line);border-radius:var(--radius);padding:12px;color:var(--ink);font:12px Consolas,monospace}.veto-list{display:grid;gap:8px;margin-top:10px}.veto-item{padding:10px;border:1px solid var(--brick);border-radius:var(--radius);background:color-mix(in srgb,var(--brick) 7%,var(--surface));color:var(--brick)}@media(max-width:800px){.trade-setup,.trade-columns{grid-template-columns:1fr}.gate-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
'@
"@

if (-not $lab.Contains('.trade-result{border-left:4px solid var(--turf)}')) {
    $lab = Replace-Block -Text $lab -StartMarker $tradeCssStart -NextMarker $tradeCssNext -Replacement $tradeCssReplacement -Label 'trade analyzer CSS'
}
$lab = $lab.Replace('Trade Lab', 'Trade Analyzer')
$lab = $lab.Replace('<div class="eyebrow">Governed trade evaluation</div>', '<div class="eyebrow">Butler recommendation</div>')
$lab = $lab.Replace('<section class="panel"><div class="eyebrow">Butler recommendation</div>', '<section class="panel trade-result"><div class="eyebrow">Butler recommendation</div>')
$lab = $lab.Replace('>Evaluate trade</button>', '>Get Butler recommendation</button>')
$lab = $lab.Replace('<h1 class="headline">Evaluate a trade</h1>', '<h1 class="headline">Analyze a trade</h1>')

foreach ($required in @(
    'Invoke-ButlerTradeV5',
    'trade recommendation $LeagueId',
    'StrategicVeto',
    'EvidenceComplete',
    'TransitionCoverage',
    'ProtectedCoverage',
    'Technical governed output',
    'READ ONLY'
)) {
    if (-not $lab.Contains($required)) {
        throw "BF-831 BLOCKED: governed BF-670 marker is missing after presentation transform: $required"
    }
}
foreach ($forbidden in @('https://api.sleeper.app', 'Start-Process', 'Invoke-RestMethod', 'Invoke-WebRequest', 'Method = "POST"')) {
    if ($host.Contains($forbidden) -or $lab.Contains($forbidden)) {
        throw "BF-831 BLOCKED: Trade Analyzer introduced provider, API, or write behavior marker $forbidden"
    }
}

[System.IO.File]::WriteAllText($TradeHostPath, $host, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($TradeLabPath, $lab, [System.Text.UTF8Encoding]::new($false))
