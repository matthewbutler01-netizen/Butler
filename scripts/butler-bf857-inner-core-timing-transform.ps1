param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]$env:BUTLER_APP_BF857_CORE_TIMING -cne '1') {
    throw 'BF-857 BLOCKED: inner-core timing transform requires BUTLER_APP_BF857_CORE_TIMING=1.'
}
foreach ($required in @($CorePath, $DashboardPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "BF-857 BLOCKED: staged runtime source missing at $required"
    }
}

function Replace-ExactOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Original,
        [Parameter(Mandatory = $true)][string]$Replacement,
        [Parameter(Mandatory = $true)][string]$Contract
    )
    $count = [regex]::Matches($Text, [regex]::Escape($Original)).Count
    if ($count -ne 1) {
        throw "BF-857 BLOCKED: $Contract contract count was $count, expected exactly 1."
    }
    return $Text.Replace($Original, $Replacement)
}

$coreText = [IO.File]::ReadAllText($CorePath)

$coreBootstrapOriginal = @'
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
'@
$coreBootstrapReplacement = @'
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$bf857CoreTimingEnabled = ([string]$env:BUTLER_APP_BF857_CORE_TIMING -ceq '1')

function Get-Bf857ElapsedMs {
    param([Parameter(Mandatory = $true)][long]$StartedTicks)
    $elapsedTicks = [System.Diagnostics.Stopwatch]::GetTimestamp() - $StartedTicks
    return ([double]$elapsedTicks * 1000.0) / [double][System.Diagnostics.Stopwatch]::Frequency
}
'@
$coreText = Replace-ExactOnce -Text $coreText -Original $coreBootstrapOriginal -Replacement $coreBootstrapReplacement -Contract 'preserved-core bootstrap'

$coreRequestOriginal = @'
    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$InnerPort$Path")
'@
$coreRequestReplacement = @'
    $bf857Started = if ($bf857CoreTimingEnabled -and $Path -ceq '/') {
        [System.Diagnostics.Stopwatch]::GetTimestamp()
    } else {
        [long]0
    }
    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$InnerPort$Path")
'@
$coreText = Replace-ExactOnce -Text $coreText -Original $coreRequestOriginal -Replacement $coreRequestReplacement -Contract 'preserved-core Dashboard proxy start'

$coreReturnOriginal = @'
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            StatusText = [string]$response.StatusDescription
            ContentType = if ([string]::IsNullOrWhiteSpace($response.ContentType)) { "text/plain; charset=utf-8" } else { [string]$response.ContentType }
            Body = $body
        }
'@
$coreReturnReplacement = @'
        $bf857Timing = $null
        if ($bf857CoreTimingEnabled -and $Path -ceq '/') {
            $preservedDashboardMs = Get-Bf857ElapsedMs -StartedTicks $bf857Started
            $childTiming = [string]$response.Headers['X-Butler-BF857-Timing']
            $bf857Timing = 'preserved_dashboard_ms=' + [string]::Format(
                [Globalization.CultureInfo]::InvariantCulture,
                '{0:0.0}',
                $preservedDashboardMs)
            if (-not [string]::IsNullOrWhiteSpace($childTiming)) {
                $bf857Timing += ';' + $childTiming
            }
        }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            StatusText = [string]$response.StatusDescription
            ContentType = if ([string]::IsNullOrWhiteSpace($response.ContentType)) { "text/plain; charset=utf-8" } else { [string]$response.ContentType }
            Body = $body
            Bf857Timing = $bf857Timing
        }
'@
$coreText = Replace-ExactOnce -Text $coreText -Original $coreReturnOriginal -Replacement $coreReturnReplacement -Contract 'preserved-core Dashboard proxy return'

$coreSendParamOriginal = @'
        [Parameter(Mandatory = $true)][string]$ContentType,
        [Parameter(Mandatory = $true)][string]$Body
    )
'@
$coreSendParamReplacement = @'
        [Parameter(Mandatory = $true)][string]$ContentType,
        [Parameter(Mandatory = $true)][string]$Body,
        [string]$Bf857Timing
    )
'@
$coreText = Replace-ExactOnce -Text $coreText -Original $coreSendParamOriginal -Replacement $coreSendParamReplacement -Contract 'preserved-core response timing parameter'

$coreHeaderOriginal = @'
    $headers = "HTTP/1.1 $StatusCode $StatusText`r`n" +
'@
$coreHeaderReplacement = @'
    $bf857Header = ''
    if ($bf857CoreTimingEnabled -and -not [string]::IsNullOrWhiteSpace($Bf857Timing)) {
        $bf857Header = 'X-Butler-BF857-Timing: ' + $Bf857Timing + "`r`n"
    }
    $headers = "HTTP/1.1 $StatusCode $StatusText`r`n" +
'@
$coreText = Replace-ExactOnce -Text $coreText -Original $coreHeaderOriginal -Replacement $coreHeaderReplacement -Contract 'preserved-core response timing header'

$coreConnectionOriginal = @'
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'`r`n" +
        "Connection: close`r`n`r`n"
'@
$coreConnectionReplacement = @'
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'`r`n" +
        $bf857Header +
        "Connection: close`r`n`r`n"
'@
$coreText = Replace-ExactOnce -Text $coreText -Original $coreConnectionOriginal -Replacement $coreConnectionReplacement -Contract 'preserved-core timing header placement'

$coreProxySendOriginal = @'
                Send-HttpResponse -Stream $stream -StatusCode $proxied.StatusCode -StatusText $proxied.StatusText -ContentType $proxied.ContentType -Body $body
'@
$coreProxySendReplacement = @'
                Send-HttpResponse -Stream $stream -StatusCode $proxied.StatusCode -StatusText $proxied.StatusText -ContentType $proxied.ContentType -Body $body -Bf857Timing $proxied.Bf857Timing
'@
$coreText = Replace-ExactOnce -Text $coreText -Original $coreProxySendOriginal -Replacement $coreProxySendReplacement -Contract 'preserved-core timing propagation'

$dashboardText = [IO.File]::ReadAllText($DashboardPath)

$dashboardBootstrapOriginal = @'
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
'@
$dashboardBootstrapReplacement = @'
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$bf857CoreTimingEnabled = ([string]$env:BUTLER_APP_BF857_CORE_TIMING -ceq '1')
$bf857Timing = $null

function Get-Bf857ElapsedMs {
    param([Parameter(Mandatory = $true)][long]$StartedTicks)
    $elapsedTicks = [System.Diagnostics.Stopwatch]::GetTimestamp() - $StartedTicks
    return ([double]$elapsedTicks * 1000.0) / [double][System.Diagnostics.Stopwatch]::Frequency
}

function Get-Bf869ProcessCpuMs {
    return [System.Diagnostics.Process]::GetCurrentProcess().TotalProcessorTime.TotalMilliseconds
}
'@
$dashboardText = Replace-ExactOnce -Text $dashboardText -Original $dashboardBootstrapOriginal -Replacement $dashboardBootstrapReplacement -Contract 'Dashboard timing bootstrap'

$summaryOriginal = @'
function Invoke-ButlerReadOnlySummary {
    return Invoke-Bf740PersistentCoreWorker -Operation 'LATEST_SUMMARY' -BoundaryName "BF-643"
}
'@
$summaryReplacement = @'
function Invoke-ButlerReadOnlySummary {
    $bf857SummaryStarted = if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        [System.Diagnostics.Stopwatch]::GetTimestamp()
    } else {
        [long]0
    }
    $bf869SummaryCpuStarted = if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        Get-Bf869ProcessCpuMs
    } else {
        [double]0.0
    }
    try {
        return Invoke-Bf740PersistentCoreWorker -Operation 'LATEST_SUMMARY' -BoundaryName "BF-643"
    }
    finally {
        if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
            $bf857Timing.dashboard_summary_ms = Get-Bf857ElapsedMs -StartedTicks $bf857SummaryStarted
            $bf857Timing.dashboard_summary_cpu_ms = [Math]::Max(0.0, (Get-Bf869ProcessCpuMs) - $bf869SummaryCpuStarted)
        }
    }
}
'@
$dashboardText = Replace-ExactOnce -Text $dashboardText -Original $summaryOriginal -Replacement $summaryReplacement -Contract 'Dashboard persistent summary timing'

$pathOriginal = @'
            $path = $parts[1].Split('?')[0]
'@
$pathReplacement = @'
            $path = $parts[1].Split('?')[0]
            $bf857Timing = $null
            if ($bf857CoreTimingEnabled -and $path -ceq '/') {
                $bf857Timing = @{
                    dashboard_process_id = [double]$PID
                    dashboard_summary_ms = 0.0
                    dashboard_summary_cpu_ms = 0.0
                    dashboard_html_ms = 0.0
                    dashboard_parse_base_ms = 0.0
                    dashboard_parse_base_cpu_ms = 0.0
                    dashboard_snapshot_ms = 0.0
                    dashboard_snapshot_cpu_ms = 0.0
                    dashboard_priority_ms = 0.0
                    dashboard_priority_cpu_ms = 0.0
                    dashboard_decision_ms = 0.0
                    dashboard_decision_cpu_ms = 0.0
                    dashboard_manager_ms = 0.0
                    dashboard_manager_cpu_ms = 0.0
                    dashboard_materialize_ms = 0.0
                    dashboard_materialize_cpu_ms = 0.0
                    dashboard_base_fields_ms = 0.0
                    dashboard_shell_ms = 0.0
                    dashboard_refresh_block_ms = 0.0
                    dashboard_next_plan_block_ms = 0.0
                    dashboard_explanation_lookup_ms = 0.0
                    dashboard_presnapshot_tail_ms = 0.0
                }
            }
'@
$dashboardText = Replace-ExactOnce -Text $dashboardText -Original $pathOriginal -Replacement $pathReplacement -Contract 'Dashboard exact-root timing scope'

$htmlOriginal = @'
                    $html = ConvertTo-DashboardHtml -Summary $summary
'@
$htmlReplacement = @'
                    $bf857HtmlStarted = if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
                        [System.Diagnostics.Stopwatch]::GetTimestamp()
                    } else {
                        [long]0
                    }
                    $html = ConvertTo-DashboardHtml -Summary $summary
                    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
                        $bf857Timing.dashboard_html_ms = Get-Bf857ElapsedMs -StartedTicks $bf857HtmlStarted
                    }
'@
$dashboardText = Replace-ExactOnce -Text $dashboardText -Original $htmlOriginal -Replacement $htmlReplacement -Contract 'Dashboard HTML assembly timing'

$dashboardSendParamOriginal = @'
        [Parameter(Mandatory=$true)][string]$Body
    )
'@
$dashboardSendParamReplacement = @'
        [Parameter(Mandatory=$true)][string]$Body,
        [hashtable]$Bf857Timing
    )
'@
$dashboardText = Replace-ExactOnce -Text $dashboardText -Original $dashboardSendParamOriginal -Replacement $dashboardSendParamReplacement -Contract 'Dashboard response timing parameter'

$dashboardHeaderOriginal = @'
    $headers = "HTTP/1.1 $StatusCode $StatusText`r`n" +
'@
$dashboardHeaderReplacement = @'
    $bf857Header = ''
    if ($bf857CoreTimingEnabled -and $null -ne $Bf857Timing) {
        $bf857Pairs = New-Object System.Collections.Generic.List[string]
        foreach ($bf857Key in @(
            'dashboard_process_id',
            'dashboard_summary_ms',
            'dashboard_summary_cpu_ms',
            'dashboard_html_ms',
            'dashboard_parse_base_ms',
            'dashboard_parse_base_cpu_ms',
            'dashboard_snapshot_ms',
            'dashboard_snapshot_cpu_ms',
            'dashboard_priority_ms',
            'dashboard_priority_cpu_ms',
            'dashboard_decision_ms',
            'dashboard_decision_cpu_ms',
            'dashboard_manager_ms',
            'dashboard_manager_cpu_ms',
            'dashboard_materialize_ms',
            'dashboard_materialize_cpu_ms',
            'dashboard_base_fields_ms',
            'dashboard_shell_ms',
            'dashboard_refresh_block_ms',
            'dashboard_next_plan_block_ms',
            'dashboard_explanation_lookup_ms',
            'dashboard_presnapshot_tail_ms'
        )) {
            $bf857Value = if ($Bf857Timing.ContainsKey($bf857Key)) { [double]$Bf857Timing[$bf857Key] } else { 0.0 }
            $bf857Pairs.Add(
                $bf857Key + '=' +
                [string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0:0.0}', $bf857Value))
        }
        $bf857Header = 'X-Butler-BF857-Timing: ' + ($bf857Pairs -join ';') + "`r`n"
    }
    $headers = "HTTP/1.1 $StatusCode $StatusText`r`n" +
'@
$dashboardText = Replace-ExactOnce -Text $dashboardText -Original $dashboardHeaderOriginal -Replacement $dashboardHeaderReplacement -Contract 'Dashboard response timing header'

$dashboardConnectionOriginal = @'
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'`r`n" +
        "Connection: close`r`n`r`n"
'@
$dashboardConnectionReplacement = @'
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'`r`n" +
        $bf857Header +
        "Connection: close`r`n`r`n"
'@
$dashboardText = Replace-ExactOnce -Text $dashboardText -Original $dashboardConnectionOriginal -Replacement $dashboardConnectionReplacement -Contract 'Dashboard timing header placement'

$dashboardSuccessSendOriginal = @'
                Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
'@
$dashboardSuccessSendReplacement = @'
                Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html -Bf857Timing $bf857Timing
'@
$dashboardText = Replace-ExactOnce -Text $dashboardText -Original $dashboardSuccessSendOriginal -Replacement $dashboardSuccessSendReplacement -Contract 'Dashboard timing propagation'

# BF-859: diagnostic-only renderer substage timing. This transform already runs
# last against the final staged Dashboard, so these anchors describe the exact
# manager renderer that BF-857 measured as dashboard_html_ms.
$dashboardStart = $dashboardText.IndexOf('function ConvertTo-DashboardHtml {', [StringComparison]::Ordinal)
$dashboardEnd = $dashboardText.IndexOf('function ConvertTo-TeamHtml {', $dashboardStart, [StringComparison]::Ordinal)
if ($dashboardStart -lt 0 -or $dashboardEnd -le $dashboardStart) {
    throw 'BF-859 BLOCKED: final Dashboard renderer boundary is missing.'
}
$dashboardBlock = $dashboardText.Substring($dashboardStart, $dashboardEnd - $dashboardStart)

$baseAnchor = '    $target = Get-LineValue -Text $Summary -Label "Target:"'
$snapshotAnchor = '    $lineupSnapshot = Get-Bf809AutoFillSnapshot -LeagueKey ([string]$LeagueId) -TargetHuman ([string]$target)'
$priorityAnchor = '    $priorityQueueHtml = $priorityCardList -join "`n"'
$managerAnchor = '    # BF-819 is presentation-only. It reuses the already-derived priority, evidence, record,'
$finalReturnToken = '    return @"'

foreach ($anchor in @($baseAnchor, $snapshotAnchor, $priorityAnchor, $managerAnchor)) {
    if ([regex]::Matches($dashboardBlock, [regex]::Escape($anchor)).Count -ne 1) {
        throw "BF-859 BLOCKED: renderer timing anchor missing or ambiguous: $anchor"
    }
}

$baseReplacement = @'
    $bf859StageStarted = if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        [System.Diagnostics.Stopwatch]::GetTimestamp()
    } else {
        [long]0
    }
    $bf860StageStarted = $bf859StageStarted
    $bf869StageCpuStarted = if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        Get-Bf869ProcessCpuMs
    } else {
        [double]0.0
    }
    $target = Get-LineValue -Text $Summary -Label "Target:"
'@
$dashboardBlock = $dashboardBlock.Replace($baseAnchor, $baseReplacement.TrimEnd())

# BF-860: split BF-859's pre-snapshot aggregate into helper-level buckets.
$bf860CssAnchor = '    $css = Get-SharedCss'
$bf860RefreshAnchor = '    $refreshPlan = Get-GovernedManualRefreshPlanView -Summary $Summary'
$bf860NextPlanAnchor = '    $nextDecisionPlan = Get-GovernedNextDecisionPlanView -Summary $Summary'
$bf860ExplanationAnchor = '    $explanation = Get-GovernedExplanationView -Summary $Summary'
foreach ($anchor in @($bf860CssAnchor, $bf860RefreshAnchor, $bf860NextPlanAnchor, $bf860ExplanationAnchor)) {
    if ([regex]::Matches($dashboardBlock, [regex]::Escape($anchor)).Count -ne 1) {
        throw "BF-860 BLOCKED: pre-snapshot timing anchor missing or ambiguous: $anchor"
    }
}

$bf860CssReplacement = @'
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_base_fields_ms = Get-Bf857ElapsedMs -StartedTicks $bf860StageStarted
        $bf860StageStarted = [System.Diagnostics.Stopwatch]::GetTimestamp()
    }
    $css = Get-SharedCss
'@
$dashboardBlock = $dashboardBlock.Replace($bf860CssAnchor, $bf860CssReplacement.TrimEnd())

$bf860RefreshReplacement = @'
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_shell_ms = Get-Bf857ElapsedMs -StartedTicks $bf860StageStarted
        $bf860StageStarted = [System.Diagnostics.Stopwatch]::GetTimestamp()
    }
    $refreshPlan = Get-GovernedManualRefreshPlanView -Summary $Summary
'@
$dashboardBlock = $dashboardBlock.Replace($bf860RefreshAnchor, $bf860RefreshReplacement.TrimEnd())

$bf860NextPlanReplacement = @'
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_refresh_block_ms = Get-Bf857ElapsedMs -StartedTicks $bf860StageStarted
        $bf860StageStarted = [System.Diagnostics.Stopwatch]::GetTimestamp()
    }
    $nextDecisionPlan = Get-GovernedNextDecisionPlanView -Summary $Summary
'@
$dashboardBlock = $dashboardBlock.Replace($bf860NextPlanAnchor, $bf860NextPlanReplacement.TrimEnd())

$bf860ExplanationReplacement = @'
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_next_plan_block_ms = Get-Bf857ElapsedMs -StartedTicks $bf860StageStarted
        $bf860ExplanationStarted = [System.Diagnostics.Stopwatch]::GetTimestamp()
    }
    $explanation = Get-GovernedExplanationView -Summary $Summary
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_explanation_lookup_ms = Get-Bf857ElapsedMs -StartedTicks $bf860ExplanationStarted
        $bf860StageStarted = [System.Diagnostics.Stopwatch]::GetTimestamp()
    }
'@
$dashboardBlock = $dashboardBlock.Replace($bf860ExplanationAnchor, $bf860ExplanationReplacement.TrimEnd())

$snapshotReplacement = @'
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_presnapshot_tail_ms = Get-Bf857ElapsedMs -StartedTicks $bf860StageStarted
        $bf857Timing.dashboard_parse_base_ms = Get-Bf857ElapsedMs -StartedTicks $bf859StageStarted
        $bf857Timing.dashboard_parse_base_cpu_ms = [Math]::Max(0.0, (Get-Bf869ProcessCpuMs) - $bf869StageCpuStarted)
        $bf859SnapshotStarted = [System.Diagnostics.Stopwatch]::GetTimestamp()
        $bf869StageCpuStarted = Get-Bf869ProcessCpuMs
    }
    $lineupSnapshot = Get-Bf809AutoFillSnapshot -LeagueKey ([string]$LeagueId) -TargetHuman ([string]$target)
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_snapshot_ms = Get-Bf857ElapsedMs -StartedTicks $bf859SnapshotStarted
        $bf857Timing.dashboard_snapshot_cpu_ms = [Math]::Max(0.0, (Get-Bf869ProcessCpuMs) - $bf869StageCpuStarted)
        $bf859StageStarted = [System.Diagnostics.Stopwatch]::GetTimestamp()
        $bf869StageCpuStarted = Get-Bf869ProcessCpuMs
    }
'@
$dashboardBlock = $dashboardBlock.Replace($snapshotAnchor, $snapshotReplacement.TrimEnd())

$priorityReplacement = @'
    $priorityQueueHtml = $priorityCardList -join "`n"
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_priority_ms = Get-Bf857ElapsedMs -StartedTicks $bf859StageStarted
        $bf857Timing.dashboard_priority_cpu_ms = [Math]::Max(0.0, (Get-Bf869ProcessCpuMs) - $bf869StageCpuStarted)
        $bf859StageStarted = [System.Diagnostics.Stopwatch]::GetTimestamp()
        $bf869StageCpuStarted = Get-Bf869ProcessCpuMs
    }
'@
$dashboardBlock = $dashboardBlock.Replace($priorityAnchor, $priorityReplacement.TrimEnd())

$managerReplacement = @'
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_decision_ms = Get-Bf857ElapsedMs -StartedTicks $bf859StageStarted
        $bf857Timing.dashboard_decision_cpu_ms = [Math]::Max(0.0, (Get-Bf869ProcessCpuMs) - $bf869StageCpuStarted)
        $bf859StageStarted = [System.Diagnostics.Stopwatch]::GetTimestamp()
        $bf869StageCpuStarted = Get-Bf869ProcessCpuMs
    }
    # BF-819 is presentation-only. It reuses the already-derived priority, evidence, record,
'@
$dashboardBlock = $dashboardBlock.Replace($managerAnchor, $managerReplacement.TrimEnd())

$finalReturn = $dashboardBlock.LastIndexOf($finalReturnToken, [StringComparison]::Ordinal)
$functionClose = $dashboardBlock.LastIndexOf('}', [StringComparison]::Ordinal)
if ($finalReturn -lt 0 -or $functionClose -le $finalReturn) {
    throw 'BF-859 BLOCKED: final Dashboard HTML return boundary is missing.'
}

$finalPrefix = @'
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_manager_ms = Get-Bf857ElapsedMs -StartedTicks $bf859StageStarted
        $bf857Timing.dashboard_manager_cpu_ms = [Math]::Max(0.0, (Get-Bf869ProcessCpuMs) - $bf869StageCpuStarted)
        $bf859StageStarted = [System.Diagnostics.Stopwatch]::GetTimestamp()
        $bf869StageCpuStarted = Get-Bf869ProcessCpuMs
    }
    $bf859HtmlResult = @"
'@
$dashboardBlock = $dashboardBlock.Substring(0, $finalReturn) + $finalPrefix.TrimEnd() + $dashboardBlock.Substring($finalReturn + $finalReturnToken.Length)

$functionClose = $dashboardBlock.LastIndexOf('}', [StringComparison]::Ordinal)
$finalSuffix = @'
    if ($bf857CoreTimingEnabled -and $null -ne $bf857Timing) {
        $bf857Timing.dashboard_materialize_ms = Get-Bf857ElapsedMs -StartedTicks $bf859StageStarted
        $bf857Timing.dashboard_materialize_cpu_ms = [Math]::Max(0.0, (Get-Bf869ProcessCpuMs) - $bf869StageCpuStarted)
    }
    return $bf859HtmlResult
'@
$dashboardBlock = $dashboardBlock.Substring(0, $functionClose) + $finalSuffix.TrimEnd() + "`r`n" + $dashboardBlock.Substring($functionClose)

$dashboardText = $dashboardText.Substring(0, $dashboardStart) + $dashboardBlock + $dashboardText.Substring($dashboardEnd)

[IO.File]::WriteAllText($CorePath, $coreText, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($DashboardPath, $dashboardText, [Text.UTF8Encoding]::new($false))
