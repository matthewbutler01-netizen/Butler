@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-setup-stop.ps1" %*
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
