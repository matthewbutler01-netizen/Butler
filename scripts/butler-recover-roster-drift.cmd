@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-recover-roster-drift.ps1"
exit /b %ERRORLEVEL%
