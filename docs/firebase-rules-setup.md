# Firebase Realtime Database Rules

ใช้ไฟล์ [firebase-realtime-database.rules.json](/C:/Project_winsurf/PHANToM%20GO/docs/firebase-realtime-database.rules.json) เป็นชุด Rules สำหรับโปรเจกต์นี้

## วิธีนำไปใช้

1. เปิด Firebase Console
2. ไปที่ `Build > Realtime Database > Rules`
3. สำรอง Rules เดิมไว้ก่อน
4. คัดลอกเนื้อหาจากไฟล์ `docs/firebase-realtime-database.rules.json` ไปวาง
5. กด `Publish`

## สิ่งที่ Rules ชุดนี้ทำ

- บังคับให้ทุก path หลักต้องมี `auth != null`
- จำกัด `devices` และ `presence` ให้เจ้าของเครื่องเขียนเอง
- อนุญาตให้ Main อ่าน `presence` ของ Display ที่จับคู่กับตัวเองเท่านั้น
- อนุญาตให้ Display สร้าง `pairingCodes` ของตัวเอง และให้ Main ที่สแกน QR อัปเดตได้เฉพาะ metadata การ claim
- จำกัด `pairings` ให้แก้ได้เฉพาะ Main ที่สร้าง pair หรือ Display เจ้าของเครื่อง
- จำกัด `commands` ให้สร้างได้เฉพาะ Main ที่ถูก pair กับ Display นั้น และให้แก้สถานะได้เฉพาะ Display เจ้าของเครื่อง

## ก่อน Publish

ให้ติดตั้ง build ล่าสุดทั้ง `Main` และ `Display` ก่อน เพราะ payload ใหม่เพิ่มฟิลด์:

- `displayAuthUid` ใน `pairings`
- `fromMainAuthUid` ใน `commands`

ถ้ายังมีข้อมูลเก่าจาก build ก่อนหน้าอยู่ในฐานข้อมูล อาจล้าง node เก่าเหล่านี้ก่อนทดสอบ:

- `pairingCodes`
- `pairings`
- `commands`
