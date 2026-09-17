@echo off
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0butler-lineup-evidence-recovery.ps1" %*
exit /b %ERRORLEVEL%
