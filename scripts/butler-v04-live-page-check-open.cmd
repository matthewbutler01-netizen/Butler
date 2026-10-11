@echo off
setlocal
title Butler v0.4 Local Smoke Check
echo BUTLER v0.4 - local page and audited-evidence smoke check
echo Only tests an already-running v0.4 instance on your computer.
echo It does not launch, stop or replace the frozen v0.3.0 app.
echo This diagnostic sends GET requests only. No Sleeper transaction.
echo.
call "%~dp0butler-v04-live-page-check.cmd"
set "butlerExit=%ERRORLEVEL%"
echo.
if not "%butlerExit%"=="0" (
  echo RESULT: BLOCKED. Check the error above. The frozen release was not changed.
) else (
  echo RESULT: Completed. Review any WARN statuses above before using recommendations.
)
echo.
echo Press any key to close this test window.
pause >nul
exit /b %butlerExit%
