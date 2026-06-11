# Stable Release Review - v1.0.14

วันที่รีวิว: 2026-06-11

## ขอบเขตของ Release

Release นี้แก้ความเสถียรของ Display Power Automation บน Android โดยไม่แก้ไขฝั่ง iOS และไม่เปลี่ยน Firebase contract

## ต้นเหตุของปัญหา

อุปกรณ์ Samsung ที่ใช้ทดสอบส่ง `ACTION_POWER_CONNECTED` และ `ACTION_POWER_DISCONNECTED` ตามปกติ แต่ระบบปฏิบัติการข้าม `DisplayPowerEventReceiver` ที่ประกาศใน Manifest ด้วยเหตุผล `Background execution not allowed`

ผลคือโค้ดปรับความสว่าง ปลุกจอ และล็อกเครื่องทำงานได้ แต่ไม่เคยได้รับ power event จากระบบ

## สิ่งที่แก้ไข

1. เพิ่ม runtime power receiver ภายใน `DisplayServerService` ซึ่งทำงานเป็น Foreground service
2. รับ power event ได้แม้ PHANToM GO อยู่เบื้องหลัง
3. ตรวจสถานะเสียบไฟปัจจุบันทันทีเมื่อ Display service เริ่ม เพื่อกู้สถานะที่พลาดไป
4. เพิ่ม debounce เพื่อป้องกันการทำงานซ้ำ หาก OEM ส่ง event ผ่าน receiver มากกว่าหนึ่งช่องทาง
5. แยกการลงทะเบียน power receiver ออกจาก receiver อื่น เพื่อไม่ให้ความผิดพลาดของระบบอื่นหยุด power automation
6. คง Manifest receiver เดิมไว้เป็น fallback สำหรับอุปกรณ์ที่อนุญาต

## ผลการตรวจสอบ

- `assembleDebug`: ผ่าน
- `assembleRelease`: ผ่าน
- `lintDebug`: ผ่าน
- `testDebugUnitTest`: ผ่าน โดยโปรเจกต์ Android ยังไม่มี unit-test source
- ติดตั้งและทดสอบ implementation เดียวกันบนอุปกรณ์ Samsung `SM-A075F` ก่อน bump เลข version: ผ่าน
- ตรวจ startup reconciliation ขณะเสียบไฟ: ผ่าน
- จำลองถอดสายขณะเปิดแอป: ปรับความสว่างและล็อกเครื่องสำเร็จ
- จำลองเสียบสายกลับ: ปรับความสว่างเป็น `255` และปลุกหน้าจอสำเร็จ
- จำลองถอดสายขณะแอปอยู่เบื้องหลัง: ล็อกเครื่องสำเร็จ
- ยืนยันว่า Display service ยังเป็น Foreground service ขณะทดสอบ
- ตรวจ final APK: `com.phantom.carnavrelay`, version `1.0.14 (15)`, ลายเซ็น v1/v2 ผ่าน
- ไม่สามารถติดตั้ง final APK หลัง bump version ซ้ำได้ เพราะอุปกรณ์ ADB หลุดการเชื่อมต่อก่อนขั้นตอนสุดท้าย

## Release APK

- ชื่อไฟล์: `PHANToM_GO-v1.0.14.apk`
- SHA-256: `EACDD1DDB8A534C185B52DB5D7A6854435E4D5FD828F32AAAB4B18B2E5C8FB18`

## ข้อจำกัด

- ความสว่างที่ตั้งเป็น `0` อาจถูกรอมระบบหรือ firmware แปลงเป็นค่าต่ำสุดที่อุปกรณ์รองรับ
- การปลด secure lock screen อัตโนมัติไม่ได้รับอนุญาตโดย Android
- OEM แต่ละยี่ห้ออาจมีนโยบายจัดการ Foreground service แตกต่างกัน จึงควรทดสอบเพิ่มบนอุปกรณ์เป้าหมาย

## Firebase และ iOS

- ไม่มีการแก้ Firebase Realtime Database Rules
- ผู้ใช้ไม่ต้องแก้ Firebase Console สำหรับ release นี้
- ไม่มีการแก้ไขไฟล์ภายใน `ios/`
