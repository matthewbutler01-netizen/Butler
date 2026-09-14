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

function Test-Bf752BytesEqual {
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
        throw "BF-752 BLOCKED: production core script is missing at $corePath"
    }
    if (-not (Test-Path -LiteralPath $acceptance -PathType Leaf)) {
        throw "BF-752 BLOCKED: acceptance command is missing at $acceptance"
    }

    Push-Location $repoRoot
    $locationPushed = $true
    $cleanResult = Invoke-Bf752Git -Arguments @('diff', '--quiet', '--', 'scripts/butler-app-shell-core.ps1')
    Pop-Location
    $locationPushed = $false
    if ($cleanResult.ExitCode -ne 0) {
        throw 'BF-752 BLOCKED: scripts\butler-app-shell-core.ps1 already has local changes; refusing temporary diagnostic patch.'
    }

    $originalCoreBytes = [System.IO.File]::ReadAllBytes($corePath)
    $source = [System.Text.Encoding]::UTF8.GetString($originalCoreBytes)
    $needle = '$maxCoreWorkers = 6'
    $replacement = '$maxCoreWorkers = ' + $CoreWorkers
    $matchCount = [regex]::Matches($source, [regex]::Escape($needle)).Count
    if ($matchCount -ne 1) {
        throw "BF-752 BLOCKED: expected exactly one preserved-core width contract, found $matchCount."
    }

    $patched = $source.Replace($needle, $replacement)
    [System.IO.File]::WriteAllText($corePath, $patched, [System.Text.UTF8Encoding]::new($false))
    $corePatched = $true

    Remove-Item Env:BUTLER_APP_PERSISTENT_CORE_WORKER -ErrorAction SilentlyContinue
    Remove-Item Env:BUTLER_APP_SLEEPER_TRANSPORT_PREWARM -ErrorAction SilentlyContinue
    Remove-Item Env:BUTLER_APP_CORE_POOL_WARMUP -ErrorAction SilentlyContinue

    Write-Host ("BF-752 temporary preserved-core width: {0}" -f $CoreWorkers)
    Write-Host 'BF-752 isolation: production checkout core file is patched only for this diagnostic and exact original bytes are restored in finally.'
    Write-Host 'BF-752 boundary: unchanged BF-698/BF-688 GET-only acceptance; BF-742/BF-748/BF-751 defaults enabled; /refresh excluded; no Butler or Sleeper write path is invoked.'
    Write-Host ''

    Push-Location $repoRoot
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

    if ($corePatched -and $null -ne $originalCoreBytes) {
        try {
            [System.IO.File]::WriteAllBytes($corePath, $originalCoreBytes)
            $restored = [System.IO.File]::ReadAllBytes($corePath)
            if (-not (Test-Bf752BytesEqual -Left $originalCoreBytes -Right $restored)) {
                throw 'restored bytes do not match the original core file'
            }
        }
        catch {
            throw "BF-752 CLEANUP FAILED: unable to restore scripts\butler-app-shell-core.ps1 exactly. $($_.Exception.Message)"
        }
    }
}
