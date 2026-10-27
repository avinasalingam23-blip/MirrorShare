@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Build-MirrorShare.ps1" %*
set "MirrorShareExit=%ERRORLEVEL%"
if not "%MirrorShareExit%"=="0" echo Build stopped. Read the first error above.
pause
exit /b %MirrorShareExit%
