# แผนปรับ Native iOS: Main, Display และไอคอน

วันที่เริ่ม: 2026-10-13
ฐาน: iOS v1.0.0, Android/Firebase rules เดิมไม่เปลี่ยน

## เป้าหมาย

ให้ iOS ใช้งานตามรูปแบบที่ระบบรองรับ ไม่พยายามทำ foreground service/Accessibility/บังคับสลับแอปแบบ Android และแยก server acceptance ออกจาก Display acknowledgement อย่างชัดเจน

## ระยะที่ 1: ทำได้ทันที

1. Main/Share Extension ส่งตรงผ่าน cloud schema เดิม; ลดเวลารอ acknowledgement ใน Share และรายงาน queued เมื่อบันทึกบน server แล้วแต่ Display ยังไม่ตอบรับ ไม่รายงานว่าได้รับแล้ว
2. เพิ่ม auto-send ใน Share แบบตั้งค่าได้ พร้อมป้องกันกดส่งซ้ำ และแสดง copy/paste fallback ที่อธิบาย signing/shared Keychain ตรงสาเหตุ
   Paste บน Home ส่งทันทีแบบตั้งค่าได้ ลดปุ่มอีกครั้งเมื่อ signing ไม่รองรับ Share relay
3. ตรวจ shared Keychain จาก access group ที่ iOS อนุญาตจริง ไม่เชื่อ prefix ที่เหลือจาก unsigned build อย่างเดียว และไม่ย้าย credential ไปเก็บแบบไม่ปลอดภัย
4. Display เปิด Maps ตามการกดของผู้ใช้เป็นค่าเริ่มต้น; คงตัวเลือก auto-open เมื่ออยู่ foreground สำหรับผู้ที่ต้องการ
5. Notification tap มี routing เข้า Display/Security; route notification เป็นเพียงตัวระบุ ต้องอ่านข้อมูลจาก Firebase ด้วย auth และตรวจคู่ปัจจุบันก่อนเปิด Maps
6. Resume/notification tap อ่านเส้นทางล่าสุด ไม่เปิดข้อมูลเก่าของคู่ก่อนหน้า และไม่เปิด Maps ทับ PIN challenge
7. หยุด presence observer ของ peer เก่าเมื่อเปลี่ยน/ลบคู่ และรักษา pairing/transport schema ของ APK/Web
   ไม่ตัด listener เมื่อขึ้น permission dialog ชั่วคราว และตรวจ Main UID นอกจาก device ID ก่อนเลือกเส้นทาง
8. ไอคอน iOS ใช้ตัวผีใส่แว่น แผนที่ และสัญลักษณ์แชร์จาก APK; PNG 1024x1024 แบบทึบ ไม่มีการวาดมุมโค้งลงในไฟล์ ให้ระบบ iOS mask เอง
9. เพิ่ม tests สำหรับสถานะ delivery, notification payload validation, selection ของ latest route และ shared access-group resolution; build/test บน macOS CI
10. ประกาศ orientation ที่รองรับใน Info.plist และรอ layout หมุนสำเร็จในการทดสอบ ไม่ใช้เพียงค่าตัวเลขเป็นหลักฐานว่า landscape ใช้ได้

## ระยะที่ 2: Remote push จริง

ยังไม่ deploy backend หรือเปิด billing จนทราบ Apple Developer/signing และเจ้าของยืนยันขั้นตอนที่มีค่าใช้จ่าย

- ต้องมี Apple App ID ที่เปิด Push Notifications และ provisioning profile ที่มี `aps-environment`
- ต้องมี APNs provider credential ฝั่ง server; ห้ามใส่ private key/รหัสผ่านใน IPA หรือ repository
- เลือก backend ที่รับ command ใหม่จาก RTDB แล้วส่ง visible push ไปอุปกรณ์ Display ปัจจุบัน
- ตรวจคู่, UID, command status และ dedupe/collapse ID ฝั่ง server ป้องกัน retry สร้างแจ้งเตือนซ้ำ
- Payload มีชนิด event/device/request ID เท่านั้น ไม่เชื่อ URL, PIN หรือตำแหน่งจาก push
- ผู้ใช้กด notification -> เปิด PHANToM GO -> ตรวจ Firebase/pairing -> เปิด Maps ล่าสุด
- silent push ไม่ใช่ช่องทางที่รับประกันการส่งทันที และไม่ใช้เพื่อหลบข้อจำกัด background
- การใช้ Firebase Functions/Cloud Run อาจต้อง Blaze/billing ต้องอธิบายและให้เจ้าของตัดสินใจก่อน

## การยืนยันก่อนส่งมอบ

- ไม่แก้ native APK, website/Web App หรือ Firebase rules ในระยะ 1
- ตรวจ unit/UI tests และ native arm64 IPA; simulator ไม่ใช่หลักฐาน runtime บน iPad จริง
- ตรวจขนาด PNG/alpha และ icon keys ใน Info.plist ของ main app/Share Extension
- ไม่อ้างว่า remote push ใช้งานแล้วจน signed app และ provider ส่งจริงผ่านบนเครื่อง
- build รุ่นใหม่สำหรับทดสอบก่อน; ไม่แทน Android Latest Stable

## อ้างอิง

- https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps
- https://developer.apple.com/documentation/usernotifications/handling-notifications-and-notification-related-actions
- https://developer.apple.com/help/account/reference/supported-capabilities-ios
- https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app

## สถานะ

เจ้าของยืนยันว่าใช้ Apple Account ฟรี/Sideloadly จึงยังไม่ทำ native APNs/provider deployment

ระยะ 1 กำลังตรวจโค้ดและเตรียม CI รุ่น 1.0.1 build 3 สำหรับทดสอบ ไม่แก้ Firebase Console/rules

ทางเลือกต่อไปโดยไม่สมัคร Apple Developer แบบจ่ายเงิน: Web App/PWA ที่ Add to Home Screen รองรับ Web Push บน iOS/iPadOS 16.4+ แต่ต้องมี push backend และต้องเป็นงาน Web App แยก ไม่ใช่ความสามารถของ native IPA นี้

อ้างอิง: https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/
