# BF-675/BF-676/BF-677/BF-678 native manual governed waiver refresh app module.
# GET renders confirmation only. Exact POST /refresh is token-gated and invokes
# the repo-owned governed refresh runner; no Sleeper transaction endpoint exists here.

function New-DecisionRefreshToken {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Get-DecisionRefreshTechnicalField {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $pattern = '<div>' + [regex]::Escape($Label) + '\s*(?<value>[^<]+)</div>'
    $matches = [regex]::Matches($Html, $pattern)
    if ($matches.Count -ne 1) { return $null }
    $value = [System.Net.WebUtility]::HtmlDecode($matches[0].Groups['value'].Value).Trim()
    if ([string]::IsNullOrWhiteSpace($value)) { return $null }
    return $value
}

function Add-DecisionRefreshControl {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][string]$RequestTarget
    )

    if ($RequestTarget -cne '/') { return $Html }
    if ($Html -notmatch '<nav class="nav" aria-label="Butler sections">') {
        throw 'BF-677 BLOCKED: Dashboard HTML is missing the Butler navigation contract.'
    }
    if ($Html -match 'href="/refresh"') { return $Html }

    # BF-677 is presentation eligibility only. These exact technical fields were
    # already derived from the governed compact summary by the inner Dashboard.
    # Missing/duplicate/unknown values simply omit the link. BF-676 POST preflight
    # remains the only authorization for any Butler write.
    $decisionState = Get-DecisionRefreshTechnicalField -Html $Html -Label 'Decision state:'
    $bf629State = Get-DecisionRefreshTechnicalField -Html $Html -Label 'BF-629:'
    $bf631State = Get-DecisionRefreshTechnicalField -Html $Html -Label 'BF-631:'
    if ([string]::IsNullOrWhiteSpace($decisionState) -or
        [string]::IsNullOrWhiteSpace($bf629State) -or
        [string]::IsNullOrWhiteSpace($bf631State)) {
        return $Html
    }

    $eligible = $false
    if ($decisionState -ceq 'NO_TRANSACTION_TO_ACT_ON' -and
        $bf629State -ceq 'NO_TRANSACTION_TO_REVALIDATE' -and
        $bf631State -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED') {
        $eligible = $true
    }
    elseif ($decisionState -ceq 'CURRENT_REFRESH_RECOMMENDED' -and
        $bf629State -ceq 'LIVE_ACTIONABLE_VERIFIED' -and
        $bf631State -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED') {
        $planState = Get-DecisionRefreshTechnicalField -Html $Html -Label 'BF-636 plan state:'
        $planPolicy = Get-DecisionRefreshTechnicalField -Html $Html -Label 'BF-636 plan policy:'
        $stepCount = Get-DecisionRefreshTechnicalField -Html $Html -Label 'Governed step count:'
        if ($planState -ceq 'MANUAL_REFRESH_PLAN_READY' -and
            $planPolicy -ceq 'sleeper-live-waiver-manual-refresh-plan-v1-bf635-explicit-operator-only-no-execution' -and
            $stepCount -ceq '9') {
            $eligible = $true
        }
    }

    if (-not $eligible) { return $Html }
    return $Html.Replace('</nav>', '<a href="/refresh">Check for a new decision</a></nav>')
}

function Get-DecisionRefreshConfirmationHtml {
    param(
        [Parameter(Mandatory = $true)][string]$LeagueId,
        [Parameter(Mandatory = $true)][string]$Token
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'dashboard'
    $safeLeague = ConvertTo-HtmlText $LeagueId
    $safeToken = ConvertTo-HtmlText $Token
    return @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Butler - Check for a new decision</title>
<style>$css
.refresh-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-top:16px}.refresh-button{appearance:none;border:1px solid #3b82f6;border-radius:12px;background:#2563eb;color:#fff;font:inherit;font-weight:700;padding:12px 18px;cursor:pointer}.refresh-cancel{display:inline-block;padding:12px 0}.refresh-warning{margin-top:18px;padding:16px;border:1px solid #334155;border-radius:14px}.refresh-warning p{margin:8px 0 0}.refresh-details{margin-top:18px;padding:0 16px;border:1px solid #334155;border-radius:14px}.refresh-details summary{cursor:pointer;font-weight:700;padding:16px 0}.refresh-details[open] summary{border-bottom:1px solid #334155}.refresh-list{line-height:1.7}.refresh-list li{margin:5px 0}
</style>
</head>
<body>
<main class="shell">
<div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$safeLeague</div></div>
$nav
<section class="panel">
<div class="eyebrow">Manual governed refresh</div>
<div class="statusrow"><div><h2 class="headline">Check for a new decision?</h2><p class="lede">Butler will refresh its governed waiver evidence, recompute the existing recommendation method, and capture a new immutable audit package.</p></div><span class="status done">MANUAL</span></div>
<div class="refresh-warning"><strong>This does not submit a waiver move to Sleeper.</strong><p>The current governed decision is re-checked before any Butler write.</p></div>
<form method="post" action="/refresh">
<input type="hidden" name="token" value="$safeToken">
<div class="refresh-actions"><button class="refresh-button" type="submit">Confirm and check again</button><a class="refresh-cancel" href="/">Cancel</a></div>
</form>
<details class="refresh-details"><summary>How Butler governs this refresh</summary><ul class="refresh-list"><li>An exact governed no-transaction decision remains eligible for a manual recheck under BF-675.</li><li>If Butler already has an actionable recommendation, BF-676 proceeds only when BF-635 reports the approved six-hour refresh warning and BF-636 supplies the exact ready nine-step plan.</li><li>Refreshing a warning-state recommendation may preserve it, change it, or produce no governed transaction after newer evidence is evaluated.</li><li>Fully current actionable, stale hard-gate, pending, completed/unconverged, and unknown states are blocked before BF-602.</li><li>The refresh may take several minutes while the browser waits for the nine governed stages. If a stage fails, later stages stop; earlier Butler evidence stages may already have completed.</li></ul></details>
</section>
</main>
</body>
</html>
"@
}

function Read-DecisionRefreshFormBody {
    param(
        [Parameter(Mandatory = $true)]$Reader,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    if (-not $Headers.ContainsKey('Content-Type') -or -not ([string]$Headers['Content-Type']).StartsWith('application/x-www-form-urlencoded', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'BF-676 BLOCKED: refresh POST requires application/x-www-form-urlencoded content.'
    }
    if (-not $Headers.ContainsKey('Content-Length')) {
        throw 'BF-676 BLOCKED: refresh POST is missing Content-Length.'
    }
    $contentLength = 0
    if (-not [int]::TryParse(([string]$Headers['Content-Length']).Trim(), [ref]$contentLength) -or $contentLength -lt 1 -or $contentLength -gt 4096) {
        throw 'BF-676 BLOCKED: refresh POST body length is invalid.'
    }

    $buffer = New-Object char[] $contentLength
    $readTotal = 0
    while ($readTotal -lt $contentLength) {
        $readNow = $Reader.Read($buffer, $readTotal, $contentLength - $readTotal)
        if ($readNow -le 0) {
            throw 'BF-676 BLOCKED: refresh POST body ended before Content-Length.'
        }
        $readTotal += $readNow
    }
    return (-join $buffer)
}

function Get-DecisionRefreshSubmittedToken {
    param([Parameter(Mandatory = $true)][string]$Body)

    $values = @{}
    foreach ($pair in ($Body -split '&')) {
        if ([string]::IsNullOrWhiteSpace($pair)) { continue }
        $equals = $pair.IndexOf('=')
        if ($equals -lt 1) {
            throw 'BF-676 BLOCKED: refresh POST form is malformed.'
        }
        $key = [System.Uri]::UnescapeDataString($pair.Substring(0, $equals).Replace('+', ' '))
        $value = [System.Uri]::UnescapeDataString($pair.Substring($equals + 1).Replace('+', ' '))
        if ($values.ContainsKey($key)) {
            throw 'BF-676 BLOCKED: refresh POST contains a duplicate form field.'
        }
        $values[$key] = $value
    }
    if ($values.Count -ne 1 -or -not $values.ContainsKey('token')) {
        throw 'BF-676 BLOCKED: refresh POST must contain only the one-use token.'
    }
    return [string]$values['token']
}

function Invoke-DecisionRefreshRunner {
    param(
        [Parameter(Mandatory = $true)][string]$LeagueId,
        [Parameter(Mandatory = $true)][string]$RunnerPath
    )

    if (-not (Test-Path -LiteralPath $RunnerPath)) {
        throw "BF-676 BLOCKED: refresh runner not found at $RunnerPath"
    }
    $resultLines = @(& $RunnerPath -LeagueId $LeagueId)
    return ($resultLines -join "`n")
}

function Get-DecisionRefreshSuccessHtml {
    param(
        [Parameter(Mandatory = $true)][string]$LeagueId,
        [Parameter(Mandatory = $true)][string]$ResultText
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'dashboard'
    $safeLeague = ConvertTo-HtmlText $LeagueId
    $safeResult = ConvertTo-HtmlText $ResultText
    return @"
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Butler - Decision refreshed</title><style>$css</style></head>
<body><main class="shell"><div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$safeLeague</div></div>$nav
<section class="panel"><div class="eyebrow">Manual governed refresh</div><div class="statusrow"><div><h2 class="headline">New governed decision captured</h2><p class="lede">All nine governed refresh stages completed. Butler did not submit a Sleeper transaction.</p></div><span class="status done">COMPLETE</span></div><p><a href="/">Open current Dashboard</a> &nbsp; <a href="/history">Open immutable History</a></p><details><summary>Final governed summary</summary><pre>$safeResult</pre></details></section>
</main></body></html>
"@
}

function Get-DecisionRefreshFailureHtml {
    param([Parameter(Mandatory = $true)][string]$Message)

    $css = Get-AppCss
    $nav = Get-AppNav -Active 'dashboard'
    $safeMessage = ConvertTo-HtmlText $Message
    return @"
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Butler - Refresh blocked</title><style>$css</style></head>
<body><main class="shell">$nav<section class="panel"><div class="eyebrow">Manual governed refresh</div><h2 class="headline">Refresh blocked or stopped</h2><pre>$safeMessage</pre><p>Butler did not submit, cancel, or replace a Sleeper transaction and did not set FAAB.</p><p>If execution had already begun, earlier Butler evidence stages may have completed before the failure; later stages were stopped.</p><p><a href="/refresh">Return to refresh confirmation</a> &nbsp; <a href="/">Dashboard</a></p></section></main></body></html>
"@
}
