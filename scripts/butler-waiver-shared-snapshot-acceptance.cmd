@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-waiver-shared-snapshot-acceptance.ps1" %*
exit /b %ERRORLEVEL%
