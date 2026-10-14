@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Butler v0.4 - Open Beta
echo BUTLER v0.4 - ONE-CLICK READ-ONLY BETA
echo EXTRACT ALL before running; no v0.3 changes or Sleeper transactions.
echo.
if not exist "%~dp0butler-v04-beta-one-click.ps1" goto :missing
if not exist "%~dp0butler-v04-safe-update-and-start.ps1" goto :missing
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-v04-beta-one-click.ps1"
set "result=%ERRORLEVEL%"
echo.
echo Press any key to close this launcher.
pause >nul
exit /b %result%
:missing
echo EXTRACT ALL REQUIRED. Keep the CMD and both PS1 files together.
echo No update or app launch attempted.
pause
exit /b 2
