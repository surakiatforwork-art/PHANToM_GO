# PHANToM GO iOS Native IPA: แผนงานและผล Audit

วันที่เริ่มงาน: 2026-10-13
Repository: https://github.com/surakiatforwork-art/PHANToM_GO

## เป้าหมาย

สร้างแอป SwiftUI สำหรับ iPhone/iPad เป็น native IPA จริง ไม่ใช้ WebView ครอบเว็บ และทำงานร่วมกับ Android APK กับ Web App ผ่าน Firebase schema เดิม ให้ฟีเจอร์ใกล้เคียง APK เท่าที่ public API ของ iOS ทำได้

## Audit ก่อนแก้ไข

### มีอยู่แล้ว

- SwiftUI shell สำหรับ Home / Display / Settings
- QR renderer และ scanner เบื้องต้น
- Route parser และ unit tests เบื้องต้น
- โครง cloud pairing, preferences และ map launcher
- GitHub Actions สำหรับ simulator และ signed IPA บน macOS runner

### มีบางส่วนแต่ต้องแก้

- Firebase command เดิมใช้ `resolvedURL` และไม่มี `fromMainAuthUid`/`url` จึงไม่ตรงกับ APK และ rules ปัจจุบัน
- ไม่มี ack/retry/dedupe จึงยังยืนยันไม่ได้ว่า Display ได้รับเส้นทางจริง
- listener ไม่ถูกหยุดครบเมื่อปิด Display และไม่มี lifecycle/reconnect ที่สมบูรณ์
- QR scanner เริ่มกล้องบน main thread และไม่มีการจัดการ permission/error/double scan ครบ
- map launcher นำ URL ทั้งเส้นไปใส่เป็น destination และรถยนต์/มอเตอร์ไซค์กับ preview/navigation ใช้ logic เดียวกัน
- การล้าง pairing ใช้ค่าที่อาจเก่า ต้องอ่าน pairing จริงก่อนลบเพื่อรักษาคู่ปัจจุบัน
- ไม่มี Firebase iOS configuration และไม่มี signing secrets ใน repository ใหม่
- ยังไม่มีผล compile/test จาก Xcode จริงของ iOS scaffold

### ยังไม่มี

- Share Extension / incoming route deep link
- GPS speedometer และตำแหน่ง Display
- Security tab / Find Display พร้อมแผนที่ / security alerts
- local network transport บน iOS
- power state และ brightness profile ที่ทำได้ใน foreground
- local PIN และ jack-disconnect challenge/alarm
- native dashcam, recordings, trim/export
- unsigned arm64 device IPA สำหรับเซ็นติดตั้งบน Windows

## แนวทาง Firebase

- ใช้ Firebase Auth REST และ Realtime Database REST streaming ผ่าน URLSession ของ iOS
- reuse public Web API configuration จาก `docs/web/webapp/index.html` ใน build pipeline โดยไม่ต้องมี iOS plist
- ไม่สร้าง auth แบบ admin ไม่ใช้ service-account และไม่เปิด security rules
- เก็บ refresh token ใน Keychain ไม่เก็บใน logs หรือ UserDefaults
- วิธี REST ไม่ต้องมี Firebase iOS app ID หรือ `GoogleService-Info.plist` เพื่อใช้ Auth/Database ของโปรเจกต์เดิม
- ใช้ `url`, `fromMainAuthUid`, `clientRequestId`, status ack และ timestamps ตาม APK
- แชร์ credential ให้ Share Extension เฉพาะผ่าน shared Keychain เมื่อ provisioning รองรับ; หาก signing ไม่รองรับ shared access ให้ fallback เป็น copy/paste โดยไม่สร้างตัวตน Main ใหม่

## ขั้นตอนดำเนินงาน

1. แก้ core parsing, Firebase contract, auth persistence, reconnect, reliable relay และ pairing cleanup
2. ทำ SwiftUI 4 หน้า Home / Display / Settings / Security ให้รองรับ light/dark และ iPad
3. เพิ่ม GPS speed, periodic location, Find Display MapKit และ alerts จาก Android
4. เพิ่ม local relay และฟีเจอร์ power/security ที่ iOS ทำได้จริง
5. เพิ่ม native dashcam, clip library และ trim/export
6. เพิ่ม Share Extension และ deep-link handling พร้อม fallback สำหรับ sideload
7. ปรับ GitHub Actions ให้ build/test ด้วย Xcode แล้ว package arm64 iPhoneOS `.app` เป็น unsigned IPA หรือ signed IPA เมื่อมี certificate/profile
8. รีวิวโค้ด ทดสอบ parser/contract/security policies และแก้ปัญหา compile จน CI ผ่าน
9. Publish iOS release แยกจาก Android โดยไม่เปลี่ยน Android Latest Stable
10. บันทึกข้อจำกัด ผลทดสอบจริง และวิธีติดตั้งด้วย Sideloadly/Apple signing

## ข้อจำกัดที่ต้องแสดงอย่างตรงไปตรงมา

| ฟีเจอร์ APK | iOS |
| --- | --- |
| Firebase pairing / relay / GPS / Find Display | ทำได้ด้วย native API |
| Share เข้า PHANToM GO | ใช้ Share Extension; shared access ขึ้นกับ signing |
| เปิด Maps เมื่อ foreground | ทำได้; เบื้องหลังต้องกลับเข้าแอปหรือกด notification |
| อ่าน notification ของแอปอื่นและ scoped Accessibility | ไม่มี public API ให้ทำแบบ Android |
| ปลุกจอหรือบังคับล็อกเครื่อง | แอปทั่วไปทำไม่ได้; ใช้ foreground brightness/keep-awake เท่านั้น |
| overlay ทับแอปอื่น / full-screen block ทั้งเครื่อง | ทำไม่ได้; PIN challenge ครอบคลุมเฉพาะแอปนี้ |
| กันขโมยขณะ app suspended/force-quit | ไม่รับประกัน; ใช้ notification และ re-evaluate เมื่อ resume |
| บังคับ system volume/ลำโพงทุกกรณี | ทำไม่ได้แบบ Android |
| Dashcam ขณะอยู่ในแอปอื่น/จอล็อก | หยุด recording ตาม lifecycle และแจ้งผู้ใช้ |
| Bluetooth transport Android เดิม | ไม่ใช่ transport เดียวกับ iOS; ใช้ Cloud/LAN |
| unsigned IPA จาก GitHub | ต้องเซ็นก่อนติดตั้ง; ไม่ใช่ไฟล์ที่แตะแล้วติดตั้งได้ทันที |

## เกณฑ์ส่งมอบ

- Xcode compile ผ่านสำหรับ device arm64 และ simulator
- unit tests ผ่านจาก macOS runner
- IPA มี executable ของ iPhoneOS จริงและถูกระบุสถานะ signing ชัดเจน
- มี SHA-256 และลิงก์ release/download ที่ตรวจแล้ว
- ไม่มีการอ้างว่าทดสอบ runtime บน iPad/iPhone หากยังไม่ได้ทดสอบจริง
- แยกฟีเจอร์ที่ทำได้กับฟีเจอร์ที่มีข้อจำกัดใน release notes และหน้าตั้งค่า

## แหล่งอ้างอิง

- https://firebase.google.com/docs/reference/rest/auth
- https://firebase.google.com/docs/reference/rest/database
- https://firebase.google.com/docs/database/rest/retrieve-data
- https://developer.apple.com/documentation/uikit/preparing-your-ui-to-run-in-the-background
- https://developer.apple.com/documentation/uikit/uiapplication/isidletimerdisabled
- https://developers.google.com/maps/documentation/urls/get-started
- https://developers.google.com/maps/documentation/urls/ios-urlscheme

## สถานะ

ตัวแอป native และ Share Extension build ผ่านแล้วบน Xcode 26.6 ทั้ง simulator และ iPhoneOS arm64

- ชุดแรกที่ผ่าน: unit tests 24 รายการ และ UI smoke tests 3 รายการต่อเครื่องบน iPhone/iPad simulator
- แก้ regression เพิ่มก่อนปล่อย: alarm จาก wrong PIN ขณะขอ disarm ต้องเดินเวลา/เสียงพูด/ยกระดับหน้าจอได้แม้ยังไม่ถอด jack และไม่ส่ง alarm event ซ้ำ
- ผลสุดท้ายของ commit `087b4d0`: unit tests 25 รายการ, iPhone UI tests 3 รายการ และ iPad UI tests 3 รายการผ่านทั้งหมด ไม่มี test failure
- Simulator run: https://github.com/surakiatforwork-art/PHANToM_GO/actions/runs/38376680495
- Device IPA run: https://github.com/surakiatforwork-art/PHANToM_GO/actions/runs/38376712861
- Tag ที่ใช้ส่งมอบ: `ios-v1.0.0`; unsigned IPA สำหรับเซ็นเอง ไม่เปลี่ยน Android Latest Stable
- ไฟล์ในเครื่อง: `docs/build/ios-v1.0.0/PHANToM_GO-iOS-v1.0.0-unsigned.ipa` พร้อม `SHA256SUMS.txt`
- SHA-256: `c96f8859b2228742064c856d9e1a362d00d77e36b3011e1aec553e7aff52cb1e`
- หลังติดตั้งต้องทดสอบบนเครื่องจริงตาม `TEST_READINESS.md`; ยังไม่มีผล real-device runtime ในงานนี้
