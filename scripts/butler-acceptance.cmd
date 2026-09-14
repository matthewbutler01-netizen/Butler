@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-acceptance-preflight.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
set "BUTLER_APP_PERSISTENT_CORE_WORKER=1"
echo BF-740 canary: persistent JVM worker enabled for /team and /league acceptance only; normal app launch remains unchanged.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-acceptance.ps1" %*
set "BF740_ACCEPTANCE_ERROR=%ERRORLEVEL%"
set "BUTLER_APP_PERSISTENT_CORE_WORKER="
if not "%BF740_ACCEPTANCE_ERROR%"=="0" exit /b %BF740_ACCEPTANCE_ERROR%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-slow-route-stage-diagnostic.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-dispatch-startup-diagnostic.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-long-lived-jvm-worker-diagnostic.ps1"
exit /b %ERRORLEVEL%