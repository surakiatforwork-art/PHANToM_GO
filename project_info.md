# PHANToM GO — project_info (สำหรับ Agent)

เอกสารนี้สรุป **ทุกอย่างที่เกี่ยวกับ Android native APK** ของโปรเจกต์ PHANToM GO: มีฟีเจอร์/ฟังก์ชันอะไร, โค้ดอยู่ไฟล์ไหน, ทำงานยังไง, ผู้ใช้ต้องการให้เป็นแบบไหน และข้อควรระวังที่ agent ต้องรู้ก่อนแก้

> ขอบเขต: เอกสารนี้โฟกัส **native APK เท่านั้น** ส่วน Web App (`docs/web/`) และ iOS (`ios/`) อยู่นอกขอบเขต — โดยเฉพาะ **ห้ามแตะ `ios/`** เว้นแต่ผู้ใช้สั่งทำ iOS โดยตรง
> โฟลเดอร์ `PHANToM GO pararell/`, `Automagics/`, `PHANToM_GO_VideoKit/`, `stitch_phantom_go_ui_redesign/` เป็นไฟล์/สำเนาแยกที่ผู้ใช้ถืออยู่ — **อย่าแก้/อย่า stage** เว้นแต่ถูกสั่ง

---

## 1. ภาพรวมโปรเจกต์

PHANToM GO คือระบบ "ส่งปลายทาง/เส้นทางจากมือถือหลัก ไปแสดงบนมือถือเครื่องที่ 2 ที่ใช้เป็นจอติดรถ" เพื่อไม่ต้องเอามือถือหลักไปตากแดด/ทำงานหนัก เหมาะกับคนขับรถทั่วไปและไรเดอร์ส่งอาหาร

APK ตัวเดียว (`:app`) เป็นได้ทั้ง **Main Device** (เครื่องหลักที่รับลิงก์แล้วส่ง) และ **Display Device** (จอติดรถที่แสดง Maps) เลือกบทบาทในแอป จับคู่กันด้วย **QR + Firebase**

ภายหลังได้ผนวกฟีเจอร์ **Dashcam (GO Cam)** เข้ามาในแอปหลักด้วย (อยู่ในแท็บ "ความปลอดภัย")

---

## 2. โครงสร้างโมดูล & Build

`settings.gradle.kts` มี 3 โมดูล:

| โมดูล | applicationId / namespace | ชนิด | หน้าที่ |
|---|---|---|---|
| `:app` | `com.phantom.carnavrelay` | application (APK หลัก) | Main+Display + UI ทั้งหมด, depend `:dashcam-feature` |
| `:dashcam-feature` | `com.phantom.dashcam` | **android library** | ตรรกะ+UI ของ dashcam ทั้งหมด, มี OpenCV |
| `:gocam` (dir = `GO cam/`) | `com.phantom.gocam` | application (APK แยก) | **thin shell** เปิด dashcam แบบ standalone (launcher อย่างเดียว) versionName 0.1.0 |

**สำคัญ:** dashcam ถูก integrate เข้า `:app` (เข้าผ่านแท็บความปลอดภัย) **และ** มี `:gocam` เป็น APK แยกอีกตัว — ทั้งคู่ใช้ `:dashcam-feature` ร่วมกัน แก้ที่ library = กระทบทั้งสอง

### ค่าคอนฟิกหลัก (อ่านจาก gradle จริง)
- AGP **8.6.1**, Kotlin **2.0.20**, google-services **4.4.4**, JVM **17**
- `:app` → compileSdk **35**, minSdk **23**, targetSdk **34**, versionCode **29**, versionName **1.2.2**
- **abiFilters = `arm64-v8a` เท่านั้น** (ตัด 32-bit/x86 ทิ้ง — ดูเหตุผลข้อ 8: OpenCV 16KB)
- Firebase BOM **34.11.0** (auth, database, messaging)
- ไลบรารีเด่น: NanoHTTPD 2.3.1 (HTTP server), OkHttp 4.12.0, ZXing (QR), WorkManager 2.9.0
- `:dashcam-feature` → compileSdk 35, minSdk 23, CameraX 1.5.1, **OpenCV `org.opencv:opencv:4.13.0`** (กันสั่น)
- `allowBackup="false"` (ไม่ backup token/ข้อมูลความปลอดภัยออกนอกเครื่อง)

### คำสั่ง build/test/install (Windows)
```powershell
.\gradlew.bat :app:assembleDebug                       # build APK หลัก
.\gradlew.bat :dashcam-feature:testDebugUnitTest       # unit test (StorageQuotaPlanner, StabilizationMath)
.\gradlew.bat assembleDebug assembleRelease lintDebug testDebugUnitTest --stacktrace   # checklist เต็ม
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk   # ติดตั้งคงข้อมูลเดิม
adb logcat -d -v time PHANTOM_GO:D '*:S'               # log หลัก (tag PHANTOM_GO), dashcam ใช้ tag GO_CAM_*
```

---

## 3. ฟีเจอร์ฝั่ง Main / Display (โมดูล `:app`)

### 3.1 การจับคู่ (Pairing) — QR + Firebase
- Display เปิด Display mode → สร้าง QR + publish pairing code ขึ้น Firebase; Main สแกน QR → claim ด้วย anonymous auth → บันทึก peer
- ไฟล์: `MainPairActivity.kt`, `PairScanActivity.kt`, `PortraitCaptureActivity.kt` (ZXing), `FirebaseManager.kt`, `PrefsManager.kt`, parser: `util/PairInfoParser.kt`
- agent must-know: การจับคู่ใหม่ **แทนที่** คู่เดิม ระวังข้อมูล pairing ทั้ง Main และ Display หาย; PIN/token เก็บใน local prefs

### 3.2 ส่งเส้นทาง (Route Relay) — Cloud + Wi-Fi fallback
- รับ Share จาก Google Maps/แอปอื่น → normalize ลิงก์ → ส่ง command ผ่าน Firebase ไป Display ที่จับคู่; มี local Wi-Fi (HTTP) เป็น fallback
- ไฟล์: `ShareReceiverActivity.kt`, `RelayActivity.kt`, `SilentShareReceiverActivity.kt`, `MainSender.kt`, `SendNavigationWorker.kt` (WorkManager), `HttpServer.kt`/`DisplayServerService.kt` (NanoHTTPD), normalize: `NavNormalizer.kt`, `NavLinkUtils.kt`, `util/NavLinkNormalizer.kt`
- มี **ack-based retry + dedupe** สำหรับส่งเส้นทาง (v1.2.2) — คำสั่งล่าสุดแทนคำสั่งเก่า ลดการเปิดซ้ำ
- `MapLinkHandlerAlias` (activity-alias, default disabled) = ให้แอปรับ `geo:`, `google.navigation:`, `https://...maps...` ได้เมื่อเปิด toggle

### 3.3 Maps Auto-Share (Main) — Accessibility/Notification
- ดักการแชร์จาก Google Maps อัตโนมัติฝั่ง Main ผ่าน Accessibility + Notification listener
- ไฟล์: `MapsAutoShareAccessibilityService.kt`, `MapsAutoShareNotificationListenerService.kt`, `MapsAutoShareCoordinator.kt`, `MapsAutoSharePolicy.kt`, `MapsAutoShareStatus.kt`, `MapsUiMatcher.kt`, `MapsUiVocabulary.kt`
- agent must-know: เป็น exported service ผูก permission ระบบ (BIND_ACCESSIBILITY_SERVICE / BIND_NOTIFICATION_LISTENER_SERVICE) — ผู้ใช้ต้องเปิดเองในตั้งค่าระบบ

### 3.4 Display mode + Speedometer + Find Display
- Display แสดง Maps ตามโหมดรถยนต์/มอเตอร์ไซค์, มีมาตรวัดความเร็วจาก GPS, และ Find Display (ตำแหน่งล่าสุด)
- ไฟล์: `DisplayActivity.kt`, `DisplayServerService.kt` (foreground service: cloud command listener + location), `DisplayScreenFragment.kt`, `DisplayModeFlow.kt`, `DisplaySpeedMonitor.kt` (GPS 1Hz), `OverlayController.kt` (ปุ่มลอย)
- `DisplaySpeedMonitor` ใช้ `requestLocationUpdates` 1000ms บน main looper, callback เบา
- **จอ active ตลอด:** `DisplayServerService` ถือ `SCREEN_BRIGHT_WAKE_LOCK` (tag `PHANTOM_GO:DisplayScreenOn`) เพื่อไม่ให้จอดับแม้อยู่นอกแอป (เช่นใน Google Maps) — **ผูกกับ "โหมดจอเปิด + เสียบไฟ"** (acquire เมื่อ charging, release เมื่อถอดไฟ) เพื่อไม่ชน lock-on-power-disconnect และไม่กินแบตตอนถอดปลั๊ก
- **ปุ่ม toggle โหมดจอ** (`switchDisplayMode` ใน `DisplayScreenFragment`): ใช้ optimistic state (`desiredDisplayMode`) สะท้อนเจตนาผู้ใช้ทันทีแล้ว reconcile กับสถานะ service จริงทีหลัง — เพราะ `getRunningServices` รายงาน foreground service ที่เพิ่งเริ่มไม่ทัน ทำให้ปุ่มเคยเด้งกลับต้องกดซ้ำ

### 3.5 Anti-Theft (กันขโมย) — trigger สาย 3.5 มม.
- แสดงเมื่ออุปกรณ์รองรับ + ตรวจพบ trigger สายแจ็ค; ผู้ใช้ตั้ง PIN + เปิดเอง; ถอดสาย → หน้า Security Verification → ผิด/หมดเวลา → ส่ง alert+ตำแหน่งไป Main, เล่น alarm, เร่งเสียง, ยกระดับ full-screen lock
- ไฟล์: `DisplayAntiTheftManager.kt`, `AntiTheftPinActivity.kt`, `PhantomDisplayDeviceAdminReceiver.kt`
- **ลำดับความสำคัญสูงสุด:** ขณะ anti-theft ทำงาน ระบบ **พัก** การเปิด Maps และการล็อกจาก power-disconnect (อย่าให้หน้าใส่ PIN หาย) — ดู `isAntiTheftBlocking()` ใน `DisplayPowerAutomationManager`

### 3.6 Power Automation (Display) — เสียบ/ถอดไฟ
- ทำงานเฉพาะบทบาท Display: เสียบไฟ → ปลุกจอ (best effort ผ่าน `DisplayWakeActivity`); ถอดไฟ → ล็อกเครื่อง (Device Admin `lockNow`) ถ้าเปิดตัวเลือก
- ไฟล์: `DisplayPowerAutomationManager.kt`, `DisplayPowerEventReceiver.kt` (manifest receiver → ทำงานแม้ OEM พัก service), `DisplayPowerStatePolicy.kt`, `DisplayWakeActivity.kt`, `DisplayServiceRestoreReceiver.kt` (boot/replace → restart Display service)
- ดัก edge จาก `ACTION_POWER_CONNECTED/DISCONNECTED` **และ** sticky `ACTION_BATTERY_CHANGED` (กัน OEM ส่ง event ไม่ครบ) + watchdog 2 วิ

### 3.7 อื่น ๆ ฝั่ง app
- `MainActivity.kt` = Activity หลัก (Home/Display/Settings + bottom nav), fragments: `HomeFragment.kt`, `DisplayScreenFragment.kt`, `SettingsScreenFragment.kt`, `SecurityFragment.kt`
- `PermissionSetupActivity.kt`, `SettingsActivity.kt`, `DiagnosticsActivity.kt`
- `FirebaseManager.kt` (auth/db), `PrefsManager.kt` (local prefs ทั้งหมดฝั่ง app), `PhantomLog.kt` (tag `PHANTOM_GO`), `CrashReporter.kt`, `PhantomGoApplication.kt`
- Bluetooth (`bt/DisplayServerService.kt`, `bt/MainClientService.kt`, `BtConst.kt`): **เก็บไว้สำหรับ 2-device ในอนาคต ห้ามลบ** (ตามที่ผู้ใช้สั่ง)

---

## 4. ฟีเจอร์ Dashcam / GO Cam (โมดูล `:dashcam-feature`)

เข้าได้จาก **แท็บความปลอดภัย → กล้องติดรถ (Dashcam)** ในแอปหลัก (`SecurityFragment.kt` มีปุ่ม `dashcamStartStopButton`, `dashcamSecurityViewClipsButton`) หรือเปิด APK `:gocam` แยก

### 4.1 การอัด (Recording) — แกนหลัก
- ไฟล์: `DashcamRecordingService.kt` (LifecycleService, CameraX `Recorder`+`VideoCapture`)
- อัดเป็น **segment ละ 60 วิ** ต่อเนื่อง (crash-safe: ไฟปลั๊กหลุดเสีย worst-case แค่ segment ปัจจุบัน, ที่เหลือปิดสมบูรณ์แล้ว)
- โหมด: REAR (default) / FRONT / DUAL (กล้องคู่ — เปิด encoder 2 ชุด, cap ที่ HD, ต้องผ่าน capability check)
- คุณภาพ default **720p (HD)**; มี overlay timestamp burn-in (cache ต่อวินาที); preview **ปิด default**
- enums/state: `DashcamMode.kt`, `DashcamQuality.kt`, `DashcamCapabilities.kt`, `DashcamStateStore.kt` (StateFlow), `DashcamUiState.kt`, `SurfaceRotationStore.kt`

### 4.2 Storage / Loop recording
- ไฟล์: `DashcamClipStorageManager.kt` (+ `StorageQuotaPlanner.kt`)
- เก็บที่ app external files: `…/files/Movies/PHANToM GO Cam/` (มี `.nomedia`); ไฟล์ชื่อ `GOCam_<label>_<yyyyMMdd_HHmmss>.mp4`; ไฟล์กำลังเขียนเป็น `.pending-…`
- quota: default 32GB, min 8GB, max 256GB (`DashcamPreferences`); เกิน quota ลบไฟล์เก่าสุด (เลือกด้วย size)
- **agent must-know:** `buildStoredClip` คำนวณ `durationMs` จาก **ชื่อไฟล์ + lastModified** (ไม่เปิด `MediaMetadataRetriever` ในเส้นทางร้อน เพื่อลด lag) → duration เป็นค่าประมาณ ~60s/segment ซึ่งเพียงพอ; export ตัดด้วย sample timestamp จริง

### 4.3 Export / Merge
- ไฟล์: `DashcamExportService.kt` (foreground service + progress/cancel), `DashcamClipMerger.kt` (รวมไฟล์แบบ **remux ไม่ re-encode**), `DashcamClipExporter.kt` (save to gallery / MediaStore), `DashcamExport.kt` (destination + progress state), `DashcamTimelineView.kt` (เลือกช่วงเวลา), `DashcamClipsActivity.kt` (หน้า Recorded clips)
- UI: Export recent (3/5/10 นาที), Export by time range (timeline), Merge a full recording (session)

### 4.4 กันสั่นวิดีโอ (Software Stabilization) — OpenCV, post-process
- ไฟล์: `VideoStabilizer.kt` (เครื่องยนต์), `StabilizationMath.kt` (ตรรกะ smoothing — pure, unit-test ใน `StabilizationMathTest.kt`), `StabilizationCropStrength.kt` (LOW/MEDIUM/HIGH), `DashcamStabilizeService.kt` + `DashcamStabilizeProgress.kt` (mirror ของ export)
- UI: **long-press คลิป** ในหน้า Recorded clips → Play / Stabilize and save / Delete → เลือกระดับครอป (default MEDIUM ~10%) → save to gallery
- การทำงาน: **offline post-process เท่านั้น** (ไม่ทำตอนอัดสด), 2-pass — decode (MediaCodec `getOutputImage`) → optical flow (OpenCV) → smooth trajectory → warpAffine (CPU) + zoom → re-encode H.264 (`getInputImage` YUV420Flexible) → mux + ก๊อปปี้เสียงเดิม
- ข้อจำกัดที่ต้องบอกผู้ใช้: กันสั่น**ไม่เพิ่มรายละเอียดทะเบียนที่เบลอ** (ช่วยแค่ความนิ่ง), ช้ากว่า realtime, ครอป FOV; แนะนำอัด 1080p ถ้าเน้นอ่านทะเบียน
- เฟสถัดไป (ยังไม่ทำ): gyro-assisted + GPU warp

### 4.5 Auto-record on power + watchdog
- ไฟล์: `AutoRecordPowerManager.kt`, `PowerConnectionReceiver.kt`, `PowerMonitoringService.kt`, `PowerStateUtils.kt`, `AutoRecordLaunchActivity.kt` (ปลุกแอปจาก background เพื่ออัดเมื่อเสียบชาร์จ), `BootCompletedReceiver.kt`
- Android 12+ อาจบล็อกเปิดกล้องจาก background → แสดง notification ให้ผู้ใช้กดเริ่มเอง
- `DashcamNotificationHelper.kt`: notification IDs **7001–7007** (recording 7001, power 7002, monitor 7003, export 7004/7005, stabilize 7006/7007), channel `gocam_recording`/`gocam_alerts`/`gocam_export`

### 4.6 GO Cam shell
- `dashcam-feature/.../MainActivity.kt` = หน้า GO Cam standalone (เปิดผ่าน APK `:gocam`); ส่วน UI/setting dashcam ในแอปหลักอยู่ใน `SecurityFragment`

---

## 5. Permissions ที่ใช้ (จาก manifest)
- `:app`: INTERNET, ACCESS_NETWORK/WIFI_STATE, FINE/COARSE/BACKGROUND_LOCATION, CAMERA (QR), BLUETOOTH*, FOREGROUND_SERVICE(+DATA_SYNC/LOCATION), POST_NOTIFICATIONS, WAKE_LOCK, DISABLE_KEYGUARD, VIBRATE, MODIFY_AUDIO_SETTINGS, USE_FULL_SCREEN_INTENT, WRITE_SETTINGS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, RECEIVE_BOOT_COMPLETED, SYSTEM_ALERT_WINDOW; `queries` มี Google Maps + launcher apps
- `:dashcam-feature`: CAMERA, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE(+CAMERA/DATA_SYNC/MICROPHONE), WAKE_LOCK, RECEIVE_BOOT_COMPLETED
- สิทธิ์เสริมข้ามได้และตั้งภายหลัง ยกเว้นฟีเจอร์ที่พึ่งสิทธิ์นั้นตรง ๆ

---

## 6. ข้อมูล/Firebase
- Firebase **anonymous auth** (ไม่ต้องสมัครบัญชี); เก็บ device record, presence, pairing code/relation, route commands, security alerts
- local prefs เก็บ pairing peer/token/settings/pending routes/last command; **PIN กันขโมยเก็บเป็น hash+salt** ไม่เก็บ plain
- `docs/firebase-realtime-database.rules.json`: การ push git **ไม่** publish rules ให้อัตโนมัติ — ถ้าแก้ rules ต้องแจ้งผู้ใช้ publish เอง; ห้ามเปิด `.read/.write=true`; ห้ามใส่ secret ลง git

---

## 7. กฎการทำงานสำหรับ Agent (จาก `docs/AGENT_WORKFLOW_GUIDE.md`)
- ตอบโต้กับผู้ใช้ **เป็นภาษาไทยเสมอ**
- ขั้นตอน: อ่านไฟล์ที่เกี่ยวข้องครบ → แก้เฉพาะที่จำเป็น (ไม่ rewrite ของเดิมที่ทำงานอยู่) → ไล่ error/ความเสี่ยง → สรุป "แก้อะไร + ความเสี่ยงใหม่เทียบรอบก่อน"
- **ห้าม** `git add -A`/`git add .` — stage เฉพาะไฟล์งานด้วย path ชัดเจน; ปล่อยไฟล์ที่ผู้ใช้แก้เองและไม่เกี่ยว
- **ห้ามแตะ `ios/`**
- การแก้ Android ต้องรักษา flow เดิม: **pairing, Firebase relay, Display mode, anti-theft, permission, power automation, speedometer, route relay** — และ release flow ล่าสุด
- ความหมายคำสั่ง: "ตรวจสอบ/เช็ค" = วิเคราะห์ ยังไม่แก้ • "แก้ไข" = แก้+build+test+สรุป • "Push" = ขึ้น git (ไม่สร้าง release) • "Push และสร้าง Release" = +tag+GitHub Release+APK (bump versionCode เสมอ, ตั้งชื่อ `PHANToM_GO-vX.Y.Z.apk`, ใส่ SHA-256)
- ก่อนส่งงานต้อง build + ทดสอบส่วนที่แก้จริง; **ห้ามอ้างว่าทดสอบผ่านถ้ายังไม่รัน**; ถ้ามี ADB เชื่อมอยู่ ให้ติดตั้ง+ดู log บนเครื่องจริง
- Versioning: PATCH=บั๊ก, MINOR=ฟีเจอร์ใหม่, MAJOR=เปลี่ยน architecture; `versionCode` เพิ่มทุก APK

---

## 8. Gotchas / ข้อควรระวังที่เพิ่งเจอ (สำคัญมากสำหรับ dashcam)

1. **Recording executor ต้องเป็น process-singleton:** callback ของ CameraX `VideoRecordEvent` รันบน `recordingCallbackExecutor` (single-thread, อยู่ใน **companion object**, **ห้าม shutdown**). ถ้า shutdown ใน `onDestroy` → event `Finalize` ตัวสุดท้าย (ที่ commit/rename `.pending-` → ชื่อจริง) ถูก reject → คลิปสุดท้าย**ค้างเป็น `.pending-` ไม่โผล่ในลิสต์** (เคยเป็นบั๊กมาแล้ว) ดูคอมเมนต์ใน `DashcamRecordingService.onDestroy`
2. **Trim ทุก 5 segment** (`TRIM_EVERY_N_SEGMENTS`) ไม่ใช่ทุกนาที; cleanup/trim/commit รันบน executor พื้นหลัง (ลด jank)
3. **OpenCV ต้องเป็น 4.13.0:** 4.11.0 และ **5.0.0** มี `libc++_shared.so` ELF p_align=0x1000 → Android 15 เด้ง warning 16KB page size. **4.13.0** aligned (0x4000) ทั้ง libopencv + libc++_shared → ใช้ตัวนี้เท่านั้น. คู่กับ `abiFilters=arm64-v8a` (ตัด 32-bit ที่ไม่ aligned ทิ้ง). เครื่องมือตรวจ ELF p_align อยู่ใน scratchpad (parse program headers ด้วย python)
4. **`.pending-` ที่สมบูรณ์กู้ได้** ด้วยการ rename เป็นชื่อจริง (CameraX เขียนไฟล์เสร็จแล้ว แค่ไม่ได้ rename); แต่ `cleanupIncompleteClips` จะลบ `.pending-` ทิ้งเมื่อเริ่มอัดรอบใหม่
5. แก้ `:dashcam-feature` กระทบทั้ง `:app` และ `:gocam`
6. ค่า default ที่เหมาะกับเครื่องระดับกลาง (เช่น Helio G99): 720p / single rear / preview off / quota 16GB

---

## 9. สิ่งที่ผู้ใช้ต้องการ (สรุป)
- ตอบเป็น **ภาษาไทย** เสมอ, ทำตาม workflow guide เป๊ะ
- รักษาฟีเจอร์เดิมทั้งหมดไม่ให้พัง (ข้อ 7)
- Dashcam ต้องลื่นบนเครื่องระดับกลาง (ลด lag เป็นเรื่องสำคัญ); กันสั่นเป็น offline ไม่ทำตอนอัดสด
- คุม APK ไม่ให้บวมเกินจำเป็น (arm64-only) และไม่มี warning ระบบ
- ห้ามแตะ Bluetooth code (เก็บไว้ทำ 2-device), ห้ามแตะ `ios/`, ระวังข้อมูล pairing/PIN/Device Admin หาย (อย่า uninstall พลการ ใช้ `install -r`)

---

## 10. ดัชนีไฟล์เอกสารอื่น (อ้างอิงเพิ่ม)
- `docs/AGENT_WORKFLOW_GUIDE.md` — กฎทำงาน (ต้องอ่าน)
- `docs/PHANTOM_GO_APP_OVERVIEW.md` — overview เดิม (v1.0.17, เก่ากว่าเอกสารนี้)
- `docs/display-anti-theft-spec.md`, `docs/POWER_AUTOMATION_SECURITY_DISPLAY_IMPLEMENTATION_PLAN.md` — สเปก anti-theft/power
- `docs/GO_CAM_MAIN_APP_INTEGRATION_PLAN.md`, `docs/DASHCAM_*` — แผน/สถานะ dashcam
- `docs/RELEASE_v1.2.2.md` (+ rุ่นก่อน) — release notes ล่าสุด
- `docs/firebase-realtime-database.rules.json` — Firebase rules (publish เองเมื่อแก้)
</content>
