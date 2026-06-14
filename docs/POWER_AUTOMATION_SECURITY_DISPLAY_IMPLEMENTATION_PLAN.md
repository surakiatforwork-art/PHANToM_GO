# PHANToM GO - Power Automation, Anti-Theft, Display UI Implementation Plan

วันที่เริ่มงาน: 2026-06-14

## เป้าหมาย

ปรับระบบฝั่ง Display ให้ตรวจจับการเสียบ/ถอดไฟและสาย Jack 3.5 ได้สม่ำเสมอ ทำงานได้แม้หน้าจอดับ ปลุกหน้าจอโดยไม่ดึง PHANToM GO ขึ้นมาทับแอปเดิม และจัดหน้า Display/Security ให้ตรงกับหน้าที่ของแต่ละหน้า

งานรอบนี้ไม่แก้ Firebase Rules และไม่แตะโค้ดภายใน `ios/`

## ขอบเขตการแก้ไข

### 1. Display Power Automation

- รวมการรับ `ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED` และ `ACTION_BATTERY_CHANGED` ผ่าน coordinator กลาง
- เก็บสถานะเสียบไฟล่าสุดแบบถาวร เพื่อกู้ event ที่ ROM พลาดหรือ process ถูกสร้างใหม่
- ตรวจสถานะไฟซ้ำเป็นระยะขณะ Display service ทำงาน เพื่อรองรับ ROM ที่ส่ง broadcast ไม่สม่ำเสมอ
- เมื่อถอดไฟ ให้เรียก Device Admin lock แล้วตรวจผลจริง หากจอยังเปิดให้ลองล็อกซ้ำแบบจำกัดจำนวนครั้ง
- เมื่อเปิด/ปิด Power Automation ให้เริ่มหรือหยุด monitor และ reset state อย่างชัดเจน
- เพิ่ม log ที่บอกแหล่ง event, สถานะก่อน/หลัง, ผลการ wake/lock และเหตุผลที่ skip

### 2. Wake Screen โดยไม่เปลี่ยนแอปที่แสดงอยู่

- แยก Activity โปร่งใสสำหรับปลุกจอออกจาก `MainActivity`
- Activity สำหรับปลุกจอต้องไม่มี UI, ไม่อยู่ใน Recent apps และปิดตัวเองทันที
- การเสียบไฟต้องไม่บังคับเปิดหน้า Display ของ PHANToM GO
- การปลุกก่อนเปิด Google Maps ยังคงใช้ ordered navigation pipeline เดิม

### 3. Anti-Theft Background Reliability

- เมื่อ Jack 3.5 ถูกถอดครั้งแรกขณะระบบ armed ให้ส่งแจ้งเตือนไป Main ทันที
- จากนั้นยังคง challenge/countdown/alarm flow ตามเดิม
- ทำให้ตัวตรวจจับ Jack อยู่กับ Display foreground service และทำงานแม้หน้าจอดับ
- ใช้ partial wake lock เฉพาะช่วงประมวลผล event/countdown/alarm ที่จำเป็น
- ตรวจและกู้ state หลัง process/service กลับมาทำงาน โดยไม่ลดความปลอดภัย
- Anti-theft challenge/alarm มี priority สูงกว่า power automation และ navigation launch

### 4. Display UI

- ก่อนจับคู่: แสดง QR card ขนาดใหญ่ และซ่อน speedometer
- หลังจับคู่: ซ่อน QR card และแสดง speedometer ขนาดใหญ่แทน
- ย้ายสถานะเชื่อมต่อไว้ใต้ความเร็ว ให้มีขนาดเล็กและเหลือข้อมูลจำเป็น
- ทำเลขความเร็ว responsive รองรับหน้าจอหลายขนาดและเลขสามหลัก
- จัดเลขให้อยู่กึ่งกลาง ไม่ถูกตัดในแนวตั้ง และวางหน่วยโดยไม่แย่งพื้นที่เลข
- นำการ์ด Anti-Theft ออกจากหน้า Display

### 5. Security UI

- ย้าย Arm/Disarm, ตั้ง PIN, สถานะ Jack และการควบคุม Anti-Theft ไปหน้า Security
- ฝั่ง Display แสดง Security controls และสถานะ permission/readiness
- ฝั่ง Main ยังคง Find Display, ตำแหน่งล่าสุด, แผนที่ และสถานะแจ้งเตือน
- Settings เก็บเฉพาะการตั้งค่าเชิงลึกและ permission ที่เกี่ยวข้อง

## Priority Rules

1. Anti-theft fullscreen lock / alarm
2. Anti-theft challenge และการแจ้ง Main
3. Navigation receive pipeline
4. Power connect/disconnect automation
5. Speedometer และ location sync

## State และ Recovery

- Power monitor ต้องจำสถานะ plugged ล่าสุดและเวลาที่ตรวจพบ
- การเปิด monitor ใหม่ต้อง seed baseline โดยไม่สร้าง wake/lock event ปลอม
- หาก baseline เดิมเป็น plugged แต่สถานะจริงปัจจุบันเป็น unplugged ให้กู้ disconnect event ที่พลาด
- Anti-theft ต้องจำ state, armed status, headset status และ deadline ที่สำคัญ
- เมื่อ service กลับมา ต้อง resume flow ที่ยังไม่จบหรือยกระดับ alarm ตามเวลาที่ผ่านไป

## Test Checklist

### Power Automation

- เปิด/ปิดสวิตช์ซ้ำแล้วกลับมาทำงานทันที
- เสียบ/ถอดไฟอย่างน้อย 30 รอบ
- ถอดไฟขณะ PHANToM GO อยู่หน้า Home, Display, Settings, แอปอื่น และจอดับ
- เสียบไฟขณะอยู่หน้า Home และแอปอื่น ต้องกลับมาหน้าเดิม
- ถอดไฟขณะ Device Admin ปิด ต้องไม่ crash และแสดงสถานะชัด
- anti-theft active ต้องไม่ถูก power automation กลบ

### Anti-Theft

- ถอด Jack ครั้งแรกต้องส่ง Main alert ทันที
- ทำงานเมื่อจอเปิด จอดับ และแอปอยู่เบื้องหลัง
- challenge/countdown/alarm ทำงานต่อหลัง service/process recovery
- เสียบ Jack คืน, PIN ถูก, PIN ผิด และ alarm flow ทำงานตามสเปก

### Display / Security UI

- ยังไม่จับคู่: QR ใหญ่และไม่มี speedometer
- จับคู่แล้ว: speedometer ใหญ่และไม่มี QR
- เลข `0`, `99`, `100`, `199` ไม่ถูกตัดและอยู่กึ่งกลาง
- สถานะเชื่อมต่ออ่านง่ายแต่ไม่แย่งจุดนำสายตา
- Anti-theft controls ใช้งานได้ครบจากหน้า Security

### Regression

- Firebase pairing และ command listener
- Share route และเปิด Google Maps
- Find Display และ location sync
- Main/Display mode switching
- Bottom navigation และ Settings

## Release Checklist

- รัน `testDebugUnitTest`
- รัน `lintDebug`
- รัน `assembleDebug`
- รัน `assembleRelease`
- ติดตั้งและทดสอบบนอุปกรณ์ ADB ถ้ามี
- ตรวจ versionName/versionCode และชื่อ APK
- รวมไฟล์ docs ที่ผู้ใช้แก้ไขไว้ใน commit
- Push branch `main`
- สร้าง GitHub stable release พร้อม APK และ release notes

## สถานะ

- [x] วิเคราะห์ปัญหาและสร้างแผน
- [x] แก้ Power Automation
- [x] แก้ wake behavior
- [x] แก้ Anti-Theft background flow
- [x] ปรับ Display UI
- [x] ปรับ Security UI
- [x] Build และทดสอบ
- [x] Push และสร้าง release
