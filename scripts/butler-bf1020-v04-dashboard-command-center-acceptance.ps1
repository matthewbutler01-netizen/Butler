Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$dashboardTransform = Join-Path $PSScriptRoot 'butler-dashboard-bf907-decision-center-transform.ps1'
$historyTransform = Join-Path $PSScriptRoot 'butler-dashboard-bf969-history-shortcut-transform.ps1'
$requestWorker = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'

foreach ($path in @($dashboardTransform, $historyTransform, $requestWorker)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "BF-1020 BLOCKED: required source missing at $path"
    }
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1020-dashboard-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-1020 BLOCKED: staged Dashboard has parse errors: $summary"
    }

    $functions = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-DashboardHtml'
    }, $true))
    if ($functions.Count -ne 1) {
        throw "BF-1020 BLOCKED: expected one staged Dashboard renderer, found $($functions.Count)."
    }

    $dashboard = $functions[0].Extent.Text

    # Exercise the full BF-715-to-v0.4 staged renderer, not an invented
    # template: public automatic recheck requires one real audited ID and
    # exact state, actionability, lineage, and plan fields from this surface.
    foreach ($field in @(
        '<div class="butler-refresh-contract" hidden>',
        'Decision state: $(ConvertTo-HtmlText $state)',
        'BF-629: $(ConvertTo-HtmlText $bf629)',
        'BF-631: $(ConvertTo-HtmlText $bf631)',
        'BF-636 plan state: $(ConvertTo-HtmlText $refreshPlan.State)',
        'BF-636 plan policy: $(ConvertTo-HtmlText $refreshPlan.Policy)',
        'Governed step count: $($refreshPlan.Steps.Count)',
        'Audit ID: $(ConvertTo-HtmlText $audit.Id)'
    )) {
        if ($dashboard.IndexOf($field, [StringComparison]::Ordinal) -lt 0) {
            throw "BF-1020 BLOCKED: staged Dashboard lacks refresh contract field $field"
        }
    }
    if ([regex]::Matches($dashboard, [regex]::Escape('Audit ID: $(ConvertTo-HtmlText $audit.Id)')).Count -ne 1) {
        throw 'BF-1020 BLOCKED: staged audited Dashboard identity must be unique.'
    }
    foreach ($required in @(
        'BF-1020 v0.4 Dashboard Command Center',
        'dashboard-summary-row',
        '<span>Attention</span>',
        '<span>Start/Sit</span>',
        '<span>Waivers</span>',
        '<span>Roster</span>',
        'dashboard-quick-tools',
        '<h2>More manager tools</h2>',
        'href="/history?load=1">Decision History</a>',
        '.manager-hero{padding:14px 18px!important',
        '.week-glance-card:nth-child(2)',
        'content:"START/SIT ASSISTANT"',
        '.week-glance-card .week-tools{display:flex',
        '.week-glance-card .week-tool{display:inline-flex',
        '.top{padding:15px 20px!important'
    )) {
        if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1020 BLOCKED: staged Dashboard marker is missing: $required"
        }
    }

    $weekStart = $dashboard.IndexOf('$bf907WeekGlanceHtml = @"', [System.StringComparison]::Ordinal)
    $weekEnd = $dashboard.IndexOf('"@', $weekStart + 24, [System.StringComparison]::Ordinal)
    if ($weekStart -lt 0 -or $weekEnd -le $weekStart) {
        throw 'BF-1020 BLOCKED: staged week-at-a-glance source block is missing.'
    }
    $weekBlock = $dashboard.Substring($weekStart, $weekEnd - $weekStart)
    if ($weekBlock.IndexOf('dashboard-quick-tools', [System.StringComparison]::Ordinal) -ge 0 -or
        $weekBlock.IndexOf('Decision History', [System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-1020 BLOCKED: secondary Quick Tools still live inside the primary week overview.'
    }

    $quickToolsIndex = $dashboard.IndexOf('$bf1020QuickToolsHtml', [System.StringComparison]::Ordinal)
    $footerIndex = $dashboard.LastIndexOf('<div class="manager-readonly"><strong>Butler is read only.</strong>', [System.StringComparison]::Ordinal)
    if ($quickToolsIndex -lt 0 -or $footerIndex -lt 0 -or $quickToolsIndex -ge $footerIndex) {
        throw 'BF-1020 BLOCKED: lower Quick Tools placement is not preserved before the read-only footer.'
    }

    $worker = [IO.File]::ReadAllText($requestWorker)
    foreach ($required in @(
        'padding:14px 8px;border:1px solid var(--line)',
        'padding:8px 11px;border-radius:8px',
        'padding:7px 11px 11px'
    )) {
        if ($worker.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-1020 BLOCKED: tightened Playbook rail marker is missing: $required"
        }
    }

    Write-Host 'BF-1020 V0.4 DASHBOARD COMMAND CENTER ACCEPTANCE: PASS'
    Write-Host 'Coverage: compact attention banner, primary weekly overview, existing-state summary row, stronger Start/Sit card, lower secondary tools, tighter Playbook rail.'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
