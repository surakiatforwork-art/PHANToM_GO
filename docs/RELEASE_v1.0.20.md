# PHANToM GO v1.0.20

## สรุป

รุ่นนี้แก้ระบบแชร์เส้นทางอัตโนมัติฝั่ง Main ให้ทนต่อเครื่องช้าและ Google Maps ที่โหลดไม่ทัน โดยไม่แชร์ปลายทางเก่าหรือปลายทางที่ยังคำนวณไม่เสร็จ และไม่ทำ trigger เส้นทางใหม่สูญหายในช่วง cooldown

## สิ่งที่เปลี่ยน

- เปลี่ยน Auto Share เป็น state machine ที่มี timeout แยกตามขั้นตอนและ safety timeout รวม
- รอให้ route controls และปุ่ม Share นิ่งก่อนกด ป้องกันปลายทางผิดเพี้ยนเมื่อ Maps ยังโหลดไม่เสร็จ
- เก็บ trigger เส้นทางใหม่ที่เข้าระหว่าง session หรือ cooldown แล้วทำงานต่ออัตโนมัติ
- สแกน accessibility node tree รอบเดียวต่อ scan และปรับช่วง polling ตามเวลาที่รอ เพื่อลดภาระเครื่องสเปกต่ำ
- ตรวจทุก Share Sheet window ที่อยู่ใน allowlist และค้นหา PHANToM GO ด้วย fast text lookup
- จำกัดและเว้นระยะ Share retry ป้องกันการกดซ้ำเร็วเกินไป
- ยืนยันความสำเร็จเมื่อ `ShareReceiverActivity` รับ payload จริง ไม่ใช้เพียงการหายไปของ Share Sheet
- รองรับ shared payload ที่เข้าทาง `onNewIntent`
- เพิ่ม unit tests สำหรับ timeout, route readiness, adaptive polling และ Share retry

## การตรวจสอบ

- `testDebugUnitTest`: ผ่าน
- `lintDebug`: ผ่าน
- `assembleDebug`: ผ่าน
- `assembleRelease`: ผ่าน
- `git diff --check`: ผ่าน
- ติดตั้งและทดสอบบน iQOO V2408A, Android 15: ผ่าน
- เส้นทางปกติส่งพิกัด `13.7563,100.5018` ถูกต้อง
- เส้นทางที่สองซึ่งเริ่มในช่วง cooldown ถูก queue และส่งพิกัดใหม่ `13.81,100.56` ถูกต้อง
- Share Sheet เลือก PHANToM GO ภายใน 54-111 ms หลังพร้อม และไม่มี Share retry ในการทดสอบ
- เปิดหน้าแอปและตรวจ log แล้ว ไม่พบ crash หรือ ANR

## APK

- Package: `com.phantom.carnavrelay`
- Version: `1.0.20 (23)`
- File: `PHANToM_GO-v1.0.20.apk`
- APK ใช้ Android debug signer เพื่อให้ติดตั้งอัปเดตทับ release เดิมได้
- SHA-256: `68EDA7CFBC9FEE88F43255D2E0858AABB03647204D6229560ACF6D990DF1C467`

## ข้อควรทราบ

- Auto Share ต้องเปิด Notification access และ PHANToM GO Accessibility Service
- ระบบจะไม่กด Share หากยังไม่มีหลักฐานว่า route พร้อม เพื่อให้ความถูกต้องมาก่อนการแชร์เร็วเกินไป
- รุ่นนี้ไม่ต้องแก้หรือ publish Firebase Rules เพิ่ม
