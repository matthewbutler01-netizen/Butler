param(
    [Parameter(Position = 0)]
    [ValidateNotNullOrEmpty()]
    [string]$Branch = 'main',

    [ValidateNotNullOrEmpty()]
    [string]$FastLanePath = 'C:\ButlerDev\fastlane'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRepo = Split-Path -Parent $scriptDir
$git = (Get-Command git.exe -ErrorAction Stop).Source

function Invoke-GitCapture {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = & $git @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = (($output | ForEach-Object { "$_" }) -join [Environment]::NewLine).Trim()
    if ($exitCode -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $exitCode. $text"
    }
    return $text
}

function Invoke-GitPassthrough {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & $git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
}

function Get-NormalizedPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    return [System.IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}

try {
    Write-Host 'Butler Fast Lane verification (BF-921)'
    Write-Host "Requested branch: $Branch"
    Write-Host "Reusable worktree: $FastLanePath"
    Write-Host 'Boundary: exact remote branch SHA; clean reusable worktree only; full BF-885/BF-912 journey once; no worktree prune.'

    [void](Invoke-GitCapture -Arguments @('check-ref-format', '--branch', $Branch))

    $sourceTop = Invoke-GitCapture -Arguments @('-C', $sourceRepo, 'rev-parse', '--show-toplevel')
    if ((Get-NormalizedPath -Path $sourceTop) -cne (Get-NormalizedPath -Path $sourceRepo)) {
        throw "source repository root mismatch: expected $sourceRepo, found $sourceTop"
    }

    Invoke-GitPassthrough -Arguments @(
        '-C', $sourceRepo,
        'fetch', 'origin',
        ("+refs/heads/{0}:refs/remotes/origin/{0}" -f $Branch)
    )

    $remoteRef = "refs/remotes/origin/$Branch"
    $targetSha = Invoke-GitCapture -Arguments @(
        '-C', $sourceRepo,
        'rev-parse', '--verify', ($remoteRef + '^{commit}')
    )
    if ($targetSha -notmatch '^[0-9a-f]{40}$') {
        throw "remote branch did not resolve to one full commit SHA: $targetSha"
    }

    $normalizedFastLane = Get-NormalizedPath -Path $FastLanePath
    if (Test-Path -LiteralPath $FastLanePath) {
        if (-not (Test-Path -LiteralPath $FastLanePath -PathType Container)) {
            throw "Fast Lane path exists but is not a directory: $FastLanePath"
        }

        $registered = $false
        $worktreeList = Invoke-GitCapture -Arguments @('-C', $sourceRepo, 'worktree', 'list', '--porcelain')
        foreach ($line in @($worktreeList -split '\r?\n')) {
            if (-not $line.StartsWith('worktree ', [System.StringComparison]::Ordinal)) { continue }
            $candidate = $line.Substring('worktree '.Length).Trim()
            if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
            if ((Get-NormalizedPath -Path $candidate) -ieq $normalizedFastLane) {
                $registered = $true
                break
            }
        }
        if (-not $registered) {
            throw "Fast Lane directory exists but is not a registered worktree: $FastLanePath"
        }

        $dirty = Invoke-GitCapture -Arguments @(
            '-C', $FastLanePath,
            'status', '--porcelain=v1', '--untracked-files=all'
        )
        if (-not [string]::IsNullOrWhiteSpace($dirty)) {
            throw "Fast Lane worktree is dirty. Preserve or remove those changes before reuse. status=$dirty"
        }
    }
    else {
        $parent = Split-Path -Parent $FastLanePath
        if (-not [string]::IsNullOrWhiteSpace($parent) -and -not (Test-Path -LiteralPath $parent -PathType Container)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }

        Invoke-GitPassthrough -Arguments @(
            '-C', $sourceRepo,
            'worktree', 'add', '--detach', $FastLanePath, $targetSha
        )
    }

    Invoke-GitPassthrough -Arguments @('-C', $FastLanePath, 'checkout', '--detach', $targetSha)

    $head = Invoke-GitCapture -Arguments @('-C', $FastLanePath, 'rev-parse', 'HEAD')
    if ($head -cne $targetSha) {
        throw "Fast Lane HEAD mismatch: expected $targetSha, found $head"
    }

    $before = Invoke-GitCapture -Arguments @(
        '-C', $FastLanePath,
        'status', '--porcelain=v1', '--untracked-files=all'
    )
    if (-not [string]::IsNullOrWhiteSpace($before)) {
        throw "Fast Lane worktree became dirty before verification. status=$before"
    }

    Push-Location $FastLanePath
    try {
        & '.\gradlew.bat' ':bet:bet-cli:installDist'
        if ($LASTEXITCODE -ne 0) {
            throw "Fast Lane installDist failed with exit code $LASTEXITCODE."
        }

        & '.\scripts\butler-manager-journey-acceptance.cmd'
        if ($LASTEXITCODE -ne 0) {
            throw "Fast Lane manager journey failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $after = Invoke-GitCapture -Arguments @(
        '-C', $FastLanePath,
        'status', '--porcelain=v1', '--untracked-files=all'
    )
    if (-not [string]::IsNullOrWhiteSpace($after)) {
        throw "Fast Lane verification changed tracked or untracked files. status=$after"
    }

    Write-Host ("BUTLER FASTLANE HEAD: {0}" -f $targetSha.Substring(0, 8))
    Write-Host 'BUTLER FASTLANE WORKTREE: CLEAN'
    Write-Host 'BUTLER FASTLANE: PASS'
}
catch {
    Write-Error ("BUTLER FASTLANE: BLOCKED - {0}" -f $_.Exception.Message)
    exit 1
}
