@echo off
setlocal
echo Butler one-command runtime release acceptance (BF-776)
echo Boundary: exact current-HEAD prebuilt runtime package first; existing Butler acceptance second; /refresh excluded; no Butler or Sleeper transaction write.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-runtime-packaged-launch-acceptance.ps1"
set "BF776_RUNTIME_ERROR=%ERRORLEVEL%"
if not "%BF776_RUNTIME_ERROR%"=="0" exit /b %BF776_RUNTIME_ERROR%
call "%~dp0butler-acceptance.cmd" %*
set "BF776_ACCEPTANCE_ERROR=%ERRORLEVEL%"
if not "%BF776_ACCEPTANCE_ERROR%"=="0" exit /b %BF776_ACCEPTANCE_ERROR%
echo BF-776 RELEASE ACCEPTANCE: PASS
exit /b 0
