Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$outputDir = Join-Path $repoRoot 'release-output'
$bundleScript = Join-Path $scriptDir 'butler-release-evidence-bundle.ps1'
if (-not (Test-Path -LiteralPath $bundleScript -PathType Leaf)) {
    throw "BF-787 BLOCKED: evidence bundle script not found at $bundleScript"
}

$fullCommit = '1111111111111111111111111111111111111111'
$shortCommit = '11111111'
$runtimeName = "Butler-runtime-$shortCommit.zip"
$manifestName = "Butler-runtime-$shortCommit.manifest.txt"
$recordName = "Butler-release-$shortCommit.verified.txt"
$evidenceName = "Butler-release-evidence-$shortCommit.zip"
$runtimePath = Join-Path $outputDir $runtimeName
$runtimeChecksumPath = $runtimePath + '.sha256'
$manifestPath = Join-Path $outputDir $manifestName
$recordPath = Join-Path $outputDir $recordName
$evidencePath = Join-Path $outputDir $evidenceName
$evidenceChecksumPath = $evidencePath + '.sha256'
$ownedPaths = @($runtimePath, $runtimeChecksumPath, $manifestPath, $recordPath, $evidencePath, $evidenceChecksumPath)
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf787-acceptance-{0}" -f [Guid]::NewGuid().ToString('N'))

foreach ($path in $ownedPaths) {
    if (Test-Path -LiteralPath $path) {
        throw "BF-787 BLOCKED: synthetic acceptance path already exists and will not be overwritten: $path"
    }
}

try {
    [IO.Directory]::CreateDirectory($outputDir) | Out-Null
    [IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    [IO.File]::WriteAllText((Join-Path $tempRoot 'runtime-sentinel.txt'), 'BF-787 synthetic runtime evidence only', [Text.Encoding]::ASCII)
    Compress-Archive -Path (Join-Path $tempRoot 'runtime-sentinel.txt') -DestinationPath $runtimePath -CompressionLevel Optimal

    $runtimeHash = (Get-FileHash -LiteralPath $runtimePath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($runtimeChecksumPath, ("{0}  {1}`r`n" -f $runtimeHash, $runtimeName), [Text.Encoding]::ASCII)

    $manifestLines = @(
        'BUTLER_RUNTIME_RELEASE_MANIFEST_V1',
        "commit=$fullCommit",
        "artifact=$runtimeName",
        "sha256=$runtimeHash",
        'boundary=CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA'
    )
    [IO.File]::WriteAllText($manifestPath, (($manifestLines -join "`r`n") + "`r`n"), [Text.Encoding]::ASCII)

    $recordLines = @(
        'BUTLER_RELEASE_VERIFICATION_V1',
        "commit=$fullCommit",
        "short_commit=$shortCommit",
        "runtime_artifact=$runtimeName",
        "runtime_sha256=$runtimeHash",
        "runtime_manifest=$manifestName",
        'release_acceptance=BF-776_PASS',
        'runtime_boundary=CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA',
        'verification_boundary=RELEASE_METADATA_ONLY_NO_RUNTIME_DATA'
    )
    [IO.File]::WriteAllText($recordPath, (($recordLines -join "`r`n") + "`r`n"), [Text.Encoding]::ASCII)

    & $bundleScript -RecordPath $recordPath

    if (-not (Test-Path -LiteralPath $evidencePath -PathType Leaf)) {
        throw 'BF-787 BLOCKED: evidence acceptance did not create the portable archive.'
    }
    if (-not (Test-Path -LiteralPath $evidenceChecksumPath -PathType Leaf)) {
        throw 'BF-787 BLOCKED: evidence acceptance did not create the archive checksum.'
    }

    $checksumText = (Get-Content -LiteralPath $evidenceChecksumPath -Raw -Encoding ASCII).Trim()
    if ($checksumText -notmatch '^([0-9a-f]{64})\s+(.+)$') {
        throw 'BF-787 BLOCKED: evidence archive checksum format is invalid.'
    }
    $actualEvidenceHash = (Get-FileHash -LiteralPath $evidencePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($matches[1] -cne $actualEvidenceHash -or $matches[2].Trim() -cne $evidenceName) {
        throw 'BF-787 BLOCKED: evidence archive checksum does not match the archive.'
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($evidencePath)
    try {
        $entryNames = @($zip.Entries | Where-Object { -not [string]::IsNullOrEmpty($_.Name) } | ForEach-Object { $_.FullName })
        $expectedNames = @($runtimeName, ($runtimeName + '.sha256'), $manifestName, $recordName)
        if ($entryNames.Count -ne 4) {
            throw "BF-787 BLOCKED: synthetic evidence archive contains $($entryNames.Count) files instead of exactly 4."
        }
        foreach ($expectedName in $expectedNames) {
            if ($entryNames -cnotcontains $expectedName) {
                throw "BF-787 BLOCKED: synthetic evidence archive is missing $expectedName"
            }
        }
    }
    finally {
        $zip.Dispose()
    }

    Write-Host 'Butler portable release evidence acceptance (BF-787)'
    Write-Host 'Boundary: SYNTHETIC_RELEASE_METADATA_ONLY; NO_RUNTIME_DATA; NO_PUBLICATION'
    Write-Host 'BF-787 RELEASE EVIDENCE ACCEPTANCE: PASS'
}
finally {
    foreach ($path in $ownedPaths) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
