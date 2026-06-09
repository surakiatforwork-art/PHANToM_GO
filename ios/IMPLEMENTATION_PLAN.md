# PHANToM GO iOS Implementation Plan

## Executive Summary
โปรเจกต์ iOS นี้มีเป้าหมายเพื่อสร้าง `PHANToM GO` เวอร์ชัน native สำหรับ iPhone/iPad โดย reuse backend contract จากฝั่ง Android เดิมให้มากที่สุด และออกแบบให้ทำงานร่วมกับ:

- Android APK
- Web App
- Realtime Database schema เดิม

เอกสารนี้สรุป:

1. สิ่งที่ต้อง reuse จาก Android
2. สิ่งที่ต้อง redesign สำหรับ iOS
3. สิ่งที่เริ่มสร้างแล้วในรอบนี้
4. ลำดับงานถัดไป

---

## Environment Reality
การพัฒนารอบนี้ทำบนเครื่อง:

- Windows 10
- ไม่มี `swift`
- ไม่มี `xcodebuild`
- ไม่มี Xcode / iOS Simulator

ดังนั้นงานรอบนี้สามารถทำได้ถึง:

- วิเคราะห์ architecture
- ออกแบบ project structure
- สร้าง source files
- เตรียม Xcode project spec
- เตรียม build/install docs

แต่ยัง `ไม่สามารถ build iOS app จริงบนเครื่องนี้ได้` จนกว่าจะย้ายไปเปิดบน macOS ที่มี Xcode

---

## Android-to-iOS Contract Mapping

### Reuse as-is
ควร reuse contract เดิมจาก Android โดยตรง:

- `devices/{deviceId}`
- `presence/{deviceId}`
- `pairingCodes/{pairCode}`
- `pairings/byDisplay/{displayDeviceId}`
- `pairings/byMain/{mainDeviceId}`
- `commands/{displayDeviceId}/{commandId}`

### Android files used as source of truth
อิงจากโค้ด Android เดิม:

- [FirebaseManager.kt](C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/FirebaseManager.kt)
- [PrefsManager.kt](C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/PrefsManager.kt)
- [NavLinkUtils.kt](C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/NavLinkUtils.kt)
- [PairInfoParser.kt](C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/util/PairInfoParser.kt)
- [docs/web/webapp/index.html](C:/Project_winsurf/PHANToM%20GO/docs/web/webapp/index.html)

---

## Features Already Defined by Product

### Main Device
- Pair กับ display ผ่าน QR payload
- ส่ง route ไปยัง display
- รับ input จาก URL / manual paste / deep link

### Display Device
- แสดง QR code ตอนยังไม่ pair
- ฟัง route command จาก cloud
- แสดง latest route
- เปิดแผนที่ตามค่าตั้ง

### Settings
- nav mode
- open behavior
- preferred map app
- display mode toggle

---

## iOS Constraints That Affect Design

### What iOS is good at
- QR scan
- Firebase auth/database
- share/open URL handling
- foreground UI ที่ลื่นและสวย
- opening Apple Maps / Google Maps

### What iOS limits
- background route interception
- aggressive auto-open from background
- overlay floating controls แบบ Android
- accessibility automation แบบเดียวกับ Android
- browser-style "always listening" tricks

ดังนั้น iOS v1 ต้องเน้น:

- `Main mode` ที่ดี
- `Display mode` ที่ reliable
- `cloud-based interoperability`

และไม่พยายามฝืนให้เหมือน Android ทุกพฤติกรรม

---

## Architecture Chosen in This Round

### UI Layer
- `SwiftUI`
- `TabView` 3 แท็บ:
  - Home
  - Display
  - Settings

### State Layer
- `AppContainer`
- ใช้ `ObservableObject` และ `@Published`
- รับผิดชอบ:
  - auth bootstrap
  - pairing state
  - display mode state
  - command state
  - settings persistence

### Core Services
- `FirebaseService`
- `PrefsStore`
- `RouteParser`
- `MapLaunchPlanner`

### Pairing Utilities
- parse `phantomgo://pair?...`
- build cloud pair payload
- generate local pair code

---

## Files Created in This Round

### Project/bootstrap
- `ios/project.yml`
- `ios/BUILD_AND_INSTALL.md`
- `ios/IMPLEMENTATION_PLAN.md`

### App
- `ios/PHANToMGO/Sources/App/PHANToMGOApp.swift`
- `ios/PHANToMGO/Sources/App/AppContainer.swift`

### Core models
- `ios/PHANToMGO/Sources/Core/Models/AppTab.swift`
- `ios/PHANToMGO/Sources/Core/Models/RoutePayload.swift`
- `ios/PHANToMGO/Sources/Core/Models/PairInfo.swift`
- `ios/PHANToMGO/Sources/Core/Models/FirebaseRecords.swift`
- `ios/PHANToMGO/Sources/Core/Models/DisplayOptions.swift`

### Core services/utils
- `ios/PHANToMGO/Sources/Core/Services/PrefsStore.swift`
- `ios/PHANToMGO/Sources/Core/Services/FirebaseService.swift`
- `ios/PHANToMGO/Sources/Core/Utils/RouteParser.swift`
- `ios/PHANToMGO/Sources/Core/Utils/MapLaunchPlanner.swift`
- `ios/PHANToMGO/Sources/Core/Utils/QRCodeRenderer.swift`

### Features
- `ios/PHANToMGO/Sources/Features/Home/HomeView.swift`
- `ios/PHANToMGO/Sources/Features/Display/DisplayView.swift`
- `ios/PHANToMGO/Sources/Features/Settings/SettingsView.swift`
- `ios/PHANToMGO/Sources/Features/Pairing/QRScannerView.swift`
- `ios/PHANToMGO/Sources/Features/Pairing/PairingSheetView.swift`

### Resources
- `ios/PHANToMGO/Resources/GoogleService-Info.plist.template`

---

## Scope Actually Implemented

### Implemented now
- App shell 3 tabs
- Firebase bootstrap hooks
- Anonymous auth hook
- Device ID persistence
- Pair code persistence
- Cloud pairing payload builder/parser
- Route parser (best effort)
- Main-side manual route input
- QR pairing flow scaffold
- Display mode scaffold
- Display command listener scaffold
- Map opening planner
- Settings persistence
- unit test target scaffold
- parser tests for core route/pairing logic

### Partially implemented
- Firebase realtime listeners and writes are coded, but not runtime-validated on device yet
- QR scanner UI exists, but not runtime-tested on real iPhone camera
- Map opening logic exists, but not validated against actual iOS device behavior yet

### Not yet implemented
- Share Extension target
- Universal link routing polish
- Apple Maps / Google Maps preference validation on real device
- Background notification polish
- Route history
- crash-safe reconnect behavior

---

## Highest-Risk Areas

1. `FirebaseService.swift`
   - API shape is implemented from Android contract
   - still needs runtime validation against real Firebase credentials

2. `QRScannerView.swift`
   - camera permission + metadata scanner must be tested on device

3. `MapLaunchPlanner.swift`
   - Google Maps URL scheme handling on iOS needs real-device confirmation

4. `Display command listening`
   - needs real command push test from Android Main / Web Main

5. `Remote CI validation`
   - workflows are prepared, but real GitHub/macOS execution still needs to happen

---

## Recommended Next Test Order

1. Open app on iPhone
2. Confirm Firebase anonymous auth works
3. Confirm `devices` and `presence` write successfully
4. Open Display tab and confirm `pairingCodes/{pairCode}` appears
5. Pair Android Main -> iPhone Display
6. Send route Android Main -> iPhone Display
7. Open route on iPhone
8. Pair iPhone Main -> Android Display
9. Send route iPhone Main -> Android Display

---

## Installation / Setup Dependencies
To continue on macOS, required tools:

- Xcode
- Xcode Command Line Tools
- Ruby/CocoaPods is **not required** if using SPM
- Optional: `xcodegen`

Primary setup expected:

1. Copy real `GoogleService-Info.plist`
2. Generate/open Xcode project
3. Build on Simulator
4. Build on physical iPhone

---

## What Should Happen Next

### Immediate next step
เปิดโปรเจกต์บน macOS แล้วลอง build project นี้ก่อนโดยยังไม่เพิ่ม feature ใหม่

### After first successful build
- validate Firebase
- validate QR scan
- validate pairing
- validate route relay

### Then
- add Share Extension target
- add better command history
- polish UX

---

## Plan Progress Update

หลังจากรอบก่อนที่มีแค่ scaffold และ simulator/Appetize plan ตอนนี้เราเพิ่มแล้ว:

1. workflow `iOS CI` สำหรับ simulator build
2. workflow `iOS Device Build` สำหรับ signed IPA
3. เอกสาร no-Mac usage flow
4. เอกสาร device signing pipeline

หมายความว่าแผน iOS ตอนนี้ขยับจาก `architecture-ready` มาเป็น `delivery-pipeline-ready` ในระดับแรกแล้ว

---

## New Next-Step Plan

ลำดับงานต่อจากนี้ควรเป็น:

1. รัน `iOS CI` ให้ผ่านจริงบน GitHub
2. ตรวจ build artifact และถ้าต้องการให้เปิดบน browser ให้ลอง Appetize
3. ตั้ง signing secrets
4. รัน `iOS Device Build`
5. ตรวจว่า IPA export ผ่าน
6. ค่อยเริ่มงานฟีเจอร์ถัดไป เช่น Share Extension

---

## Next Feature Plan After Build Pipelines

เมื่อ build pipeline พร้อมแล้ว ฟีเจอร์ถัดไปที่ควรทำคือ:

### Priority A
- Share Extension target
- universal link / open URL polishing
- route handoff UX

### Priority B
- route history
- reconnect flow
- notification polish

### Priority C
- iPad-specific layout tuning
- richer diagnostics for Firebase / pairing

---

## Tooling Added For The Next Round

เพื่อให้รอบถัดไปเดินงานได้เร็วขึ้น ตอนนี้เพิ่มแล้ว:

- `ios/.gitignore`
- `ios/scripts/generate-github-secrets.ps1`
- `ios/PHANToMGOTests/RouteParserTests.swift`

---

## Definition of Success for This Round
รอบนี้ถือว่าสำเร็จถ้า:

- มีเอกสารวิเคราะห์พร้อม
- มี project scaffold พร้อม
- มี source files เริ่มต้นพร้อม
- โครง iOS สอดคล้องกับ Android contract เดิม
- พร้อมย้ายไป build ต่อบน macOS โดยไม่ต้องเริ่มคิดใหม่จากศูนย์
