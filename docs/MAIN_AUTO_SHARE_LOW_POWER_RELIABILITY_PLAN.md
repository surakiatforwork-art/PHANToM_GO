# PHANToM GO - Main Auto Share Low-Power Reliability Plan

วันที่บันทึก: 2026-06-14
สถานะ: ทำเสร็จและผ่านการทดสอบอุปกรณ์จริง (2026-06-14) — แก้ trigger cooldown, route readiness, Share retry และ handoff acknowledgement แล้ว

## สรุปสิ่งที่ทำจริง (2026-06-14)

- เพิ่ม `MapsAutoSharePolicy.kt` (pure, unit-testable): per-phase timeout + adaptive scan interval + `AutoSharePhase`
- `MapsUiMatcher.kt`: เปลี่ยนเป็น single-pass `scanMapsUi()` → `MapsUiSnapshot` (warningExit/continue/share/stop/preview + visitedNodeCount + scanDurationMs) และ `findPhantomGoTarget()` fast path ผ่าน `findAccessibilityNodeInfosByText` + fallback BFS
- `MapsAutoShareCoordinator.kt`: rewrite เป็น state machine ใหม่ (FIND_ROUTE_ACTION → VERIFY_SHARE_SHEET → FIND_SHARE_TARGET → VERIFY_HANDOFF + fallback Preview/Stop), per-phase timeout (12s/10s/10s + safety 30s), adaptive scan (event ใหม่ยกเลิก poll แล้ว scan ทันที), ค้นหา PHANToM GO ทุก interactive window ใน allowlist, retry Share จำกัด, verify handoff, diagnostics (ไม่เก็บ route/PII)
- `MapsUiVocabulary.kt`: เพิ่ม `PHANTOM_GO_SEARCH`, `isResolverPackage()`
- `maps_auto_share_accessibility_service.xml`: เพิ่ม `typeWindowsChanged` + `flagRetrieveInteractiveWindows`
- Unit tests: `MapsAutoSharePolicyTest` (timeout/adaptive interval) + เพิ่มเคส `isResolverPackage` ใน `MapsUiVocabularyTest`

## Reliability follow-up จากการทดสอบอุปกรณ์จริง (2026-06-14)

- พบว่า notification ที่เข้าระหว่าง success cooldown ถูกทำเครื่องหมายว่าเคยจัดการแล้ว ทำให้เส้นทางใหม่ไม่เริ่ม session
- พบว่า Share อาจถูกกดก่อน Google Maps คำนวณเส้นทางเสร็จ ส่งผลให้ payload ปลายทางคลาดเคลื่อน
- พบว่า resolver หายไปไม่ได้ยืนยันเสมอว่า PHANToM GO รับ payload แล้ว
- พบว่า Share retry เดิมอาจกดซ้ำเร็วเกินไปบน Share Sheet ที่เปิดช้า

แนวแก้ที่นำมาใช้:

- เก็บ notification trigger ที่เข้าระหว่าง cooldown แล้วเริ่มทำงานเมื่อ cooldown จบ แทนการทิ้ง trigger
- เพิ่ม route readiness gate แบบตรวจความนิ่งของ route controls และ Share โดยไม่ใช้ fixed sleep ยาว
- แยก timeout ของ post-share แต่ละ phase และยังคง safety timeout รวม 30 วินาที
- เว้น Share retry อย่างน้อย 1.5 วินาที และจำกัดจำนวน retry
- ให้ `ShareReceiverActivity` ส่ง acknowledgement เมื่อรับ payload จริง รวมถึงรองรับ `onNewIntent`
- VERIFY_HANDOFF จะสำเร็จเมื่อรับ acknowledgement เท่านั้น หากไม่รับจะ fail อย่างปลอดภัย
- ห้ามใช้ timeout อย่างเดียวเป็นสัญญาณว่า route พร้อม: ต้องเห็น route controls ที่นิ่งก่อน จึงป้องกันการแชร์ route preview เก่าหรือปลายทางที่ Maps ยังคำนวณไม่เสร็จ

## ผลทดสอบอุปกรณ์จริง (2026-06-14)

- อุปกรณ์: iQOO V2408A, Android 15
- เส้นทางปกติ: ผ่าน ระบบรอ route พร้อม กด Share เลือก PHANToM GO และได้รับ payload acknowledgement จริง
- ความถูกต้องปลายทาง: ส่งพิกัด `13.7563,100.5018` ตรงตาม intent ที่เริ่มนำทาง
- เส้นทางใหม่ในช่วง success cooldown: ผ่าน trigger ถูก queue และเริ่ม session ใหม่อัตโนมัติเมื่อ cooldown จบ
- ความถูกต้องเส้นทางที่สอง: ส่งพิกัดใหม่ `13.81,100.56` ไม่ส่ง route เก่าซ้ำ
- Share Sheet: พบ PHANToM GO ภายใน 54-111 ms หลัง Share Sheet พร้อม
- Share retry: 0 ครั้งในการทดสอบทั้งสอง flow
- ไม่พบ crash หรือ ANR ของ PHANToM GO ระหว่างทดสอบ

## เดิม (แผนก่อนลงมือ)

## เป้าหมาย

ปรับฟีเจอร์แชร์อัตโนมัติฝั่ง Main ที่ใช้ Scoped Accessibility Service ให้ทำงานน่าเชื่อถือขึ้นบนอุปกรณ์สเปกต่ำ หรืออุปกรณ์ที่ลดความเร็ว CPU เพื่อประหยัดพลังงาน โดยยังคงกดปุ่มทันทีเมื่อ UI พร้อม และจบ flow ให้เร็วที่สุดเท่าที่อุปกรณ์ทำได้

## ปัญหาที่พบ

- การค้นหาและกดปุ่มใน Google Maps ทำงานได้บ้างไม่ได้บ้างเมื่อ CPU ทำงานช้าลง
- หลังจากค้นพบและกดปุ่ม Share แล้ว ระบบใช้เวลานานเกินไปกว่าจะเลือกแอป `PHANToM GO`
- Timeout ปัจจุบันเป็นแบบรวมทั้ง session ทำให้เวลาที่เหลือสำหรับค้นหา PHANToM GO ลดลงตามเวลาที่ใช้ในขั้นตอนก่อนหน้า
- Polling ทุก `40 ms` อาจสแกน accessibility node tree ซ้ำหลายรอบต่อหนึ่ง scan ทำให้แย่ง CPU จาก Google Maps และ Share Sheet
- การค้นหาเป้าหมายพึ่ง `rootInActiveWindow` ซึ่งอาจยังไม่ใช่หน้าต่าง Share Sheet บนอุปกรณ์หรือ ROM บางรุ่น

## สาเหตุจากโค้ดปัจจุบัน

- Session timeout รวม: `5.5 วินาที`
- Share target timeout: `2.8 วินาที`
- Polling interval: `40 ms`
- Matcher อาจเดินค้น node tree สูงสุด `700 nodes` หลายครั้งใน scan เดียว
- Accessibility config ยังไม่รับ `typeWindowsChanged`
- ถ้ามี polling scan รออยู่ event ใหม่ไม่สามารถแทนที่ด้วย immediate scan ได้ เพราะใช้ `scanQueued` guard

## แนวทางหลัก

ใช้แนวคิด:

> ตอบสนองทันทีเมื่อ UI พร้อม แต่ยอมรอได้นานขึ้นโดยไม่สร้างภาระหนักเมื่ออุปกรณ์ช้า

ห้ามเพิ่ม fixed delay หรือ `sleep` หลังการกดปุ่ม เพราะจะทำให้อุปกรณ์เร็วช้าลงโดยไม่จำเป็น

## แผนการปรับปรุง

### 1. แยก Timeout ตาม Phase

เปลี่ยนจาก session timeout สั้นแบบรวม เป็น timeout เฉพาะแต่ละขั้นตอน:

- ค้นหา Warning, Share, Stop หรือ Preview: สูงสุด `12 วินาที`
- หลัง Stop navigation รอ Share: สูงสุด `10 วินาที`
- หลังคลิก Share รอ Share Sheet และ PHANToM GO: สูงสุด `10 วินาที`
- Safety timeout รวมทั้ง session: `30 วินาที`

Timeout เป็นเพียงเพดานสูงสุด หากพบปุ่มก่อนต้องกดทันที

### 2. Adaptive Scan Scheduling

- Accessibility event ใหม่ต้องสามารถยกเลิก polling ที่กำลังรอและสั่ง scan ทันที
- หลังคลิกปุ่มให้ request scan รอบใหม่แบบ `0 ms`
- Polling สำรองใช้ช่วงเวลาปรับตามระยะเวลาที่รอ:
  - ช่วงแรก: ประมาณ `60 ms`
  - หลังรอเกิน `1.5 วินาที`: ประมาณ `120 ms`
  - หลังรอเกิน `4 วินาที`: ประมาณ `180 ms`
- หลีกเลี่ยง busy-loop และลดการแย่ง CPU จาก Google Maps/Share Sheet

### 3. สแกน Maps UI เพียงรอบเดียว

สร้างผลลัพธ์ snapshot จาก node tree หนึ่งรอบ เช่น:

```text
MapsUiSnapshot(
  warningExit,
  warningContinue,
  share,
  stop,
  preview,
  visitedNodeCount,
  scanDurationMs
)
```

จากนั้น coordinator ใช้ snapshot เดียวตัดสินใจแต่ละ action แทนการเดิน tree ใหม่หลายครั้ง

### 4. เร่งการค้นหา PHANToM GO ใน Share Sheet

- เพิ่ม accessibility event `typeWindowsChanged`
- เพิ่ม flag `flagRetrieveInteractiveWindows`
- เมื่ออยู่ Phase `FIND_SHARE_TARGET` ให้ตรวจทุก interactive window ที่ package อยู่ใน allowlist
- ใช้ fast path:
  - ค้นด้วย `findAccessibilityNodeInfosByText("PHANToM GO")`
  - ตรวจ text และ content description
  - ตรวจ clickable ancestor
- หาก fast path ไม่พบ จึง fallback ไป BFS matcher เดิม
- เมื่อ Share Sheet ส่ง event ใหม่ ให้ scan ทันทีโดยไม่รอ polling

### 5. ยืนยันผลการคลิก

- หลังคลิก Share ต้องตรวจว่าหน้าต่าง resolver/Share Sheet ปรากฏจริง
- หากคลิกสำเร็จแต่ UI ไม่เปลี่ยน ให้ลองกดซ้ำแบบจำกัดจำนวนครั้ง
- หลังคลิก PHANToM GO ต้องตรวจว่า resolver หายไปหรือ share receiver เริ่มทำงาน
- ป้องกันการกดซ้ำด้วย session token, action timestamp และ candidate fingerprint

### 6. Resolver Package Compatibility

- รักษา allowlist ปัจจุบันสำหรับ Android, Samsung และ Xiaomi
- เพิ่ม diagnostics สำหรับ package ของ Share Sheet ที่พบจริงแต่ไม่อยู่ใน allowlist
- ห้ามกด node จาก package ที่ไม่ผ่าน allowlist

### 7. Diagnostics

เก็บข้อมูลเฉพาะที่จำเป็นสำหรับวิเคราะห์ประสิทธิภาพ:

- Trigger ถึงเริ่ม session
- เริ่ม sessionถึงพบ Share
- กด Share ถึง Share Sheet เปิด
- Share Sheet เปิดถึงพบ PHANToM GO
- พบ PHANToM GO ถึงคลิกสำเร็จ
- จำนวน scan ต่อ phase
- จำนวน node ที่ตรวจต่อ scan
- ระยะเวลาของแต่ละ scan
- package และจำนวน window ที่ตรวจ
- สาเหตุ timeout หรือ fallback

ห้ามบันทึกข้อความเส้นทางหรือข้อมูลส่วนตัวจาก node tree แบบถาวร

## State Machine ที่เสนอ

```text
IDLE
  -> FIND_ROUTE_ACTION
  -> DISMISS_WARNING (ถ้ามี)
  -> FIND_SHARE
  -> VERIFY_SHARE_SHEET
  -> FIND_SHARE_TARGET
  -> VERIFY_HANDOFF
  -> COMPLETE

Fallback:
FIND_ROUTE_ACTION
  -> PREVIEW_THEN_STOP
  -> FIND_SHARE_AFTER_STOP
  -> VERIFY_SHARE_SHEET
```

## ลำดับความเร็วสูงสุด

1. รับ notification/accessibility trigger
2. สแกน Maps UI หนึ่งรอบ
3. ถ้ามี Warning ให้กด Exit และ scan ต่อทันที
4. ถ้ามี Share ให้กดทันที
5. เมื่อ Share Sheet event มาถึง ให้ค้น PHANToM GO จากทุก resolver window ทันที
6. กด PHANToM GO และตรวจ handoff
7. หาก UI ยังไม่พร้อม ใช้ adaptive polling จนถึง phase timeout

## Test Checklist

### Performance

- ทดสอบ CPU ความเร็วปกติ
- ทดสอบ CPU ที่ลดความเร็วเพื่อประหยัดพลังงาน
- ทดสอบอุปกรณ์ RAM ต่ำและเครื่องเก่า
- วัดเวลา Trigger ถึงกด PHANToM GO
- ยืนยันว่าอุปกรณ์เร็วไม่มี delay เพิ่มขึ้น

### Functional

- Share ปรากฏทันที
- Share ปรากฏช้า
- Closed-destination warning
- Navigation active และต้อง Preview/Stop ก่อน Share
- Share Sheet Android มาตรฐาน
- Samsung Share Sheet
- Xiaomi/MIUI Share Sheet
- PHANToM GO อยู่หน้าแรกและอยู่หน้ารายการเพิ่มเติม

### Safety

- ไม่กด UI ของ package นอก allowlist
- ไม่แชร์ route เดิมซ้ำจาก notification update
- Timeout แล้วหยุดและเข้า cooldown
- Accessibility ปิดแล้วทุก session หยุดทันที
- ไม่กระทบ pairing, relay และ MainSender เดิม

## ไฟล์ที่คาดว่าจะเกี่ยวข้องเมื่อเริ่มลงมือ

- `app/src/main/java/com/phantom/carnavrelay/MapsAutoShareCoordinator.kt`
- `app/src/main/java/com/phantom/carnavrelay/MapsUiMatcher.kt`
- `app/src/main/java/com/phantom/carnavrelay/MapsUiVocabulary.kt`
- `app/src/main/java/com/phantom/carnavrelay/MapsAutoShareAccessibilityService.kt`
- `app/src/main/res/xml/maps_auto_share_accessibility_service.xml`
- `docs/main-scoped-accessibility-auto-share-spec.md`
- Unit tests ใหม่สำหรับ phase timeout, adaptive interval และ matcher snapshot

## สิ่งที่ยังไม่ทำในรอบนี้

- ยังไม่แก้ค่า timeout หรือ polling
- ยังไม่เปลี่ยน matcher
- ยังไม่เปลี่ยน Accessibility Service config
- ยังไม่ build หรือสร้าง release
