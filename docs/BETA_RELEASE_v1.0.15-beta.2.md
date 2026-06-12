# PHANToM GO v1.0.15-beta.2

## Purpose

This beta is the Android UI redesign validation release. It follows the Daylight Console and Midnight Executive references while keeping the existing pairing, route relay, Display mode, anti-theft, power automation, speedometer, and scoped Maps auto-share flows.

## User-Facing Changes

- Added automatic Daylight and Midnight theme palettes.
- Added a persistent fourth `Security` bottom-navigation tab.
- Added an in-app Find Display map using the existing Firebase last-known location.
- Reorganized Settings and converted toggles to fixed-size Material switches.
- Dynamic Settings status text can no longer push switches off-screen.
- Updated cards, buttons, navigation dock, spacing, borders, and status colors.
- Added Security and Find Display access to the Home drawer.

## Reliability Review

- Kept existing Firebase paths, pairing payloads, cloud commands, and transport logic unchanged.
- Kept Display runtime layout parent types unchanged.
- Kept the existing anti-theft state machine unchanged.
- Removed several safe-null assertion risks in anti-theft, speed tracking, Display location sync, reconnect, Bluetooth, and retry code.
- Removed the old duplicate Find Display dialog flow after moving it to the Security tab.

## Verification Completed

- `assembleDebug`: passed
- `assembleRelease`: passed
- `testDebugUnitTest`: passed
- `lintDebug`: passed with no blocking errors
- `git diff --check`: passed
- APK package/version: `com.phantom.carnavrelay`, `1.0.15-beta.2 (17)`
- APK signature: Android debug signer, v1/v2 verified
- APK SHA-256: `38C2BE19045CC40E7D28576295273517B479C10534864C65D0F94768AF3E4FD1`
- No Android device was connected during final validation, so physical-device visual and interaction testing is required before stable release.

## Beta Test Checklist

1. Test Home, Display, Settings, and Security tabs in both system light and dark mode.
2. Confirm the bottom dock stays in place and the active tab cannot be pressed again.
3. Confirm all Settings switches remain visible when status text changes.
4. Confirm QR pairing and cloud pairing still work.
5. Confirm route sharing reaches the Display and opens Maps.
6. Confirm Display QR/speedometer priority resizing still works.
7. Confirm anti-theft arm, PIN challenge, alert, location attachment, and disarm still work.
8. Confirm Display power automation still works.
9. Confirm Find Display shows online/offline status, last location, map, and external Maps action.
10. Confirm scoped Maps auto-share still works.

## Known Limitations

- The in-app map requires internet access and uses OpenStreetMap/Leaflet resources.
- If map tiles cannot load, the location coordinates, online state, speed, and timestamp remain visible.
- The Settings page intentionally remains a single long page to preserve every existing setting and listener; a future release can split it into sub-pages after beta validation.

## Firebase

No Firebase Console or Firebase Rules changes are required for this beta.
