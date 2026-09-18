@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-weekly-matchup-acceptance.ps1" %*
exit /b %ERRORLEVEL%
