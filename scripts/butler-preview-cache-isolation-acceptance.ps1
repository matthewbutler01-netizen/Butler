Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$path=Join-Path $PSScriptRoot 'butler-bf808-autofill-command-center-transform.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
if (@($errors).Count) { throw 'Preview transform does not parse.' }
$helpers=@($ast.FindAll({param($n)
    $n -is [Management.Automation.Language.StringConstantExpressionAst] -and
    $n.Value.StartsWith('function Get-Bf808AutoFillSnapshotPath {')
},$true))
if ($helpers.Count -ne 2) { throw 'Core/Dashboard helper pair missing.' }
$original=[Environment]::GetEnvironmentVariable('BUTLER_APP_PREVIEW_DIR','Process')
try {
    $results=@()
    foreach($helper in $helpers) {
        # These insertion strings end with another renderer's opening brace.
        # Parse the complete path helper before that next function anchor.
        $next=$helper.Value.IndexOf("`nfunction ",[StringComparison]::Ordinal)
        if ($next -lt 0) { throw 'Generated path-helper boundary missing.' }
        $helperAst=[Management.Automation.Language.Parser]::ParseInput($helper.Value.Substring(0,$next),[ref]$tokens,[ref]$errors)
        if (@($errors).Count) { throw 'Generated preview helpers do not parse.' }
        $fn=@($helperAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Get-Bf808AutoFillSnapshotPath'},$true))
        if ($fn.Count -ne 1) { throw 'Ambiguous generated preview path function.' }
        . ([scriptblock]::Create($fn[0].Extent.Text))
        [Environment]::SetEnvironmentVariable('BUTLER_APP_PREVIEW_DIR',$null,'Process')
        $default=Get-Bf808AutoFillSnapshotPath -LeagueKey 'fixture-league'
        $ownerRoot=[Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
        if ([string]::IsNullOrWhiteSpace($ownerRoot)) {$ownerRoot=$env:LOCALAPPDATA}
        if ([string]::IsNullOrWhiteSpace($ownerRoot)) {$ownerRoot=[IO.Path]::GetTempPath()}
        $expected=Join-Path $ownerRoot 'Butler\autofill-preview\fixture-league.json'
        if ($default -cne $expected) { throw 'Default owner preview path changed.' }
        $isolated=Join-Path ([IO.Path]::GetTempPath()) ('butler-preview-fixture-'+[guid]::NewGuid().ToString('N'))
        $env:BUTLER_APP_PREVIEW_DIR=$isolated
        $actual=Get-Bf808AutoFillSnapshotPath -LeagueKey '../fixture:league'
        if ((Split-Path -Parent $actual) -cne $isolated -or (Split-Path -Leaf $actual) -cne '.._fixture_league.json') { throw 'Override escaped its selected cache directory.' }
        if (Test-Path -LiteralPath $isolated) { throw 'Path resolution unexpectedly created or wrote the cache.' }
        foreach($bad in @('relative-cache','.\cache')) {
            $env:BUTLER_APP_PREVIEW_DIR=$bad;$rejected=$false
            try { [void](Get-Bf808AutoFillSnapshotPath -LeagueKey 'fixture-league') } catch {$rejected=$true}
            if (-not $rejected) {throw 'Relative override silently fell back to owner state.'}
        }
        $results+=([string]$fn[0].Extent.Text)
    }
    if ($results[0] -cne $results[1]) { throw 'Core and Dashboard use different preview path contracts.' }
}
finally { [Environment]::SetEnvironmentVariable('BUTLER_APP_PREVIEW_DIR',$original,'Process') }
Write-Host 'PREVIEW CACHE ISOLATION: PASS (default preserved, absolute override, relative rejection, matching readers/writers, no filesystem writes).'
