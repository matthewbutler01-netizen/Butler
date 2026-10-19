# Local, best-effort observations only. Never infer who closed a window.
function Get-ButlerLifetimeDirectory {
    $base = [string]$env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($base)) { return '' }
    if (-not [IO.Path]::IsPathRooted($base)) { return '' }
    return [IO.Path]::GetFullPath((Join-Path $base 'Butler\diagnostics'))
}

function Write-ButlerLifetimeEvent {
    param([string]$Path,
        [ValidateSet('SUPERVISOR_STARTED','GUARD_STARTED','GUARD_EXITED','SUPERVISOR_FAILED',
            'SUPERVISOR_FINALIZING','SUPERVISOR_DISAPPEARED','GUARD_DISAPPEARED')][string]$Event,
        [Nullable[int]]$ExitCode)
    try {
        $directory = Get-ButlerLifetimeDirectory
        if ([string]::IsNullOrWhiteSpace($directory) -or [string]::IsNullOrWhiteSpace($Path)) { return }
        $full = [IO.Path]::GetFullPath($Path)
        if (-not [IO.Path]::GetDirectoryName($full).Equals($directory, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($full) -cnotmatch '^runtime-lifetime-[0-9]{4,5}-[0-9]+-[0-9a-f]{32}\.txt$') { return }
        # Do not create a missing receipt supplied by another process.
        if (-not [IO.File]::Exists($full)) { return }
        $line = 'utc=' + [DateTime]::UtcNow.ToString('o') + ' event=' + $Event
        if ($Event -ceq 'GUARD_EXITED' -and $null -ne $ExitCode) { $line += ' code=' + [string]$ExitCode }
        for ($attempt = 0; $attempt -lt 3; $attempt++) {
            try { [IO.File]::AppendAllText($full, $line + "`r`n", [Text.Encoding]::ASCII); return }
            catch { if ($attempt -lt 2) { Start-Sleep -Milliseconds 10 } }
        }
    } catch { }
}

function New-ButlerLifetimeReceipt {
    param([int]$Port, [int]$SupervisorProcessId)
    try {
        $directory = Get-ButlerLifetimeDirectory
        if ([string]::IsNullOrWhiteSpace($directory) -or $Port -lt 1024 -or $Port -gt 65535 -or $SupervisorProcessId -lt 1) { return '' }
        [IO.Directory]::CreateDirectory($directory) | Out-Null
        $path = Join-Path $directory ('runtime-lifetime-' + $Port + '-' + $SupervisorProcessId + '-' + [guid]::NewGuid().ToString('N') + '.txt')
        $file = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
        try {
            $bytes = [Text.Encoding]::ASCII.GetBytes("BUTLER_RUNTIME_LIFETIME_V1`r`n")
            $file.Write($bytes, 0, $bytes.Length)
        } finally { $file.Dispose() }
        Write-ButlerLifetimeEvent -Path $path -Event SUPERVISOR_STARTED
        return $path
    } catch { return '' }
}
