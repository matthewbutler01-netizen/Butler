param(
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$outputDir = Join-Path $repoRoot 'release-output'
$sourceBuilder = Join-Path $scriptDir 'butler-release-bundle.ps1'
$gradle = Join-Path $repoRoot 'gradlew.bat'
$runtimeLibSource = Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli\lib'
$packagedRuntimePrefix = 'bet/bet-cli/build/install/bet-cli/lib/'

$gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
if ($null -eq $gitCommand) {
    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
}
if ($null -eq $gitCommand) {
    throw 'BF-773 BLOCKED: Git executable is unavailable.'
}
$git = $gitCommand.Source

function Invoke-GitText {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    Push-Location $repoRoot
    try {
        $lines = @(& $git @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($exitCode -ne 0) {
        $text = ($lines | ForEach-Object { "$_" }) -join "`n"
        throw ("BF-773 BLOCKED: Git command failed: git {0}`n{1}" -f ($Arguments -join ' '), $text)
    }
    return (($lines | ForEach-Object { "$_" }) -join "`n").Trim()
}

$fullSha = Invoke-GitText -Arguments @('rev-parse', '--verify', 'HEAD^{commit}')
if ($fullSha -notmatch '^[0-9a-fA-F]{40}$') {
    throw 'BF-773 BLOCKED: HEAD did not resolve to one exact commit SHA.'
}
$fullSha = $fullSha.ToLowerInvariant()
$shortSha = $fullSha.Substring(0, 8)

$worktreeStatus = Invoke-GitText -Arguments @('status', '--porcelain', '--untracked-files=all')
if (-not [string]::IsNullOrWhiteSpace($worktreeStatus)) {
    throw "BF-773 BLOCKED: runtime release requires a clean tracked/untracked worktree before compiling the exact HEAD artifact.`n$worktreeStatus"
}
if (-not (Test-Path -LiteralPath $sourceBuilder -PathType Leaf)) {
    throw "BF-773 BLOCKED: BF-769 source release builder not found at $sourceBuilder"
}
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw "BF-773 BLOCKED: Gradle wrapper not found at $gradle"
}

$originalGradleOpts = $env:GRADLE_OPTS
$gradleNoDaemonOpt = '-Dorg.gradle.daemon=false'
try {
    $existing = [string]$env:GRADLE_OPTS
    if ([string]::IsNullOrWhiteSpace($existing)) {
        $env:GRADLE_OPTS = $gradleNoDaemonOpt
    }
    elseif ($existing.IndexOf($gradleNoDaemonOpt, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        $env:GRADLE_OPTS = $existing.TrimEnd() + ' ' + $gradleNoDaemonOpt
    }

    Push-Location $repoRoot
    try {
        $buildLines = @(& $gradle '--no-daemon' ':bet:bet-cli:installDist' 2>&1)
        $buildExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($buildExit -ne 0) {
        $buildText = ($buildLines | ForEach-Object { "$_" }) -join "`n"
        throw "BF-773 BLOCKED: prebuilt read runtime compilation failed with Gradle exit code $buildExit.`n$buildText"
    }
}
finally {
    if ($null -eq $originalGradleOpts) {
        Remove-Item Env:GRADLE_OPTS -ErrorAction SilentlyContinue
    }
    else {
        $env:GRADLE_OPTS = $originalGradleOpts
    }
}

if (-not (Test-Path -LiteralPath $runtimeLibSource -PathType Container)) {
    throw "BF-773 BLOCKED: prepared bet-cli runtime library not found at $runtimeLibSource"
}
$runtimeJars = @(Get-ChildItem -LiteralPath $runtimeLibSource -Filter '*.jar' -File -ErrorAction Stop)
if ($runtimeJars.Count -eq 0) {
    throw "BF-773 BLOCKED: prepared bet-cli runtime contains no JARs at $runtimeLibSource"
}
$appJars = @($runtimeJars | Where-Object { $_.Name -like 'bet-cli*.jar' })
if ($appJars.Count -ne 1) {
    throw "BF-773 BLOCKED: prepared runtime must contain exactly one bet-cli application JAR; found $($appJars.Count)."
}
$appJar = $appJars[0]
$appJarName = [string]$appJar.Name
if ([string]::IsNullOrWhiteSpace($appJarName)) {
    throw 'BF-775 BLOCKED: prepared bet-cli application JAR resolved without a filename.'
}
$requiredAppJarEntry = $packagedRuntimePrefix + $appJarName

[IO.Directory]::CreateDirectory($outputDir) | Out-Null
& $sourceBuilder -Force
$sourceZip = Join-Path $outputDir ("Butler-source-{0}.zip" -f $shortSha)
$sourceChecksum = $sourceZip + '.sha256'
if (-not (Test-Path -LiteralPath $sourceZip -PathType Leaf) -or
    -not (Test-Path -LiteralPath $sourceChecksum -PathType Leaf)) {
    throw 'BF-773 BLOCKED: exact BF-769 source artifact/checksum was not produced.'
}

$artifactName = "Butler-runtime-$shortSha.zip"
$artifactPath = Join-Path $outputDir $artifactName
$checksumPath = $artifactPath + '.sha256'
$manifestPath = Join-Path $outputDir "Butler-runtime-$shortSha.manifest.txt"
foreach ($path in @($artifactPath, $checksumPath, $manifestPath)) {
    if (Test-Path -LiteralPath $path) {
        if (-not $Force) {
            throw "BF-773 BLOCKED: runtime release output already exists at $path. Use -Force to replace the exact HEAD artifact."
        }
        Remove-Item -LiteralPath $path -Force
    }
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf773-bundle-{0}" -f [Guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    Expand-Archive -LiteralPath $sourceZip -DestinationPath $tempRoot -Force

    $packagedRuntimeLib = Join-Path $tempRoot 'bet\bet-cli\build\install\bet-cli\lib'
    [IO.Directory]::CreateDirectory($packagedRuntimeLib) | Out-Null
    foreach ($jar in $runtimeJars) {
        Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $packagedRuntimeLib $jar.Name) -Force
    }

    $packagedGradle = Join-Path $tempRoot 'gradlew.bat'
    $shim = @(
        '@echo off',
        'setlocal',
        'if /I not "%~1"=="--no-daemon" goto :blocked',
        'if /I not "%~2"==":bet:bet-cli:installDist" goto :blocked',
        'if not "%~3"=="" goto :blocked',
        'exit /b 0',
        ':blocked',
        'echo BF-773 BLOCKED: runtime package Gradle shim only authorizes the prebuilt installDist startup probe. 1>&2',
        'exit /b 77'
    ) -join "`r`n"
    [IO.File]::WriteAllText($packagedGradle, ($shim + "`r`n"), [Text.Encoding]::ASCII)

    $wrapperDir = Join-Path $tempRoot 'gradle'
    if (Test-Path -LiteralPath $wrapperDir) {
        Remove-Item -LiteralPath $wrapperDir -Recurse -Force
    }
    $unixGradle = Join-Path $tempRoot 'gradlew'
    if (Test-Path -LiteralPath $unixGradle) {
        Remove-Item -LiteralPath $unixGradle -Force
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $tempRoot,
        $artifactPath,
        [System.IO.Compression.CompressionLevel]::Optimal,
        $false
    )
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
    throw 'BF-773 BLOCKED: runtime release ZIP was not created.'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($artifactPath)
try {
    $entries = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
    foreach ($required in @(
        'scripts/butler-app.cmd',
        'scripts/butler-app.ps1',
        'scripts/butler-app-shell-core.ps1',
        'gradlew.bat',
        $requiredAppJarEntry
    )) {
        if ($entries -cnotcontains $required) {
            throw "BF-773 BLOCKED: runtime release archive is missing required entry $required"
        }
    }
    if ($entries -contains 'gradle/wrapper/gradle-wrapper.jar' -or $entries -contains 'gradlew') {
        throw 'BF-773 BLOCKED: runtime release must not contain the Gradle wrapper toolchain.'
    }

    foreach ($entry in $entries) {
        if ([string]::IsNullOrWhiteSpace($entry) -or $entry.EndsWith('/')) { continue }
        $lower = $entry.ToLowerInvariant()
        $name = [IO.Path]::GetFileName($entry).ToLowerInvariant()
        $segments = @($lower -split '/')
        $isPackagedRuntimeJar = $lower.StartsWith($packagedRuntimePrefix, [System.StringComparison]::Ordinal) -and $name.EndsWith('.jar')

        $forbidden = $false
        if ($name -ceq 'butler.db' -or
            $name.EndsWith('.db-journal') -or
            $name.EndsWith('.db-shm') -or
            $name.EndsWith('.db-wal') -or
            $name.EndsWith('.db.init.lock')) {
            $forbidden = $true
        }
        elseif (($name -ceq '.env' -or $name.StartsWith('.env.')) -and $name -cne '.env.example') {
            $forbidden = $true
        }
        elseif ($name.EndsWith('.pem') -or
                $name.EndsWith('.key') -or
                $name.EndsWith('.p12') -or
                $name.EndsWith('.pfx') -or
                $name.EndsWith('.jks') -or
                $name.EndsWith('.keystore') -or
                $name.EndsWith('.iml') -or
                $name -ceq '.ds_store' -or
                $name -ceq 'thumbs.db') {
            $forbidden = $true
        }
        elseif ($segments -contains '.git' -or
                $segments -contains '.gradle' -or
                $segments -contains '.idea' -or
                $segments -contains 'release-output') {
            $forbidden = $true
        }
        elseif (($segments -contains 'build') -and -not $isPackagedRuntimeJar) {
            $forbidden = $true
        }

        if ($forbidden) {
            throw "BF-773 BLOCKED: forbidden runtime/private/generated entry found in runtime release archive: $entry"
        }
    }
}
finally {
    $archive.Dispose()
}

$hash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($checksumPath, "$hash  $artifactName`r`n", [Text.Encoding]::ASCII)

$manifestLines = @(
    'BUTLER_RUNTIME_RELEASE_MANIFEST_V1',
    "commit=$fullSha",
    "short_commit=$shortSha",
    "artifact=$artifactName",
    "sha256=$hash",
    'boundary=CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA',
    'source=BF-769 git archive HEAD plus exact Gradle installDist JAR library',
    'packaged_runtime=bet/bet-cli/build/install/bet-cli/lib',
    'gradle_wrapper=REMOVED',
    'startup_gradle=FAIL_CLOSED_SHIM_EXACT_INSTALLDIST_PROBE_ONLY',
    'refresh_toolchain=BLOCKED_FAIL_CLOSED_IN_RUNTIME_PACKAGE',
    'host_java=REQUIRED',
    'runtime_data=EXTERNAL_GOVERNED_ONLY'
)
[IO.File]::WriteAllText($manifestPath, (($manifestLines -join "`r`n") + "`r`n"), [Text.Encoding]::ASCII)

Write-Host 'Butler prebuilt read-runtime release bundle (BF-773)'
Write-Host "Commit: $fullSha"
Write-Host "Artifact: $artifactPath"
Write-Host "SHA-256: $hash"
Write-Host "Checksum: $checksumPath"
Write-Host "Manifest: $manifestPath"
Write-Host "Runtime JARs: $($runtimeJars.Count)"
Write-Host 'Boundary: CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA; Gradle wrapper/toolchain absent; fail-closed shim authorizes only the exact prebuilt installDist startup probe; runtime database remains external.'
Write-Host 'BF-773 RUNTIME RELEASE BUNDLE: PASS'
