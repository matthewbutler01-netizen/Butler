param(
    [ValidateRange(2, 6)]
    [int]$CoreWorkers = 4
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Restore-Bf752EnvironmentValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowNull()][string]$Value
    )

    if ($null -eq $Value) {
        Remove-Item ("Env:{0}" -f $Name) -ErrorAction SilentlyContinue
    }
    else {
        [Environment]::SetEnvironmentVariable($Name, $Value, [EnvironmentVariableTarget]::Process)
    }
}

function Invoke-Bf752Git {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $git @Arguments 2>&1
        $exitCode = $LASTEXITCODE
        return [pscustomobject]@{
            Output = @($output)
            ExitCode = $exitCode
        }
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$gitCommand = Get-Command git.exe -ErrorAction Stop
$git = $gitCommand.Source
$tempRoot = [System.IO.Path]::GetTempPath()
$worktreePath = Join-Path $tempRoot ("Butler-bf752-{0}-{1}" -f $PID, [Guid]::NewGuid().ToString('N'))
$worktreeAdded = $false
$worktreeAddAttempted = $false
$locationPushed = $false

$originalPersistentWorker = [Environment]::GetEnvironmentVariable('BUTLER_APP_PERSISTENT_CORE_WORKER', [EnvironmentVariableTarget]::Process)
$originalTransportPrewarm = [Environment]::GetEnvironmentVariable('BUTLER_APP_SLEEPER_TRANSPORT_PREWARM', [EnvironmentVariableTarget]::Process)
$originalCoreWarmup = [Environment]::GetEnvironmentVariable('BUTLER_APP_CORE_POOL_WARMUP', [EnvironmentVariableTarget]::Process)

try {
    Push-Location $repoRoot
    $locationPushed = $true
    $worktreeAddAttempted = $true
    $worktreeResult = Invoke-Bf752Git -Arguments @('worktree', 'add', '--detach', $worktreePath, 'HEAD')
    Pop-Location
    $locationPushed = $false
    if ($worktreeResult.ExitCode -ne 0) {
        throw "BF-752 BLOCKED: unable to create detached diagnostic worktree. $($worktreeResult.Output -join ' ')"
    }
    $worktreeAdded = $true

    $worktreeCore = Join-Path $worktreePath 'scripts\butler-app-shell-core.ps1'
    if (-not (Test-Path -LiteralPath $worktreeCore -PathType Leaf)) {
        throw "BF-752 BLOCKED: temporary worktree core script is missing at $worktreeCore"
    }

    $source = [System.IO.File]::ReadAllText($worktreeCore)
    $needle = '$maxCoreWorkers = 6'
    $replacement = '$maxCoreWorkers = ' + $CoreWorkers
    $matchCount = [regex]::Matches($source, [regex]::Escape($needle)).Count
    if ($matchCount -ne 1) {
        throw "BF-752 BLOCKED: expected exactly one preserved-core width contract, found $matchCount."
    }
    $source = $source.Replace($needle, $replacement)
    [System.IO.File]::WriteAllText($worktreeCore, $source, [System.Text.UTF8Encoding]::new($false))

    Remove-Item Env:BUTLER_APP_PERSISTENT_CORE_WORKER -ErrorAction SilentlyContinue
    Remove-Item Env:BUTLER_APP_SLEEPER_TRANSPORT_PREWARM -ErrorAction SilentlyContinue
    Remove-Item Env:BUTLER_APP_CORE_POOL_WARMUP -ErrorAction SilentlyContinue

    $acceptance = Join-Path $worktreePath 'scripts\butler-acceptance.cmd'
    if (-not (Test-Path -LiteralPath $acceptance -PathType Leaf)) {
        throw "BF-752 BLOCKED: acceptance command is missing at $acceptance"
    }

    Write-Host ("BF-752 temporary preserved-core width: {0}" -f $CoreWorkers)
    Write-Host 'BF-752 isolation: detached temporary Git worktree only; production checkout is not modified.'
    Write-Host 'BF-752 boundary: unchanged BF-698/BF-688 GET-only acceptance; BF-742/BF-748/BF-751 defaults enabled; /refresh excluded; no Butler or Sleeper write path is invoked.'
    Write-Host ''

    Push-Location $worktreePath
    $locationPushed = $true
    & $acceptance
    $acceptanceExit = $LASTEXITCODE
    Pop-Location
    $locationPushed = $false
    if ($acceptanceExit -ne 0) {
        throw "BF-752 BLOCKED: temporary $CoreWorkers-core acceptance failed with exit code $acceptanceExit."
    }
}
finally {
    if ($locationPushed) {
        try { Pop-Location } catch {}
    }

    Restore-Bf752EnvironmentValue -Name 'BUTLER_APP_PERSISTENT_CORE_WORKER' -Value $originalPersistentWorker
    Restore-Bf752EnvironmentValue -Name 'BUTLER_APP_SLEEPER_TRANSPORT_PREWARM' -Value $originalTransportPrewarm
    Restore-Bf752EnvironmentValue -Name 'BUTLER_APP_CORE_POOL_WARMUP' -Value $originalCoreWarmup

    if ($worktreeAdded -or $worktreeAddAttempted -or (Test-Path -LiteralPath $worktreePath)) {
        try {
            Push-Location $repoRoot
            try {
                [void](Invoke-Bf752Git -Arguments @('worktree', 'remove', '--force', $worktreePath))
                [void](Invoke-Bf752Git -Arguments @('worktree', 'prune'))
            }
            finally {
                Pop-Location
            }
        }
        catch {
            Write-Warning ("BF-752 cleanup warning: unable to remove temporary worktree {0}: {1}" -f $worktreePath, $_.Exception.Message)
        }
    }

    if (Test-Path -LiteralPath $worktreePath) {
        try { Remove-Item -LiteralPath $worktreePath -Recurse -Force -ErrorAction Stop } catch {
            Write-Warning ("BF-752 cleanup warning: temporary directory remains at {0}: {1}" -f $worktreePath, $_.Exception.Message)
        }
    }
}
