# Mirror Share

**Windows laptop to Android TV screen sharing over a local network.**

![Mirror Share](Branding/mirror_share_logo.png)

Maintainer: **Avinasalingam — avinasalingam23-blip**.

This codebase contains the Windows sender, Android TV receiver, matching branding, and installer build configuration. Names, header logos, central symbols, launcher labels, icons, and installer metadata use Mirror Share branding.

## Current build status

The edited source and packaging configuration have been checked structurally. **New MSI and APK files have not been compiled here.** This environment has no .NET/Android SDKs, and their download endpoints return HTTP 403. The included Windows build script compiles this actual source to produce the installers; the old installers have not been renamed or substituted.

## Build on your Windows laptop

1. Extract the whole bundle to a local folder.
2. Install the **.NET 10 SDK** and **Android Studio** if needed.
3. In Android Studio's SDK Manager install Android SDK Platform **36**, Build Tools **35.0.0**, and Android SDK Command-line Tools. Use **JDK 17 or 21** for this project.
4. Keep the supplied `ffmpeg.exe` in `WindowsSender`. It is included in this bundle and excluded from Git commits because of its size.
5. Double-click **Build-MirrorShare.cmd**, or run from this folder:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-MirrorShare.ps1
```

The first build needs internet to restore .NET, WiX, Gradle, and Android dependencies. If `sdkmanager` is available, the script can install missing Platform 36 / Build Tools 35.0.0; respond to any license prompt yourself.

Expected outputs after a successful build:

| File in `artifacts/` | Purpose |
| --- | --- |
| `MirrorShare-Setup.msi` | Windows x64 installer, self-contained sender, and FFmpeg |
| `MirrorShare-TV.apk` | Signed Android TV release APK |
| `MirrorShare-Windows-Portable.zip` | Portable Windows sender folder |
| `SHA256SUMS.txt` | Checksums of the produced installer files |
| `Build.log` | Build output for troubleshooting |

Build Windows first, then the TV APK separately if preferred:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-MirrorShare.ps1 -Target Windows
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-MirrorShare.ps1 -Target Android
```

For nonstandard SDK/JDK locations:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-MirrorShare.ps1 -Target Android -AndroidSdkPath "$env:LOCALAPPDATA\Android\Sdk" -JavaInstallPath "C:\Program Files\Android\Android Studio\jbr"
```

## Install and test

1. Install `MirrorShare-Setup.msi` on the Windows laptop and open **Mirror Share** from Start.
2. Install `MirrorShare-TV.apk` on the TV and open **Mirror Share TV**.
3. Put both devices on the same local network.
4. Select the discovered TV in the sender, or use Manual Connect with the TV's IP address.
5. Start casting.

The TV package is now `com.mirrorshare.receiver`. It installs as a separate app from the previous receiver. Launch **Mirror Share TV** during testing; the old receiver is not removed automatically.

Video uses raw H.264 over TCP 5000, audio uses stereo 44.1 kHz PCM over TCP 5001, and discovery uses UDP 5002. The wire protocol and streaming behavior are retained.

## Audio

The sender captures system audio using **VB-Audio Virtual Cable**. Keep the previously working driver and routing, or install the driver separately from its vendor. Windows playback should be set to **CABLE Input** so FFmpeg can capture **CABLE Output**. Without that audio device, video can still stream while audio reports unavailable.

The new MSI does not automatically install an audio driver: the uploaded source contained neither that installer nor the previous MSI's driver custom-action definition. Third-party tool names are retained.

## Android signing

The build script generates a persistent release key under `%LOCALAPPDATA%\Mirror Share\Signing` on the first build, then reuses it. The password file uses Windows DPAPI, tied to the current Windows account/machine. Keep that signing folder private and retain the key for future updates; copying the DPAPI file to another account/machine is not a portable password backup.

To use an existing release key, set `MIRRORSHARE_KEYSTORE_PATH`, `MIRRORSHARE_KEYSTORE_PASSWORD`, and optionally `MIRRORSHARE_KEY_ALIAS`. No key or password is committed to the project. The script checks the produced APK with `apksigner`.

## Layout

| Path | Contents |
| --- | --- |
| `WindowsSender/` | C# .NET 10 WPF app and `MirrorShare.csproj` |
| `AndroidTVReceiver/` | Kotlin receiver, Gradle wrapper, manifest, and resources |
| `Packaging/` | WiX MSI project and installer definition |
| `Branding/` | Wordmark, symbol, and Windows icon |
| `Build-MirrorShare.ps1` | Windows/Android build command |
| `docs/CHANGES.md` | Branding changes and retained behavior |
| `docs/VALIDATION.md` | Completed checks and remaining verification |

## Replacing the old release

Build and test both new applications before changing the old repository or its downloads. Git ignore rules exclude the large FFmpeg executable, generated output, and signing credentials from normal source commits. Upload the compiled installers as GitHub Release assets after testing.

The MSI build does not add an Authenticode signature. A verified Windows publisher requires a separate signing certificate; the installer product/manufacturer metadata is Mirror Share.
