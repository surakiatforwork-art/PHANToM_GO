# PHANToM GO - App Overview

อัปเดตสำหรับ Stable Release `v1.0.13`

## แอปนี้คืออะไร

PHANToM GO เป็นระบบส่งปลายทางและเส้นทางจากอุปกรณ์หลักไปยังอุปกรณ์อีกเครื่องที่ใช้เป็นจอติดรถ โดยนำมือถือหรือแท็บเล็ต Android เครื่องเก่ากลับมาใช้ประโยชน์แทนการนำมือถือหลักไปตากแดด ตากฝน หรือทำงานหนักตลอดการเดินทาง

ระบบเหมาะกับผู้ขับรถทั่วไปและไรเดอร์ส่งอาหารที่ต้องเปิดแอปรับงานบนมือถือหลัก แต่ต้องการดูแผนที่บนจอแยก ลดความร้อนของเครื่องหลักและลดความสับสนเมื่อมีหลายงานหรือหลายปลายทาง

## องค์ประกอบของโปรเจกต์

### Android APK

- ใช้ APK เดียวเป็นได้ทั้ง Main Device และ Display Device
- มีหน้า `Home`, `Display`, `Settings` ภายใน Activity หลักเดียว
- จับคู่ด้วย QR code และ Firebase anonymous authentication
- ส่งเส้นทางผ่าน Firebase Cloud และใช้ local Wi-Fi เป็น fallback เมื่อมีข้อมูล local transport
- รับ Share จาก Google Maps หรือแอปอื่น
- รองรับ Map Link Hub สำหรับจัดการลิงก์แผนที่
- Display mode เปิด Google Maps ตามโหมดรถยนต์/มอเตอร์ไซค์และพฤติกรรมที่ตั้งไว้
- มีมาตรวัดความเร็วจากตำแหน่ง GPS
- มี Find Display พร้อมตำแหน่งล่าสุด
- มี Display Anti-Theft สำหรับ trigger สาย 3.5 มม.
- มีระบบปลุกจอ ปรับแสง และล็อกเครื่องเมื่อถอดไฟภายนอก

### Browser Web App

- อยู่ที่ `docs/web/webapp/index.html`
- มีหน้า Home, Display และ Settings ใน HTML เดียว
- จับคู่กับ APK หรือ browser อีกเครื่องผ่าน Firebase Cloud
- ฝั่ง Main วางลิงก์ Google Maps แล้วส่งไป Display ได้
- ฝั่ง browser Display แสดง popup เมื่อได้รับเส้นทางใหม่ และเปิด Maps ในแท็บใหม่
- browser ไม่สามารถปลุกหน้าจอ เปิดแอป Maps อัตโนมัติ หรือรับ notification แบบ APK ได้

### Landing Page

- อยู่ที่ `docs/web/index.html`
- อธิบายจุดเด่น วิธีใช้ วิดีโอ และภาพหน้าจอ
- มีปุ่มดาวน์โหลด stable APK และทางเข้า Web App

### iOS Parallel Project

- อยู่ที่ `ios/`
- มี SwiftUI scaffold, Firebase contract, route parser tests และ GitHub Actions
- ยังต้องยืนยันการ compile และ runtime บน macOS/iPhone/iPad จริง

## Workflow หลัก

### การจับคู่

1. เปิดหน้า Display บนอุปกรณ์จอติดรถและเปิด Display Mode
2. Display สร้าง QR payload และ publish pairing code ไป Firebase
3. Main เปิด Home แล้วสแกน QR
4. Main claim pairing ด้วย anonymous auth และบันทึกอุปกรณ์ Display
5. Display ซ่อน QR และเปลี่ยนเป็นสถานะ Connected
6. หากต้องการเปลี่ยนคู่ ให้ Disconnect/Reset หรือ Clear Pairing ก่อน

### การส่งเส้นทาง

1. ผู้ใช้เลือก Share จาก Google Maps หรือแอปอื่นไปที่ PHANToM GO
2. แอป normalize ลิงก์หรือข้อความตำแหน่ง
3. Main ส่งคำสั่ง Cloud ไปยัง Display ที่จับคู่ไว้
4. Display ตรวจ anti-theft และความพร้อมของหน้าจอ
5. ถ้าจอพร้อม เปิด Maps ทันที
6. ถ้าจอยังไม่พร้อม ปลุกจอและปรับแสงก่อนเปิด Maps
7. คำสั่งเส้นทางใหม่ล่าสุดแทนคำสั่งเก่า ลดการเปิดปลายทางซ้ำ

### ลำดับความสำคัญของระบบ

1. Anti-Theft challenge/alarm/full-screen lock
2. คำสั่งเส้นทางล่าสุดที่รอเปิด
3. Display readiness: ปลุกจอและปรับแสง
4. เปิด Google Maps และ overlay

## Display Anti-Theft

- แสดงเฉพาะเมื่ออุปกรณ์รองรับและตรวจพบ trigger สาย 3.5 มม.
- ผู้ใช้ต้องตั้ง PIN และเปิดระบบเอง
- เมื่อถอดสาย ระบบแสดง `Security Verification`
- หากไม่ใส่ PIN, ใส่ผิด หรือหมดเวลา ระบบส่ง alert พร้อมตำแหน่งล่าสุดไป Main
- ระบบสามารถเล่น alarm, เร่งระดับเสียง และยกระดับเป็น full-screen lock
- ขณะ anti-theft กำลังทำงาน ระบบจะพักการเปิด Maps และการล็อกจาก power disconnect เพื่อไม่ให้หน้าป้อน PIN หายไป

## Power Automation

- ทำงานเฉพาะเมื่ออุปกรณ์อยู่ในบทบาท Display
- เมื่อเสียบไฟ ใช้ profile ความสว่างกลางวัน/กลางคืนและปลุกจอแบบ best effort
- เมื่อถอดไฟ หรี่จอและล็อกเครื่องหากเปิดตัวเลือกและให้ Device Admin แล้ว
- ตัวรับเหตุการณ์ power อยู่ใน Manifest จึงยังทำงานได้แม้ OEM ปิด Display service ชั่วคราว
- หาก process เปิดใหม่ในบทบาท Display แอปจะร้องขอเริ่ม Display service กลับอัตโนมัติ

## Permissions ที่ใช้

- Camera: สแกน QR
- Notifications: แสดงสถานะ service, route และ anti-theft alert
- Fine/Coarse Location: วัดความเร็วและตำแหน่ง Display
- Background Location: ติดตามความเร็ว/ตำแหน่งขณะ Display mode ทำงานเบื้องหลัง
- Overlay: แสดงปุ่มลอยเหนือแอปอื่นเมื่อผู้ใช้เปิดใช้
- Modify System Settings: ปรับความสว่างอัตโนมัติ
- Device Admin: ล็อกเครื่องเมื่อถอดไฟภายนอก
- Ignore Battery Optimization: ช่วยให้ Display service ทำงานต่อเนื่องบน OEM ที่ปิด background service รุนแรง

สิทธิ์เสริมทั้งหมดสามารถข้ามและกลับมาตั้งภายหลังได้ ยกเว้นฟีเจอร์ที่พึ่งพาสิทธิ์นั้นโดยตรง

## การเก็บข้อมูล

- แอปใช้ Firebase anonymous auth จึงไม่ต้องสมัครบัญชี
- Firebase เก็บ device record, presence, pairing code, pairing relation, route commands และ security alerts
- อุปกรณ์เก็บ pairing peer, token, settings, pending routes, last command และข้อมูลสถานะที่จำเป็นใน local preferences
- PIN กันขโมยเก็บเป็น hash พร้อม salt ไม่เก็บ PIN แบบข้อความตรง
- Android Backup ถูกปิดเพื่อไม่สำรอง token จับคู่และข้อมูลความปลอดภัยออกนอกเครื่อง

## UX/UI

- Bottom navigation เดิมคงอยู่ระหว่างหน้า Home, Display และ Settings
- ปุ่มของหน้าปัจจุบันถูกปิดไม่ให้กดซ้ำ
- เนื้อหาแต่ละหน้ามี bottom padding เพื่อไม่ให้ปุ่มถูก dock บัง
- หน้า Display เน้น QR เมื่อยังไม่จับคู่ และเน้น speedometer เมื่อจับคู่แล้ว
- หน้า Settings เรียงจาก permissions, Display, Main และ logs
- ปุ่มสำคัญมี touch target ขนาดใหญ่และสถานะ disabled ชัดเจน

## ข้อจำกัดที่ต้องเข้าใจ

- Android/OEM แต่ละยี่ห้อจัดการ background service, full-screen intent และการปลุกหน้าจอไม่เหมือนกัน
- แอปปกติไม่สามารถกันผู้ใช้กด Home/Recent, Force Stop หรือถอนการติดตั้งได้สมบูรณ์เหมือน Device Owner/Kiosk
- secure lock screen ไม่สามารถถูกข้ามได้
- browser Display ไม่สามารถปลุกหน้าจอหรือเปิด native Maps อัตโนมัติได้
- Firebase Realtime Database ไม่มี TTL cleanup อัตโนมัติสำหรับ command/alert เก่า จึงควรวางแผน cleanup ฝั่ง backend ก่อนขยายผู้ใช้จำนวนมาก
- ฝั่ง iOS ยังอยู่ในสถานะ scaffold/remote-CI readiness ไม่ใช่ runtime-validated release

## Stable v1.0.13 Highlights

- แก้ Display Mode crash จาก LayoutParams cast
- ทำให้ lock-on-power-disconnect รับ event ได้แม้ Display service ถูก OEM ปิด
- ป้องกัน power automation ทำงานบน Main Device
- รักษาบทบาท Display เมื่อ service ถูกปิดโดยระบบ และเปลี่ยนกลับ Main เฉพาะผู้ใช้สั่งหยุด
- รักษาสถานะ anti-theft เมื่อ Display service หยุดแบบไม่คาดคิด
- กู้ Display service เมื่อ process เปิดใหม่
- ซ่อม permission onboarding ไม่ให้ข้ามขั้นตอนโดยไม่ตั้งใจ
- ป้องกัน pending route ซ้ำและแก้ race ระหว่าง retry callback
- เพิ่ม package visibility สำหรับ Google Maps
- แก้ Share receiver ให้คง MIME type/data ถูกต้อง
- ปรับ accessibility ของปุ่ม Home และภาพสำคัญ
- อัปเดต Landing Page ให้ชี้ stable release `v1.0.13`

ดูแผนภาพ workflow แบบ interactive ได้ที่ `docs/PHANTOM_GO_WORKFLOW.html`
