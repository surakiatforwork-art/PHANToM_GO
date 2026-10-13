# PHANToM GO v1.3.2-beta.1

Beta hotfix for Firebase QR pairing users who also want automatic same-LAN local relay when both Android devices are on the same Wi-Fi or hotspot.

## Changes
- Added Auto Local LAN Relay upgrade after Firebase QR pairing.
- Main Device now keeps Display `localToken` and `localPort` as a discovery seed even when the QR/Firebase IP is missing or invalid.
- Invalid local IPs such as `0.0.0.0`, loopback, link-local, multicast, and broadcast are no longer treated as usable local endpoints.
- Main Device can discover the paired Display on the current LAN without needing a stored IP first.
- Route sending now prefers local HTTP relay when a usable local endpoint is available, then falls back to Firebase cloud.
- If Firebase cloud delivery fails, Main triggers local discovery and retries queued routes once the Display is found.
- Display presence now publishes current Android local relay endpoint data for the paired Main Device to recover older cloud-only pairings.

## No Firebase Console Change Expected
This beta writes local endpoint data under existing Display `presence`, which the current rules already allow the paired Main Device to read.

## Limitations
- This is same-LAN local relay, not Android Wi-Fi Direct / P2P group pairing.
- Local relay requires both Android devices to be reachable on the same Wi-Fi/hotspot network.
- Routers with client isolation can still block local relay.
- Browser Display mode cannot host the native Android HTTP server, so this hotfix targets APK Display mode.

## Test Focus
- Pair with Firebase QR.
- Share route with both devices on the same Wi-Fi/hotspot.
- Test with internet unstable but LAN still active.
- Confirm Firebase cloud fallback still works when devices are not on the same LAN.

## Repository Migration (2026-10-13)

- Published under https://github.com/surakiatforwork-art/PHANToM_GO after migrating the Git repository.
- This hotfix remains a pre-release; Stable downloads continue to use `v1.3.1`.
- Android build, unit-test tasks, and lint passed during migration verification.
- The installable APK is byte-identical to the previously published beta and retains its original signing certificate.
- No Android source or Firebase rules were changed during migration.
- No ADB device was connected for runtime testing in this migration.

## APK

- File: `PHANToM_GO-v1.3.2-beta.1.apk`
- Package: `com.phantom.carnavrelay`
- Version: `1.3.2-beta.1` (`versionCode 32`)
- SHA-256: `0cd9bace25c15ddc48af7fd9e3004e439cddaa48c5703395534b5e4827d5f8cc`
