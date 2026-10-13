# PHANToM GO iOS เมื่อไม่มี Mac

อัปเดต: 2026-10-13

## เส้นทางปัจจุบัน

GitHub Actions macOS runner ใช้ Xcode build native iPhoneOS arm64 ให้ แล้วดาวน์โหลด IPA มาเซ็น/ติดตั้งบน Windows ด้วย Sideloadly

```text
Source > XcodeGen > Xcode device Release build > unsigned IPA
       > GitHub Release > Windows/Sideloadly > Apple signing > iPhone/iPad
```

ไม่ต้องซื้อ Mac เพื่อใช้ขั้นตอนนี้ และไม่ต้องเปิด Firebase Console ใหม่เพื่อ Auth/Database แบบ REST

## เริ่มทดสอบบน iPad

1. อัปเดต iPadOS ให้ถึง 16+ ถ้าอุปกรณ์รองรับ
2. ดาวน์โหลด IPA จาก release ที่ขึ้นต้น `ios-`
3. ทำตาม BUILD_AND_INSTALL.md
4. ให้ Android และ iPad มีอินเทอร์เน็ตสำหรับการจับคู่ครั้งแรก
5. ทดสอบ iOS Main -> Android Display และ Android Main -> iOS Display
6. ทดสอบบน Wi-Fi/hotspot วงเดียวกันสำหรับ local relay
7. เปิด Maps จาก iOS Display แล้วกลับเข้าแอปเพื่อรับ/เปิด route ถัดไป

## Simulator กับเครื่องจริง

- Simulator artifact `.app.zip` ใช้กับ simulator ไม่ใช่ iPad จริง
- Device artifact `.ipa` เป็น executable สำหรับ iPhoneOS จริง แต่ยังต้องเซ็นถ้าเป็น unsigned
- UI tests ใช้ข้อมูลจำลองเฉพาะ Debug test mode ไม่ได้ยืนยัน GPS/กล้อง/Jack/Maps บนอุปกรณ์จริง
- ต้องทดสอบ GPS, Camera, background, audio routing และ Maps บน iPhone/iPad จริง

## Signed IPA ในอนาคต

ถ้ามี Apple Developer certificate และ profile สำหรับ Main/Share targets จะใช้ `signing_mode=signed` บน GitHub Actions ได้

การแจกให้ผู้ใช้ทั่วไปแบบสะดวกควรใช้ TestFlight/App Store ในงานถัดไป เพราะการดาวน์โหลด IPA จาก GitHub ไม่ได้ทำให้ทุกเครื่องติดตั้งได้โดยไม่มี signing
