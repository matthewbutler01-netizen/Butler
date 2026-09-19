@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-dashboard-outer-route-timing-diagnostic.ps1" %*
exit /b %ERRORLEVEL%
