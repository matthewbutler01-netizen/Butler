@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-manager-guardrail-acceptance.ps1" %*
exit /b %ERRORLEVEL%
