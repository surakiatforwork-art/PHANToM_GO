# PHANToM GO iOS v1.0.0

วันที่: 2026-10-13 · Native SwiftUI · iOS/iPadOS 16+ · arm64

## ไฟล์ติดตั้ง

- ไฟล์: `PHANToM_GO-iOS-v1.0.0-unsigned.ipa`
- Bundle ID: `com.phantom.PHANToMGO`
- Version: `1.0.0`, build `2`
- เป็น native executable ของ iPhoneOS พร้อม Share Extension ไม่ใช่ simulator app หรือ WebView
- IPA นี้เป็น unsigned ต้องเซ็นและติดตั้งด้วย Sideloadly หรือ Apple signing ที่เหมาะสมก่อน
- SHA-256: `c96f8859b2228742064c856d9e1a362d00d77e36b3011e1aec553e7aff52cb1e`

## ฟีเจอร์

- Home / Display / Settings / Security รองรับ light/dark ตามระบบ
- จับคู่ด้วย QR/payload กับ Android APK และ Web App ผ่าน Firebase schema เดิม
- Firebase Anonymous Auth REST; session เก็บใน Keychain ไม่ต้องสมัครบัญชี
- ส่งเส้นทางแบบรอ acknowledgement พร้อม retry และ persistent dedupe ฝั่ง Display
- Local LAN relay สำหรับคู่ที่มี endpoint/token และหมุน token เมื่อ disconnect
- Google Maps/Apple Maps, preview/navigation และโหมดรถยนต์/มอเตอร์ไซค์ตาม Maps support
- GPS speedometer เฉพาะเมื่อ Display จับคู่แล้ว และตำแหน่งสำหรับ Find Display
- Find Display ใน MapKit พร้อมพิกัด/ที่อยู่/เวลา และอ่าน security alerts จาก APK
- Share Extension one-tap เมื่อ signing รองรับ shared Keychain; Copy/Paste fallback พร้อมใช้ในแอปหลัก
- Foreground brightness profiles และ keep-awake แบบ opt-in
- Local PIN/jack anti-theft challenge/alarm บนอุปกรณ์ 3.5 มม. ที่รองรับ
- Native GO Cam: loop 60 วินาที, H.264, hardware stabilization เมื่อรองรับ, quota, playback, trim/export และ Photos

## แตกต่างจาก Android

- ไม่อ่าน notification ของแอปอื่น ไม่ใช้ Accessibility automation และไม่ overlay ทับแอปอื่น
- ไม่ปลุกจอล็อก/บังคับล็อกเครื่อง/บังคับ system volume แบบ APK
- Receiving/security ขณะ app suspended หรือ force-quit ไม่รับประกัน และยังไม่มี APNs backend
- เปิด Maps อัตโนมัติได้เมื่อ PHANToM GO active; iOS/Maps อาจต้องให้ผู้ใช้กด Start
- กล้องหยุดเมื่อออกจากแอปหรือจอล็อก; เปิด Maps จะหยุด recording
- Export ยังเป็นคลิปเดี่ยว ไม่มี multi-clip timeline/merge/GPU overlay ครบแบบ APK
- iOS Main ยังไม่มี local subnet discovery ครบแบบ APK; IP เปลี่ยนขณะ offline อาจต้องกลับ online อ่าน presence
- iPad Wi-Fi ที่ไม่มี GPS อาจไม่มีค่าความเร็วจริง
- PIN เป็น local disarm ไม่ได้ล็อกทั้งระบบหรือกันถอนการติดตั้ง

## Build และการตรวจ

- Device Release build จาก Xcode 26.6 ผ่าน
- Package verifier ตรวจ arm64 Mach-O, iPhoneOS, version/build และ Share Extension ผ่าน
- Unit tests 25 รายการ และ UI smoke tests 3 รายการต่อเครื่องบน iPhone/iPad simulator ผ่านทั้งหมด
- ตรวจ Firebase REST Anonymous Auth และ rules read ที่อนุญาตจริง ผ่าน; ไม่แก้ Firebase rules/customer data
- Device build: https://github.com/surakiatforwork-art/PHANToM_GO/actions/runs/38376712861
- Simulator tests: https://github.com/surakiatforwork-art/PHANToM_GO/actions/runs/38376680495
- UI tests ใช้ fixture เฉพาะ Debug mode ไม่ใช่ GPS/กล้อง/การจับคู่จริง
- ยังไม่ได้ติดตั้งและทดสอบบน iPad/iPhone จริง จึงต้องทดสอบ runtime ตาม `ios/TEST_READINESS.md`

## ติดตั้ง

ทำตาม `ios/BUILD_AND_INSTALL.md` ด้วย Sideloadly บน Windows หากยังไม่มี Apple certificate/profile

Personal Team profiles มีอายุ 7 วันตาม Apple; ต้อง refresh signing ตามระยะเวลา

## Firebase

ไม่ต้องเปลี่ยน Firebase Console/rules หรือหา `GoogleService-Info.plist` สำหรับ native REST implementation นี้

## Source

- Repository: https://github.com/surakiatforwork-art/PHANToM_GO
- iOS release tag: `ios-v1.0.0`
- Native binary source: `087b4d0e9157d3754ad3138aea2ec10635959870`; final tag รวมเอกสารส่งมอบเพิ่มเติม
- Android APK/source และ Android Latest Stable คงเดิม
