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

function Replace-ExactOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$OldText,
        [Parameter(Mandatory = $true)][string]$NewText,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if ($Text.Contains($NewText)) {
        return $Text
    }
    if (-not $Text.Contains($OldText)) {
        throw "BF-831 could not find expected text for $Label"
    }
    return $Text.Replace($OldText, $NewText)
}

$core = [System.IO.File]::ReadAllText($CorePath)
if (-not $core.Contains('--turf:#2E6B47')) {
    throw 'BF-831 requires the BF-830 command-center visual baseline.'
}

$host = [System.IO.File]::ReadAllText($TradeHostPath)
$hostCssReplacement = @"
function Get-AppCss {
    return @'
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#16201A;background:#F4F2EA;line-height:1.45;--bg:#F4F2EA;--surface:#FFFFFF;--surface-2:#ECE9DD;--line:#D8D4C4;--turf:#2E6B47;--turf-deep:#1F4D33;--gold:#C98A1F;--ink:#16201A;--muted:#5B6459;--brick:#A8452F;--font-display:'Teko',Impact,sans-serif;--font-body:'Inter',Segoe UI,Arial,sans-serif;--radius:3px}*{box-sizing:border-box}html{background:var(--bg)}body{font-family:var(--font-body);margin:0;min-height:100vh;background:var(--bg);color:var(--ink)}a{color:var(--turf-deep)}h1,h2,h3{color:var(--ink)}.shell{max-width:1320px;margin:0 auto;padding:28px 30px 64px}.top{display:flex;justify-content:space-between;gap:28px;align-items:flex-end;padding:8px 0 20px;border-bottom:1px solid var(--ink)}.brand h1{font-family:var(--font-display);font-weight:600;font-size:42px;line-height:.9;letter-spacing:.03em;margin:0;text-transform:uppercase}.brand p{margin:8px 0 0;color:var(--muted);font-size:11px;letter-spacing:.08em;text-transform:uppercase}.target{font-size:11px;color:var(--muted);text-align:right;max-width:420px}.nav{display:flex;gap:22px;margin:0 0 30px;flex-wrap:wrap;padding:13px 0;border-bottom:1px solid var(--line)}.nav a{color:var(--muted);text-decoration:none;padding:4px 0 9px;font-weight:800;font-size:10px;letter-spacing:.09em;text-transform:uppercase;border-bottom:3px solid transparent}.nav a:hover{color:var(--ink)}.nav a.active{color:var(--turf);border-bottom-color:var(--turf)}.panel{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);padding:22px 24px;box-shadow:none;margin-bottom:18px}.section-head,.statusrow{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.status{display:inline-flex;align-items:center;font-weight:900;padding:6px 8px;border-radius:var(--radius);font-size:9px;white-space:nowrap;letter-spacing:.08em;text-transform:uppercase;border:1px solid currentColor;background:transparent}.good{color:var(--turf)}.warn{color:#8a6319}.danger,.bad{color:var(--brick)}.done{color:var(--muted)}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-top:16px}.card,.action{padding:14px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface)}.meta{color:var(--muted);font-size:12px;margin-top:4px}.empty{padding:14px;border:1px dashed var(--line);border-radius:var(--radius);background:var(--surface-2);color:var(--muted)}code{background:var(--surface-2);padding:2px 5px;border-radius:var(--radius)}.trade-loading-shell{min-height:55vh;display:grid;place-items:center}.trade-loading-card{max-width:520px;text-align:center}.trade-loading-spinner{width:42px;height:42px;margin:0 auto 16px;border:3px solid var(--line);border-top-color:var(--turf);border-radius:50%;animation:trade-spin .8s linear infinite}.trade-loading-card h2{font-family:var(--font-display);font-size:32px;line-height:1;margin:0 0 8px;text-transform:uppercase}.trade-loading-card p{margin:0;color:var(--muted)}.trade-loading-card .trade-loading-sub{margin-top:8px;font-size:11px;color:var(--muted)}@keyframes trade-spin{to{transform:rotate(360deg)}}@media(prefers-color-scheme:dark){:root{--bg:#111713;--surface:#18201b;--surface-2:#202b24;--line:#344137;--turf:#77b58b;--turf-deep:#9ec7aa;--gold:#e1ad52;--ink:#edf4ef;--muted:#aab7ad;--brick:#d77a62}}@media(max-width:760px){.grid{grid-template-columns:1fr}.top,.section-head,.statusrow{flex-direction:column}.target{text-align:left}.shell{padding:20px 16px 46px}.nav{gap:14px}}
'@
}
"@

if (-not $host.Contains('--turf:#2E6B47')) {
    $host = Replace-Block -Text $host -StartMarker 'function Get-AppCss {' -NextMarker 'function Get-AppNav {' -Replacement $hostCssReplacement -Label 'trade host command-center CSS'
}
$host = Replace-ExactOnce -Text $host -OldText "<a href='/trade'>Trade Lab</a>" -NewText "<a href='/trade'>Trade Analyzer</a>" -Label 'trade navigation label'
$host = Replace-ExactOnce -Text $host -OldText '<h2>Loading Trade Lab</h2>' -NewText '<h2>Loading Trade Analyzer</h2>' -Label 'trade loading title'

$lab = [System.IO.File]::ReadAllText($TradeLabPath)
$tradeCssStart = '$tradeCss = @' + "'"
$headlineStart = '$headline = @' + '"'
$tradeCssReplacement = @"
`$tradeCss = @'
<style>
.trade-lab{display:grid;gap:16px}.trade-card{border:1px solid var(--line);border-radius:var(--radius);padding:18px;background:var(--surface)}.trade-card h2{font-family:var(--font-display);font-size:30px;line-height:1;margin:0 0 10px;color:var(--ink);text-transform:uppercase}.trade-setup{border-top:4px solid var(--turf);background:var(--surface)}.trade-section{margin-top:18px}.trade-section h3{margin:0 0 8px;color:var(--ink);font-size:13px;text-transform:uppercase;letter-spacing:.06em}.opponent-select{display:grid;grid-template-columns:minmax(0,420px);gap:6px;margin-top:10px}select{padding:10px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface);color:var(--ink)}.asset-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:8px}.asset-option{display:grid;grid-template-columns:auto 1fr;gap:4px 8px;align-items:center;border:1px solid var(--line);border-radius:var(--radius);padding:10px;background:var(--surface)}.asset-option:hover{background:var(--surface-2)}.asset-option input{grid-row:1 / span 2}.asset-option small{color:var(--muted)}.trade-submit{margin-top:14px;border:1px solid var(--turf);border-radius:var(--radius);padding:11px 16px;background:var(--turf);color:#fff;font-weight:900;letter-spacing:.06em;text-transform:uppercase;cursor:pointer}.trade-submit:hover{background:var(--turf-deep);border-color:var(--turf-deep)}.trade-errors{border-color:var(--brick);background:color-mix(in srgb,var(--brick) 7%,var(--surface))}.trade-errors ul{margin:8px 0 0}.trade-result{border-left:4px solid var(--turf);background:var(--surface)}.trade-kicker,.decision-label,.trade-evidence-cell span{display:block;margin:0 0 5px;color:var(--muted);font-size:9px;font-weight:900;letter-spacing:.09em;text-transform:uppercase}.decision-line{margin-top:18px;padding-top:14px;border-top:1px solid var(--line)}.decision-action{font-family:var(--font-display);font-size:32px;line-height:1;text-transform:uppercase}.decision-reason{max-width:900px;font-size:15px;line-height:1.55}.trade-evidence-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:0;margin-top:18px;border-top:1px solid var(--line);border-left:1px solid var(--line)}.trade-evidence-cell{padding:12px;border-right:1px solid var(--line);border-bottom:1px solid var(--line);background:var(--surface)}.trade-evidence-cell strong{font-size:12px}.trade-secondary-evidence{margin-top:14px;color:var(--muted);font-size:12px}.trade-secondary-evidence p{margin:6px 0}.trade-raw{margin-top:16px;padding-top:12px;border-top:1px solid var(--line)}.trade-raw summary{cursor:pointer;font-weight:800;font-size:11px;color:var(--muted)}.trade-coverage{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:8px;margin:12px 0 0;padding:0;list-style:none}.trade-coverage li{border:1px solid var(--line);border-radius:var(--radius);padding:9px;background:var(--surface)}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--surface-2);color:var(--ink);padding:12px;border:1px solid var(--line);border-radius:var(--radius)}@media(max-width:760px){.asset-list,.trade-coverage{grid-template-columns:1fr}.trade-evidence-grid{grid-template-columns:1fr}}
</style>
'@
"@

if (-not $lab.Contains('.trade-evidence-grid')) {
    $lab = Replace-Block -Text $lab -StartMarker $tradeCssStart -NextMarker $headlineStart -Replacement $tradeCssReplacement -Label 'trade analyzer CSS'
}
$lab = Replace-ExactOnce -Text $lab -OldText '<h2>Trade Lab</h2>' -NewText '<h2>Trade Analyzer</h2>' -Label 'trade analyzer headline'
$lab = Replace-ExactOnce -Text $lab -OldText 'Get-AppShellStart -Title "Trade Lab"' -NewText 'Get-AppShellStart -Title "Trade Analyzer"' -Label 'trade analyzer page title'

$oldResult = @'
<section class='trade-card trade-result'>
  <div class='statusrow'><h2>Recommendation</h2><span class='status good'>$recommendation</span></div>
  <p><strong>Action:</strong> $action</p>
  <p>$reason</p>
  <p><strong>Pressure:</strong> $pressureTier ($pressureScore) · <strong>Evidence:</strong> $coverage · <strong>Confidence:</strong> $confidence</p>
  <p><strong>Selected opponent:</strong> $selectedId</p>
  <p><strong>Blocking issue:</strong> $blocking</p>
  <p><strong>Gates:</strong> $gates</p>
  <details>
    <summary>Raw BF-670 v5 result</summary>
    <pre>$raw</pre>
  </details>
</section>
'@
$newResult = @'
<section class='trade-card trade-result'>
  <div class='statusrow'><div><p class='trade-kicker'>Butler Recommendation</p><h2>$recommendation</h2></div><span class='status good'>Governed BF-670 v5</span></div>
  <div class='decision-line'><span class='decision-label'>Action</span><strong class='decision-action'>$action</strong></div>
  <p class='decision-reason'>$reason</p>
  <div class='trade-evidence-grid'>
    <div class='trade-evidence-cell'><span>Pressure</span><strong>$pressureTier ($pressureScore)</strong></div>
    <div class='trade-evidence-cell'><span>Evidence</span><strong>$coverage</strong></div>
    <div class='trade-evidence-cell'><span>Confidence</span><strong>$confidence</strong></div>
    <div class='trade-evidence-cell'><span>Opponent</span><strong>$selectedId</strong></div>
  </div>
  <div class='trade-secondary-evidence'>
    <p><strong>Blocking issue:</strong> $blocking</p>
    <p><strong>Gates:</strong> $gates</p>
  </div>
  <details class='trade-raw'>
    <summary>Raw Butler evidence · BF-670 v5</summary>
    <pre>$raw</pre>
  </details>
</section>
'@
if (-not $lab.Contains('Butler Recommendation')) {
    $lab = Replace-ExactOnce -Text $lab -OldText $oldResult -NewText $newResult -Label 'governed recommendation hierarchy'
}

if (-not $lab.Contains('Invoke-ButlerTradeV5')) {
    throw 'BF-831 BLOCKED: trade analyzer transform lost the governed BF-670 v5 invocation.'
}
foreach ($forbidden in @('https://api.sleeper.app', 'Start-Process', 'Invoke-RestMethod', 'Invoke-WebRequest')) {
    if ($host.Contains($forbidden) -or $lab.Contains($forbidden)) {
        throw "BF-831 BLOCKED: trade analyzer introduced provider, API, or write behavior marker $forbidden"
    }
}

[System.IO.File]::WriteAllText($TradeHostPath, $host, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($TradeLabPath, $lab, [System.Text.UTF8Encoding]::new($false))
