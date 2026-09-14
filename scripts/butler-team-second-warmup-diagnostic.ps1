Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Restore-Bf755EnvironmentValue {
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

function Invoke-Bf755Git {
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

function Test-Bf755BytesEqual {
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

$teamWarmupFunction = @'
function Invoke-Bf755PreservedCoreTeamWarmup {
    param([Parameter(Mandatory = $true)][int]$BackendPort)

    if ([string]$env:BUTLER_APP_CORE_POOL_WARMUP -ceq '0') {
        return
    }

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$BackendPort/team")
    $request.Method = 'GET'
    $request.Timeout = 3000
    $request.ReadWriteTimeout = 3000
    $request.Proxy = $null
    $request.KeepAlive = $false
    $response = $null
    try {
        $response = $request.GetResponse()
        if ([int]$response.StatusCode -ne 200) {
            throw "HTTP $([int]$response.StatusCode)"
        }
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try {
            [void]$reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    catch {
        Write-Warning ("BF-755 preserved-core team warmup skipped on port {0}: {1}" -f $BackendPort, $_.Exception.Message)
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

'@

try {
    if (-not (Test-Path -LiteralPath $corePath -PathType Leaf)) {
        throw "BF-755 BLOCKED: production core script is missing at $corePath"
    }
    if (-not (Test-Path -LiteralPath $acceptance -PathType Leaf)) {
        throw "BF-755 BLOCKED: acceptance command is missing at $acceptance"
    }

    Push-Location $repoRoot
    $locationPushed = $true
    $cleanResult = Invoke-Bf755Git -Arguments @('diff', '--quiet', '--', 'scripts/butler-app-shell-core.ps1')
    Pop-Location
    $locationPushed = $false
    if ($cleanResult.ExitCode -ne 0) {
        throw 'BF-755 BLOCKED: scripts\butler-app-shell-core.ps1 already has local changes; refusing temporary diagnostic patch.'
    }

    $originalCoreBytes = [System.IO.File]::ReadAllBytes($corePath)
    $source = [System.Text.Encoding]::UTF8.GetString($originalCoreBytes)

    $productionWaiverTarget = 'http://127.0.0.1:$BackendPort/waivers'
    $waiverTargetCount = [regex]::Matches($source, [regex]::Escape($productionWaiverTarget)).Count
    if ($waiverTargetCount -ne 1) {
        throw "BF-755 BLOCKED: expected exactly one production /waivers warmup target, found $waiverTargetCount."
    }

    $functionMarker = 'function Stop-OwnedProcessTree {'
    $functionMarkerCount = [regex]::Matches($source, [regex]::Escape($functionMarker)).Count
    if ($functionMarkerCount -ne 1) {
        throw "BF-755 BLOCKED: expected exactly one warmup function insertion marker, found $functionMarkerCount."
    }

    $warmCall = '        Invoke-PreservedCoreWarmup -BackendPort $backendPort'
    $warmCallCount = [regex]::Matches($source, [regex]::Escape($warmCall)).Count
    if ($warmCallCount -ne 1) {
        throw "BF-755 BLOCKED: expected exactly one production warmup call, found $warmCallCount."
    }

    $patched = $source.Replace($functionMarker, $teamWarmupFunction + $functionMarker)
    $patched = $patched.Replace(
        $warmCall,
        $warmCall + "`r`n        Invoke-Bf755PreservedCoreTeamWarmup -BackendPort `$backendPort")
    [System.IO.File]::WriteAllText($corePath, $patched, [System.Text.UTF8Encoding]::new($false))
    $corePatched = $true

    Remove-Item Env:BUTLER_APP_PERSISTENT_CORE_WORKER -ErrorAction SilentlyContinue
    Remove-Item Env:BUTLER_APP_SLEEPER_TRANSPORT_PREWARM -ErrorAction SilentlyContinue
    Remove-Item Env:BUTLER_APP_CORE_POOL_WARMUP -ErrorAction SilentlyContinue

    Write-Host 'BF-755 temporary preserved-core warmup profile: /waivers then /team'
    Write-Host 'BF-755 isolation: production checkout core file is patched only for this diagnostic and exact original bytes are restored in finally.'
    Write-Host 'BF-755 boundary: unchanged six-core BF-698/BF-688 GET-only acceptance; production /waivers warmup preserved; one additional sequential best-effort /team warm read per core; /refresh excluded; no Butler or Sleeper write path is invoked.'
    Write-Host ''

    Push-Location $repoRoot
    $locationPushed = $true
    & $acceptance
    $acceptanceExit = $LASTEXITCODE
    Pop-Location
    $locationPushed = $false
    if ($acceptanceExit -ne 0) {
        throw "BF-755 BLOCKED: temporary waiver-plus-team warmup acceptance failed with exit code $acceptanceExit."
    }
}
finally {
    if ($locationPushed) {
        try { Pop-Location } catch {}
    }

    Restore-Bf755EnvironmentValue -Name 'BUTLER_APP_PERSISTENT_CORE_WORKER' -Value $originalPersistentWorker
    Restore-Bf755EnvironmentValue -Name 'BUTLER_APP_SLEEPER_TRANSPORT_PREWARM' -Value $originalTransportPrewarm
    Restore-Bf755EnvironmentValue -Name 'BUTLER_APP_CORE_POOL_WARMUP' -Value $originalCoreWarmup

    if ($corePatched -and $null -ne $originalCoreBytes) {
        try {
            [System.IO.File]::WriteAllBytes($corePath, $originalCoreBytes)
            $restored = [System.IO.File]::ReadAllBytes($corePath)
            if (-not (Test-Bf755BytesEqual -Left $originalCoreBytes -Right $restored)) {
                throw 'restored bytes do not match the original core file'
            }
        }
        catch {
            throw "BF-755 CLEANUP FAILED: unable to restore scripts\butler-app-shell-core.ps1 exactly. $($_.Exception.Message)"
        }
    }
}
