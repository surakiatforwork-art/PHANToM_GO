# GO Cam Auto Record Fix Plan

Date: 2026-06-26

## Goal

Fix `Auto record on charging` so GO Cam can reliably:

- start recording when external power is connected
- stop recording when external power is disconnected
- work on the connected Android 16 test device as far as platform policy allows

## Constraints

- Android 12+ restricts camera and microphone foreground services started from the background.
- The solution must be tested on the connected device, not only by static code inspection.
- We can read and reuse ideas from the main `PHANToM GO` project, but we will not modify the main app.

## Planned work

1. Inspect the current GO Cam power automation flow and test the failure path on-device.
2. Reuse the robust trigger pattern from the main app:
   - sticky `ACTION_BATTERY_CHANGED` reconciliation
   - debounce and persisted power state
   - transparent wake/helper activity pattern
3. Keep the helper activity alive long enough for the camera foreground service to fully start, instead of finishing too early.
4. Add clearer logging and status updates so we can see whether failure happens at:
   - receiver delivery
   - background activity launch
   - foreground service start
   - camera access
5. Build, install, and test through ADB with the connected device.

## Expected output

- Updated GO Cam APK installed on the connected device
- Verified behavior for:
  - charger connected -> recording starts
  - charger disconnected -> recording stops
- Notes on any remaining platform or OEM limitations if they still block the flow
