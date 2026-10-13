@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Butler v0.4 - Safe pinned update and start
echo BUTLER v0.4 - ONE-CLICK PINNED UPDATE
echo Read README first. Stop your current Butler app normally with Ctrl+C.
echo.
if not exist "%~dp0butler-v04-safe-update-and-start.ps1" goto :extract
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-v04-safe-update-and-start.ps1"
set "result=%ERRORLEVEL%"
echo.
if not "%result%"=="0" echo UPDATE NOT COMPLETED. Your v0.3 release was not replaced.
echo Press any key to close.
pause >nul
exit /b %result%
:extract
echo EXTRACT ALL REQUIRED. The companion .ps1 must be beside this CMD.
echo Right-click the ZIP, select Extract All, then run this CMD again.
echo Nothing was updated or started.
pause >nul
exit /b 2
