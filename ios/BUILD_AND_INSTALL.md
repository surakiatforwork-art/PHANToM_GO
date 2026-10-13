# วิธีติดตั้ง PHANToM GO Native iOS

อัปเดต 2026-10-13

## สิ่งที่ต้องมี

- iPhone/iPad ที่ใช้ iOS/iPadOS 16 ขึ้นไป
- Windows และสาย USB สำหรับวิธี Sideloadly
- Apple Account ของผู้ติดตั้ง ใช้กรอกในโปรแกรมติดตั้งเอง
- IPA ของ PHANToM GO จาก GitHub Release

APK ใช้ติดตั้งบน iOS ไม่ได้ และ simulator `.app.zip` ก็ใช้ติดตั้งบน iPad จริงไม่ได้ ต้องใช้ IPA ของ iPhoneOS

## ติดตั้งด้วย Sideloadly บน Windows

1. ดาวน์โหลด Sideloadly จากเว็บไซต์ทางการ https://sideloadly.io/
2. ติดตั้งส่วนประกอบ Apple/iTunes/iCloud ตามคำแนะนำ Windows ของ Sideloadly รุ่นปัจจุบัน
3. ดาวน์โหลดไฟล์ `PHANToM_GO-iOS-v1.0.0-unsigned.ipa` จาก iOS release ของ repo https://github.com/surakiatforwork-art/PHANToM_GO
4. เสียบ iPhone/iPad กับ Windows ปลดล็อกเครื่อง และเลือก Trust เมื่อระบบถาม
5. เปิด Sideloadly แล้วเลือกอุปกรณ์ที่ต่ออยู่
6. ลาก IPA ใส่โปรแกรม
7. ใช้ Apple Account ของคุณใน Sideloadly แล้วเริ่มขั้นตอนเซ็นและติดตั้งตามโปรแกรม
8. หาก iOS ขอให้ Trust developer ให้ไปที่ Settings > General > VPN & Device Management แล้วเลือก profile ของคุณ
9. หาก development signing ต้องการ Developer Mode ให้ไปที่ Settings > Privacy & Security > Developer Mode แล้วทำตามขั้นตอนรีสตาร์ต/ยืนยันของ Apple
10. เปิด PHANToM GO แล้วทดสอบตามรายการด้านล่าง

อย่าส่ง Apple Account password, OTP หรือ private certificate ในแชท ให้กรอกในขั้นตอน Apple/Sideloadly ที่คุณควบคุมเอง

Personal Team มี provisioning profile อายุ 7 วัน จึงต้องเซ็น/refresh ใหม่ตามระยะเวลา การอัปเดตควรใช้ account และ bundle ID เดิมเพื่อรักษาข้อมูลเดิม อย่าถอนแอปโดยไม่จำเป็น เพราะข้อมูลใน app sandbox อาจหาย

Share Extension แบบส่งทันทีต้องเข้าถึง shared Keychain หากเครื่องมือเซ็น/profile ไม่รองรับ ส่วน Main ในแอปยังส่งได้โดย Copy Route แล้ว Paste บน Home

## เปิดใช้งานครั้งแรก

1. ให้อุปกรณ์ทั้งสองมีอินเทอร์เน็ต
2. ถ้าจะเป็น Main ให้เปิด Home แล้ว Scan QR จาก Display
3. ถ้าจะเป็น Display ให้เปิด Display แล้วเปิด Display Mode
4. อนุญาต Camera เฉพาะเมื่อสแกนหรือบันทึกวิดีโอ
5. เปิด Notifications เพื่อรับแจ้งเตือนที่แอปสร้างได้ขณะกำลังทำงาน
6. อนุญาต Location เพื่อใช้ความเร็ว/Find Display; เลือก Always หากต้องการ Background GPS
7. จับคู่สำเร็จแล้วส่งตำแหน่งทดสอบก่อนใช้งานจริง
8. Display เลือก Maps app/โหมดยานพาหนะ/preview หรือ navigation ใน Settings
9. ถ้า iOS Display อยู่ในแอปอื่น ให้กลับเข้า PHANToM GO แล้วเปิด latest route; การรับใน background ไม่รับประกัน
10. หากใช้ GO Cam ให้เปิดจาก Display และเริ่ม recording; การออกไป Maps จะหยุดกล้องตามข้อจำกัด iOS

ไม่ต้องเปลี่ยน Firebase rules หรือใช้ `GoogleService-Info.plist` สำหรับ implementation แบบ REST นี้

## Build เองบน macOS

```bash
cd ios
brew install xcodegen
python3 scripts/prepare_configuration.py
swift scripts/generate_icon.swift
xcodegen generate
xcodebuild -project PHANToMGO.xcodeproj -scheme PHANToMGO -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

## Build โดยไม่มี Mac

- GitHub > Actions > iOS Device Build > Run workflow > `signing_mode=unsigned`
- รอผลสำเร็จและดาวน์โหลด artifact `PHANToMGO-ios-device-ipa-unsigned`
- signed mode ต้องมี certificate และ provisioning profile สำหรับทั้ง Main app กับ Share Extension; ดู DEVICE_BUILD_PIPELINE.md

## แหล่งอ้างอิง

- https://sideloadly.io/
- https://developer.apple.com/help/account/basics/about-your-developer-account
- https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device
