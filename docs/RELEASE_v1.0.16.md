# PHANToM GO v1.0.16

## Release Summary

This stable release fixes unreadable button labels that could still appear on some Samsung and vendor Android ROMs after the `v1.0.15` UI refresh.

## What Changed

- Patched every custom-background action button used by the Android app to explicitly disable both framework and AppCompat/Material background tint overrides.
- Kept semantic text colors for primary actions so labels remain readable in both light mode and dark mode.
- Revalidated the redesigned Settings and Security screens where the issue was most visible.
- Kept pairing, cloud relay, display mode, anti-theft, speedometer, and power automation logic unchanged.

## Validation

- `assembleDebug`: passed
- `assembleRelease`: passed
- `testDebugUnitTest`: passed
- `lintDebug`: passed
- `git diff --check`: passed
- APK package/version: `com.phantom.carnavrelay`, `1.0.16 (19)`
- Verified on connected Samsung device in both dark mode and light mode.

## APK

- File: `PHANToM_GO-v1.0.16.apk`
- SHA-256: `E2AEA370904E4218380955F54C3749066ADD047DCE44CC0A7DF3F08EDBB65D39`

## Firebase

No Firebase Console or Firebase Rules changes are required.
