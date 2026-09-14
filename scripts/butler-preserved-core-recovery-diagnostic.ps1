Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Restore-Bf757EnvironmentValue {
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

function Invoke-Bf757Git {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

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

function Test-Bf757BytesEqual {
    param(
        [Parameter(Mandatory = $true)][byte[]]$Left,
        [Parameter(Mandatory = $true)][byte[]]$Right
    )

    if ($Left.Length -ne $Right.Length) { return $false }
    for ($index = 0; $index -lt $Left.Length; $index++) {
        if ($Left[$index] -ne $Right[$index]) { return $false }
    }
    return $true
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$corePath = Join-Path $repoRoot 'scripts\butler-app-shell-core.ps1'
$acceptance = Join-Path $repoRoot 'scripts\butler-acceptance.cmd'
$gitCommand = Get-Command git.exe -ErrorAction Stop
$git = $gitCommand.Source
$originalCoreBytes = $null
$corePatched = $false
$locationPushed = $false

$originalPersistentWorker = [Environment]::GetEnvironmentVariable('BUTLER_APP_PERSISTENT_CORE_WORKER', [EnvironmentVariableTarget]::Process)
$originalTransportPrewarm = [Environment]::GetEnvironmentVariable('BUTLER_APP_SLEEPER_TRANSPORT_PREWARM', [EnvironmentVariableTarget]::Process)
$originalCoreWarmup = [Environment]::GetEnvironmentVariable('BUTLER_APP_CORE_POOL_WARMUP', [EnvironmentVariableTarget]::Process)

try {
    if (-not (Test-Path -LiteralPath $corePath -PathType Leaf)) {
        throw "BF-757 BLOCKED: production core script is missing at $corePath"
    }
    if (-not (Test-Path -LiteralPath $acceptance -PathType Leaf)) {
        throw "BF-757 BLOCKED: acceptance command is missing at $acceptance"
    }

    Push-Location $repoRoot
    $locationPushed = $true
    $cleanResult = Invoke-Bf757Git -Arguments @('diff', '--quiet', '--', 'scripts/butler-app-shell-core.ps1')
    Pop-Location
    $locationPushed = $false
    if ($cleanResult.ExitCode -ne 0) {
        throw 'BF-757 BLOCKED: scripts\butler-app-shell-core.ps1 already has local changes; refusing temporary fault injection.'
    }

    $originalCoreBytes = [System.IO.File]::ReadAllBytes($corePath)
    $source = [System.Text.Encoding]::UTF8.GetString($originalCoreBytes)

    foreach ($required in @('function Restart-PreservedCore {', '$hasExited = [bool]$backend.Process.HasExited', 'Restart-PreservedCore -BackendIndex $index')) {
        if ($source.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-757 BLOCKED: production recovery contract is missing '$required'."
        }
    }

    $marker = '    $requestPool.Open()'
    $markerCount = [regex]::Matches($source, [regex]::Escape($marker)).Count
    if ($markerCount -ne 1) {
        throw "BF-757 BLOCKED: expected exactly one request-pool open marker, found $markerCount."
    }

    $injection = @'
    if ($backendProcesses.Count -lt 1) {
        throw 'BF-757 fault injection requires at least one preserved core.'
    }
    Write-Warning ("BF-757 fault injection: stopping owned preserved core on port {0} before public dispatch." -f $backendProcesses[0].Port)
    Stop-OwnedProcessTree -Process $backendProcesses[0].Process

'@

    $patched = $source.Replace($marker, $injection + $marker)
    [System.IO.File]::WriteAllText($corePath, $patched, [System.Text.UTF8Encoding]::new($false))
    $corePatched = $true

    Remove-Item Env:BUTLER_APP_PERSISTENT_CORE_WORKER -ErrorAction SilentlyContinue
    Remove-Item Env:BUTLER_APP_SLEEPER_TRANSPORT_PREWARM -ErrorAction SilentlyContinue
    Remove-Item Env:BUTLER_APP_CORE_POOL_WARMUP -ErrorAction SilentlyContinue

    Write-Host 'BF-757 fault injection: one owned preserved core will be stopped after startup and before public dispatch.'
    Write-Host 'BF-757 expected behavior: scheduler detects the exited process, starts a fresh core, waits for health, warms it, and serves unchanged GET-only acceptance without 502.'
    Write-Host 'BF-757 isolation: production checkout core file is patched only for this diagnostic and exact original bytes are restored in finally.'
    Write-Host 'BF-757 boundary: /refresh excluded; no Butler or Sleeper write path is invoked; no provider concurrency is added.'
    Write-Host ''

    Push-Location $repoRoot
    $locationPushed = $true
    & $acceptance
    $acceptanceExit = $LASTEXITCODE
    Pop-Location
    $locationPushed = $false
    if ($acceptanceExit -ne 0) {
        throw "BF-757 BLOCKED: preserved-core recovery fault-injection acceptance failed with exit code $acceptanceExit."
    }
}
finally {
    if ($locationPushed) {
        try { Pop-Location } catch {}
    }

    Restore-Bf757EnvironmentValue -Name 'BUTLER_APP_PERSISTENT_CORE_WORKER' -Value $originalPersistentWorker
    Restore-Bf757EnvironmentValue -Name 'BUTLER_APP_SLEEPER_TRANSPORT_PREWARM' -Value $originalTransportPrewarm
    Restore-Bf757EnvironmentValue -Name 'BUTLER_APP_CORE_POOL_WARMUP' -Value $originalCoreWarmup

    if ($corePatched -and $null -ne $originalCoreBytes) {
        try {
            [System.IO.File]::WriteAllBytes($corePath, $originalCoreBytes)
            $restored = [System.IO.File]::ReadAllBytes($corePath)
            if (-not (Test-Bf757BytesEqual -Left $originalCoreBytes -Right $restored)) {
                throw 'restored bytes do not match the original core file'
            }
        }
        catch {
            throw "BF-757 CLEANUP FAILED: unable to restore scripts\butler-app-shell-core.ps1 exactly. $($_.Exception.Message)"
        }
    }
}
