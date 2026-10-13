# PHANToM GO iOS Native

อัปเดต: 2026-10-13 · iOS/iPadOS 16+ · SwiftUI · iPhoneOS arm64

แอป native สำหรับ iPhone/iPad ไม่ใช่ WebView สามารถเป็น Main หรือ Display และใช้ Firebase project/rules เดิมร่วมกับ Android APK และ Web App

## ฟีเจอร์ที่พัฒนาแล้ว

- Home / Display / Settings / Security พร้อมสี light/dark ตามระบบ
- Anonymous Auth ผ่าน Firebase REST; เก็บ session ใน Keychain ไม่ต้องลงทะเบียนผู้ใช้
- QR scan และวาง QR payload เพื่อจับคู่ทีละคู่
- Route relay แบบรอ Display ตอบรับ พร้อม retry, client request ID และ dedupe
- Share Extension สำหรับส่งจาก Google Maps เมื่อ signing รองรับ shared Keychain; มี Copy/Paste fallback
- เปิดลิงก์ `phantomgo://relay?url=...` และ `phantomgo://pair?...`
- Display QR เปลี่ยนเป็น speedometer เมื่อจับคู่แล้ว ตัวเลขปรับขนาดตามพื้นที่
- CoreLocation speed/GPS และตำแหน่งล่าสุดของ Display
- Find Display ด้วย MapKit พร้อมพิกัด ที่อยู่ และเวลาเก็บตำแหน่ง
- อ่าน security alerts ที่ Display Android ส่งมาในขณะที่ iOS app ทำงาน
- Local HTTP relay บน LAN เมื่อมี endpoint/token จาก cloud pairing
- ตั้งค่า Google Maps/Apple Maps, รถยนต์/มอเตอร์ไซค์ และ preview/navigation
- Foreground power/brightness profiles และ keep-awake แบบเลือกเปิด
- Local PIN, challenge/alarm และแจ้ง Main เมื่อถอด jack บนอุปกรณ์ที่มีช่อง 3.5 มม. ในตัวที่รองรับ
- Native GO Cam: H.264, hardware stabilization เมื่ออุปกรณ์รองรับ, loop 60 วินาที, quota, playback, trim/export และบันทึกไป Photos

## สิ่งที่แตกต่างจาก APK

- iOS ไม่ให้แอปทั่วไปปลุกจอล็อก บังคับล็อกเครื่อง หรือกด UI/อ่าน notification ของแอปอื่น
- PIN challenge บังได้เฉพาะ PHANToM GO ไม่ได้บล็อก Home/System Settings/แอปอื่น
- กันขโมยและรับเส้นทางขณะถูก suspend/force-quit ไม่รับประกัน; ไม่มี APNs backend ในรอบนี้
- Volume/audio output อยู่ภายใต้ iOS ไม่สามารถบังคับทุกกรณีแบบ Android
- กล้องหยุดเมื่อออกจากแอปหรือจอล็อก; เปิด Maps แล้วจะหยุด dashcam
- Export รอบนี้เป็นการ trim คลิปเดี่ยว ยังไม่มี timeline/merge หลายคลิปและ GPU overlay ครบชุดแบบ APK
- รถมอเตอร์ไซค์/เริ่มนำทางทันทีขึ้นกับ Google Maps และภูมิภาค; Apple Maps ไม่มีโหมดมอเตอร์ไซค์แยก
- iPad Wi-Fi ที่ไม่มี GPS อาจแสดงความเร็วไม่ได้ แม้ตำแหน่งผ่าน Wi-Fi จะใช้ได้
- ไม่ใช้ Bluetooth SPP transport แบบ Android; ใช้ Cloud/LAN
- Main iOS ใช้ local endpoint ที่เก็บไว้และ endpoint จาก presence; ไม่มี subnet discovery ครบชุดเหมือน APK

## Build และ Firebase

`project.yml` ใช้ XcodeGen สร้าง project บน macOS runner

Firebase ใช้ REST API ทางการผ่าน URLSession เพื่อ reuse Auth/Database ของโปรเจกต์เดิม โดย build script อ่าน public config จาก Web App ไม่ใช้ admin credentials และไม่ต้องเพิ่ม iOS app/`GoogleService-Info.plist` เพื่อใช้ Auth/Database ใน implementation นี้

- `iOS CI`: compile, unit tests, iPhone/iPad UI tests และ simulator artifact
- `iOS Device Build`: Release iPhoneOS arm64 IPA แบบ unsigned หรือ signed เมื่อมี Apple signing secrets
- unsigned IPA ต้องเซ็นก่อนติดตั้ง ไม่ใช่แตะไฟล์บน iPad แล้วติดตั้งได้ทันที

## เอกสารหลัก

- [วิธี build และติดตั้ง](BUILD_AND_INSTALL.md)
- [วิธีใช้ Windows โดยไม่มี Mac](NO_MAC_PLAYBOOK.md)
- [ระบบ signed/unsigned IPA](DEVICE_BUILD_PIPELINE.md)
- [แผนงานและ audit](NATIVE_IPA_PARITY_PLAN.md)
- [สิ่งที่ต้องทดสอบจริง](TEST_READINESS.md)
- [ผลรีวิวและข้อจำกัด](REVIEW_SUMMARY.md)
