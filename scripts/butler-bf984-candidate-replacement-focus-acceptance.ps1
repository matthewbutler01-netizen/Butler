Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-dashboard-bf984-candidate-replacement-focus-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-984 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf984-candidate-focus-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-984 BLOCKED: staged Dashboard failed PowerShell parse: $summary"
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
            throw "BF-984 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0]
    }

    $helperText = (Get-OneFunction -Ast $ast -Name 'Convert-Bf984ReplacementCandidateActions').Extent.Text
    $candidateText = (Get-OneFunction -Ast $ast -Name 'ConvertTo-WaiverCandidateDetailHtml').Extent.Text
    Invoke-Expression $helperText

    $fixture = '<div class="actions candidate-workflow-actions"><a class="button" href="/waivers/compare?left=301&position=WR">Compare candidate</a><a class="button" href="/waivers/roster-compare?candidate=301&roster=201&position=WR">Compare to replacement context</a><a class="button" href="/waivers?position=WR&roster=201">Back to Waiver Board</a></div>'

    $focused = Convert-Bf984ReplacementCandidateActions -Actions $fixture -ReplacementContextActive $true
    if ($focused.IndexOf('>Compare candidate</a>', [System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-984 BLOCKED: replacement Candidate Detail still exposes generic candidate compare.'
    }
    foreach ($required in @(
        'button waiver-quick-primary',
        'Compare to held starter',
        'candidate=301&roster=201&position=WR',
        'href="/waivers?position=WR&roster=201">Back to Waiver Board</a>'
    )) {
        if ($focused.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-984 BLOCKED: focused Candidate Detail lost required action/context: $required"
        }
    }

    $normal = Convert-Bf984ReplacementCandidateActions -Actions $fixture -ReplacementContextActive $false
    if ($normal -cne $fixture) {
        throw 'BF-984 BLOCKED: normal Candidate Detail actions changed outside replacement mode.'
    }

    foreach ($required in @(
        '$candidateWorkflowActions = Convert-Bf984ReplacementCandidateActions',
        'ReplacementContextActive (-not [string]::IsNullOrWhiteSpace($encodedRosterFocus))'
    )) {
        if ($candidateText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-984 BLOCKED: final Candidate Detail focus marker is missing: $required"
        }
    }

    $surface = $helperText + [Environment]::NewLine + $candidateText
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-984 BLOCKED: Candidate Detail replacement focus introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BF-984 CANDIDATE DETAIL REPLACEMENT FOCUS ACCEPTANCE: PASS'
    Write-Host 'Coverage: replacement Candidate Detail exposes one held-starter compare path and preserves exact Board return context'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
