@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Butler v0.4 - Milestone 1 My Team and Waiver Check
echo BUTLER v0.4 - MILESTONE 1: MY TEAM AND WAIVER BOARD
echo Read-only first-open and second-open check of the already-running v0.4 manager.
echo Four loopback GETs plus one health GET. Does not start, stop or replace a release.
echo No Sleeper transaction. Page GETs may trigger Butler's existing governed local evidence recovery.
echo Report includes fixed route/status/timing only; no private roster/player data.
echo.
rem Windows File Explorer extracts only the clicked CMD when run INSIDE a ZIP.
rem Fail with a clear Extract All instruction before calling missing helpers.
for %%F in (
  butler-v04-live-page-check.cmd
  butler-v04-live-page-check.ps1
  butler-decision-refresh.ps1
) do (
  if not exist "%~dp0%%F" goto :missing_package
)
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
:missing_package
echo.
echo ================================================================
echo  EXTRACT ALL FILES FIRST - THE CHECK HAS NOT RUN
echo ================================================================
echo Windows cannot run this multi-file diagnostic directly from inside
echo the ZIP. Opening the CMD inside a ZIP only extracts that one file.
echo.
echo 1. Return to the ZIP file in File Explorer.
echo 2. Right-click the ZIP, choose Extract All, then Extract.
echo 3. Open the extracted folder with all five files together.
echo 4. Double-click butler-v04-roster-waiver-first-open.cmd again.
echo.
echo No application was started or stopped; no Sleeper data was changed.
echo Press any key to close this window.
pause >nul
exit /b 2
