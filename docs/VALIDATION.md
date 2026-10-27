# Validation status

## Completed checks

- Source XML, XAML, project files, WiX authoring, and local resource references were checked.
- Mirror Share wordmarks, symbols, launcher resources, and Windows icon assets are included.
- C# and Kotlin runtime code was compared with the supplied source, accounting for the branding, namespace, and storage-path changes.
- Discovery tokens, ports, and media formats were retained.
- The original FFmpeg binary was retained in the source bundle; it is excluded from normal Git commits.
- Gradle wrapper integrity and Git whitespace/ignore rules were checked.

## Windows build and interface

The project owner successfully ran the Windows build with .NET SDK 10.0.403.

- Windows sender compilation: passed.
- WiX MSI build: passed after correcting the advertised shortcut icon identifier and executable icon source.
- Output: `artifacts/MirrorShare-Setup.msi`.
- The owner confirmed the updated Windows interface, and supplied the screenshot in `assets/ui-sender.png`.

The screenshot shows the sender waiting for a receiver. It is not evidence of an active streaming session.

## Android TV build

[GitHub Actions run 40496219479](https://github.com/avinasalingam23-blip/MirrorShare/actions/runs/40496219479) built the receiver from commit `e3644d98487620ad662f0b3e8091df69241f894b`.

- Gradle `:app:assembleDebug`: passed.
- Android APK signature verification: passed, including v1 and v2 signatures.
- Downloaded artifact and APK ZIP integrity: passed.
- APK label `Mirror Share TV`, package `com.mirrorshare.receiver`, and new logo/symbol resources: checked.
- APK SHA-256: `2833c56e97738fdc67a7e69e87c8dda53ccc14ad114c05c083c3b07f19af572d`.
- The APK attached to the v1.0.0 GitHub release matches that checksum.

This APK is a debug-signed test build. The local Android release-signing path and permanent release-key setup have not yet been tested.

## Published files

The [v1.0.0 pre-release](https://github.com/avinasalingam23-blip/MirrorShare/releases/tag/v1.0.0) contains:

- `MirrorShare-Setup.msi`.
- `MirrorShare-TV-test.apk`.

Publishing the files does not establish end-to-end device test results.

## Pending device tests

- [ ] Install and launch the new TV APK; check its name, launcher icon, top logo, and central symbol.
- [ ] Discover the TV automatically from the Windows sender.
- [ ] Connect using the TV's IP address.
- [ ] Confirm video output using each streaming profile.
- [ ] Confirm audio using the separately configured VB-CABLE driver.
- [ ] Test stop/start, reconnect, and device restart behavior.
- [ ] Record Windows version, laptop hardware, TV model/Android version, and network setup.
- [ ] Measure frame rate and latency before making performance claims.
- [ ] Configure and retain permanent Android release-signing credentials.

Previous successful casting with older binaries is separate from testing this newly built sender/receiver pair.
