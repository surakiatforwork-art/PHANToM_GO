# PHANToM GO v1.3.1

วันที่: 2026-06-30 • Stable • versionCode 31

## สรุป
รุ่นนี้เป็น patch release หลัง v1.3.0 เน้นความแม่นยำของ timeline export ใน GO Cam, เพิ่มความเสถียรของ Web App route relay ให้ทำงานใกล้เคียง native APK มากขึ้น, และเก็บ lint warnings ชุดแรกใน native UI โดยไม่เปลี่ยน flow หลักของ pairing, display, security, power automation หรือ dashcam recording

## มีอะไรใหม่ / แก้อะไร

### GO Cam / Dashcam
- ปรับแถบล่างของ timeline export ให้เป็นการ scrub ช่วงเวลาที่เลือกอยู่จริง: ความยาวแถบล่างแทน duration ของ selection เสมอ และการลากแถบล่างจะเลื่อน footage เข้ามาอยู่ในช่วงนั้นโดยไม่เปลี่ยน duration
- แถบบนยังคงพฤติกรรมเดิมสำหรับกำหนดช่วงเวลาและซูมละเอียด

### Web App
- เพิ่ม reliable cloud route delivery ให้ browser main: ส่งคำสั่งซ้ำแบบมี `clientRequestId` 3 attempt และรอ ack จาก display ก่อนแจ้งว่าสำเร็จ
- เพิ่ม display-side dedupe ใน browser display เพื่อให้ retry ของคำสั่งเดียวกันไม่เปิด Maps ซ้ำ
- อัปเดต webapp device metadata เป็น `webapp-1.3.1`

### Native APK UI / Lint Cleanup
- ลบ nested vertical scroll ในหน้า Settings โดยเปลี่ยน Logcat panel เป็น TextView ที่ scroll ได้เอง
- ย้าย hardcoded text/title ใน XML ไปเป็น string resources ทั้งไทยและอังกฤษ
- ลด Android lint จาก 220 warnings เหลือ 206 warnings และเคลียร์กลุ่ม `NestedScrolling` / `HardcodedText`

### Website
- อัปเดตลิงก์ดาวน์โหลดและข้อความ Current Stable บน Landing Page ไปยัง v1.3.1

## ทดสอบที่รันจริง
- `node --check` สำหรับ module script ของ Web App ผ่าน
- `git diff --check` ผ่าน (มีเฉพาะคำเตือน line ending จาก Git บน Windows)
- `assembleDebug assembleRelease lintDebug testDebugUnitTest --stacktrace` ผ่านทุกโมดูล
- `app:lintDebug` ผ่าน: `0 errors, 206 warnings`
- ติดตั้ง APK ใหม่บนอุปกรณ์จริง 2 เครื่องผ่าน ADB:
  - `R8YYB1FKTZY` → versionCode 31 / versionName 1.3.1
  - `e3b20843` → versionCode 31 / versionName 1.3.1

## ข้อจำกัดที่ทราบ
- APK release asset ใช้ build debug ที่ signed แล้วเพื่อให้ติดตั้งได้จริงจาก GitHub Release; `app-release-unsigned.apk` ที่ Gradle สร้างยังเป็น unsigned และไม่เหมาะสำหรับผู้ใช้ทั่วไป
- ยังมี lint warnings เหลือ 206 รายการ ส่วนใหญ่เป็น `UnusedResources`, typography/style และ compatibility warnings ที่ควรทยอยเก็บในรอบถัดไป
- Display power automation ยังเป็น best-effort ตามข้อจำกัดของ ROM/OEM โดยเฉพาะบางรุ่นของ Samsung

## Firebase
ไม่ต้องแก้ rules — รุ่นนี้ไม่เปลี่ยนโครงสร้างข้อมูล Firebase

## APK
- ไฟล์: `PHANToM_GO-v1.3.1.apk` (package `com.phantom.carnavrelay`)
- SHA-256: `7cbcaf918082945a71b3eb9c3b627e61a0c2c2656e8b90294241308808e4d7f8`
