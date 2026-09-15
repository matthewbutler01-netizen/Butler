@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "BUTLER_APP_RUNTIME_LIB=%~dp0..\bet\bet-cli\build\install\bet-cli\lib"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-direct-java-dispatch.ps1" -Task "%~1" -ArgumentText "%~2" -ArgumentRemainder "%~3 %~4 %~5 %~6 %~7 %~8 %~9"
exit /b %ERRORLEVEL%
