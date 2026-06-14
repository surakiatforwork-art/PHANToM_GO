# PHANToM GO v1.0.19

## สรุป

รุ่นนี้ปรับความน่าเชื่อถือของระบบจัดการพลังงานฝั่ง Display และระบบกันขโมย พร้อมจัดหน้า Display/Security ให้ข้อมูลสำคัญเด่นตามสถานการณ์ใช้งานจริง

## สิ่งที่เปลี่ยน

- Power Automation ตรวจการเสียบ/ถอดไฟจาก broadcast และ watchdog พร้อมจำ state เพื่อกู้ event ที่ ROM ส่งพลาด
- ถอดไฟแล้วตรวจผล Device Admin lock และลองซ้ำหากหน้าจอยังเปิดอยู่
- เปิด/ปิด Power Automation ใหม่แล้ว reset state และสั่งฟื้น Display service ได้เอง
- เสียบไฟจะปลุกจอผ่าน Activity โปร่งใส โดยไม่เปิด PHANToM GO หรือเปลี่ยนหน้าที่ผู้ใช้เปิดค้างไว้
- Display service รักษา command listener และ power watchdog ขณะจอดับตลอดอายุ Display mode
- Anti-theft ส่งแจ้งเตือนไป Main ทันทีเมื่อถอด Jack 3.5 ครั้งแรกขณะ armed
- Anti-theft รักษาการตรวจจับ/countdown/alarm ขณะจอดับ และกู้ state หลัง service กลับมาทำงาน
- ก่อนจับคู่หน้า Display แสดง QR ขนาดใหญ่; หลังจับคู่จะแสดง speedometer ขนาดใหญ่แทน
- ปรับเลขความเร็วให้รองรับเลขสามหลัก จัดกึ่งกลาง และปรับขนาดตามพื้นที่
- ย้ายการควบคุม Anti-theft จากหน้า Display ไปหน้า Security
- รวมการปรับปรุงเว็บไซต์และเอกสารที่ผู้ใช้จัดทำไว้

## การตรวจสอบ

- `compileDebugKotlin`: ผ่าน
- `testDebugUnitTest`: ผ่าน
- `lintDebug`: ผ่าน
- `assembleDebug`: ผ่าน
- `assembleRelease`: ผ่าน
- `git diff --check`: ผ่าน
- ไม่มีอุปกรณ์ ADB เชื่อมต่อในรอบ release นี้ จึงยังไม่ได้ทดสอบ plug/unplug และ Jack 3.5 บนอุปกรณ์จริง

## APK

- Package: `com.phantom.carnavrelay`
- Version: `1.0.19 (22)`
- File: `PHANToM_GO-v1.0.19.apk`
- APK ใช้ Android debug signer เพื่อให้ติดตั้งอัปเดตทับ release เดิมได้
- SHA-256: `2B19262588AD946BD9ED6B3DF55C910214A002321784516BD1A36D53868F56B7`

## ข้อควรทราบ

- Power Automation ต้องเปิด Display mode และ Device Admin สำหรับการล็อกเมื่อถอดไฟ
- Anti-theft ต้องเปิด Display mode, ตั้ง PIN, เสียบ Jack 3.5 และ Arm ระบบ
- Persistent partial wake lock เพิ่มความน่าเชื่อถือขณะจอดับ แต่จะใช้แบตเตอรี่มากขึ้นเมื่อ Display mode หรือ Anti-theft ทำงาน
- รุ่นนี้ไม่ต้องแก้หรือ publish Firebase Rules เพิ่ม
