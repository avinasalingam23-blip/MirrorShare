# Validation status

## Preparation checks

- XML/XAML/project/WiX structure and local file references checked.
- Old company branding strings and resource filenames removed from first-party source and assets.
- New PNG/ICO images included and readable; Windows icon contains multiple sizes.
- Runtime C# and Kotlin source compared with the upload, accounting only for namespace/resource/name/storage-path edits.
- Discovery tokens, ports, and stream formats retained on both sides.
- Original FFmpeg binary retained byte-for-byte.
- Gradle wrapper integrity, Git whitespace/ignore rules, and final ZIP integrity checked.

## Remaining build/device checks

The environment has no .NET SDK, Windows build tools, Android SDK, or TV device. SDK/dependency download requests returned HTTP 403. The rebranded Windows application, MSI, and APK have therefore not been compiled or run here. PowerShell build execution also requires Windows.

The owner's successful test of the previous apps does not constitute a test of this edited source or its new packaging.

On the Windows laptop:

1. Run `Build-MirrorShare.cmd` and confirm the MSI and signed APK are produced.
2. Check the installed sender's title, header, central logo, settings window, icon, and Start Menu entry.
3. Launch **Mirror Share TV** and check its top wordmark, central logo, launcher icon, and name.
4. Test discovery, manual connection, video, and audio with the working VB-CABLE setup.
5. Test stop/start and reconnect behavior.
6. Replace the old downloads only after the new pair works.
