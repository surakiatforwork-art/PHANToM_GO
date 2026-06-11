# Stable Release Review - v1.0.13

วันที่รีวิว: 2026-06-11

## ขอบเขต

- Android APK: pairing, route relay, Display service, anti-theft, power automation, permissions, UI navigation
- Firebase contract และ rules file ที่มีใน repo
- Browser Web App และ Landing Page
- iOS scaffold และ CI readiness
- build, lint, unit-test task และ release packaging

## ปัญหาที่แก้ก่อน Stable

1. แก้ power disconnect receiver ให้ไม่ผูกกับอายุของ Display service
2. ป้องกัน power automation ไปหรี่หรือล็อก Main Device
3. เปลี่ยนบทบาทกลับ Main เฉพาะเมื่อผู้ใช้สั่งหยุด Display
4. ไม่ disarm anti-theft เมื่อ Display service หยุดแบบไม่คาดคิด
5. กู้ Display service เมื่อ process เปิดใหม่ในบทบาท Display
6. ซ่อม permission onboarding ที่ข้าม Write Settings และ Device Admin เมื่อกดข้าม Overlay
7. แสดง permission onboarding ซ้ำจนผู้ใช้กด Continue เอง
8. ป้องกัน pending route ซ้ำ, จำกัด 25 รายการ และแก้ retry counter race
9. กัน status polling หน้า Home ซ้อนกัน
10. แก้การส่งต่อ Share intent ให้ไม่ทำ MIME type หาย
11. เพิ่ม Google Maps package visibility
12. ปิด Android Backup สำหรับ pairing token และข้อมูลความปลอดภัย
13. แก้ accessibility click และ content description ในหน้าที่ใช้งานจริง
14. ลบ Android CI workflow ที่ซ้ำกัน
15. เพิ่ม `.gitignore` สำหรับ generated build/cache
16. อัปเดต version เป็น `1.0.13 (14)` และอัปเดต Landing Page
17. นำ `.gradle`, `app/build` และ `local.properties` ออกจาก Git index
18. แก้ Main Pair view lookup ให้ fail พร้อมสาเหตุที่ชัดเจน
19. แก้ parser ให้รับ `mainName: null` จากอุปกรณ์หรือ client รุ่นอื่นได้อย่างปลอดภัย
20. อัปเดต README และสร้างเอกสารภาพรวม/Workflow แบบ interactive

## ผลการตรวจ

- `assembleDebug`: ผ่าน
- `assembleRelease`: ผ่าน
- `lintDebug`: ผ่านโดยไม่มี Error
- Lint ยังมี Warning 432 รายการ ส่วนใหญ่เป็น hardcoded text, unused/legacy resources, deprecated API และ Settings layout ที่ซับซ้อน ไม่มีรายการที่หยุด build
- `testDebugUnitTest`: task ผ่าน แต่โปรเจกต์ Android ยังไม่มี unit-test source จึงแสดง `NO-SOURCE`
- ตรวจ APK: `com.phantom.carnavrelay`, version `1.0.13 (14)`, ตรวจลายเซ็น v1/v2 ผ่าน
- ตรวจ manifest merge: receiver power event อยู่ใน APK และ Display activity ไม่เปิดให้แอปภายนอกเรียก
- ตรวจ static workflow: pairing, command listener, route launch, anti-theft priority และ power priority ไม่พบ conflict ใหม่
- ตรวจหน้า Workflow HTML, Landing Page และ Web App บน desktop/mobile viewport ผ่าน ไม่มี horizontal page overflow และปุ่มนำทางหลักแสดงถูกต้อง

## UI/UX Review

### จุดแข็ง

- Bottom navigation คงอยู่ใน Activity เดียวและไม่สร้างใหม่เมื่อสลับ Fragment
- ปุ่ม active tab ถูก disable
- Home แสดง pairing/connection/pending state และ disable action ที่ยังใช้ไม่ได้
- Display เปลี่ยน visual priority ระหว่าง QR กับ speedometer ตามสถานะ pairing
- Settings จัดกลุ่ม permissions, Display, Main และ logs ชัดเจน
- Security Verification ไม่แสดง branding หน้า Home และยกระดับ full-screen ได้

### หนี้งานที่ยังไม่รื้อใน Stable นี้

- `activity_settings.xml` มี view จำนวนมากและมี log ScrollView ซ้อน ScrollView; ใช้งานได้แต่ควรแยกเป็น section/child screen ในรุ่นถัดไป
- มี layout/resource legacy ที่ไม่ได้ใช้จาก flow รุ่นเก่า
- Android code บางส่วนยังใช้ deprecated APIs เพื่อรองรับอุปกรณ์รุ่นเก่าและตรวจ service/local network
- ภาษาบน UI ยังผสมไทย/อังกฤษหลายหน้า ควรย้าย hardcoded text ไป strings resources และทำ localization

## ข้อจำกัดการยืนยัน

- รอบนี้ไม่มีอุปกรณ์ USB เชื่อมต่อ จึงไม่สามารถทำ manual touch test, QR scan, jack trigger, power cable และ Maps launch บนอุปกรณ์จริงได้
- iOS ไม่สามารถ build/runtime test บน Windows ได้ ต้องยืนยันผ่าน macOS GitHub Actions และอุปกรณ์จริง
- Web App และ Landing Page ต้องยืนยันบน hosting จริงอีกครั้งหลัง deploy

## คำแนะนำหลัง Release

1. ทดสอบสองอุปกรณ์จริง: pairing, route send, route update ระหว่างนำทาง และ re-pair
2. ทดสอบ power disconnect หลังเปิด Display นานกว่า 30 นาที
3. ทดสอบ anti-theft ทุก escalation และตำแหน่งที่ส่งไป Main
4. วาง cleanup policy สำหรับ Firebase commands/security alerts ก่อนขยายผู้ใช้จำนวนมาก
5. เพิ่ม Android unit/instrumentation tests และแยก Settings screen เพื่อลดความซับซ้อน
