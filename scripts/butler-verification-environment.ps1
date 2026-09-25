param([string]$JavaHome = $env:JAVA_HOME)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Called inside the command process; never changes user or machine settings.
if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    if (-not [IO.Path]::IsPathRooted($JavaHome) -or
        -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe') -PathType Leaf)) {
        throw 'Butler verification: JavaHome must name an installed JDK using an absolute path.'
    }
    $env:JAVA_HOME = [IO.Path]::GetFullPath($JavaHome)
}
else {
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw 'Butler verification: Java 25 is unavailable. Pass -JavaHome "C:\path\to\jdk-25" or set JAVA_HOME.'
    }
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $command.Source)
}

& (Join-Path $PSScriptRoot 'butler-java-preflight.ps1')
if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe') -PathType Leaf)) {
    throw 'Butler verification: a JDK containing javac.exe is required.'
}
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
# The HTML report is optional. Console errors and task failures remain enabled.
$env:GRADLE_OPTS = ([string]$env:GRADLE_OPTS + ' -Dorg.gradle.problems.report=false').Trim()
