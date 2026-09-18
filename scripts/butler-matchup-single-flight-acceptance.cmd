@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-matchup-single-flight-acceptance.ps1" %*
exit /b %ERRORLEVEL%
