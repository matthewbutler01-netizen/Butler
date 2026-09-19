@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-bf623-provider-contention-diagnostic.ps1" %*
exit /b %ERRORLEVEL%
