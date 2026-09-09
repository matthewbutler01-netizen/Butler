from pathlib import Path

path = Path('scripts/butler-dashboard.ps1')
text = path.read_text(encoding='ascii')

function_marker = 'function Get-GovernedExplanationView {'
refresh_function = r'''function Get-GovernedManualRefreshPlanView {
    param([Parameter(Mandatory = $true)][string]$Summary)

    $decisionState = Get-LineValue -Text $Summary -Label "Decision status:"
    if ([string]::IsNullOrWhiteSpace($decisionState)) {
        throw "BF-655 BLOCKED: current governed decision state is missing"
    }
    if ($decisionState -cne "CURRENT_REFRESH_RECOMMENDED") {
        return [pscustomobject]@{
            Active = $false
            Policy = "none"
            State = "NOT_REQUIRED"
            Instruction = "none"
            Steps = @()
        }
    }

    $planMarkers = [regex]::Matches($Summary, '(?m)^BF-636 - governed MANUAL refresh plan\r?$')
    if ($planMarkers.Count -ne 1) {
        throw "BF-655 BLOCKED: CURRENT_REFRESH_RECOMMENDED requires exactly one BF-636 governed manual refresh plan"
    }
    $planText = $Summary.Substring($planMarkers[0].Index)
    $policy = Get-LineValue -Text $planText -Label "Plan policy:"
    $planState = Get-LineValue -Text $planText -Label "Plan state:"
    $instruction = Get-LineValue -Text $planText -Label "Operator instruction:"
    if ([string]::IsNullOrWhiteSpace($policy)) {
        throw "BF-655 BLOCKED: BF-636 plan policy is missing"
    }
    if ($planState -cne "MANUAL_REFRESH_PLAN_READY") {
        throw "BF-655 BLOCKED: CURRENT_REFRESH_RECOMMENDED requires BF-636 MANUAL_REFRESH_PLAN_READY"
    }
    if ([string]::IsNullOrWhiteSpace($instruction)) {
        throw "BF-655 BLOCKED: BF-636 operator instruction is missing"
    }

    $stepPattern = '(?m)^ {2}(?<order>\d+)\. (?<bf>[^|\r\n]+?) \| (?<mode>[^|\r\n]+?) \| (?<task>[^\r\n]+)\r?\n {5}(?<command>[^\r\n]+)\r?\n {5}Purpose: (?<purpose>[^\r\n]+)$'
    $stepMatches = [regex]::Matches($planText, $stepPattern)
    if ($stepMatches.Count -ne 9) {
        throw "BF-655 BLOCKED: BF-636 ready plan must contain exactly nine rendered steps"
    }

    $steps = @()
    for ($index = 0; $index -lt $stepMatches.Count; $index++) {
        $match = $stepMatches[$index]
        $order = [int]$match.Groups['order'].Value
        if ($order -ne ($index + 1)) {
            throw "BF-655 BLOCKED: BF-636 step order is malformed or non-contiguous"
        }
        $mode = $match.Groups['mode'].Value.Trim()
        if ($mode -cne "BUTLER_WRITE" -and $mode -cne "READ_ONLY") {
            throw "BF-655 BLOCKED: BF-636 step mode is unsupported: $mode"
        }
        $steps += [pscustomobject]@{
            Order = $order
            Bf = $match.Groups['bf'].Value.Trim()
            Mode = $match.Groups['mode'].Value.Trim()
            TaskName = $match.Groups['task'].Value.Trim()
            Command = $match.Groups['command'].Value.Trim()
            Purpose = $match.Groups['purpose'].Value.Trim()
        }
    }

    return [pscustomobject]@{
        Active = $true
        Policy = $policy
        State = $planState
        Instruction = $instruction
        Steps = @($steps)
    }
}

'''
if function_marker not in text:
    raise SystemExit('BF-655 function insertion marker missing')
if 'function Get-GovernedManualRefreshPlanView' in text:
    raise SystemExit('BF-655 refresh-plan function already present')
text = text.replace(function_marker, refresh_function + function_marker, 1)

css_marker = '.board-note{margin-top:16px;padding:15px 17px;border:1px solid #6a5427;border-radius:14px;background:#261f10;color:#f0d79a}'
css_addition = '.refresh-plan-note{margin-top:14px;padding:14px 16px;border:1px solid #6a5427;border-radius:14px;background:#261f10;color:#f0d79a}.refresh-steps{display:grid;gap:10px;margin-top:16px}.refresh-step{padding:15px 16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630}.refresh-step-head{display:flex;align-items:center;gap:9px;flex-wrap:wrap}.refresh-step-num{font-size:18px;font-weight:900}.refresh-step-bf{color:#a9c6ff;font-weight:800}.refresh-mode{display:inline-block;padding:4px 7px;border-radius:999px;font-size:10px;font-weight:900}.refresh-mode.read{background:#1c315c;color:#a9c6ff}.refresh-mode.write{background:#4b3713;color:#ffd98b}.refresh-task{margin-top:7px;font-weight:800}.refresh-command{margin-top:9px;padding:11px 12px;border-radius:10px;background:#080f20;border:1px solid #26345c;color:#c7d9ff;font-family:Consolas,monospace;font-size:12px;overflow-wrap:anywhere}.refresh-purpose{margin-top:8px;color:#aebada;font-size:12px}'
if css_marker not in text:
    raise SystemExit('BF-655 CSS marker missing')
text = text.replace(css_marker, css_addition + '\n' + css_marker, 1)

logic_marker = '    $explanation = Get-GovernedExplanationView -Summary $Summary\n    if ($explanation.Ready) {'
logic_addition = r'''    $refreshPlan = Get-GovernedManualRefreshPlanView -Summary $Summary
    $refreshPlanSection = ""
    if ($refreshPlan.Active) {
        $refreshCards = ""
        foreach ($step in $refreshPlan.Steps) {
            $modeClass = if ($step.Mode -ceq "READ_ONLY") { "read" } else { "write" }
            $refreshCards += @"
<article class="refresh-step">
  <div class="refresh-step-head"><span class="refresh-step-num">$(ConvertTo-HtmlText $step.Order)</span><span class="refresh-step-bf">$(ConvertTo-HtmlText $step.Bf)</span><span class="refresh-mode $modeClass">$(ConvertTo-HtmlText $step.Mode)</span></div>
  <div class="refresh-task">$(ConvertTo-HtmlText $step.TaskName)</div>
  <div class="refresh-command">$(ConvertTo-HtmlText $step.Command)</div>
  <div class="refresh-purpose">$(ConvertTo-HtmlText $step.Purpose)</div>
</article>
"@
        }
        $refreshPlanSection = @"
<section class="panel">
  <div class="eyebrow">Governed manual refresh plan</div>
  <h2>Refresh Butler evidence safely</h2>
  <p class="lede">Run these manually, one at a time, and inspect each result before continuing.</p>
  <div class="refresh-plan-note"><strong>MANUAL ONLY.</strong> BF-636 executes none of these commands, and this dashboard does not run them for you. `BUTLER_WRITE` means the listed CLI task persists governed Butler data; `READ_ONLY` means it only projects/revalidates governed state.</div>
  <div class="refresh-steps">$refreshCards</div>
  <details><summary>Technical details</summary><div class="tech"><div>BF-636 plan state: $(ConvertTo-HtmlText $refreshPlan.State)</div><div>BF-636 plan policy: $(ConvertTo-HtmlText $refreshPlan.Policy)</div><div>Governed step count: $($refreshPlan.Steps.Count)</div><div>Source: existing compact governed decision summary</div></div><div class="raw-guard">$(ConvertTo-HtmlText $refreshPlan.Instruction)</div></details>
</section>
"@
    }

    $explanation = Get-GovernedExplanationView -Summary $Summary
    if ($explanation.Ready) {'''
if logic_marker not in text:
    raise SystemExit('BF-655 dashboard logic marker missing')
text = text.replace(logic_marker, logic_addition, 1)

html_marker = '</section>\n$whySection\n<section class="panel"><div class="eyebrow">Safety checks</div>'
html_replacement = '</section>\n$refreshPlanSection\n$whySection\n<section class="panel"><div class="eyebrow">Safety checks</div>'
if html_marker not in text:
    raise SystemExit('BF-655 dashboard HTML marker missing')
text = text.replace(html_marker, html_replacement, 1)

boundary_old = '<section class="panel boundary"><span class="lock">READ ONLY.</span> Butler does not refresh evidence, rerank players, capture an audit, set FAAB, submit a Sleeper transaction, cancel a transaction, or mutate your league from this dashboard.</section>'
boundary_new = '<section class="panel boundary"><span class="lock">READ ONLY.</span> If BF-636 manual refresh instructions are shown, they are copyable operator instructions only. Butler does not execute those commands, refresh evidence, rerank players, capture an audit, set FAAB, submit a Sleeper transaction, cancel a transaction, or mutate your league from this dashboard.</section>'
if boundary_old not in text:
    raise SystemExit('BF-655 dashboard boundary marker missing')
text = text.replace(boundary_old, boundary_new, 1)

path.write_text(text, encoding='ascii')
