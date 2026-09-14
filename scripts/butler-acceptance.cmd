@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-acceptance-preflight.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
set "BUTLER_APP_PERSISTENT_CORE_WORKER="
echo BF-741 default: persistent JVM worker enabled for /team and /league; emergency opt-out is BUTLER_APP_PERSISTENT_CORE_WORKER=0.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-acceptance.ps1" %*
set "BF741_ACCEPTANCE_ERROR=%ERRORLEVEL%"
set "BUTLER_APP_PERSISTENT_CORE_WORKER=0"
if not "%BF741_ACCEPTANCE_ERROR%"=="0" exit /b %BF741_ACCEPTANCE_ERROR%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-slow-route-stage-diagnostic.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-dispatch-startup-diagnostic.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-long-lived-jvm-worker-diagnostic.ps1"
exit /b %ERRORLEVEL%