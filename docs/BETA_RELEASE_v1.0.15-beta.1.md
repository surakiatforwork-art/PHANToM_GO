# PHANToM GO v1.0.15-beta.1

วันที่: 2026-06-11

## เป้าหมายของ Beta

เพิ่ม `Google Maps Auto Share` ฝั่ง Main Device เพื่อเลียนแบบ workflow แบบ
CarPlay / Android Auto ให้ใกล้ขึ้น โดยใช้ notification เป็น start signal และใช้
Scoped Accessibility Assist กด Share และเลือก PHANToM GO ให้อัตโนมัติ

## สิ่งที่เพิ่ม

1. เพิ่มการ์ด `Google Maps Auto Share` บนหน้า Home ฝั่ง Main
2. ใช้สถานะ Accessibility Service เป็น source of truth สำหรับเปิด/ปิดฟีเจอร์
3. เพิ่ม Notification Listener สำหรับรับสัญญาณเริ่มนำทางจาก Google Maps
4. จำกัด automation ให้ทำงานกับ Google Maps และ share sheet ระหว่าง active session
5. เพิ่ม fast-path สำหรับ warning ปลายทางอาจปิด:
   - ตรวจปุ่ม `ออก / Exit` และ `ต่อไป / Continue` พร้อมกัน
   - กด Exit
   - เริ่ม scan Share ต่อทันทีโดยไม่มี fixed delay
6. เพิ่ม direct-share path และ fallback preview/stop-navigation path
7. ใช้ share receiver และ route relay เดิมทั้งหมด ไม่สร้าง transport หรือ Firebase flow ใหม่
8. เพิ่ม session timeout, cooldown, Main-mode guard และ paired-state guard
9. dedupe Maps navigation notification จนกว่า notification เดิมจะถูกลบ เพื่อป้องกัน route เดิมถูกส่งซ้ำ
10. เพิ่ม unit tests สำหรับ text matcher และ security allowlist

## วิธีเปิดทดสอบ

1. จับคู่ Main กับ Display ให้เรียบร้อย
2. หน้า Home กด `Enable in Accessibility Settings`
3. เปิด `PHANToM GO Maps Auto Share`
4. กลับหน้า Home แล้วกด `Allow Maps notification signal`
5. อนุญาต PHANToM GO อ่าน notification
6. เปิดเส้นทางจากแอปต้นทางหรือเริ่มนำทางใน Google Maps
7. ตรวจว่า PHANToM GO เปิด Share และส่งเส้นทางไป Display

ถ้าต้องการปิด ให้กด `Manage / Disable in Accessibility Settings` แล้วปิด service จริง

## การตรวจสอบก่อน Release

- `assembleDebug`: ผ่าน
- `assembleRelease`: ผ่าน
- `testDebugUnitTest`: ผ่าน 5 tests
- `lintDebug`: ผ่าน ไม่มี error; warning เดิมของโปรเจกต์ยังคงอยู่
- ตรวจ APK: `com.phantom.carnavrelay`, `1.0.15-beta.1 (16)`, ลายเซ็น v1/v2 ผ่าน
- APK SHA-256: `B93E717FBF5DCB32EAFD939413BCA4F468E8AC0C36C9625B8CD770E2CDEE60E5`
- ตรวจ source-level integration:
  - ไม่แก้ Firebase contract/rules
  - ไม่แก้ pairing transport
  - ไม่แก้ Display route receiver
  - ไม่แก้ Display power automation
  - ไม่แก้ anti-theft flow
  - ไม่แก้ไฟล์ภายใน `ios/`

## Known Limitations

- ต้องเปิด Accessibility และ Notification access ด้วยตนเอง
- Android 13 ขึ้นไปอาจต้องเปิด `Allow restricted settings` ใน App info ก่อนจึงจะเปิด
  Accessibility ของ APK ที่ติดตั้งนอก Store ได้
- แอปธนาคารบางแอปอาจตรวจพบว่าอุปกรณ์เปิด Accessibility Service แม้ PHANToM GO
  จะจำกัด logic ให้ทำงานเฉพาะ Google Maps
- Google Maps หรือ share sheet ของ OEM อาจเปลี่ยนข้อความและโครงสร้าง UI ในอนาคต
- ถ้า share sheet ของ OEM ไม่อยู่ใน allowlist ระบบจะหยุดอย่างปลอดภัยและให้ผู้ใช้
  เลือก PHANToM GO เอง
- รอบนี้ไม่มีอุปกรณ์ ADB เชื่อมต่อ จึงยังไม่ได้ทดสอบ end-to-end กับ Google Maps จริง
- ไม่มีการแก้ Firebase Console หรือ Firebase Rules สำหรับ beta นี้
