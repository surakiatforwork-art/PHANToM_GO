# PHANToM GO v1.0.17

## Release Summary

This stable release completes the Android UI text cleanup that was left unfinished during the redesign follow-up work. It makes Thai the default app language, adds English locale resources, and fixes unreadable disabled-button labels across the redesigned dark and light themes.

## What Changed

- Added `values-en/strings.xml` and moved remaining user-facing Android strings into shared resources.
- Finished i18n cleanup across Home, Display, Settings, Security, Permission setup, Pairing, Relay, and Anti-Theft flows.
- Replaced remaining hardcoded Anti-Theft and Security runtime messages so the display-security UI no longer shows mixed Thai/English text.
- Patched custom button resources with explicit enabled/disabled contrast handling for both soft UI buttons and Display action buttons.
- Updated README, landing page, workflow HTML, and app overview docs to point to the new stable release.

## Validation

- `assembleDebug`: passed
- `testDebugUnitTest`: passed
- `lintDebug`: passed
- `assembleRelease`: passed
- `git diff --check`: passed
- Release APK installed successfully on the connected Samsung device.
- Verified visually on the connected Samsung device in both dark mode and light mode.
- APK package/version: `com.phantom.carnavrelay`, `1.0.17 (20)`
- APK signer: Android debug signer
- APK signer SHA-256: `2B3D3EFFF71526168656B137A2DF5625C08BF488A0656DDACFBD54A0FF286A74`

## APK

- File: `PHANToM_GO-v1.0.17.apk`
- SHA-256: `5C0C2D8F298308AB334B5784580ED3D281C5349DDCFEF47167F7B031386916B8`

## Firebase

No Firebase Console or Firebase Rules changes are required for this release.
