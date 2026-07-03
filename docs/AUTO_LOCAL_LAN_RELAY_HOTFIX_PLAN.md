# PHANToM GO Auto Local LAN Relay Hotfix Plan

## Goal
After two Android devices are paired through Firebase QR pairing, PHANToM GO should automatically prefer a same-LAN local HTTP relay when the Main Device and Display Device can see each other on Wi-Fi or hotspot. Firebase remains the safe fallback when local relay is unavailable.

This hotfix is intentionally **not Android Wi-Fi Direct / P2P group pairing**. It is an automatic local LAN relay upgrade after cloud pairing because that matches the current architecture, avoids extra system pairing prompts, and has lower UX risk for a beta hotfix.

## Problem Found
- Firebase pairing and cloud relay require internet on both devices.
- When internet is unstable, cloud route delivery may timeout.
- The app already has a Display HTTP server and local route relay, but cloud-only pairing can skip local discovery because the Main Device expects a complete stored IP/token transport.
- A QR payload can contain a stale or invalid IP like `0.0.0.0`, which should not be treated as a usable local endpoint.

## Existing Components To Reuse
- `HttpServer` on Display:
  - `/status`
  - `/pair`
  - `/open-url`
  - `/refresh-token`
  - `/unpair`
- `DisplayServerService` keeps the Display HTTP server alive.
- `MainSender` already has:
  - local `/open-url` sender
  - subnet and hotspot discovery
  - known-IP probing
  - pending queue retry after reconnect
- Firebase already stores pairing and presence data.

## Hotfix Implementation
1. Store local discovery seed after cloud pairing:
   - Keep `localToken` and `localPort` when Firebase pairing returns them.
   - Store `localIp` only if it is a usable IPv4 address.
   - Do not let `0.0.0.0`, loopback, multicast, or blank IP count as a local transport.

2. Allow discovery without a current IP:
   - Discovery can run when Main has a paired Display ID plus local token/port.
   - This lets Main scan the active subnet even if the QR IP was stale or invalid.

3. Trigger discovery immediately after cloud pairing:
   - After a successful Firebase claim, run local discovery in the background.
   - The UI can still finish pairing immediately; discovery is opportunistic.

4. Prefer local relay when available:
   - If cloud pairing also has a usable local endpoint, send route locally first.
   - If local send fails, fall back to Firebase cloud.
   - If cloud send fails, trigger local discovery and retry pending routes when found.

5. Publish Display local endpoint through Firebase presence:
   - Display presence includes current `localIp`, `localPort`, and `localToken`.
   - This helps old cloud-paired devices recover a local discovery seed after updating.
   - Current Firebase rules already allow the paired Main to read Display presence, so no console rule change is expected for this hotfix.

## Expected Behavior
- Same Wi-Fi / same hotspot:
  - Main discovers Display automatically and sends routes through local HTTP.
  - If internet drops but LAN is still reachable, route delivery can still work.
- Different networks / mobile data only:
  - Main uses Firebase cloud as before.
- Router client isolation enabled:
  - Local relay may not work; Firebase fallback remains.
- Display IP changes:
  - Network callback and route-send failure trigger rediscovery.

## Test Checklist
- Pair Main and Display by QR through Firebase.
- Confirm pairing succeeds even if QR local IP is `0.0.0.0`.
- Put both devices on the same hotspot/Wi-Fi, share route, confirm local relay works.
- Turn off internet while keeping LAN reachable, share route, confirm local relay still works after discovery.
- Move Display to a different Wi-Fi IP, confirm rediscovery can update endpoint.
- Put devices on different networks, confirm Firebase cloud fallback still works.
- Verify no Firebase rules change is required.

## Known Limits
- This does not create a Wi-Fi Direct group automatically.
- Some routers block peer-to-peer clients on the same Wi-Fi; local relay cannot bypass that.
- Browser web display cannot host the Android local HTTP server, so this hotfix applies to native APK Display mode.
