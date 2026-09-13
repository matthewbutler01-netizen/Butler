Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$gradle = Join-Path $repoRoot 'gradlew.bat'
$generatedClasses = Join-Path $repoRoot 'bet\bet-cli\build\classes\java\main'
$runtimeLib = Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli\lib'
$originalGradleOpts = $env:GRADLE_OPTS
$gradleNoDaemonOpt = '-Dorg.gradle.daemon=false'

if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw "BF-714 BLOCKED: Gradle wrapper not found at $gradle"
}

function Enable-ButlerGradleNoDaemon {
    $existing = [string]$env:GRADLE_OPTS
    if ([string]::IsNullOrWhiteSpace($existing)) {
        $env:GRADLE_OPTS = $gradleNoDaemonOpt
        return
    }
    if ($existing.IndexOf($gradleNoDaemonOpt, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        $env:GRADLE_OPTS = $existing.TrimEnd() + ' ' + $gradleNoDaemonOpt
    }
}

function Restore-GradleOpts {
    if ($null -eq $originalGradleOpts) {
        Remove-Item Env:GRADLE_OPTS -ErrorAction SilentlyContinue
    }
    else {
        $env:GRADLE_OPTS = $originalGradleOpts
    }
}

function Invoke-InstallDist {
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    Push-Location $repoRoot
    try {
        try {
            $ErrorActionPreference = 'Continue'
            $lines = & $gradle '--no-daemon' ':bet:bet-cli:installDist' 2>&1
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    finally {
        Pop-Location
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Text = (($lines | ForEach-Object { "$_" }) -join "`n")
    }
}

function Get-BoundedTail {
    param([AllowNull()][string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return '' }
    $normalized = [regex]::Replace($Text, '\s+', ' ').Trim()
    if ($normalized.Length -gt 1600) {
        return '...' + $normalized.Substring($normalized.Length - 1600)
    }
    return $normalized
}

function Test-StaleGeneratedClassesFailure {
    param([Parameter(Mandatory = $true)][string]$Text)
    if ($Text.IndexOf('Unable to delete directory', [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        return $false
    }
    return $Text.IndexOf($generatedClasses, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
}

try {
    Enable-ButlerGradleNoDaemon
    $result = Invoke-InstallDist

    if ($result.ExitCode -ne 0 -and (Test-StaleGeneratedClassesFailure -Text $result.Text)) {
        Write-Host 'BF-714: recovering stale generated bet-cli classes before Butler startup.'
        if (Test-Path -LiteralPath $generatedClasses) {
            try {
                Remove-Item -LiteralPath $generatedClasses -Recurse -Force -ErrorAction Stop
            }
            catch {
                $tail = Get-BoundedTail -Text $result.Text
                throw "BF-714 BLOCKED: targeted generated-class cleanup failed at $generatedClasses. $($_.Exception.Message) Original Gradle failure: $tail"
            }
        }
        $result = Invoke-InstallDist
    }

    if ($result.ExitCode -ne 0) {
        $tail = Get-BoundedTail -Text $result.Text
        throw "BF-714 BLOCKED: read-only Butler runtime preflight failed with Gradle exit code $($result.ExitCode). $tail"
    }

    if (-not (Test-Path -LiteralPath $runtimeLib -PathType Container)) {
        throw "BF-714 BLOCKED: prepared Butler runtime library directory not found at $runtimeLib"
    }
    $jars = @(Get-ChildItem -LiteralPath $runtimeLib -Filter '*.jar' -File -ErrorAction Stop)
    if ($jars.Count -eq 0) {
        throw "BF-714 BLOCKED: prepared Butler runtime contains no jars at $runtimeLib"
    }
}
finally {
    Restore-GradleOpts
}
