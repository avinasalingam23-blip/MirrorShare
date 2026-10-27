$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

Write-Host "Verifying C# WPF sender..." -ForegroundColor Cyan
& dotnet build .\MirrorShare.csproj -c Debug
if ($LASTEXITCODE -ne 0) { throw "C# sender build failed." }
Write-Host "Sender build succeeded." -ForegroundColor Green
