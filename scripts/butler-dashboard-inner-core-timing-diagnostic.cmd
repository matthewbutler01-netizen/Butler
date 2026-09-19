@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-dashboard-inner-core-timing-diagnostic.ps1" %*
exit /b %ERRORLEVEL%
