# PHANToM GO iOS Device Build Pipeline

เอกสารนี้อธิบายเส้นทาง build `IPA สำหรับเครื่องจริง` ของ PHANToM GO iOS ผ่าน GitHub Actions โดยไม่ต้องมี Mac อยู่ในมือทุกวัน

## เป้าหมาย

หลังจากรอบก่อนเราเตรียม `simulator build + Appetize` แล้ว รอบนี้เราเพิ่มเส้นทางสำหรับ:

- สร้าง `signed IPA`
- ดาวน์โหลดไฟล์จาก GitHub Actions
- ใช้ต่อกับการติดตั้ง / แจกทดสอบบนอุปกรณ์จริง

workflow ใหม่อยู่ที่:

- `.github/workflows/ios-device.yml`

---

## Reality Check

เส้นนี้ `ยังต้องใช้ Apple signing assets` อยู่ดี

แปลว่า ถึงจะไม่มี Mac อยู่กับตัว คุณก็ยังต้องมีอย่างน้อย:

- Apple Developer account ที่เหมาะกับรูปแบบแจกจ่ายที่ต้องการ
- certificate (`.p12`)
- provisioning profile (`.mobileprovision`)

ถ้ายังไม่มี 3 อย่างนี้ เราจะ build ได้แค่ simulator/app preview ต่อไปก่อน

---

## Workflow นี้ทำอะไร

เมื่อสั่งรัน `iOS Device Build` มันจะ:

1. รับ Firebase plist จาก GitHub secret
2. ติดตั้ง certificate และ provisioning profile ลงบน macOS runner ชั่วคราว
3. generate Xcode project จาก `ios/project.yml`
4. archive แอปแบบ Release
5. export เป็น `.ipa`
6. upload artifact กลับขึ้น GitHub Actions

---

## Inputs ของ workflow

### `distribution_method`
เลือกวิธี export IPA:

- `development`
- `ad-hoc`
- `app-store`

### ควรใช้แบบไหน

- `development`: เหมาะสำหรับทดสอบกับ provisioning profile แบบ development
- `ad-hoc`: เหมาะสำหรับแจกให้เครื่องที่ลงทะเบียน UDID ไว้แล้ว
- `app-store`: เหมาะสำหรับเส้นทาง TestFlight / App Store ต่อ

ถ้าตอนนี้เป้าหมายคือ “เอาไปลองบน iPad จริงแบบตรงที่สุด” ให้เริ่มจาก:

- `development` ถ้ามี development profile
- `ad-hoc` ถ้าคุณเตรียม UDID เครื่องไว้ใน profile แล้ว

---

## Secrets ที่ต้องเพิ่มใน GitHub

เปิด:

- `Settings`
- `Secrets and variables`
- `Actions`

แล้วเพิ่มค่าต่อไปนี้

### 1. `IOS_FIREBASE_PLIST_BASE64`
Base64 ของ `GoogleService-Info.plist`

### 2. `BUILD_CERTIFICATE_BASE64`
Base64 ของไฟล์ certificate `.p12`

### 3. `P12_PASSWORD`
รหัสผ่านของไฟล์ `.p12`

### 4. `BUILD_PROVISION_PROFILE_BASE64`
Base64 ของไฟล์ provisioning profile `.mobileprovision`

### 5. `KEYCHAIN_PASSWORD`
รหัสผ่านชั่วคราวสำหรับ keychain บน runner

แนะนำให้สุ่มเป็นข้อความยาวๆ เช่น 24 ตัวอักษรขึ้นไป

### 6. `APPLE_TEAM_ID`
Team ID จาก Apple Developer account

### 7. `IOS_BUNDLE_IDENTIFIER` (ไม่บังคับ)
ถ้าไม่ใส่ ระบบจะใช้ค่า default:

```text
com.phantom.PHANToMGO
```

ถ้า bundle identifier ในโปรเจกต์เปลี่ยนในอนาคต ค่อยตั้ง secret นี้ทับ

### 8. `CODE_SIGN_IDENTITY` (ไม่บังคับ)
ใช้เฉพาะกรณีที่ archive ล้มเหลวเพราะ Xcode เลือก identity ไม่ตรงเอง

ตัวอย่างค่าที่พบบ่อย:

- `Apple Development`
- `Apple Distribution`

---

## วิธีแปลงไฟล์เป็น Base64 บน Windows

### plist / p12 / mobileprovision

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\your-file"))
```

ใช้คำสั่งเดียวกันได้กับทุกไฟล์ binary/text ที่ต้องเก็บเป็น GitHub secret

---

## วิธีรัน workflow

1. push โค้ดขึ้น GitHub
2. ไปที่ `Actions`
3. เลือก workflow `iOS Device Build`
4. กด `Run workflow`
5. เลือก `distribution_method`
6. รอ job จบ

เมื่อสำเร็จแล้ว ให้ดาวน์โหลด artifact ชื่อประมาณ:

- `PHANToMGO-ios-device-ipa-development`
- `PHANToMGO-ios-device-ipa-ad-hoc`
- `PHANToMGO-ios-device-ipa-app-store`

---

## สิ่งที่ได้จาก workflow

- ไฟล์ `.ipa`
- ไฟล์ `SHA256SUMS.txt`
- log ของการ archive/export

---

## หลังได้ IPA แล้วทำอะไรต่อ

### กรณี `development`
- ใช้กับเครื่อง/flow ที่รองรับ development signing
- เหมาะกับการลองก่อนว่าตัวแอปลงได้ไหม

### กรณี `ad-hoc`
- ใช้แจกให้เครื่องที่มี UDID อยู่ใน provisioning profile แล้ว
- เหมาะกับ test ภายในกลุ่มเล็ก

### กรณี `app-store`
- เหมาะกับการต่อยอดไป TestFlight / App Store
- แต่ workflow นี้ยังหยุดที่การ export IPA ก่อน ยังไม่ได้ upload ต่อให้อัตโนมัติ

---

## จุดที่ยังไม่ได้ทำในรอบนี้

เพื่อให้ระบบยังคุมความเสี่ยงง่าย เรา `ยังไม่ได้` เพิ่ม:

- upload ขึ้น TestFlight อัตโนมัติ
- notarization/distribution automation เพิ่มเติม
- installer บน Windows ที่กดครั้งเดียวจบ

ถ้ารอบถัดไปจะทำต่อ ฉันแนะนำ 2 ทาง:

1. เพิ่ม `TestFlight pipeline`
2. เพิ่ม `installation playbook` สำหรับ ad-hoc IPA บน Windows/iPad

---

## ข้อควรระวัง

1. provisioning profile ต้องตรงกับ bundle identifier
2. certificate ต้องตรงกับ profile และรูปแบบ export ที่เลือก
3. ถ้าใช้ `ad-hoc` อุปกรณ์ปลายทางต้องมี UDID อยู่ใน profile
4. ถ้าใช้ `app-store` ไฟล์ที่ได้ยังไม่เท่ากับ “ติดตั้งบนเครื่องได้ทันที” เสมอไป เพราะเส้นนั้นมักไปต่อที่ TestFlight
5. ถ้า archive แจ้งเรื่อง signing identity ไม่ตรง ให้ลองตั้ง `CODE_SIGN_IDENTITY` เพิ่ม

---

## Recommended Next Step

ถ้าอยากเดินแบบคุ้มที่สุดตอนนี้:

1. รัน `iOS CI` เพื่อเช็ก compile ก่อน
2. ถ้าผ่าน ค่อยรัน `iOS Device Build` แบบ `development` หรือ `ad-hoc`
3. ถ้า IPA ออกสำเร็จ ค่อยวางแผนขั้น install/distribute ต่อ
