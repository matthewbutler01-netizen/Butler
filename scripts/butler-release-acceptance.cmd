@echo off
setlocal
echo Butler one-command runtime release acceptance (BF-776)
echo Boundary: exact current-HEAD prebuilt runtime package first; isolated missing-database fail-closed probe second; existing Butler acceptance third; /refresh excluded; no Butler or Sleeper transaction write.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-runtime-packaged-launch-acceptance.ps1"
set "BF776_RUNTIME_ERROR=%ERRORLEVEL%"
if not "%BF776_RUNTIME_ERROR%"=="0" exit /b %BF776_RUNTIME_ERROR%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-missing-runtime-db-acceptance.ps1"
set "BF786_MISSING_DB_ERROR=%ERRORLEVEL%"
if not "%BF786_MISSING_DB_ERROR%"=="0" exit /b %BF786_MISSING_DB_ERROR%
call "%~dp0butler-acceptance.cmd" %*
set "BF776_ACCEPTANCE_ERROR=%ERRORLEVEL%"
if not "%BF776_ACCEPTANCE_ERROR%"=="0" exit /b %BF776_ACCEPTANCE_ERROR%
set "BUTLER_BF776_ACCEPTANCE_VERIFIED=1"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-release-verification-record.ps1"
set "BF777_RECORD_ERROR=%ERRORLEVEL%"
set "BUTLER_BF776_ACCEPTANCE_VERIFIED="
if not "%BF777_RECORD_ERROR%"=="0" exit /b %BF777_RECORD_ERROR%
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-release-verification-check.ps1"
set "BF778_VERIFY_ERROR=%ERRORLEVEL%"
if not "%BF778_VERIFY_ERROR%"=="0" exit /b %BF778_VERIFY_ERROR%
echo BF-786 MISSING RUNTIME DATABASE ACCEPTANCE: PASS
echo BF-776 RELEASE ACCEPTANCE: PASS
echo BF-777 RELEASE VERIFICATION RECORD: PASS
echo BF-780 RELEASE SELF-VERIFICATION: PASS
exit /b 0
