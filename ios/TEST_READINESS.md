# PHANToM GO iOS Test Readiness

เอกสารนี้สรุปแบบเร็วว่า ตอนนี้ฝั่ง iOS “พร้อมเทสถึงระดับไหนแล้ว”

## Ready now

- SwiftUI app scaffold ครบ `Home / Display / Settings`
- Firebase contract อิง Android เดิม
- GitHub Actions สำหรับ simulator build
- GitHub Actions สำหรับ signed IPA build
- unit tests สำหรับ `RouteParser` และ pairing parsing
- PowerShell script ช่วยแปลงไฟล์เป็น GitHub secrets

---

## Automated checks prepared

ตอนนี้ workflow `iOS CI` ถูกเตรียมให้:

1. generate Xcode project
2. resolve Swift packages
3. หา simulator ที่ available อัตโนมัติ
4. run unit tests
5. build simulator app
6. upload logs และ artifact

---

## Manual checks still required later

เมื่อกลับมาเทสจริง ควรเช็กตามลำดับนี้:

1. Firebase anonymous sign-in
2. เขียน `devices` / `presence`
3. เปิด Display mode แล้ว publish pair code
4. Android Main -> iOS Display pairing
5. Android Main -> iOS route relay
6. iOS Main -> Android Display route relay
7. QR scanner กล้องจริง
8. เปิด Apple Maps / Google Maps บนเครื่องจริง

---

## Known limitation

แม้ตอนนี้ pipeline และ test harness พร้อมแล้ว แต่บนเครื่อง Windows นี้ยัง:

- compile iOS app จริงไม่ได้
- รัน XCTest จริงไม่ได้
- export IPA จริงไม่ได้

ดังนั้น “พร้อมเทส” ในความหมายตอนนี้คือ:

- repo พร้อมสำหรับ remote CI/macOS validation
- ไม่ใช่ยืนยันว่าแอป iOS ผ่าน runtime test จริงแล้วในเครื่องนี้

---

## Next best action when you return

1. push ขึ้น GitHub
2. ตั้ง secrets
3. รัน `iOS CI`
4. ถ้าผ่าน ค่อยรัน `iOS Device Build`
5. ดาวน์โหลด artifact และเริ่ม manual test
