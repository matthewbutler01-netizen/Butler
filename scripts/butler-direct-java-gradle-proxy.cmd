@echo off
setlocal EnableExtensions DisableDelayedExpansion
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\butler-direct-java-dispatch.ps1" -Task "%~1" -ArgumentText "%~2" -ArgumentRemainder "%~3 %~4 %~5 %~6 %~7 %~8 %~9"
exit /b %ERRORLEVEL%
