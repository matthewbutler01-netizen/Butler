Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-dashboard-bf983-replacement-decision-focus-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-983 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf983-replacement-focus-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-983 BLOCKED: staged Dashboard failed PowerShell parse: $summary"
    }

    function Get-OneFunction {
        param(
            [Parameter(Mandatory = $true)]$Ast,
            [Parameter(Mandatory = $true)][string]$Name
        )

        $matches = @($Ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $Name
        }, $true))
        if ($matches.Count -ne 1) {
            throw "BF-983 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0]
    }

    $helperText = (Get-OneFunction -Ast $ast -Name 'Convert-Bf983ReplacementFocusedCards').Extent.Text
    $waiverText = (Get-OneFunction -Ast $ast -Name 'ConvertTo-WaiverHtml').Extent.Text

    Invoke-Expression $helperText

    $fixture = '<div class="actions"><a class="button" href="/waivers/candidate/301?position=WR">View governed details</a><a class="button" href="/waivers/roster-compare?candidate=301&position=WR">Compare to roster</a><a class="button waiver-quick-primary" href="/waivers/roster-compare?candidate=301&roster=201&position=WR">Compare to held starter</a></div>'

    $focused = Convert-Bf983ReplacementFocusedCards -Cards $fixture -ReplacementContextActive $true
    if ($focused.IndexOf('>Compare to roster</a>', [System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-983 BLOCKED: exact replacement mode still exposes the competing generic roster compare action.'
    }
    foreach ($required in @(
        'View governed details',
        'Compare to held starter',
        'candidate=301&roster=201&position=WR'
    )) {
        if ($focused.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-983 BLOCKED: focused replacement card lost required action/context: $required"
        }
    }

    $normal = Convert-Bf983ReplacementFocusedCards -Cards $fixture -ReplacementContextActive $false
    if ($normal -cne $fixture) {
        throw 'BF-983 BLOCKED: normal Waiver Board cards changed outside exact replacement mode.'
    }

    foreach ($required in @(
        'button waiver-quick-primary',
        'Compare to held starter',
        '$cards = Convert-Bf983ReplacementFocusedCards -Cards $cards -ReplacementContextActive ($null -ne $replacementRosterPlayer)'
    )) {
        if ($waiverText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-983 BLOCKED: final Waiver Board decision-focus marker is missing: $required"
        }
    }

    $dashboardSource = [IO.File]::ReadAllText($dashboardPath)
    foreach ($required in @(
        'function Convert-Bf983ReplacementFocusedCards',
        'BF-983 Waiver replacement decision focus applied.'
    )) {
        if ($dashboardSource.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
            $required -ne 'BF-983 Waiver replacement decision focus applied.') {
            throw "BF-983 BLOCKED: final staged Dashboard marker is missing: $required"
        }
    }

    $surface = $helperText + [Environment]::NewLine + $waiverText
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-983 BLOCKED: replacement decision focus introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BF-983 WAIVER REPLACEMENT DECISION FOCUS ACCEPTANCE: PASS'
    Write-Host 'Coverage: exact replacement mode prioritizes held-starter compare; normal Waiver Board behavior remains unchanged'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
