@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-trade-analyzer-acceptance.ps1" %*
exit /b %ERRORLEVEL%
