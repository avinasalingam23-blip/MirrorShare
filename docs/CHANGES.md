# Mirror Share rebranding

## Windows

- Changed application title, header, central heading, settings title, project/solution, namespace, executable name, and product metadata.
- Replaced both displayed logo images and added an application/window/installer/shortcut icon.
- Moved settings/log storage to the Mirror Share profile folder.
- Preserved the supplied FFmpeg executable and all streaming/encoder behavior.

## Android TV

- Replaced the top wordmark and central symbol.
- Renamed the launcher to **Mirror Share TV** and added a launcher icon and readable light-background banner.
- Changed package/namespace to `com.mirrorshare.receiver`, version `1.0.0`.
- Added release signing and a persistent local signing-key workflow.
- Restored the omitted Gradle wrapper and aligned the toolchain with compile SDK 36.

## Installer/build support

- Added the WiX MSI project and package definition missing from the uploaded code.
- The MSI includes the self-contained app, FFmpeg, brand assets, Start Menu shortcut, and private-profile LAN discovery firewall rule.
- Added one build command with separate Windows/Android targets.
- Removed build caches, old binaries, machine-local SDK settings, obsolete screenshots, and old logo assets.

## Compatibility

The legacy wire identifiers `WIRELESS_DISPLAY_DISCOVER` and `WIRELESS_DISPLAY_RECEIVER` remain unchanged. They are protocol tokens shared by sender and receiver. Ports, encoding, audio format, profiles, discovery, controls, and decoding logic are retained.

No existing repository has been deleted or overwritten. No old installer has been relabelled as a newly compiled binary.
