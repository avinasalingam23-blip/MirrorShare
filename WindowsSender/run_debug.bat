@echo off
setlocal
cd /d "%~dp0"
where dotnet >nul 2>&1
if errorlevel 1 (
  echo .NET SDK was not found.
  echo Install the .NET SDK or run from Visual Studio.
  pause
  exit /b 1
)
dotnet run --project "MirrorShare.csproj"
pause
