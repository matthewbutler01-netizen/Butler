param(
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$outputDir = Join-Path $repoRoot 'release-output'

$gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
if ($null -eq $gitCommand) {
    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
}
if ($null -eq $gitCommand) {
    throw 'BF-769 BLOCKED: Git executable is unavailable.'
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
        throw ("BF-769 BLOCKED: Git command failed: git {0}`n{1}" -f ($Arguments -join ' '), $text)
    }
    return (($lines | ForEach-Object { "$_" }) -join "`n").Trim()
}

$fullSha = Invoke-GitText -Arguments @('rev-parse', '--verify', 'HEAD^{commit}')
if ($fullSha -notmatch '^[0-9a-fA-F]{40}$') {
    throw 'BF-769 BLOCKED: HEAD did not resolve to one exact commit SHA.'
}
$fullSha = $fullSha.ToLowerInvariant()
$shortSha = $fullSha.Substring(0, 8)

[IO.Directory]::CreateDirectory($outputDir) | Out-Null
$artifactName = "Butler-source-$shortSha.zip"
$artifactPath = Join-Path $outputDir $artifactName
$checksumPath = $artifactPath + '.sha256'
$manifestPath = Join-Path $outputDir "Butler-source-$shortSha.manifest.txt"

foreach ($path in @($artifactPath, $checksumPath, $manifestPath)) {
    if (Test-Path -LiteralPath $path) {
        if (-not $Force) {
            throw "BF-769 BLOCKED: release output already exists at $path. Use -Force to replace the exact HEAD artifact."
        }
        Remove-Item -LiteralPath $path -Force
    }
}

Push-Location $repoRoot
try {
    & $git 'archive' '--format=zip' ("--output=$artifactPath") 'HEAD'
    $archiveExit = $LASTEXITCODE
}
finally {
    Pop-Location
}
if ($archiveExit -ne 0 -or -not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
    throw "BF-769 BLOCKED: git archive HEAD failed with exit code $archiveExit."
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($artifactPath)
try {
    $entries = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\\', '/') })
    $requiredEntries = @(
        'README.md',
        'SECURITY.md',
        'build.gradle.kts',
        'settings.gradle.kts',
        'gradle.properties',
        'gradlew',
        'gradlew.bat',
        'gradle/wrapper/gradle-wrapper.jar',
        'gradle/wrapper/gradle-wrapper.properties',
        'bet/bet-cli/build.gradle.kts',
        'scripts/butler-app.cmd',
        'scripts/butler-app.ps1',
        'scripts/butler-acceptance.cmd'
    )
    foreach ($required in $requiredEntries) {
        if ($entries -cnotcontains $required) {
            throw "BF-769 BLOCKED: release archive is missing required entry $required"
        }
    }

    foreach ($entry in $entries) {
        if ([string]::IsNullOrWhiteSpace($entry) -or $entry.EndsWith('/')) { continue }
        $lower = $entry.ToLowerInvariant()
        $name = [IO.Path]::GetFileName($entry).ToLowerInvariant()
        $segments = @($lower -split '/')

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
                $segments -contains 'build' -or
                $segments -contains 'release-output') {
            $forbidden = $true
        }

        if ($forbidden) {
            throw "BF-769 BLOCKED: forbidden runtime/private/generated entry found in release archive: $entry"
        }
    }
}
finally {
    $archive.Dispose()
}

$hash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumText = "$hash  $artifactName`r`n"
[IO.File]::WriteAllText($checksumPath, $checksumText, [Text.Encoding]::ASCII)

$manifestLines = @(
    'BUTLER_RELEASE_MANIFEST_V1',
    "commit=$fullSha",
    "short_commit=$shortSha",
    "artifact=$artifactName",
    "sha256=$hash",
    'boundary=CODE_ONLY_NO_RUNTIME_DATA',
    'source=git archive HEAD',
    'standalone_runtime=NOT_YET_VERIFIED_BF770_REQUIRED'
)
[IO.File]::WriteAllText($manifestPath, (($manifestLines -join "`r`n") + "`r`n"), [Text.Encoding]::ASCII)

Write-Host 'Butler code-only source release bundle (BF-769)'
Write-Host "Commit: $fullSha"
Write-Host "Artifact: $artifactPath"
Write-Host "SHA-256: $hash"
Write-Host "Checksum: $checksumPath"
Write-Host "Manifest: $manifestPath"
Write-Host 'Boundary: CODE_ONLY_NO_RUNTIME_DATA; local Butler database, credentials, generated build output, IDE state, and Git metadata are excluded.'
Write-Host 'BF-769 RELEASE BUNDLE: PASS'
