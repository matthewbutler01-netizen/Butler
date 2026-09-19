@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-dashboard-summary-stage-diagnostic.ps1" %*
exit /b %ERRORLEVEL%
