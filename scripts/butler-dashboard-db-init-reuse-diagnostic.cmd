@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-dashboard-db-init-reuse-diagnostic.ps1" %*
exit /b %ERRORLEVEL%
