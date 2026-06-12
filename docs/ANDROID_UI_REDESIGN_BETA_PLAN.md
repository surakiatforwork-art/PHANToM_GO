# Android UI Redesign Beta Plan

## Goal

Redesign the Android APK UI using `stitch_phantom_go_ui_redesign` as the visual reference while preserving the existing pairing, route relay, display, anti-theft, power automation, speedometer, and scoped Maps auto-share flows.

## Release Target

- Target version: `1.0.15-beta.2`
- Target tag: `v1.0.15-beta.2`
- Stable website download links must not be changed to this beta.
- iOS is out of scope.

## Implementation Scope

1. Add semantic Daylight and Midnight color tokens.
2. Keep the existing single-activity shell and persistent bottom navigation.
3. Add a fourth `Security` tab without duplicating anti-theft or location logic.
4. Add an in-app map for the paired Display's latest Firebase location.
5. Reorganize Settings while preserving all existing view IDs and listeners.
6. Prevent dynamic Settings status text from pushing or hiding switches.
7. Restyle Home, Display, Settings, Security, cards, buttons, badges, and bottom navigation.

## Safety Constraints

- Do not change Firebase paths, Firebase rules, pairing payloads, or cloud command formats.
- Do not change Display screen parent types used by runtime layout resizing.
- Do not replace the existing anti-theft state machine.
- Do not replace route relay, scoped accessibility auto-share, or power automation logic.
- Preserve all Settings view IDs.
- Keep the Security map read-only and use the existing `getPairedDisplayLocation` API.

## Main Risks

- `DisplayScreenFragment` changes runtime layout sizes and can crash if layout parameter parent types change.
- `SettingsScreenFragment` binds many concrete view IDs and widget types.
- Day/night colors can reduce contrast if legacy fixed colors are not handled carefully.
- Web map loading can fail offline; coordinates and timestamp must remain visible as a fallback.

## Verification Matrix

- Build debug and release APKs.
- Run unit tests and Android lint.
- Verify all Settings IDs inflate and all switches remain visible with long status text.
- Verify Home, Display, Settings, and Security tabs switch without recreating the bottom dock.
- Verify Display QR/connected and speedometer priority resizing still works.
- Verify pairing and route relay code is unchanged.
- Verify Security map handles paired online, paired offline, no location, and no pairing states.
- Verify anti-theft status reads from `DisplayAntiTheftManager.snapshot`.
- Review final diff for accidental Firebase, transport, or service behavior changes.

## Stable Release Handoff

After the beta is tested successfully:

1. Confirm no blocking crash, pairing regression, route relay regression, or Settings issue.
2. Update `versionCode` and `versionName` to the approved stable version.
3. Build and test release APK.
4. Commit only intended Android/docs changes.
5. Push `main`.
6. Create a non-prerelease GitHub release with the correctly named APK.
7. Update public website download links only after the stable release exists.

## Implementation Status

- Semantic Daylight/Midnight theme: complete
- Persistent four-tab navigation: complete
- Settings toggle layout fix: complete
- Security page: complete
- In-app Find Display map: complete
- Android null-safety review: complete
- Clean build, unit tests, lint, release build: complete
- Physical-device beta validation: pending owner test
