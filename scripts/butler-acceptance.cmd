@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-acceptance-preflight.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
set "BUTLER_APP_PERSISTENT_CORE_WORKER="
set "BUTLER_APP_WORKER_DATABASE_WARMUP="
echo BF-743 default: shared persistent workers preinitialize database schema once while preserving fresh reads and BF-623 verification; warmup-only emergency opt-out is BUTLER_APP_WORKER_DATABASE_WARMUP=0; full worker opt-out remains BUTLER_APP_PERSISTENT_CORE_WORKER=0.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-acceptance.ps1" %*
set "BF743_ACCEPTANCE_ERROR=%ERRORLEVEL%"
set "BUTLER_APP_PERSISTENT_CORE_WORKER=0"
if not "%BF743_ACCEPTANCE_ERROR%"=="0" exit /b %BF743_ACCEPTANCE_ERROR%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-slow-route-stage-diagnostic.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-dispatch-startup-diagnostic.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-long-lived-jvm-worker-diagnostic.ps1"
exit /b %ERRORLEVEL%