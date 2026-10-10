@echo off
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-v04-live-page-check.ps1" %*
exit /b %ERRORLEVEL%
