Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-dashboard-bf960-waiver-candidate-actions-transform.ps1'
$transformTokens = $null
$transformErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$transformTokens, [ref]$transformErrors)
if (@($transformErrors).Count -ne 0) {
    $summary = (@($transformErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-960 BLOCKED: transform script failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf960-waiver-candidate-' + [guid]::NewGuid().ToString('N'))
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
        throw 'BF-960 BLOCKED: staged Dashboard has parse errors.'
    }

    $function = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-WaiverCandidateDetailHtml'
    }, $true)
    if ($null -eq $function) {
        throw 'BF-960 BLOCKED: staged Waiver Candidate Detail renderer is missing.'
    }

    $candidate = $function.Extent.Text
    foreach ($required in @(
        '$candidateWorkflowActions',
        'if ([string]$Candidate.SleeperId -match ''^[0-9]+$'') {',
        '$candidateWorkflowHrefId = [System.Uri]::EscapeDataString([string]$Candidate.SleeperId)',
        'candidate-workflow-actions',
        'href="/waivers/compare?left=',
        '">Compare candidate</a>',
        'href="/waivers/roster-compare?candidate=',
        '">Compare to roster</a>',
        '">Back to Waiver Board</a>'
    )) {
        if ($candidate.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-960 BLOCKED: staged candidate workflow marker is missing: $required"
        }
    }

    $surfaceStart = $candidate.IndexOf('$candidateWorkflowActions =', [System.StringComparison]::Ordinal)
    $surfaceEnd = $candidate.IndexOf('return @"', $surfaceStart, [System.StringComparison]::Ordinal)
    if ($surfaceStart -lt 0 -or $surfaceEnd -le $surfaceStart) {
        throw 'BF-960 BLOCKED: candidate workflow surface could not be isolated.'
    }

    $surface = $candidate.Substring($surfaceStart, $surfaceEnd - $surfaceStart)
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-960 BLOCKED: candidate workflow surface introduced provider, optimizer, FAAB, or write behavior.'
    }

    Write-Host 'BF-960 WAIVER CANDIDATE WORKFLOW ACTIONS ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
