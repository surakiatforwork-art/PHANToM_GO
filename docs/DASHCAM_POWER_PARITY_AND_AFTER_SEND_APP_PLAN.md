# Dashcam Power Parity + Crash-Safe Recording + Open-App-After-Send Plan

Date: 2026-06-26
Target release: **v1.1.0 (stable, versionCode 25)** — ไม่ใช่ beta

## เป้าหมาย

1. ทำให้การตรวจจับเสียบ/ถอดสายชาร์จของ **dashcam auto-record** ทำงานใกล้เคียงกับ **display power automation** มากที่สุด เพื่อความมั่นใจว่าทำงาน ~100% เหมือนกัน
2. boot มาแล้วถ้ากำลังชาร์จอยู่ → เริ่มบันทึกทันที (เป็นพฤติกรรมที่ผู้ใช้ต้องการ)
3. กันไฟล์วิดีโอเสียหายเมื่อเครื่องดับกะทันหันระหว่างบันทึก (ไม่ต้องกังวล)
4. ฟีเจอร์ "เปิดแอปหลังส่ง": เพิ่มสวิตช์เปิด-ปิดชัดเจน และแสดงไอคอนแอป+ชื่อ ในหน้าตัวเลือก

---

## ส่วนที่ 1 — Power detection parity (dashcam ≈ display)

### สถานะเดิม (ก่อนแก้)
แกนหลักเหมือน display แล้ว: manifest receiver + foreground service ฟัง `ACTION_BATTERY_CHANGED` (sticky) + edge detection + จำสถานะข้าม restart
ขาด 2 อย่างที่ display มี:
- **Watchdog poll เป็นระยะ** (display poll ทุก 2 วิ เป็น safety net)
- **Debounce ตามเวลา** (display มี debounce 1 วิ; dashcam กันซ้ำด้วย edge-equality เท่านั้น)
และไม่มี **BOOT_COMPLETED** receiver → boot ขณะเสียบชาร์จอยู่จะไม่เริ่มบันทึกจนกว่าจะมี event อื่น

### งานที่ทำ (อยู่ใน `:dashcam-feature` ล้วน)
- `AutoRecordPowerManager`
  - เพิ่ม **time-debounce** (~1.2 วิ) ใน `handlePluggedState` กัน edge ซ้ำที่มาจากหลาย source พร้อมกัน (bypass เมื่อ `forceStartWhenConnected`)
  - เพิ่ม `observeCurrentPowerState(context, source)` สำหรับ watchdog (อ่านสถานะปัจจุบัน แล้วจัดการเฉพาะเมื่อมี edge จริง)
- `PowerMonitoringService`
  - เพิ่ม **watchdog poll** ด้วย Handler ทุก 2 วิ ขณะ service ทำงาน → เรียก `observeCurrentPowerState` (กู้ edge ที่ broadcast หาย)
- `BootCompletedReceiver` (ใหม่) + ประกาศใน manifest ของ library (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`)
  - ถ้า auto-record เปิดอยู่ → `syncMonitoring` + `maybeStartIfChargingAndReady("boot")`
  - ใช้ flow เดิม (`AutoRecordLaunchActivity`/overlay หรือ notification) เพื่อข้อจำกัด background camera FGS ของ Android 12+

### ข้อ 2 (boot ขณะชาร์จ → เริ่มทันที)
ได้จาก `BootCompletedReceiver` + `maybeStartIfChargingAndReady` ซึ่ง force start เมื่อเสียบอยู่และยังไม่บันทึก

---

## ส่วนที่ 2 — กันไฟล์วิดีโอเสียเมื่อไฟดับกะทันหัน (สำคัญ)

### ปัญหา
CameraX บันทึกเป็นไฟล์ MP4 ต่อเนื่องไฟล์เดียวต่อ session ถ้าไฟดับกะทันหัน ไฟล์จะไม่ถูก finalize (ไม่มี moov atom) → **เปิดไม่ได้**

### กรณีปกติในรถ = ปลอดภัยอยู่แล้ว
ดับเครื่องยนต์ = ถอดไฟ = `ACTION_POWER_DISCONNECTED` → flow เดิมสั่ง stop → finalize ไฟล์เรียบร้อย
เสี่ยงเฉพาะ "ไฟหายทันทีจริง ๆ" จนสั่ง stop ไม่ทัน

### วิธีแก้: บันทึกแบบแบ่งเซ็กเมนต์ (loop ตามมาตรฐาน dashcam)
- ตั้ง `MediaStoreOutputOptions.setDurationLimitMillis(SEGMENT_DURATION_MS)` (เริ่มที่ **2 นาที/เซ็กเมนต์**)
- เมื่อเซ็กเมนต์ครบเวลา → CameraX finalize ไฟล์นั้นสมบูรณ์ (เล่นได้) แล้วเริ่มเซ็กเมนต์ใหม่ทันที
- ไฟดับกะทันหัน → เสียแค่ "เซ็กเมนต์ที่กำลังอัดอยู่" (≤2 นาที) เซ็กเมนต์ก่อนหน้าทั้งหมดปลอดภัย
- finalize codes ที่ถือว่า "ไฟล์สมบูรณ์": `ERROR_NONE`, `ERROR_DURATION_LIMIT_REACHED`, `ERROR_FILE_SIZE_LIMIT_REACHED` → ถ้าเป็นการแบ่งและยังไม่สั่งหยุด ให้เริ่มเซ็กเมนต์ถัดไป; code อื่น = error จริง → stop
- รองรับทั้งกล้องเดี่ยวและกล้องคู่ (แต่ละกล้องแบ่งเซ็กเมนต์ของตัวเอง)

### ข้อจำกัดที่อธิบายผู้ใช้
เซ็กเมนต์สุดท้ายที่กำลังอัดตอนไฟหายทันที ยังเสียได้ (สูงสุด ~2 นาที) — เป็นข้อจำกัดของฮาร์ดแวร์/ระบบไฟล์ การแบ่งเซ็กเมนต์คือมาตรฐานที่จำกัดความเสียหายให้น้อยที่สุด

---

## ส่วนที่ 3 — "เปิดแอปหลังส่ง": สวิตช์ + ไอคอนแอป

- `PrefsManager`: เพิ่ม boolean `afterSendOpenEnabled`; `isOpenAppAfterSendEnabled()` = enabled && มี package
- Settings: เพิ่ม **MaterialSwitch** เปิด-ปิด + แถวแอปที่เลือก (ชื่อ + ปุ่ม "เลือกแอป" เปิดใช้เมื่อสวิตช์เปิด)
  - เปิดสวิตช์แล้วยังไม่เลือกแอป → เปิดหน้าตัวเลือกให้เลย; ถ้ายกเลิกโดยไม่เลือก → ปิดสวิตช์กลับ
- หน้าตัวเลือกแอป: ใช้ adapter แสดง **ไอคอน + ชื่อแอป** (layout `item_app_choice.xml`) เรียงตามชื่อ
- RelayActivity: logic เดิม (เปิดแอปที่เลือก, แอปแผนที่เปิดพร้อมจุดหมาย, ไม่งั้นเปิดแอปเปล่า)

---

## ส่วนที่ 4 — Build / Release

1. bump `versionCode 24→25`, `versionName → 1.1.0`
2. `assembleDebug assembleRelease lintDebug testDebugUnitTest`
3. ติดตั้ง debug บนอุปกรณ์จริง ตรวจไม่ crash
4. อัปเดตลิงก์ดาวน์โหลด stable ใน README → v1.1.0
5. push ขึ้น main (stage เฉพาะไฟล์งาน)
6. สร้าง **Stable Release** `v1.1.0` (ไม่ใช่ pre-release), APK `PHANToM_GO-v1.1.0.apk`, แนบ SHA-256, ตั้งเป็น Latest

## สิ่งที่ต้อง QA จริง
- เสียบชาร์จขณะแอปปิด/หลัง reboot → เริ่มอัดอัตโนมัติ
- อัดต่อเนื่องแล้วดึงไฟปุบปับ → คลิปเซ็กเมนต์ก่อนหน้ายังเล่นได้
- เปิดแอปหลังส่ง: สวิตช์ on/off ทำงาน, ตัวเลือกมีไอคอน, เปิดแอปถูกตัว
