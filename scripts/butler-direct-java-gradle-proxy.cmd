@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\butler-direct-java-dispatch.ps1" -Task "%~1" -ArgumentText "%~2"
exit /b %ERRORLEVEL%
