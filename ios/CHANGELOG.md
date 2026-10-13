# iOS Changelog

## 1.0.1 (build 3) - In Progress

- Shorter Share relay wait, optional auto-send and one-tap Paste/Send fallback.
- Separate cloud acceptance/queued state from Display acknowledgement; retain pending route for retry.
- Resolve and verify actual shared Keychain access groups after re-signing; never bypass entitlements.
- Explicit latest-route refresh/open action for free-account Display and notification-tap routing.
- Tap-to-open Maps by default; preserve explicitly configured automatic foreground opening.
- Keep listeners during temporary inactive/permission dialogs, remove old peer presence observers.
- Validate both current Main device ID and auth UID; ignore older peer/history routes.
- Match the APK ghost launcher icon, generate opaque RGB 1024x1024, verify compiled icon metadata.
- Explicit iPhone/iPad orientations and stronger landscape UI validation.
- Native remote push remains unavailable until supported signing/provider setup; no Firebase rules changes.

## 1.0.0 (build 2) - 2026-10-13

- Native SwiftUI Home / Display / Settings / Security.
- Firebase REST anonymous authentication, protected Keychain session and APK/Web-compatible pairing.
- Reliable route relay with acknowledgement/retry and persistent dedupe.
- Local HTTP relay, cached-pair offline startup and token rotation after disconnect.
- CoreLocation speedometer/location and MapKit Find Display.
- Security alerts and capability-limited local PIN/jack alarm.
- Fixed alarm escalation during PIN-protected disarm without a jack-removal event.
- Foreground power/brightness settings and opt-in keep-awake.
- Native hardware-encoded dashcam, loop storage, playback, clip trim/export and Photos.
- Share Extension using shared Keychain when signing supports it, with Copy/Paste fallback.
- macOS Xcode device IPA pipeline and iPhone/iPad simulator validation.
- Documented differences from Android and unsigned-IPA signing requirements.
