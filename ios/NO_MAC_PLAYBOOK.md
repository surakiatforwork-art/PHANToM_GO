# PHANToM GO iOS No-Mac Playbook

เอกสารนี้คือแผนใช้งานจริงสำหรับกรณี `ไม่มี Mac` แต่ยังต้องการเดินงาน iOS ต่อให้ได้เร็วที่สุด

## เป้าหมายของเอกสารนี้

เราจะเปิด 2 ทางพร้อมกัน:

1. `GitHub Actions` build แอป iOS บน macOS runner ให้แทน
2. `Appetize` ใช้ simulator build เพื่อเปิดดู/ทดสอบผ่าน browser

และตอนนี้มีทางที่ 3 เพิ่มแล้ว:

3. `GitHub Actions signed IPA pipeline` สำหรับ device build เมื่อมี Apple signing assets

แนวทางนี้เหมาะกับการ:

- เช็กว่าโปรเจกต์ compile ผ่านหรือไม่
- ดูหน้าตา UI
- เดิน flow หน้า Home / Display / Settings
- แชร์ลิงก์ให้คนอื่นลองเปิดใน browser ได้

แต่ยัง `ไม่ใช่การแทนที่ iPad จริง 100%`

---

## สิ่งที่ทำได้แล้วในโปรเจกต์

- มี workflow ใหม่ที่ไฟล์ `.github/workflows/ios.yml`
- มี workflow สำหรับเครื่องจริงที่ไฟล์ `.github/workflows/ios-device.yml`
- workflow จะ build iOS simulator app จากโฟลเดอร์ `ios`
- สามารถอัปโหลด artifact เป็นไฟล์ `.zip` ของ `.app`
- สามารถสั่งให้อัปโหลดขึ้น Appetize ต่อได้ ถ้าตั้ง secrets ครบ
- สามารถ export `.ipa` สำหรับเครื่องจริงได้ ถ้าตั้ง signing secrets ครบ

---

## สิ่งที่ต้องเตรียมใน GitHub

ไปที่ repo บน GitHub แล้วเปิด:

- `Settings`
- `Secrets and variables`
- `Actions`

จากนั้นเพิ่ม secrets ดังนี้

### 1. `IOS_FIREBASE_PLIST_BASE64`
ใช้สำหรับใส่ `GoogleService-Info.plist` ของ iOS เข้า CI

ตัวอย่างแปลงไฟล์เป็น Base64 บน Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\GoogleService-Info.plist"))
```

คัดลอกผลลัพธ์ทั้งหมด แล้วนำไปวางเป็นค่า secret

ถ้าไม่ใส่ secret นี้:

- workflow ยังสามารถ compile-check ได้
- แต่ runtime ฝั่ง Firebase ใน build นั้นจะไม่พร้อมใช้งานจริง

### 2. `APPETIZE_API_TOKEN`
ใช้เมื่ออยากให้งาน CI อัปโหลด build ไปยัง Appetize อัตโนมัติ

วิธีเอา token:

- เข้า Appetize
- ไปที่ Organization
- หา `API Token`

### 3. `APPETIZE_PUBLIC_KEY` (ไม่บังคับ)
ใส่เมื่ออยากให้อัปเดตแอป Appetize ตัวเดิมทุกครั้ง

ถ้าไม่ใส่:

- CI จะสร้าง app entry ใหม่บน Appetize

---

## วิธีรันครั้งแรก

### ทางที่ 1: เอาไว้เช็กว่า build ผ่านไหม

1. push โค้ดขึ้น GitHub
2. ไปที่ `Actions`
3. เปิด workflow `iOS CI`
4. ดู job `Build iOS Simulator App`
5. ดาวน์โหลด artifact ชื่อ `PHANToMGO-ios-simulator-app`

### ทางที่ 2: อยากเปิดดูผ่าน browser ด้วย Appetize

1. ตั้ง `APPETIZE_API_TOKEN`
2. ถ้ามี app เดิมบน Appetize อยู่แล้ว ค่อยตั้ง `APPETIZE_PUBLIC_KEY`
3. ไปที่ `Actions`
4. กด `Run workflow`
5. เปิด input `upload_to_appetize` เป็น `true`
6. รอให้ workflow จบ
7. เปิด workflow summary เพื่อดู `public key` และลิงก์ Appetize

---

## Appetize เหมาะกับอะไร

- ตรวจความสวยงามของหน้าจอ
- เช็กการจัดวางบน iPhone simulator
- เช็ก flow ภายในแอป
- ตรวจข้อความ ปุ่ม และสถานะ pairing UI

---

## Appetize ไม่ควรถูกใช้ตัดสินเรื่องเหล่านี้

- กล้องจริงสำหรับสแกน QR
- การเปิด Google Maps / Apple Maps แบบ device-to-device จริง
- การทำงานกับ hardware sensor
- การติดตั้งบน iPad จริง
- พฤติกรรมบางอย่างของ Firebase ที่ขึ้นกับ environment จริง

สรุปคือ `ดีมากสำหรับดูแอปและ flow` แต่ `ยังไม่ใช่การทดสอบแทนเครื่องจริงทั้งหมด`

---

## ถ้าต้องการติดตั้งลง iPad จริงจาก Windows

ตรงนี้ยังต้องมี `signed IPA` ก่อนเสมอ

ลำดับที่ถูกต้องคือ:

1. build iOS app บน macOS หรือ macOS CI
2. sign ให้เรียบร้อยด้วย Apple certificate + provisioning profile
3. จึงค่อยใช้เครื่องมือบน Windows เช่น `Sideloadly` เพื่อลงแอป

หมายความว่า:

- Windows ช่วย `ลงแอป` ได้
- แต่ Windows `สร้าง iOS build ที่ติดตั้งได้เองไม่ได้`

---

## ทางเลือกสำหรับ iPad จริง

### แบบเร็วสุด
- ใช้ Cloud Mac หรือเครื่อง Mac สักเครื่อง build และ export `.ipa`

### แบบทีม/ระยะยาว
- ใช้ GitHub Actions device build pipeline ที่เพิ่มไว้แล้ว
- ใส่ Apple signing secrets ลง GitHub Actions

ตอนนี้ในโปรเจกต์นี้มีครบ 2 เส้นแล้ว:

- `simulator build + Appetize`
- `signed IPA via GitHub Actions`

รายละเอียด device build ดูเพิ่มที่:

- `ios/DEVICE_BUILD_PIPELINE.md`

---

## ลำดับงานที่แนะนำต่อจากนี้

1. push โค้ดและให้ `iOS CI` รันก่อน
2. ดูว่า compile ผ่านไหม
3. ตั้ง `IOS_FIREBASE_PLIST_BASE64`
4. รันใหม่พร้อม `upload_to_appetize = true`
5. ใช้ Appetize ตรวจ UI/flow
6. ถ้าทุกอย่างโอเค ค่อยรัน `iOS Device Build`
7. ดาวน์โหลด IPA artifact ไปใช้ต่อ

---

## Reality Check

ถ้าเป้าหมายตอนนี้คือ:

- อยากเช็กความคืบหน้า iOS
- อยากเห็นแอปทำงานแบบเร็วที่สุด
- ยังไม่มี Mac

ทางนี้ถือว่าเป็นเส้นที่คุ้มที่สุดในตอนนี้
