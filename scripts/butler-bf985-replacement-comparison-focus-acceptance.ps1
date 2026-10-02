Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-dashboard-bf985-replacement-comparison-focus-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-985 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf985-comparison-focus-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-985 BLOCKED: staged Dashboard failed PowerShell parse: $summary"
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
            throw "BF-985 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0]
    }

    $helperText = (Get-OneFunction -Ast $ast -Name 'Convert-Bf985ReplacementComparisonActions').Extent.Text
    $rosterText = (Get-OneFunction -Ast $ast -Name 'ConvertTo-WaiverRosterCompareHtml').Extent.Text
    Invoke-Expression $helperText

    $fixture = '<div class="actions" style="margin-top:14px"><a class="button" href="/waivers/roster-compare?candidate=301&position=WR">Compare another roster player</a><a class="button" href="/waivers/compare?left=301&position=WR">Compare with waiver candidate</a><a class="button" href="/waivers/candidate/301?position=WR&roster=201">Back to candidate</a><a class="button" href="/waivers?position=WR&roster=201">Back to Waiver Board</a></div>'

    $focused = Convert-Bf985ReplacementComparisonActions -Actions $fixture -ReplacementContextActive $true
    foreach ($forbidden in @(
        'Compare another roster player',
        'Compare with waiver candidate'
    )) {
        if ($focused.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
            throw "BF-985 BLOCKED: replacement comparison retained generic action: $forbidden"
        }
    }
    foreach ($required in @(
        'href="/waivers/candidate/301?position=WR&roster=201">Back to candidate</a>',
        'button waiver-quick-primary',
        'href="/waivers?position=WR&roster=201">Back to replacement candidates</a>'
    )) {
        if ($focused.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-985 BLOCKED: replacement comparison lost required focused action/context: $required"
        }
    }

    $normal = Convert-Bf985ReplacementComparisonActions -Actions $fixture -ReplacementContextActive $false
    if ($normal -cne $fixture) {
        throw 'BF-985 BLOCKED: normal roster comparison actions changed outside replacement mode.'
    }

    foreach ($required in @(
        '$replacementComparisonActive = $false',
        '[string]$replacementComparedRoster.RosterSlot -ceq ''STARTER''',
        '$replacementComparisonActions = Convert-Bf985ReplacementComparisonActions',
        'href="/waivers/candidate/$candidateHref$waiverReplacementBoardSuffix">'
    )) {
        if ($rosterText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-985 BLOCKED: final Roster Compare focus marker is missing: $required"
        }
    }

    $surface = $helperText + [Environment]::NewLine + $rosterText
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-985 BLOCKED: replacement comparison focus introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BF-985 REPLACEMENT COMPARISON FOCUS ACCEPTANCE: PASS'
    Write-Host 'Coverage: same-position held-starter comparison removes generic branches and preserves exact candidate/Board replacement context'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
