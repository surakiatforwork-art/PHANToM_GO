# แผนต่อสำหรับ PHANToM GO Native iOS

อัปเดต: 2026-10-13

สถานะใหม่และ audit ดู NATIVE_IPA_PARITY_PLAN.md; เอกสารนี้แทนแผน scaffold เดิม

## งานรุ่นแรก

- Native SwiftUI 4 แท็บ, Firebase Auth/Database REST, QR pairing และ reliable route relay
- GPS/speed/location, Find Display, security alerts, native local HTTP server
- Foreground brightness/keep-awake, PIN/jack challenge/alarm ตาม capability ของ iOS
- GO Cam recording/library/trim/export
- Share Extension และ deep-link input
- CI ทดสอบ iPhone/iPad และ device arm64 IPA

## งานถัดไปหลังเจ้าของทดสอบ

1. เก็บผลจริงของ iOS Main/Display ร่วมกับ APK/Web ตาม TEST_READINESS.md
2. เตรียม Apple signing ของ Main/Share targets ถ้าต้องการ one-tap Share และแจกที่ติดตั้งสะดวก
3. เพิ่ม APNs/FCM backend อย่างถูกต้อง หากต้องการ incoming notification ขณะ app suspended; ต้องมี Apple credentials และงาน backend แยก
4. เพิ่ม background recovery/permission guidance ตามผลจริงของ iPhone/iPad
5. เพิ่ม local subnet discovery/Bonjour interoperability แบบลดภาระอุปกรณ์
6. เพิ่ม multi-clip export/timeline และลดช่องว่างระหว่าง dashcam segments
7. เพิ่ม localization ภาษาไทยเต็มชุดของ UI และ runtime messages
8. วาง TestFlight/App Store pipeline โดยไม่ใช้ profile/certificate ที่ไม่เป็นของเจ้าของ
9. เพิ่ม backend command/security alert retention ก่อนขยายผู้ใช้มาก
10. ไม่พยายามทำ Accessibility automation, global overlays, force volume หรือ auto device lock/wake ด้วย private API
11. เพิ่ม UI screenshot validation หลัง rotation เสร็จและทดสอบ dark mode/Dynamic Type/เครื่องจอเล็กเพิ่ม
12. เพิ่ม Firebase emulator integration tests และทดสอบ pairing/relay/ack กับ APK/Web บนเครื่องจริงก่อนแจกผู้ใช้ทั่วไป
13. จัดการ presence observer ของ peer เก่าให้หยุดทันทีหลัง re-pair/cloud unpair และเพิ่ม lifecycle regression tests

## วิธีทำงานของ Agent ต่อไป

- Repository: https://github.com/surakiatforwork-art/PHANToM_GO
- ตรวจ git status และ diff ก่อนแก้
- อย่า stage untracked ส่วนตัวหรือ private signing files
- อย่าแก้ Android/Firebase rules เพียงเพื่อให้ iOS compile
- Build จาก iOS Device Build; ไม่ rename simulator app เป็น device IPA
- iOS release ใช้ prefix `ios-` และไม่แทน Android Latest Stable
- รายงาน unsigned/signed และแยก simulator test ออกจาก real-device test ชัดเจน
- อัปเดต .md เมื่อมีแผน/ข้อจำกัด/ผลทดสอบใหม่
