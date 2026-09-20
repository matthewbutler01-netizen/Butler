@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-manager-journey-acceptance.ps1" %*
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
