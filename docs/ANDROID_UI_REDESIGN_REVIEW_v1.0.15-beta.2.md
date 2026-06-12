# Android UI Redesign Review - v1.0.15-beta.2

## Review Scope

The review covered Android navigation, Home, Display, Settings, Security, pairing entry points, route relay, Firebase location lookup, anti-theft, display power automation, speed tracking, application reconnect behavior, Bluetooth connection checks, resources, build configuration, and release packaging.

## Architecture Result

- The existing single-activity architecture remains intact.
- The existing persistent bottom dock remains one shared view across tabs.
- Security is a new fragment inside the existing shell, not a new activity or duplicate flow.
- Pairing, Firebase transport, route command format, accessibility auto-share, and Display service lifecycle were not rewritten.
- Find Display reuses `FirebaseManager.getPairedDisplayLocation`.
- Security status reuses `DisplayAntiTheftManager.snapshot`.

## UI/UX Result

- Daylight and Midnight semantic palettes now follow system day/night mode.
- Bottom navigation now has Home, Display, Settings, and Security.
- Active navigation items cannot be pressed again.
- Cards, buttons, borders, spacing, and status colors use a calmer automotive-oriented visual hierarchy.
- Settings switches have a fixed touch target and no longer compete with dynamic status text for horizontal space.
- The existing Settings order remains Notifications, Display Device, Main Device, and Logs.
- Security centralizes protection state and Find Display location.
- The in-app map keeps coordinates/status visible even when map tiles cannot load.

## Reliability Findings Fixed

1. Anti-theft headset debounce used a nullable runnable with a forced assertion. It now schedules a stable local runnable.
2. Anti-theft security alert session used a forced assertion. It now safely snapshots the session ID.
3. Speed delta calculation repeatedly dereferenced mutable previous location state. It now uses one local snapshot.
4. Display HTTP server start check used a forced assertion. It now uses a null-safe running-state check.
5. Display location sync repeatedly dereferenced mutable coordinate state. It now uses local snapshots.
6. Network callback registration used a forced assertion. It now registers a stable local callback.
7. Relay retry used a forced assertion on the previous raw route. It now exits safely if unavailable.
8. Bluetooth connection state used a forced assertion. It now checks the socket safely.
9. The old Home Find Display dialog/address lookup path became duplicate behavior after the Security page was introduced and was removed.

## Regression Review

- No Firebase database path or security rule changed.
- No pairing payload or QR format changed.
- No route relay payload or command listener changed.
- No Display runtime layout parent type changed.
- No anti-theft state transition changed.
- No display power automation trigger changed.
- No scoped accessibility auto-share matcher or coordinator changed.
- No public website or stable download link changed.

## Verification

```text
clean assembleDebug testDebugUnitTest lintDebug assembleRelease: PASS
git diff --check: PASS
APK package/version: com.phantom.carnavrelay / 1.0.15-beta.2 (17)
APK v1/v2 signature verification: PASS
```

## Remaining Risks

- Physical-device UI/interaction testing could not be performed because no Android device was connected during the final review.
- The in-app map depends on internet access and OpenStreetMap/Leaflet resources.
- The Settings screen still has many views in one long layout. It preserves compatibility now, but a future redesign should split it into smaller preference sub-pages only after this beta is proven stable.
- Existing deprecated Android API warnings remain in legacy flows. They are not blocking this beta but should be migrated gradually rather than during a visual redesign.
