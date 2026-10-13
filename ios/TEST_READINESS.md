# รายการทดสอบ PHANToM GO Native iOS

อัปเดต: 2026-10-13

ผล CI ของ binary commit `087b4d0`: unit 25 รายการ, iPhone UI 3 รายการ, iPad UI 3 รายการผ่านทั้งหมด และ device IPA ผ่าน verifier

## ตรวจอัตโนมัติบน macOS CI

- Xcode compile ของ Main app และ Share Extension
- Route parser: QR, coordinates, geo, navigation, Google Maps links, shared text, viewport/destination
- Contract: `url`, `fromMainAuthUid`, request ID, ack states
- Firebase stream put/patch/delete
- Persistent dedupe และ private LAN endpoint validation
- Local token rotation หลังยกเลิกคู่
- PIN validation และ alarm timing policy
- wrong PIN 2 ครั้งขณะ disarm โดยไม่ถอด jack: เริ่ม alarm และครบ 30 วินาทีต้องยกระดับ PIN screen
- UI: เปิดทุกแท็บ, scroll Settings, ความเร็ว 3 หลักทั้งแนวตั้ง/แนวนอน
- UI ทำงานด้วย fixture เฉพาะ Debug testing ไม่ติดต่อ Firebase production
- Native device IPA: arm64 Mach-O, iPhoneOS platform, version/build, extension

## ต้องทดสอบบนเครื่องจริง

1. Sideload/sign และเปิด native app บน iOS/iPadOS 16+
2. Anonymous Auth ต่อ Firebase project เดิมและเขียน device/presence
3. iOS Main -> Android/Web Display: QR pairing, route send, ack, retry
4. Android/Web Main -> iOS Display: QR pairing, live UI update, received route และ Maps
5. เพื่อนเดิมส่งไม่ได้หลัง disconnect/reset/new pairing
6. LAN ทั้งสองฝั่งขณะอินเทอร์เน็ตขาด; token เดิมใช้ไม่ได้หลัง reset
7. iOS Main เมื่อ endpoint เปลี่ยน IP ให้กลับมา online เพื่ออ่าน presence ใหม่; subnet scan แบบ APK ยังไม่มี
8. GPS ความเร็ว/ความแม่นยำ/ตำแหน่งล่าสุด และ Find Display offline
9. Google Maps/Apple Maps, preview/navigation, รถยนต์/มอเตอร์ไซค์ตามที่ Maps รองรับ
10. Permission ปฏิเสธ/อนุญาตภายหลัง ไม่ทำให้แอปค้าง
11. Foreground brightness/keep-awake และคืน brightness เมื่อออกจากแอป
12. PIN setup/change/disarm, ถอด/เสียบ jack, wrong PIN, timeout, alarm และ alert ไป Main บนอุปกรณ์ 3.5 มม. ที่รองรับ
13. Security verification มี priority ก่อนเปิด route
14. Camera 720p/1080p/audio, loop recording, quota, playback, trim/export, Photos
15. เปิด Maps/ล็อกจอ/ไป background ระหว่าง recording: หยุดและรักษาคลิปที่บันทึกได้
16. Share Extension: shared Keychain signing พร้อมส่ง one-tap; ถ้าไม่รองรับต้องแสดง copy/paste fallback
17. App force-quit/suspend ไม่ควรถูกอ้างว่ารับ route/กันขโมยได้ตลอดเวลา
18. เปลี่ยน Main/Display และเปลี่ยนคู่ซ้ำ: ตรวจ observer ของ peer เก่า การใช้ network และข้อความ Reconnecting

## สิ่งที่ไม่ใช่หลักฐาน

- Build ผ่าน ไม่ได้ยืนยัน GPS/กล้อง/แจ้งเตือนบน iPad จริง
- Simulator fixture ความเร็ว 123 ไม่ได้เป็นค่าจาก GPS
- unsigned IPA ไม่ใช่ signed installable IPA
- มี background modes ไม่ได้แปลว่า iOS อนุญาตให้ทุกฟีเจอร์ทำงานตลอดเวลา
