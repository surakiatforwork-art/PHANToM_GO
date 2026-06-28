# Dashcam Workflow Improvements (9 items) + Dual-Cam Approach

Date: 2026-06-26

แก้ตามผลรีวิว workflow ของ dashcam ทั้ง 9 ข้อ หลักการ: behavior เดิมต้องคงอยู่ และข้อที่เสี่ยง (โดยเฉพาะ #9) ต้อง **ย้อนกลับง่ายด้วย flag/constant เดียว**

## รายการแก้

1. **Resume หลัง service ถูกฆ่าทั้งที่ยังเสียบชาร์จ** — เพิ่ม `userStopped` ใน DashcamPreferences (set เมื่อกด Stop เอง, clear เมื่อเสียบไฟ/กด Start) watchdog เรียก `maybeStartIfChargingAndReady` (มีเงื่อนไข `!userStopped`) เพื่อกลับมาอัดอัตโนมัติ
2. **กันไฟวูบ (disconnect grace)** — เลื่อนการ stop ตอนถอดไฟออกไป `DISCONNECT_GRACE_MS` (4s) ถ้าเสียบกลับมาทันให้ยกเลิก stop (อัดต่อเนื่อง)
3. **เอาสวิตช์ "แสดงพรีวิว" ออกจาก Settings แอปหลัก** — เพราะแอปหลักไม่มี surface แสดงพรีวิว (พรีวิวใช้เฉพาะหน้า GO Cam) คง pref ไว้ให้ GO Cam ใช้
4. **ยุบสวิตช์ master ในหน้า Security** — เหลือสวิตช์เดียว = "บันทึกอัตโนมัติเมื่อเสียบชาร์จ" ซึ่งเป็น gate จริง (ปิด = หยุด monitor/boot) เลิกใช้ `isFeatureEnabled` ที่ไม่ได้ gate อะไร
5. **ปุ่มยกเว้น Battery Optimization** ในการ์ด Security dashcam (แสดงเมื่อยังไม่ยกเว้น) เพื่อความเสถียรของ FGS บน OEM ดุ
6. **เก็บกวาดคลิปเสีย/ขนาด 0** — `DashcamClipStorageManager.cleanupIncompleteClips` เรียกตอนเริ่มอัด
7. **โหมด Dual: probe ความสามารถ** — `DashcamCapabilities.supportsDualCamera` (CameraManager.getConcurrentCameraIds, API 30+) ถ้าไม่รองรับ → disable ปุ่ม Dual ใน Settings/หน้า GO Cam
8. **Clip browser**: เพิ่ม thumbnail (best-effort, API 29+) + ปุ่มลบ (API 30+ ใช้ MediaStore.createDeleteRequest, ต่ำกว่านั้น delete ตรง)
9. **ลด power-monitor ซ้ำซ้อน (เน้นเป็นพิเศษ, ย้อนง่าย)** — เพิ่ม constant `DEDUPE_POWER_MONITORING` (ค่าเริ่มต้น true) ใน DisplayServerService:
   - เมื่ออยู่ Display mode: app ส่งต่อ power event จาก receiver/watchdog เดิมของ Display เข้า `AutoRecordPowerManager` และตั้ง `setExternalPowerFeedActive(true)` → dashcam **ไม่สตาร์ท PowerMonitoringService ของตัวเอง** (ลด FGS/notification/receiver/poll ซ้ำ)
   - dashcam ยังได้รับ edge เหมือนเดิม → การอัดทำงานเหมือนเดิม
   - **Revert ง่าย**: ตั้ง `DEDUPE_POWER_MONITORING = false` → app เลิกส่งต่อ/เลิกตั้ง external feed → dashcam กลับไปรัน monitor ของตัวเองเป๊ะแบบเดิม
   - GO Cam standalone ไม่ได้รับผล (external feed = false เสมอ)

## ทดสอบ
- build assembleDebug/Release + lint + tests, ติดตั้งจริง เปิดไม่ crash
- QA: reboot ขณะเสียบ → อัด, ถอด-เสียบเร็ว → ไม่ขาด, Display+dashcam → เหลือ monitor ชุดเดียว, flag=false → กลับเป็นเดิม

## แนวทาง Dual cam (เฟสถัดไป — ยังไม่ลงมือ)
ดูส่วน "Dual-Cam Approach" ใน handoff/รายงาน — สรุป: CameraX `ConcurrentCamera` รองรับ 2 กล้องพร้อมกันเฉพาะเครื่องที่ประกาศ `getConcurrentCameraIds()` และมักจำกัดที่ความละเอียด ≤720p/กล้อง; ถ้าเครื่องไม่รองรับ ทางเลือกคือ Camera2 multi-camera (logical) หรือสลับเฟรม (ไม่ใช่พร้อมจริง)
