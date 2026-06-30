# Route Delivery Reliability (Ack-based retry + dedupe)

Date: 2026-06-29

## Why
เดิม Main ถือว่า "ส่งสำเร็จ" เมื่อ **เขียน command ลง Firebase สำเร็จ** เท่านั้น ไม่ได้รอให้ Display ยืนยันว่าได้รับ/เปิดจริง ทำให้บางครั้ง Display เปิดช้าหรือไม่เปิดเลย แต่ผู้ใช้เห็นว่า "สำเร็จ" (เวอร์ชันเก่ามี retry แบบ WorkManager แต่ตอนนี้อยู่ใน `SilentShareReceiverActivity` ซึ่งไม่ใช่ entrypoint หลักแล้ว จึงไม่ทำงานใน flow ปัจจุบัน)

## Flow ปัจจุบัน (ไม่เปลี่ยน)
ShareReceiverActivity → RelayActivity → MainSender → FirebaseManager → Display listener → DisplayServerService
เพิ่มชั้นความเสถียร "ใต้" flow เดิมเท่านั้น ไม่ revert/rewrite และไม่คืนชีพ SilentShareReceiverActivity

## สิ่งที่เปลี่ยน
### ฝั่ง Main (`FirebaseManager` + `MainSender`)
- เพิ่ม `sendCloudOpenUrlReliable(context, url, onAck, onFailure)`
  - สร้าง `clientRequestId` เดียวต่อการแชร์ 1 ครั้ง (`<mainDeviceId>-<ts>-<rand>`)
  - command payload เพิ่ม `clientRequestId`, `attempt`
  - รอ ack จาก Display (status เป็น `received` หรือ `opened`) ผ่าน listener ของแต่ละ command
  - retry: attempt 1 ทันที, attempt 2 ที่ ~1.5s, attempt 3 ที่ ~4s ถ้ายังไม่ ack; ยอมแพ้ที่ ~7s
  - ได้ ack ครั้งแรก → `onAck()` ครั้งเดียว แล้วถอด listener/ยกเลิก retry ที่เหลือ (กัน leak/ส่งซ้ำเกิน)
  - แต่ละ retry เป็น **command ใหม่** (push key ใหม่) ใช้ clientRequestId เดิม — เพราะ Firebase rules อนุญาตให้ Main *สร้าง* command เท่านั้น (อัปเดตทำได้เฉพาะฝั่ง Display)
- `MainSender.sendOpenUrlCloud` ใช้ `sendCloudOpenUrlReliable`: "สำเร็จ" = Display ack จริง (ไม่ใช่แค่เขียน Firebase); ถ้าล้มเหลว/ไม่ ack → fallback Wi‑Fi เดิม → คิว/แจ้ง error เดิม (UX retry เดิมคงไว้)
- `sendCloudOpenUrl` เดิมยังอยู่ (delegate ไป `writeCloudCommand`) เพื่อความเข้ากันได้

### ฝั่ง Display (`FirebaseManager.handleCloudCommandSnapshot`)
- dedupe ด้วย `clientRequestId` (fallback เป็น `commandId` ถ้าไม่มี — รองรับ command รุ่นเก่า)
- **ack ทุก command เสมอ** (ตั้ง `received`) แม้เป็น retry ซ้ำ เพื่อให้ Main รู้ว่า Display ยังตอบสนอง
- เปิด Google Maps แค่ครั้งเดียวต่อ `clientRequestId` ภายในหน้าต่าง ~3 นาที
- เก็บ key ที่ประมวลผลแล้วใน memory + persist ลง SharedPreferences (`go_cloud_dedupe`) กันเปิดซ้ำหากโปรเซสรีสตาร์ทระหว่าง burst

## ทำไม clientRequestId กันเปิดซ้ำได้
แต่ละการแชร์ของผู้ใช้ = หนึ่ง clientRequestId. retry ส่ง command หลายใบแต่ id เดียวกัน. Display จำว่าเปิดให้ id นี้ไปแล้ว จึง ack ใบซ้ำแต่ไม่เปิด Maps อีก. การแชร์เส้นทาง 2 อันต่อกันเร็ว ๆ ได้ id คนละตัว จึงไม่ชนกัน (ไม่ใช้ WorkManager unique-name ที่ cancel งานอื่น)

## Firebase rules
**ไม่ต้องแก้** — ตรวจ `docs/firebase-realtime-database.rules.json` แล้ว:
- การ "สร้าง" command ของ Main ไม่จำกัดฟิลด์ → เพิ่ม `clientRequestId`/`attempt` ได้เลย
- การ "อัปเดต" command ทำได้เฉพาะเจ้าของ Display และต้องไม่แก้ `fromMainAuthUid`/`fromMainDeviceId`/`url`/`createdAt` → การ ack เดิม (status/receivedAt/openedAt/finalUrl) ยังผ่าน

## ข้อจำกัดที่ทราบ
- ถ้า Display ออฟไลน์เกิน ~7s การส่งจะถือว่าล้มเหลว (ไป fallback Wi‑Fi/คิวตามเดิม) แต่ command ยังค้างใน Firebase และ Display จะเปิดเมื่อกลับมาออนไลน์ (อาจช้ากว่าหน้าต่าง ack) — Main จะรายงานล้มเหลวแม้ภายหลัง Display อาจเปิดได้
- dedupe ใช้หน้าต่างเวลา (~3 นาที) ถ้าผู้ใช้ตั้งใจส่ง "เส้นทางเดิม url เดิม" ซ้ำเร็วมาก จะถือเป็นคนละ clientRequestId (เปิดได้) — ถูกต้องตามเจตนา เพราะ dedupe ผูกกับ clientRequestId ไม่ใช่ url
- timing (1.5s/4s/7s) ปรับได้ตามหน้างานจริง

## ทดสอบ
- build APK + lint ผ่าน
- logcat (tag PHANTOM_GO): ดู "Cloud route send attempt=", "Cloud route ack received", "Cloud command dedupe: acked but skip open"
