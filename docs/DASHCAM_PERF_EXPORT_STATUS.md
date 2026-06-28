# Dashcam Perf + Export/Timeline + Stabilization — Status

Date: 2026-06-26
Base release: v1.1.1 (commit 8681fb6, versionCode 26). These changes are **uncommitted on top of it**, targeting the next release **v1.2.0 (versionCode 27)**.

## งานที่ทำเสร็จแล้ว (build + lint ผ่านทุกโมดูล, ติดตั้งบนเครื่อง R8YYB1FKTZY เปิดไม่ crash)

### A. แก้อาการหน่วง/ค้างเมื่อเปิด Google Maps
- เลือกความละเอียดได้ **720p (default) / 1080p** — `DashcamQuality` + pref + ตัวเลือกในหน้า Settings; กล้องเดี่ยวใช้ค่าที่เลือก (Dual คง HD)
- Overlay timestamp เบาลง: cache ขนาดกล่อง/measure + อัปเดตข้อความเฉพาะตอนวินาทีเปลี่ยน (ยังโชว์ทุกเฟรม)

### กันสั่น (Stabilization)
- `DashcamCapabilities.supportsVideoStabilization` + pref (default เปิด) + สวิตช์ใน Settings (โชว์เฉพาะเครื่องที่รองรับ)
- ใส่ผ่าน Camera2Interop (EIS `CONTROL_VIDEO_STABILIZATION_MODE` + OIS) เฉพาะกล้องเดี่ยว; EIS ครอป FOV เล็กน้อย (ผู้ใช้ยอมรับแล้ว)

### C. รวมคลิปทั้ง session (กดเอง)
- `DashcamSession` + `loadSessions` (จัดกลุ่มเซ็กเมนต์ต่อเนื่อง gap ≤ 8 วิ)
- ปุ่ม "Merge a full recording" → เลือก session → ใช้ pipeline export เดิม

### B. เลือกช่วงแบบไทม์ไลน์
- custom view `DashcamTimelineView` (ลากที่จับ 2 ตัว) + dialog → แทน date/time picker เดิม
- ต่อ `loadClipSelectionBetween` + merge (รองรับ trim) เดิม

### Export progress popup + background + notification
- ย้าย merge/export ไป **`DashcamExportService`** (foreground dataSync)
- `DashcamExportProgress` (StateFlow) ให้ popup เกาะดู
- progress + cancel ใน `DashcamClipMerger`; progress ใน `DashcamClipExporter`
- popup: เปอร์เซ็นต์ + แถบ + เวลาโดยประมาณ + ปุ่ม **ยกเลิก** / **ทำงานเบื้องหลัง**
- เบื้องหลัง → แจ้งเตือน status bar (ปุ่มยกเลิก), เสร็จ → "บันทึกแล้ว"/"แตะเพื่อแชร์"

### segment = 1 นาที (จากรอบก่อน) — เพื่อ crash-safe + ช่วงเวลาที่แม่นยำขึ้น

## ขั้นตอนถัดไป (เราอยู่ตรงนี้)
1. **ผู้ใช้ทดสอบจริงบนเครื่อง**: 720p ลื่นขึ้นไหม / กันสั่น / timeline ลากเลือก / merge session / export popup (%/เวลา/ยกเลิก/ทำงานเบื้องหลัง/แจ้งเตือน)
2. เมื่อโอเค → **bump versionCode 26→27, versionName 1.1.1→1.2.0**
3. build + assembleRelease + lint + test → ติดตั้งยืนยัน
4. push main + สร้าง Release **v1.2.0** (APK `PHANToM_GO-v1.2.0.apk` + SHA-256) ตั้งเป็น Latest
5. ทำให้ **ซอร์ส = tag = APK = commit ตรงกัน** + อัปเดตลิงก์ดาวน์โหลด README/landing

## ความเสี่ยงที่ยังต้องเฝ้า
- 720p อย่างเดียวอาจไม่พอบนเครื่องอ่อนมาก (อาจต้องลด fps) — ยืนยันตอนทดสอบ
- EIS บางเครื่องจำกัด fps/ความละเอียด
- ปิดสิทธิ์แจ้งเตือน → "ทำงานเบื้องหลัง" ไม่มี notification (popup ยังใช้ได้)
- session ยาวมาก = ไฟล์ merge ใหญ่/นาน
- แชร์จากแจ้งเตือนผ่าน FileProvider+chooser บาง ROM อาจ finicky (เส้นทางหลักคือ share sheet ตอน popup เปิด)
- migration: คลิปเก่าก่อน v1.1.1 (public storage) ไม่โผล่ในเบราว์เซอร์รุ่นใหม่
