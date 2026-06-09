# PHANToM GO iOS Build & Install

## Important
โปรเจกต์นี้ถูก scaffold บน Windows ดังนั้น `ยัง build iOS app จริงบนเครื่องนี้ไม่ได้`

การ build และติดตั้งต้องทำบน:

- macOS
- Xcode
- iPhone จริง หรือ iOS Simulator

ถ้าคุณ `ไม่มี Mac` ให้ใช้คู่กับเอกสารนี้:

- `ios/NO_MAC_PLAYBOOK.md`
- `ios/DEVICE_BUILD_PIPELINE.md`
- `ios/TEST_READINESS.md`

เอกสารนั้นอธิบาย flow ที่เราวางไว้แล้วสำหรับ:

1. build บน GitHub Actions
2. ส่ง simulator build ไป Appetize
3. ตรวจ UI/flow ผ่าน browser

---

## What is included
ในโฟลเดอร์ `ios` ตอนนี้มี:

- source files ของแอป
- project spec (`project.yml`)
- template ของ Firebase plist
- เอกสาร implementation plan
- workflow build iOS ที่ `.github/workflows/ios.yml`
- workflow signed IPA ที่ `.github/workflows/ios-device.yml`
- unit tests ใน `ios/PHANToMGOTests`
- helper script ที่ `ios/scripts/generate-github-secrets.ps1`

---

## Prerequisites on macOS

1. ติดตั้ง Xcode ล่าสุด
2. เปิด Xcode อย่างน้อย 1 ครั้ง
3. ติดตั้ง Command Line Tools
4. ติดตั้ง `xcodegen` ถ้าจะ generate project จาก `project.yml`

ตัวอย่างติดตั้ง `xcodegen`:

```bash
brew install xcodegen
```

---

## Firebase Setup

1. ไปที่ Firebase Console ของโปรเจกต์ `PHANToM GO`
2. Add app แบบ iOS
3. กรอก bundle identifier ให้ตรงกับค่าใน project
4. ดาวน์โหลด `GoogleService-Info.plist`
5. วางไฟล์นี้ไว้ใน:

```text
ios/PHANToMGO/Resources/GoogleService-Info.plist
```

ไฟล์ `GoogleService-Info.plist.template` เป็นแค่ placeholder ห้ามใช้แทนของจริง

---

## Generate Xcode Project
ถ้าใช้ `xcodegen`:

```bash
cd ios
xcodegen generate
```

จะได้ไฟล์ `.xcodeproj`

---

## Open Project
เปิด project ใน Xcode แล้วเช็ก:

1. Signing team
2. Bundle identifier
3. Deployment target
4. Firebase package resolve สำเร็จ

---

## Build Steps

### Build for Simulator
1. เลือก iPhone Simulator
2. กด Build
3. แก้ compile errors ถ้ามี
4. รัน unit tests

ถ้าไม่มี Mac:

- ใช้ GitHub Actions เพื่อ build simulator app แทน
- workflow จะพยายามรัน unit tests ให้ก่อน build
- ดาวน์โหลด artifact จาก workflow `iOS CI`
- หรือให้อัปโหลดขึ้น Appetize เพื่อเปิดดูบน browser

### Build for Device
1. เสียบ iPhone
2. เปิด Developer Mode บนเครื่อง
3. Trust เครื่องกับ Mac
4. เลือก target เป็น iPhone เครื่องจริง
5. Build และ Run

### Build for Device without a local Mac
ถ้าไม่มี Mac อยู่กับตัว แต่มี Apple signing assets:

1. ใช้ workflow `iOS Device Build`
2. export `.ipa` ผ่าน GitHub Actions
3. ดาวน์โหลด artifact จาก workflow run
4. ใช้ต่อกับวิธีติดตั้ง/แจกจ่ายที่เหมาะกับ distribution method นั้น

---

## First Runtime Checklist

หลังเปิดแอปครั้งแรก ให้เช็ก:

1. แอปเปิดได้ถึงหน้า Home
2. Firebase auth anonymous สำเร็จ
3. เขียน `devices/{deviceId}` สำเร็จ
4. เขียน `presence/{deviceId}` สำเร็จ
5. เปิด Display tab แล้วมี `pairingCodes/{pairCode}`
6. QR scan เปิดกล้องได้
7. Pair กับ Android ได้
8. ส่ง route ไป Android Display ได้
9. รับ route จาก Android Main ได้

---

## Installation on iPhone

เมื่อ build ผ่านและ Run จาก Xcode:

1. แอปจะถูกติดตั้งลง iPhone โดยตรง
2. ถ้าใช้บัญชีฟรี อาจต้องไปที่:
   - `Settings`
   - `General`
   - `VPN & Device Management`
   - trust developer profile

หลังจากนั้นจึงเปิดแอปได้

## Without a Mac

กรณีไม่มี Mac ให้แยกเป็น 2 เรื่อง:

### ดูแอป/เช็ก flow
- ใช้ `GitHub Actions` + `Appetize`

### ลง iPhone/iPad จริง
- ต้องมี `signed IPA`
- ตอนนี้โปรเจกต์มี workflow สำหรับสร้าง IPA ผ่าน macOS CI แล้ว
- ถ้ายังไม่มี signing assets จะยังไปได้แค่ simulator/app preview ก่อน

---

## If Build Fails

### Common causes
- ไม่มี `GoogleService-Info.plist`
- bundle identifier ไม่ตรง Firebase
- signing team ยังไม่ตั้ง
- package Firebase resolve ไม่สำเร็จ
- เปิด project บนเครื่องที่ไม่มี Xcode

---

## Recommended Next Milestones After First Build

1. Validate Firebase
2. Validate pairing
3. Validate route relay
4. Add Share Extension
5. Polish display mode
