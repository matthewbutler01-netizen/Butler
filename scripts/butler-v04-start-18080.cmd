@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Butler v0.4 - Start development manager on 18080
echo BUTLER v0.4 - START DEVELOPMENT MANAGER
echo Starts Butler from this updated source checkout on 127.0.0.1:18080.
echo This does not fetch, update, replace, stop or merge another release.
echo Existing occupied-port guards remain enforced. No Sleeper transaction.
echo.
rem The portable read-only diagnostic ZIP is NOT a runnable Butler app.
rem Refuse a lone CMD copied from that ZIP or an incomplete checkout.
for %%F in (
  butler-app.cmd
  butler-app-supervisor.ps1
  butler-app-guard.ps1
  butler-app.ps1
  butler-java-preflight.ps1
) do (
  if not exist "%~dp0%%F" goto :missing_checkout
)
echo Launching current source checkout with the existing guarded starter...
echo Leave this window open while checking the roster and Waiver Board.
call "%~dp0butler-app.cmd" -Port 18080
set "result=%ERRORLEVEL%"
echo.
echo Butler process ended with status %result%.
echo If the v0.4 check still says NO CURRENT WORKER, review startup errors above.
echo Press any key to close.
pause >nul
exit /b %result%
:missing_checkout
echo BLOCKED: COMPLETE V0.4 DEVELOPMENT CHECKOUT REQUIRED.
echo This startup shortcut must remain next to the Butler app scripts.
echo The diagnostic ZIP does not contain the running application.
echo Nothing was started or stopped.
echo Press any key to close.
pause >nul
exit /b 2
