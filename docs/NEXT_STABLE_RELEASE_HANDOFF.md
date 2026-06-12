# Next Stable Release Handoff

## Current State

- Current Android beta target: `v1.0.15-beta.2`
- Android version code: `17`
- iOS is out of scope and must not be changed.
- The beta includes the Android UI redesign, Security tab, and in-app Find Display map.

## Before Promoting to Stable

The owner must test the beta on real Main and Display devices. Do not create the stable release until the following are confirmed:

- No launch, tab-switch, Settings, or Display-mode crash.
- Light and dark themes are readable.
- Every Settings toggle remains visible and works.
- QR/cloud pairing and route relay work both directions expected by the product.
- Display power automation, speedometer, anti-theft, and scoped Maps auto-share work.
- Find Display map shows the correct paired Display location.

## If A Bug Is Found

1. Reproduce it on the connected Android device.
2. Capture `adb logcat -d -v time PHANTOM_GO:D '*:S'`.
3. Fix only the affected Android code.
4. Run:

```powershell
.\gradlew.bat clean assembleDebug testDebugUnitTest lintDebug assembleRelease
```

5. Create another prerelease beta such as `v1.0.15-beta.3`.

## Stable Release Procedure

1. Choose the approved stable version. Because this release adds a visible Security page and UI system, `v1.1.0` is recommended.
2. Increase `versionCode` above `17`.
3. Set `versionName` to the stable version without `-beta`.
4. Run the full Android verification command.
5. Confirm APK package/version/signature and install it on both Main and Display devices.
6. Commit only intended Android and docs files.
7. Push `main`.
8. Create a non-prerelease GitHub release.
9. Name the APK `PHANToM_GO-vX.Y.Z.apk`.
10. Update README and public website stable download links only after the stable release exists.

## Files That Must Not Be Accidentally Staged

- `Automagics/`
- `stitch_phantom_go_ui_redesign/`
- Any user-owned untracked files in `docs/`
