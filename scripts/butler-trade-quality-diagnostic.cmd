@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-trade-quality-diagnostic.ps1" %*
exit /b %ERRORLEVEL%
