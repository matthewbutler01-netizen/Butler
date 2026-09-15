param(
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$outputDir = Join-Path $repoRoot 'release-output'
$sourceBuilder = Join-Path $scriptDir 'butler-release-bundle.ps1'
$gradlew = Join-Path $repoRoot 'gradlew.bat'

$gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
if ($null -eq $gitCommand) { $gitCommand = Get-Command git -ErrorAction SilentlyContinue }
if ($null -eq $gitCommand) { throw 'BF-773 BLOCKED: Git executable is unavailable.' }
$git = $gitCommand.Source

if (-not (Test-Path -LiteralPath $sourceBuilder -PathType Leaf)) {
    throw "BF-773 BLOCKED: BF-769 release builder not found at $sourceBuilder"
}
if (-not (Test-Path -LiteralPath $gradlew -PathType Leaf)) {
    throw "BF-773 BLOCKED: Gradle wrapper launcher not found at $gradlew"
}

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

$status = Invoke-GitText -Arguments @('status', '--porcelain=v1', '--untracked-files=all')
if (-not [string]::IsNullOrWhiteSpace($status)) {
    throw "BF-773 BLOCKED: tracked or untracked worktree changes are present. Commit or remove them before building the runtime release.`n$status"
}

Write-Host "BF-773: building exact installDist runtime for $shortSha."
Push-Location $repoRoot
try {
    & $gradlew '--no-daemon' ':bet:bet-cli:installDist'
    $gradleExit = $LASTEXITCODE
}
finally {
    Pop-Location
}
if ($gradleExit -ne 0) {
    throw "BF-773 BLOCKED: :bet:bet-cli:installDist failed with exit code $gradleExit."
}

$installLib = Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli\lib'
if (-not (Test-Path -LiteralPath $installLib -PathType Container)) {
    throw "BF-773 BLOCKED: installDist library not found at $installLib"
}
$jarFiles = @(Get-ChildItem -LiteralPath $installLib -File -Filter '*.jar' -ErrorAction Stop)
if ($jarFiles.Count -lt 1) {
    throw 'BF-773 BLOCKED: installDist library contains no JAR files.'
}
$applicationJars = @($jarFiles | Where-Object { $_.Name -like 'bet-cli-*.jar' })
if ($applicationJars.Count -ne 1) {
    throw "BF-773 BLOCKED: expected exactly one bet-cli application JAR, found $($applicationJars.Count)."
}
$nonJarFiles = @(Get-ChildItem -LiteralPath $installLib -File -ErrorAction Stop | Where-Object { $_.Extension -cne '.jar' })
if ($nonJarFiles.Count -ne 0) {
    throw 'BF-773 BLOCKED: installDist library contains non-JAR files.'
}

& $sourceBuilder -Force
$sourceZip = Join-Path $outputDir ("Butler-source-{0}.zip" -f $shortSha)
if (-not (Test-Path -LiteralPath $sourceZip -PathType Leaf)) {
    throw "BF-773 BLOCKED: exact BF-769 source artifact was not produced at $sourceZip"
}

[IO.Directory]::CreateDirectory($outputDir) | Out-Null
$artifactName = "Butler-runtime-$shortSha.zip"
$artifactPath = Join-Path $outputDir $artifactName
$checksumPath = $artifactPath + '.sha256'
$manifestPath = Join-Path $outputDir "Butler-runtime-$shortSha.manifest.txt"
foreach ($path in @($artifactPath, $checksumPath, $manifestPath)) {
    if (Test-Path -LiteralPath $path) {
        if (-not $Force) {
            throw "BF-773 BLOCKED: release output already exists at $path. Use -Force to replace the exact HEAD artifact."
        }
        Remove-Item -LiteralPath $path -Force
    }
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf773-build-{0}" -f [Guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    Expand-Archive -LiteralPath $sourceZip -DestinationPath $tempRoot -Force

    $packagedGradleDir = Join-Path $tempRoot 'gradle'
    $packagedUnixGradlew = Join-Path $tempRoot 'gradlew'
    $packagedGradlewBat = Join-Path $tempRoot 'gradlew.bat'
    if (Test-Path -LiteralPath $packagedGradleDir) { Remove-Item -LiteralPath $packagedGradleDir -Recurse -Force }
    if (Test-Path -LiteralPath $packagedUnixGradlew) { Remove-Item -LiteralPath $packagedUnixGradlew -Force }

    $packagedLib = Join-Path $tempRoot 'bet\bet-cli\build\install\bet-cli\lib'
    [IO.Directory]::CreateDirectory($packagedLib) | Out-Null
    foreach ($jar in $jarFiles) {
        Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $packagedLib $jar.Name) -Force
    }

    $shim = @(
        '@echo off',
        'setlocal',
        'rem BF-773 governed runtime release shim. No Gradle toolchain is packaged.',
        'if "%~1"=="--no-daemon" if "%~2"==":bet:bet-cli:installDist" if "%~3"=="" exit /b 0',
        'echo BF-773 BLOCKED: runtime package permits only --no-daemon :bet:bet-cli:installDist. 1^>^&2',
        'exit /b 23'
    ) -join "`r`n"
    [IO.File]::WriteAllText($packagedGradlewBat, ($shim + "`r`n"), [Text.Encoding]::ASCII)

    $runtimeLeaks = @(Get-ChildItem -LiteralPath $tempRoot -Recurse -File -ErrorAction Stop | Where-Object {
        $name = $_.Name.ToLowerInvariant()
        $name -ceq 'butler.db' -or
        $name.EndsWith('.db-wal') -or
        $name.EndsWith('.db-shm') -or
        $name.EndsWith('.db-journal') -or
        $name.EndsWith('.db.init.lock')
    })
    if ($runtimeLeaks.Count -ne 0) {
        throw 'BF-773 BLOCKED: runtime/private SQLite material is present in the staging tree.'
    }

    Compress-Archive -Path (Join-Path $tempRoot '*') -DestinationPath $artifactPath -CompressionLevel Optimal -Force
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($artifactPath)
try {
    $entries = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\\', '/') })
    if ($entries -ccontains 'gradlew') { throw 'BF-773 BLOCKED: Unix Gradle launcher is present in runtime package.' }
    if (@($entries | Where-Object { $_.StartsWith('gradle/', [StringComparison]::Ordinal) }).Count -ne 0) {
        throw 'BF-773 BLOCKED: Gradle wrapper/toolchain directory is present in runtime package.'
    }
    if ($entries -cnotcontains 'gradlew.bat') { throw 'BF-773 BLOCKED: governed gradlew.bat shim is missing.' }
    $packagedJars = @($entries | Where-Object { $_ -like 'bet/bet-cli/build/install/bet-cli/lib/*.jar' })
    if ($packagedJars.Count -ne $jarFiles.Count) {
        throw 'BF-773 BLOCKED: packaged installDist JAR library count does not match prepared runtime library.'
    }
    $packagedApplicationJars = @($packagedJars | Where-Object { [IO.Path]::GetFileName($_) -like 'bet-cli-*.jar' })
    if ($packagedApplicationJars.Count -ne 1) {
        throw 'BF-773 BLOCKED: runtime package must contain exactly one bet-cli application JAR.'
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
    'source=BF-769 git archive HEAD plus prepared installDist JAR library',
    'gradle_runtime=ABSENT',
    'gradlew_bat=BF-773_FAIL_CLOSED_STARTUP_SHIM',
    'java_host_prerequisite=REQUIRED'
)
[IO.File]::WriteAllText($manifestPath, (($manifestLines -join "`r`n") + "`r`n"), [Text.Encoding]::ASCII)

Write-Host 'Butler prebuilt read-only runtime release bundle (BF-773)'
Write-Host "Commit: $fullSha"
Write-Host "Artifact: $artifactPath"
Write-Host "SHA-256: $hash"
Write-Host "Checksum: $checksumPath"
Write-Host "Manifest: $manifestPath"
Write-Host 'Boundary: CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA; packaged Gradle toolchain absent; Java remains a host prerequisite.'
Write-Host 'BF-773 RUNTIME RELEASE BUNDLE: PASS'
