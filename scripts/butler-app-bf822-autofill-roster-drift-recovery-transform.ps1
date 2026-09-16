param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-822 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $first = $Text.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) {
        throw "BF-822 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-822 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$catchOld = @'
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler AutoFill view blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p><p><a href=`"/team`">Back to My Team</a></p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
'@

$catchNew = @'
                catch {
                    $errorMessage = [string]$_.Exception.Message
                    if ($errorMessage -match 'BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame') {
                        $css = Get-AppCss
                        $nav = Get-AppNav -Active "team"
                        $technical = ConvertTo-HtmlText $errorMessage
                        $errorHtml = @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Butler | Lineup Advisor</title>
<style>$css</style>
</head>
<body>
<main class="shell">
<div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div></div>
$nav
<section class="panel recommendation-panel">
  <div class="manager-head">
    <div>
      <div class="eyebrow">Lineup advisor</div>
      <h2>Your roster changed</h2>
      <p class="lede">Butler stopped the lineup review because its saved roster evidence no longer matches the live Sleeper roster.</p>
    </div>
    <span class="status warn">REFRESH EVIDENCE</span>
  </div>
  <div class="manager-summary">
    <div class="summary-card"><h3>What happened</h3><p>Your roster changed after Butler's last governed evidence frame, so the previous frame cannot safely support a new lineup recommendation.</p></div>
    <div class="summary-card"><h3>What to do next</h3><p>Refresh Butler's governed evidence, then run the lineup review again.</p></div>
  </div>
  <div class="button-row">
    <a class="btn btn-primary" href="/refresh">Refresh Butler Evidence</a>
    <a class="btn btn-secondary" href="/team">Back to My Team</a>
  </div>
  <details><summary>Technical details</summary><div class="technical">$technical</div></details>
  <p class="meta"><strong>Read only:</strong> no lineup was submitted to Sleeper. This page does not refresh evidence automatically; the governed refresh requires your explicit confirmation.</p>
</section>
</main>
</body>
</html>
"@
                        Send-HttpResponse -Stream $stream -StatusCode 409 -StatusText "Conflict" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                    }
                    else {
                        $errorHtml = "<!doctype html><html><body><h1>Butler AutoFill view blocked</h1><pre>$(ConvertTo-HtmlText $errorMessage)</pre><p>No Butler or Sleeper write was executed.</p><p><a href=`"/team`">Back to My Team</a></p></body></html>"
                        Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                    }
                }
'@

$core = Replace-ExactlyOnce -Text $core -Old $catchOld.TrimEnd() -New $catchNew.TrimEnd() -Contract 'AutoFill failure recovery catch'

foreach ($required in @(
    'Your roster changed',
    'REFRESH EVIDENCE',
    'Refresh Butler Evidence',
    'href="/refresh"',
    'Technical details',
    'explicit confirmation',
    'BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame'
)) {
    if ($core -notmatch [regex]::Escape($required)) {
        throw "BF-822 BLOCKED: roster-drift recovery contract '$required' is missing."
    }
}

if ($catchNew -match 'sleeperLiveWaiverSnapshotSync|sleeperLiveWaiverMarketAttentionSync|Method = "POST"|Invoke-RestMethod|create_transaction|submitTransaction') {
    throw 'BF-822 BLOCKED: AutoFill recovery presentation introduced refresh execution, provider access, or Sleeper write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
