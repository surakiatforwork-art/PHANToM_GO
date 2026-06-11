# PHANToM GO

PHANToM GO turns an Android phone or tablet into a paired in-car display. Send Google Maps destinations from a Main Device to a Display Device through Firebase Cloud Relay, with local Wi-Fi fallback when available.

The same pairing system also works with the browser Web App, so iOS and devices that cannot install the APK can still send or receive route links.

## Core Features

- QR pairing without account registration
- Cloud route relay with local Wi-Fi fallback
- Share Target and Map Link Hub for Google Maps links
- Display Mode with automatic Google Maps opening
- Browser Web App interoperability
- Display speedometer using location data
- Display-ready automation for wake and brightness
- Optional lock-on-power-disconnect automation
- Jack 3.5 mm anti-theft alarm with Main Device alerts and last known location
- Find Display from the paired Main Device

## Basic Workflow

1. Open **Display** on the device used as the in-car screen.
2. Scan its QR code from **Home** on the Main Device.
3. Share a Google Maps destination to PHANToM GO.
4. The route is relayed to the paired Display and opened according to its navigation settings.

Only one Main and one Display are paired at a time. Pairing a new device replaces the previous peer.

## Display Priority

The Display screen adapts to the current state:

- Before pairing, the QR card is large and the speedometer is compact.
- After pairing, the speedometer becomes the main visual focus and the connection card becomes compact.
- When a route arrives, display-readiness automation runs before Google Maps opens.
- Anti-theft challenges and alarms have the highest priority and do not get replaced by route actions.

## Permissions

PHANToM GO requests only the permissions needed by enabled features:

- Camera for QR scanning
- Notifications for foreground services and alerts
- Location for speed, Find Display, and anti-theft location reporting
- Background location for continuous Display location updates
- Overlay for optional floating controls and security verification
- Modify system settings for optional brightness automation
- Device Admin for optional lock-on-power-disconnect

Optional permissions can be skipped, but their related features will remain unavailable.

## Downloads and Web App

- Stable APK: [Download PHANToM GO v1.0.13](https://github.com/Phantomguy7845/PHANToM_GO/releases/download/v1.0.13/PHANToM_GO-v1.0.13.apk)
- Landing page: [`docs/web/index.html`](docs/web/index.html)
- Browser Web App: [`docs/web/webapp/index.html`](docs/web/webapp/index.html)

## Build

```bash
./gradlew clean assembleDebug
```

Installable debug-signed APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Project Documentation

- [Current app overview](docs/PHANTOM_GO_APP_OVERVIEW.md)
- [Interactive workflow review](docs/PHANTOM_GO_WORKFLOW.html)
- [Stable release review](docs/STABLE_RELEASE_REVIEW_v1.0.13.md)
- [Firebase Realtime Database rules](docs/firebase-realtime-database.rules.json)

## Important Limitations

- Android may restrict automatic background activity launches depending on device vendor settings.
- Device Admin can lock the Display after power removal, but it cannot prevent uninstall by a device owner.
- Google Maps behavior varies by installed Maps version, URL format, and selected navigation mode.
- Browser Display cannot provide the same background reliability, notification access, or automatic Maps opening as the Android APK.
- Firebase Realtime Database data cleanup requires a backend retention process before operating at large scale.
