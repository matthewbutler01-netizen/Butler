@echo off
setlocal
title Butler v0.4 - Live Sleeper NFL Week Check
echo BUTLER v0.4 - compare the displayed matchup week with Sleeper's public NFL state.
echo Only tests an already-running development v0.4 app.
echo This makes exactly one external read-only GET to api.sleeper.app/v1/state/nfl.
echo No league ID, roster, account information, or transactions are sent.
echo Your frozen v0.3.0 app is not started, stopped, or changed.
echo.
call "%~dp0butler-v04-live-page-check.cmd" -CheckSleeperWeek
set "butlerExit=%ERRORLEVEL%"
echo.
if not "%butlerExit%"=="0" (
  echo RESULT: BLOCKED - check the error above.
) else (
  echo RESULT: Complete - review WARN statuses before acting.
)
echo.
echo Press any key to close this test window.
pause >nul
exit /b %butlerExit%
