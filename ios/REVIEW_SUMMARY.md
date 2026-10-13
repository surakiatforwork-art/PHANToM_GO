# รีวิว PHANToM GO Native iOS

วันที่: 2026-10-13

## สิ่งที่แก้จาก scaffold

- เปลี่ยน Firebase adapter ที่ส่ง schema เก่าเป็น Firebase REST adapter ตาม schema/rules ของ APK ปัจจุบัน
- Auth ใช้ refresh token ใน Keychain และ shared Keychain แบบมี fallback ตาม signing capability
- ส่ง command ที่มี `url`, `fromMainAuthUid`, request ID และ retry จน Display ack จริง
- Receiver รองรับ put/patch ของ Firebase stream และ persistent dedupe; bounded pending-command cache
- Main/Display listeners หยุดและ reconnect ตาม lifecycle และหยุด command observer เมื่อปิด Display mode
- อ่าน pairing ปัจจุบันก่อน clear และหมุน local token เมื่อ disconnect เพื่อไม่รับ token ของคู่เก่า
- GPS/LAN/power เริ่มแยกจาก cloud availability เพื่อให้คู่ที่มีข้อมูลอยู่แล้วใช้ LAN ได้เมื่อ internet ขาด
- Camera QR และ dashcam session configure/start/stop บน serial camera queue ไม่ทำ synchronous camera startup บน UI thread
- Map URL ไม่เอาลิงก์ทั้งเส้นมาใส่เป็น destination และไม่ใช้ viewport เป็นปลายทาง
- ย้าย Security ไปแท็บแยก เพิ่ม Find Display MapKit และคง speedometer เฉพาะหลัง pairing
- สีใช้ semantic system colors พร้อม action button ที่กำหนด foreground/background ให้มี contrast ชัด
- แก้ bundle version metadata ให้ตรง MARKETING_VERSION/CURRENT_PROJECT_VERSION ทั้ง Main และ extension
- แก้ alarm จาก wrong PIN ตอนขอ disarm ให้ tick ทำงานแม้ไม่มี jack removal timestamp และลด alarm event ที่ส่งซ้ำ

## ความเสี่ยง/ข้อจำกัดที่ยังมี

1. พฤติกรรม Maps, GPS, camera, jack, audio routing และ background ต้องยืนยันบนเครื่องจริง
2. iOS จะ suspend แอปได้ ไม่สามารถให้ความมั่นใจแบบ Android foreground service
3. การรับ security alerts/route ใน background ยังไม่มี APNs/backend notification service
4. ไม่สามารถ force speaker/system volume หรือป้องกัน force-stop/uninstall ของ iOS
5. Share one-tap ต้องมี shared Keychain entitlement ที่ signing รองรับ; Copy/Paste เป็น fallback
6. Local iOS Main ยังไม่มี subnet discovery ครบชุดแบบ APK; IP เปลี่ยนขณะออฟไลน์อาจต้องกลับ online อ่าน presence ใหม่
7. Route delivery สามารถยืนยันว่า Display รับแล้ว แต่ไม่ได้รับประกันว่า Google Maps เริ่ม turn-by-turn อัตโนมัติทุก URL/region
8. Dashcam rotate MovieFileOutput อาจมีช่วงต่อคลิปเล็กน้อย; ยังไม่มี multi-clip timeline/merge/GPU overlay เท่าชุด GO Cam ใน APK
9. Background GPS ใช้แบตเตอรี่ และต้องเป็น permission Always จริง
10. Firebase command retention/cleanup ยังต้องเป็น backend work ในอนาคตตามข้อจำกัดเดิมของโปรเจกต์
11. Local relay ใช้ HTTP ให้ตรงกับ APK เดิม token อยู่ใน QR/คำขอ จึงควรใช้ LAN ที่ไว้ใจได้และไม่เผยแพร่ pairing payload สาธารณะ
12. ต้องทดสอบการเปลี่ยนคู่ซ้ำหลายครั้งเพิ่มเติม: presence observer ของ peer เก่าอาจยัง retry จนเข้า background แม้ callback จะปฏิเสธข้อมูลของ peer ที่ไม่ใช่คู่ปัจจุบันแล้ว

## หลักฐาน

- ตรวจ YAML/project/build scripts ใน Windows ผ่าน
- ทดสอบ Firebase REST Anonymous Auth และ read path ที่ rules เดิมอนุญาต ผ่าน; ลบ auth test account แล้ว ไม่มีการแก้ rules/customer data
- มี device iPhoneOS build จาก Xcode runner แล้ว; release สุดท้ายต้องใช้ artifact ที่ version/build ตรงและผ่าน verifier
- Native binary commit `087b4d0`: device build, unit tests 25 รายการ และ UI tests รวม 6 รายการบน iPhone/iPad ผ่านทั้งหมด
- ผล simulator UI tests และ release artifacts ฉบับสุดท้ายบันทึกใน RELEASE_iOS_v1.0.0.md หลังตรวจเสร็จ
- ยังไม่มีการติดตั้ง/ทดสอบ runtime บน iPad/iPhone จริงในรอบนี้
- ตรวจภาพ Home/Settings/Display แนวตั้งจาก iPhone/iPad simulator: ตัวเลข 123 ครบ และปุ่มหลักมี contrast ชัด
- ภาพ landscape ของ XCTest มี rotation metadata/การจับภาพระหว่างเปลี่ยน orientation จึงไม่ใช้ภาพดังกล่าวยืนยันความสมบูรณ์ของ layout; ต้องทดสอบการหมุนจอบนเครื่องจริงด้วย
