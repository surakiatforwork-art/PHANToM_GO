# PHANToM GO iOS

## Overview
PHANToM GO iOS คือโปรเจกต์คู่ขนานของฝั่ง Android สำหรับนำความสามารถหลักของ PHANToM GO มาสู่ระบบ iOS โดยยึดแนวคิดเดิมของโปรดักต์:

- `Main Device` ใช้ค้นหา / แชร์ / ส่งเส้นทาง
- `Display Device` ใช้แสดงผลแผนที่และรับปลายทางจากเครื่องหลัก

เป้าหมายของฝั่ง iOS ไม่จำเป็นต้องเหมือน Android ทุกอย่างตั้งแต่วันแรก แต่ต้องออกแบบให้ `เข้ากับ ecosystem ของ iOS` และ `ทำงานร่วมกับ Android/Web ได้`

---

## Current Status

ตอนนี้ในโฟลเดอร์ `ios` มีทั้ง:

- app scaffold ฝั่ง SwiftUI
- Firebase contract ที่อิงจาก Android เดิม
- เอกสารวิเคราะห์/แผนงาน
- workflow สำหรับ build iOS simulator บน GitHub Actions
- workflow สำหรับ signed IPA บน GitHub Actions
- flow สำหรับอัปโหลดขึ้น Appetize เมื่อไม่มี Mac

ถ้าจะเริ่มใช้งานทันที ให้ดูเอกสารเพิ่มตามนี้:

- `ios/BUILD_AND_INSTALL.md`
- `ios/NO_MAC_PLAYBOOK.md`
- `ios/DEVICE_BUILD_PIPELINE.md`
- `ios/TEST_READINESS.md`

---

## Core Product Goal
สร้างแอป iOS ที่สามารถเป็นส่วนหนึ่งของระบบ PHANToM GO ได้จริง โดยรองรับอย่างน้อย:

1. Pairing กับอุปกรณ์อื่นผ่าน QR / Cloud pairing
2. ส่ง route จาก iPhone ไปยัง Display device
3. รับ route บน iPhone แล้วเปิดแผนที่ต่อ
4. ใช้งานร่วมกับ Android APK และ Web App ได้

---

## Reality Check for iOS
iOS มีข้อจำกัดมากกว่า Android ในเรื่อง:

- background execution
- auto-launch app จากเบื้องหลัง
- clipboard / overlay / accessibility automation
- การปลุกหน้าจอหรือเปิดแอปอื่นอัตโนมัติ

ดังนั้นฝั่ง iOS ต้องออกแบบแบบ `native-friendly` และ `policy-safe` ไม่พยายามฝืนระบบ

สรุปคือ:

- ฝั่ง iOS `ทำ Main mode ได้ก่อน` จะคุ้มที่สุด
- ฝั่ง iOS `Display mode` ทำได้ แต่บาง behavior อาจไม่ aggressive เท่าฝั่ง Android
- ต้องออกแบบ fallback UX ให้ดี

---

## Recommended Scope Split

### Phase 1: iPhone as Main Device
โฟกัสให้ iOS เป็นเครื่องหลักก่อน เพราะโอกาสสำเร็จสูงและ value ต่อผู้ใช้ชัด

ต้องมี:

- Firebase sign-in / device registration
- QR scan เพื่อ pair กับ Display device
- Share sheet / open-in-app flow สำหรับลิงก์แผนที่
- Route parsing
- Relay route ไปยัง paired display ผ่าน cloud
- Settings ขั้นพื้นฐาน

### Phase 2: iPhone as Display Device
ทำให้ iOS รับ route จากเครื่องหลักและเปิดแผนที่ต่อ

ต้องมี:

- Display mode screen
- QR pairing screen
- receive route listener ผ่าน cloud
- แสดง latest route
- ปุ่มเปิด Apple Maps / Google Maps

### Phase 3: UX Optimization

- deep link polish
- route history
- reconnect flow
- better state restoration
- notifications / foreground refresh where allowed

---

## Recommended MVP
MVP ที่ควรทำก่อนมีดังนี้:

1. Native iOS app เปิดได้และมี `Home / Display / Settings`
2. Sign in แบบ anonymous กับ Firebase
3. สร้าง local device ID
4. Sync `devices / presence`
5. Pair ผ่าน QR code กับ Android/Web
6. iOS Main ส่ง Google Maps URL ไป Display ได้
7. iOS Display รับ route แล้วแสดงปุ่มเปิดแผนที่ได้

---

## Suggested Tech Stack

- `Swift`
- `SwiftUI`
- `Firebase Auth`
- `Firebase Realtime Database`
- `AVFoundation` สำหรับ QR scanning
- `CoreLocation` ถ้ามีฟีเจอร์ location/speed ในอนาคต

ถ้าต้องการเปิด Google Maps โดยตรง อาจรองรับ:

- Apple Maps URL
- Google Maps URL scheme
- Universal links / web fallback

---

## Product Compatibility Goal
ฝั่ง iOS ต้องออกแบบให้คุยกับโครงข้อมูลเดิมของ PHANToM GO ได้ เช่น:

- `devices/{deviceId}`
- `presence/{deviceId}`
- `pairingCodes/{pairCode}`
- `pairings/byMain/{deviceId}`
- `pairings/byDisplay/{deviceId}`
- `commands/{displayDeviceId}/{commandId}`

เป้าหมายคือ `reuse backend contract เดิม` ให้มากที่สุด เพื่อไม่สร้างระบบแยก

---

## Initial UX Direction

### Home
หน้าหลักของ iOS ควรเน้น:

- pairing status
- paired display info
- quick send route
- paste route manually
- open share flow explanation

### Display
หน้าจอ Display ควรมี:

- QR code ตอนยังไม่ pair
- connected state ตอน pair แล้ว
- latest route card
- ปุ่มเปิดแผนที่

### Settings
หน้าตั้งค่าควรเริ่มจาก:

- Firebase / connection status
- preferred map app
- navigation mode
- auto-open behavior (เท่าที่ iOS อนุญาต)
- debug info

---

## Important Differences from Android

ฝั่ง iOS อาจไม่รองรับหรือไม่ควรพยายามทำให้เหมือน Android ในเรื่อง:

- overlay floating controls
- aggressive background capture
- accessibility-assisted interception แบบเดียวกับ Android
- auto wake + auto launch maps จากจอดับแบบเต็มรูปแบบ
- background services ลักษณะเดียวกับ Android foreground service

ต้องออกแบบใหม่ให้เหมาะกับ iOS แทน

---

## Recommended Project Structure
เมื่อเริ่มสร้างโปรเจกต์จริง แนะนำโครงแบบนี้:

```text
ios/
  README.md
  PHANToMGO/
    App/
    Features/
      Home/
      Display/
      Settings/
      Pairing/
      RouteRelay/
    Core/
      Firebase/
      Models/
      Services/
      Utils/
    Resources/
```

---

## Data Models to Mirror
โมเดลหลักที่ควรมีตั้งแต่ต้น:

- `DeviceRecord`
- `PresenceRecord`
- `PairingCode`
- `PairingRecord`
- `RouteCommand`
- `RoutePayload`

---

## Route Parsing Requirements
ต้องรองรับ route input แบบเดียวกับระบบกลางให้มากที่สุด:

- `https://maps.app.goo.gl/...`
- `https://www.google.com/maps/...`
- `https://maps.google.com/...`
- `geo:`
- plain coordinates
- plain place names

---

## Parallel Project Rules
โปรเจกต์ iOS นี้ควรยึดหลัก:

1. อย่ารื้อ backend schema เดิมถ้าไม่จำเป็น
2. แยก concern ให้ชัดตั้งแต่แรก
3. อย่าพยายามฝืน iOS ให้เหมือน Android ทุกพฤติกรรม
4. ถ้ามีข้อจำกัดของ iOS ให้แก้ด้วย UX ที่เหมาะสมแทน hack
5. ให้ `Main mode` มาก่อน `Display mode` ถ้าต้องเลือก

---

## First Engineering Milestone
Milestone แรกที่แนะนำ:

1. สร้าง Xcode project
2. ตั้ง bundle/app name
3. ผูก Firebase
4. ทำ anonymous auth
5. ทำ home screen แบบง่าย
6. ทำ device registration
7. ทำ QR scan pairing
8. ทดสอบ pair กับ Android display

---

## Second Engineering Milestone

1. รับ route จาก share sheet
2. parse route
3. ส่ง route ผ่าน Firebase command path
4. เช็กว่า Android display เปิด route ได้จริง

---

## Third Engineering Milestone

1. ทำ iOS Display mode
2. รับ route command
3. แสดง latest route
4. เปิด Apple Maps / Google Maps
5. polish state handling

---

## Non-Goals for v1
สิ่งที่ยังไม่ควรรีบทำใน iOS v1:

- browser-style workaround ในแอป native
- automation ที่พึ่ง private API
- root-like behavior
- background interception แบบ Android
- feature parity 100% ทุกเมนู

---

## Success Criteria
ถือว่า iOS v1 สำเร็จเมื่อ:

- iPhone pair กับ Android/Web ได้
- iPhone ส่ง route ไป Display ได้
- iPhone รับ route และเปิด map ได้อย่างน้อยแบบ manual-assisted
- ผู้ใช้เข้าใจ flow โดยไม่ต้องตั้งค่าเยอะ

---

## Next Step
ขั้นต่อไปของโปรเจกต์ตอนนี้คือ:

1. รัน workflow `iOS CI` ให้ผ่านจริง
2. ใช้ Appetize ตรวจ UI/flow ถ้าต้องการดูผ่าน browser
3. ตั้ง signing secrets
4. รัน workflow `iOS Device Build`
5. ถ้า build ผ่าน ค่อยเริ่มงานฟีเจอร์ถัดไป เช่น Share Extension
