@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Butler v0.4 - Milestone 1 My Team and Waiver Check
echo BUTLER v0.4 - MILESTONE 1: MY TEAM AND WAIVER BOARD
echo Read-only first-open and second-open check of the already-running v0.4 manager.
echo Four loopback GETs plus one health GET. Does not start, stop or replace a release.
echo No Sleeper transaction. Page GETs may trigger Butler's existing governed local evidence recovery.
echo Report includes fixed route/status/timing only; no private roster/player data.
echo.
if not defined LOCALAPPDATA (
  echo BLOCKED: Windows LocalAppData is unavailable.
  pause
  exit /b 1
)
set "reportDir=%LOCALAPPDATA%\Butler\diagnostics"
if not exist "%reportDir%" mkdir "%reportDir%"
if not exist "%reportDir%" (
  echo BLOCKED: Could not create diagnostics folder.
  pause
  exit /b 1
)
set "report=%reportDir%\v04-milestone1-roster-waivers-latest.txt"
call "%~dp0butler-v04-live-page-check.cmd" -RosterWaiverOnly -RequireReady -TimeoutSeconds 30 > "%report%" 2>&1
set "result=%ERRORLEVEL%"
echo.
type "%report%"
echo.
echo Diagnostic report: "%report%"
if not "%result%"=="0" (
  echo RESULT: NOT READY. Do not use unverified roster or waiver advice.
) else (
  echo RESULT: Page contracts responded; actual live Sleeper source freshness is NOT certified.
)
start "" notepad.exe "%report%"
echo Press any key to close this test window.
pause >nul
exit /b %result%
