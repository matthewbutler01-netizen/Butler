@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Butler v0.4 - One-Click Real League Readiness
echo BUTLER v0.4 - READ-ONLY SLEEPER AND LIVE PAGE CHECK
echo Checks the already-running local Butler v0.4 instance; never launches or stops another release.
echo Sends local page GETs plus ONE public Sleeper NFL-week GET. No Sleeper transactions.
echo Local GETs may invoke Butler's existing strictly governed local evidence recovery.
echo This creates a brief shareable diagnostic with status, route and timing, not roster/player data.
echo.
if not defined LOCALAPPDATA (
  echo BLOCKED: Windows LocalAppData directory is unavailable.
  pause
  exit /b 1
)
set "butlerReportDir=%LOCALAPPDATA%\Butler\diagnostics"
if not exist "%butlerReportDir%" mkdir "%butlerReportDir%"
if not exist "%butlerReportDir%" (
  echo BLOCKED: Could not create local diagnostics folder.
  pause
  exit /b 1
)
set "butlerReport=%butlerReportDir%\v04-real-league-readiness-latest.txt"
call "%~dp0butler-v04-live-page-check.cmd" -CheckSleeperWeek -TimeoutSeconds 30 > "%butlerReport%" 2>&1
set "butlerExit=%ERRORLEVEL%"
echo.
type "%butlerReport%"
echo.
echo Local diagnostic report: "%butlerReport%"
if not "%butlerExit%"=="0" (
  echo RESULT: BLOCKED. This is a test failure, NOT a reason to use stale lineup advice.
) else (
  echo RESULT: Check completed. Every WARN still needs review; a PASS is not proof of injury clearance.
)
echo.
echo Opening the report in Notepad so you can review or share the output.
start "" notepad.exe "%butlerReport%"
echo Press any key to close this test window.
pause >nul
exit /b %butlerExit%
