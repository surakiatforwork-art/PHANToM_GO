# PHANToM GO v1.0.15

## Release Summary

This stable release promotes the tested Android UI redesign from `v1.0.15-beta.2` and fixes button-label contrast across light mode, dark mode, and vendor Android themes.

## User-Facing Changes

- Added the redesigned Daylight and Midnight themes.
- Added the Security tab with in-app Find Display map.
- Reorganized Settings and kept every switch visible beside dynamic status text.
- Fixed unreadable button labels on Samsung and other vendor ROMs by preventing system theme tint from overriding custom button backgrounds.
- Updated primary-action text colors so they automatically remain readable in both light and dark mode.
- Kept pairing, Firebase relay, Display mode, anti-theft, speedometer, scoped Maps auto-share, and power automation workflows unchanged.

## Validation

- Audited every Android layout button with a custom background.
- Confirmed all custom-background buttons explicitly disable vendor/system background tint.
- Confirmed soft primary buttons use the semantic `ui_on_primary` text color.
- `assembleDebug`: passed
- `assembleRelease`: passed
- `testDebugUnitTest`: passed
- `lintDebug`: passed
- `git diff --check`: passed
- APK package/version: `com.phantom.carnavrelay`, `1.0.15 (18)`
- APK signature: Android debug signer, v1/v2 verified
- APK SHA-256: `9F09689EACEF364F7E2EAB7D00ABDD81E55FA94184E245143401B6CCB468A571`

## Known Limitation

Display Power Automation behavior can still vary by device ROM and power-management policy. The existing automation logic was intentionally left unchanged in this release because the tested core workflows are functioning normally.

## Firebase

No Firebase Console or Firebase Rules changes are required.
