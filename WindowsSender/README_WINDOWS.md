# Mirror Share Windows Sender

C# .NET 10 WPF desktop app. Keep the supplied `ffmpeg.exe` beside `MirrorShare.csproj`.

For development:

```powershell
dotnet run --project .\MirrorShare.csproj
```

From the source root, build the self-contained sender and MSI:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-MirrorShare.ps1 -Target Windows
```

The installer build outputs `artifacts/MirrorShare-Setup.msi`. Audio requires the separately installed VB-Audio Virtual Cable device. The root README describes SDK setup and TV APK builds.
