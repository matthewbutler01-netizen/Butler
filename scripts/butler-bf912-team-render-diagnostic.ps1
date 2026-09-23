Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$coreSource = Join-Path $scriptDir 'butler-app-shell-core-single.ps1'
$dashboardSource = Join-Path $scriptDir 'butler-dashboard.ps1'
$stageTransform = Join-Path $scriptDir 'butler-dashboard-bf715-transform.ps1'
$runtimeLib = Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli\lib'

$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-912 BLOCKED: LocalApplicationData is unavailable.'
}

$configDir = Join-Path $localAppData 'Butler'
$dataDir = Join-Path $configDir 'data'
$leaguePath = Join-Path $configDir 'app-league.txt'

foreach ($required in @($coreSource, $dashboardSource, $stageTransform, $runtimeLib, $dataDir, $leaguePath)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "BF-912 BLOCKED: required staged-render diagnostic component is missing: $required"
    }
}

$leagueId = [IO.File]::ReadAllText($leaguePath, [Text.Encoding]::ASCII).Trim()
$leagueGuid = [Guid]::Empty
if ([string]::IsNullOrWhiteSpace($leagueId) -or -not [Guid]::TryParse($leagueId, [ref]$leagueGuid)) {
    throw 'BF-912 BLOCKED: configured Butler league id is invalid.'
}
$leagueId = $leagueGuid.ToString('D').ToLowerInvariant()

try {
    $java = (Get-Command java.exe -ErrorAction Stop).Source
}
catch {
    throw 'BF-912 BLOCKED: java.exe is unavailable.'
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf912-team-render-' + [Guid]::NewGuid().ToString('N'))
$tempScripts = Join-Path $tempRoot 'scripts'
$stagedCore = Join-Path $tempScripts 'butler-app-shell-core-single.ps1'
$stagedDashboard = Join-Path $tempScripts 'butler-dashboard.ps1'

$previousRuntimeLib = [Environment]::GetEnvironmentVariable('BUTLER_APP_RUNTIME_LIB', 'Process')
$previousRepoRoot = [Environment]::GetEnvironmentVariable('BUTLER_APP_REPO_ROOT', 'Process')
$previousDataDir = [Environment]::GetEnvironmentVariable('BUTLER_APP_DATA_DIR', 'Process')

function Restore-Bf912Environment {
    param([Parameter(Mandatory = $true)][string]$Name, [AllowNull()][string]$Value)

    if ($null -eq $Value) {
        Remove-Item ("Env:{0}" -f $Name) -ErrorAction SilentlyContinue
    }
    else {
        [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    }
}

function Invoke-Bf912RenderStep {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )

    try {
        $value = & $Action
        Write-Host ("BF-912 {0}: PASS" -f $Name)
        return ,$value
    }
    catch {
        $detail = [string]$_
        if ($null -ne $_.Exception) {
            $detail += " | ExceptionType=$($_.Exception.GetType().FullName) | ExceptionMessage=$($_.Exception.Message)"
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$_.FullyQualifiedErrorId)) {
            $detail += " | ErrorId=$($_.FullyQualifiedErrorId)"
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$_.ScriptStackTrace)) {
            $detail += " | Stack=$($_.ScriptStackTrace)"
        }
        throw ("BF-912 TEAM RENDER FAILED at {0}: {1}" -f $Name, $detail)
    }
}

try {
    New-Item -ItemType Directory -Path $tempScripts -Force | Out-Null
    Copy-Item -LiteralPath $coreSource -Destination $stagedCore -Force
    Copy-Item -LiteralPath $dashboardSource -Destination $stagedDashboard -Force

    $env:BUTLER_APP_RUNTIME_LIB = $runtimeLib
    $env:BUTLER_APP_REPO_ROOT = $repoRoot
    $env:BUTLER_APP_DATA_DIR = $dataDir

    Write-Host 'Butler BF-912 exact staged My Team render diagnostic'
    Write-Host ("League: {0}" -f $leagueId)
    Write-Host ("Data: {0}" -f $dataDir)
    Write-Host 'Boundary: temporary staging + read-only local TEAM_BUNDLE only; no /refresh, provider fetch, Butler evidence write, or Sleeper write.'
    Write-Host ''

    Write-Host 'BF-912 staging exact production transform chain...'
    & $stageTransform -DashboardPath $stagedDashboard
    Write-Host 'BF-912 STAGING: PASS'

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($stagedCore, [ref]$tokens, [ref]$parseErrors)
    if (@($parseErrors).Count -gt 0) {
        $summary = (@($parseErrors) | ForEach-Object {
            "line $($_.Extent.StartLineNumber): $($_.Message)"
        }) -join '; '
        throw "BF-912 BLOCKED: final staged core failed PowerShell parse: $summary"
    }

    $functions = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
    }, $true))

    foreach ($function in $functions) {
        Invoke-Expression $function.Extent.Text
    }
    Write-Host ("BF-912 FINAL FUNCTIONS: PASS ({0} loaded)" -f $functions.Count)

    $classPath = Join-Path $runtimeLib '*'
    $previousPreference = $ErrorActionPreference
    $bundleLines = $null
    $bundleExit = $null
    Push-Location $dataDir
    try {
        try {
            $ErrorActionPreference = 'Continue'
            $bundleLines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classPath 'io.butler.bet.cli.ButlerMyTeamEvidenceBundleCli' $leagueId 2>&1
            $bundleExit = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    finally {
        Pop-Location
    }

    $bundleText = (($bundleLines | ForEach-Object { "$_" }) -join [Environment]::NewLine)
    if ($bundleExit -ne 0) {
        $compact = [regex]::Replace($bundleText, '\s+', ' ').Trim()
        if ($compact.Length -gt 2400) { $compact = '...' + $compact.Substring($compact.Length - 2400) }
        throw "BF-912 TEAM BUNDLE FAILED: exit=$bundleExit; output=$compact"
    }
    Write-Host 'BF-912 DIRECT TEAM BUNDLE: PASS'

    $rosterText = Invoke-Bf912RenderStep -Name 'ROSTER_CONTEXT section' -Action {
        Get-TeamEvidenceBundleSection -Text $bundleText -Name 'ROSTER_CONTEXT'
    }
    $roster = Invoke-Bf912RenderStep -Name 'ROSTER_CONTEXT parser' -Action {
        ConvertTo-RosterContextView -Text $rosterText
    }
    $teamId = [string]$roster.ButlerTeamId

    $context = Invoke-Bf912RenderStep -Name 'TEAM_CONTEXT parser' -Action {
        $section = Get-TeamEvidenceBundleSection -Text $bundleText -Name 'TEAM_CONTEXT'
        ConvertTo-TeamContextView -Text $section -TeamId $teamId
    }
    $strength = Invoke-Bf912RenderStep -Name 'ROSTER_STRENGTH parser' -Action {
        $section = Get-TeamEvidenceBundleSection -Text $bundleText -Name 'ROSTER_STRENGTH'
        ConvertTo-RosterStrengthView -Text $section -TeamId $teamId
    }
    $pressure = Invoke-Bf912RenderStep -Name 'POSITIONAL_PRESSURE parser' -Action {
        $section = Get-TeamEvidenceBundleSection -Text $bundleText -Name 'POSITIONAL_PRESSURE'
        ConvertTo-PositionalPressureView -Text $section -TeamId $teamId
    }
    $posture = Invoke-Bf912RenderStep -Name 'TEAM_POSTURE parser' -Action {
        $section = Get-TeamEvidenceBundleSection -Text $bundleText -Name 'TEAM_POSTURE'
        ConvertTo-TeamPostureView -Text $section -TeamId $teamId
    }
    $capital = Invoke-Bf912RenderStep -Name 'FUTURE_CAPITAL parser' -Action {
        $section = Get-TeamEvidenceBundleSection -Text $bundleText -Name 'FUTURE_CAPITAL'
        ConvertTo-FutureCapitalView -Text $section -TeamId $teamId
    }
    $autoFill = Invoke-Bf912RenderStep -Name 'idle Lineup Advisor view' -Action {
        New-AutoFillIdleView
    }

    $html = Invoke-Bf912RenderStep -Name 'FINAL ConvertTo-TeamHtml' -Action {
        ConvertTo-TeamHtml -Roster $roster -Context $context -Strength $strength -Pressure $pressure -Posture $posture -Capital $capital -AutoFill $autoFill
    }

    foreach ($marker in @(
        '<title>Butler - My Team</title>',
        'Roster hub',
        'Lineup and depth at a glance',
        'Player Search',
        'Player Compare',
        'Roster construction',
        'Future flexibility',
        'READ ONLY.'
    )) {
        if ([string]$html -notlike ('*' + $marker + '*')) {
            throw "BF-912 TEAM RENDER FAILED: final HTML is missing marker: $marker"
        }
    }

    Write-Host ''
    Write-Host ("BF-912 FINAL HTML: PASS ({0} chars)" -f ([string]$html).Length)
    Write-Host 'BF-912 TEAM RENDER RESULT: COMPLETE'
    Write-Host 'Interpretation: final staged parser/render path is healthy; any remaining /team HTTP 500 is in runtime internal routing/worker transport, not My Team parsing or presentation.'
}
finally {
    Restore-Bf912Environment -Name 'BUTLER_APP_RUNTIME_LIB' -Value $previousRuntimeLib
    Restore-Bf912Environment -Name 'BUTLER_APP_REPO_ROOT' -Value $previousRepoRoot
    Restore-Bf912Environment -Name 'BUTLER_APP_DATA_DIR' -Value $previousDataDir

    if (Test-Path -LiteralPath $tempRoot) {
        try { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction Stop } catch {}
    }
}
