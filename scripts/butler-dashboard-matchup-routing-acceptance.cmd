@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-dashboard-matchup-routing-acceptance.ps1" %*
exit /b %ERRORLEVEL%
