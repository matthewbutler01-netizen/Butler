# BF-675/BF-676/BF-677/BF-678 native manual governed Butler refresh app module.
# BF-823 keeps GET confirmation-only and exact POST /refresh token-gated, then probes
# whether local lineup evidence recovery is required before falling back to BF-676.
# BF-824 simplifies only the manager-facing confirmation/completion presentation.

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

function Test-DecisionRefreshNoTransactionLineage {
    param([Parameter(Mandatory = $true)][string]$LineageState)

    return @(
        'LATEST_EVIDENCE_LINEAGE_VERIFIED',
        'MARKET_LINEAGE_SUPERSEDED',
        'WAIVER_LINEAGE_SUPERSEDED',
        'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED'
    ) -ccontains $LineageState
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
    # Missing/duplicate/unknown values simply omit the link. BF-676/BF-823 POST
    # preflight remains the only authorization for any Butler write.
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
        (Test-DecisionRefreshNoTransactionLineage -LineageState $bf631State)) {
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
    return $Html.Replace('</nav>', '<a href="/refresh">Refresh Butler data</a></nav>')
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
<title>Butler - Refresh Butler data</title>
<style>$css
.refresh-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-top:18px}.refresh-button{appearance:none;border:1px solid #3b82f6;border-radius:12px;background:#2563eb;color:#fff;font:inherit;font-weight:700;padding:12px 18px;cursor:pointer}.refresh-cancel{display:inline-block;padding:12px 0}.refresh-warning{margin-top:18px;padding:16px;border:1px solid #334155;border-radius:14px}.refresh-governance{margin-top:18px;padding:14px 16px;border:1px solid #334155;border-radius:14px}.refresh-governance summary{cursor:pointer;font-weight:700}.refresh-list{line-height:1.7;margin-bottom:0}.refresh-list li{margin:5px 0}
</style>
</head>
<body>
<main class="shell">
<div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$safeLeague</div></div>
$nav
<section class="panel">
<div class="eyebrow">Data refresh</div>
<div class="statusrow"><div><h2 class="headline">Refresh Butler's data?</h2><p class="lede">Butler found that some of its local fantasy data may be outdated. Refreshing will repair Butler's data and update the evidence used for recommendations.</p></div><span class="status done">CONFIRMATION REQUIRED</span></div>
<div class="refresh-warning"><strong>Nothing will be submitted to Sleeper.</strong><p>This refresh only updates Butler's local evidence. Butler still verifies the exact safety conditions before any Butler data is changed.</p></div>
<form method="post" action="/refresh">
<input type="hidden" name="token" value="$safeToken">
<div class="refresh-actions"><button class="refresh-button" type="submit">Confirm refresh</button><a class="refresh-cancel" href="/">Cancel</a></div>
</form>
<details class="refresh-governance"><summary>Technical details</summary><ul class="refresh-list"><li>This does not submit a lineup, waiver move, trade, or FAAB change to Sleeper.</li><li>BF-823 first performs a read-only roster/player recovery probe.</li><li>If current player mappings or exact roster evidence need repair, only the governed Butler-local recovery chain is allowed.</li><li>If lineup recovery is not needed, the unchanged BF-676 waiver refresh runner performs its existing strict preflight before any Butler evidence write.</li><li>An exact governed no-transaction decision remains eligible for a manual recheck under BF-675.</li><li>If Butler already has an actionable waiver recommendation, BF-676 proceeds only when the existing governed refresh plan is exactly authorized.</li><li>If any required state is ambiguous or unsafe, the refresh stops instead of guessing.</li></ul></details>
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

    if (-not (Test-Path -LiteralPath $RunnerPath -PathType Leaf)) {
        throw "BF-676 BLOCKED: refresh runner not found at $RunnerPath"
    }

    $recoveryRunner = Join-Path (Split-Path -Parent $RunnerPath) 'butler-lineup-evidence-recovery.ps1'
    if (-not (Test-Path -LiteralPath $recoveryRunner -PathType Leaf)) {
        throw "BF-823 BLOCKED: lineup evidence recovery runner not found at $recoveryRunner"
    }

    $probeLines = @(& $recoveryRunner -LeagueId $LeagueId -ProbeOnly)
    $probeText = (($probeLines | ForEach-Object { "$_" }) -join "`n")
    $requiresRecovery = $probeText -match '(?m)^BF-823 PROBE: RECOVERY_REQUIRED\s*$'
    $noRecovery = $probeText -match '(?m)^BF-823 PROBE: NO_RECOVERY_REQUIRED\s*$'
    if ($requiresRecovery -and $noRecovery) {
        throw 'BF-823 BLOCKED: lineup evidence recovery probe returned contradictory states.'
    }
    if ($requiresRecovery) {
        $resultLines = @(& $recoveryRunner -LeagueId $LeagueId)
        return ($resultLines -join "`n")
    }
    if (-not $noRecovery) {
        throw 'BF-823 BLOCKED: lineup evidence recovery probe did not return an exact governed state.'
    }

    # No lineup/roster recovery is required. Preserve the existing BF-676 runner and
    # its exact authorization gates without weakening or reimplementing them here.
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
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Butler - Data refreshed</title><style>$css
.refresh-success-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-top:18px}.refresh-primary,.refresh-secondary{display:inline-block;border-radius:12px;font-weight:700;padding:11px 16px;text-decoration:none}.refresh-primary{border:1px solid #3b82f6;background:#2563eb;color:#fff}.refresh-secondary{border:1px solid #334155}.refresh-tertiary{display:inline-block;padding:11px 0}.refresh-governance{margin-top:18px;padding:14px 16px;border:1px solid #334155;border-radius:14px}.refresh-governance summary{cursor:pointer;font-weight:700}
</style></head>
<body><main class="shell"><div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$safeLeague</div></div>$nav
<section class="panel"><div class="eyebrow">Data refresh</div><div class="statusrow"><div><h2 class="headline">Butler is up to date</h2><p class="lede">Your roster and player data were successfully refreshed. Butler can now use the repaired data for recommendations. No changes were submitted to Sleeper.</p></div><span class="status done">UP TO DATE</span></div><div class="refresh-success-actions"><a class="refresh-primary" href="/">Return to Dashboard</a><a class="refresh-secondary" href="/team">Review My Team</a><a class="refresh-tertiary" href="/history">View History</a></div><details class="refresh-governance"><summary>Technical details</summary><pre>$safeResult</pre></details></section>
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
<body><main class="shell">$nav<section class="panel"><div class="eyebrow">Explicit governed refresh</div><h2 class="headline">Butler data refresh stopped</h2><p class="lede">Butler could not prove that the requested recovery was safe to continue.</p><details open><summary>View refresh details</summary><pre>$safeMessage</pre></details><p>Butler did not submit, cancel, or replace a Sleeper transaction and did not set FAAB.</p><p>If an authorized Butler-local recovery had already begun, earlier Butler evidence stages may have completed before the failure; later stages were stopped.</p><p><a href="/refresh">Return to refresh confirmation</a> &nbsp; <a href="/">Dashboard</a></p></section></main></body></html>
"@
}
