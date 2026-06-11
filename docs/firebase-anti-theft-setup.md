# Firebase Setup for Display Anti-Theft Alerts

เอกสารนี้ใช้สำหรับเปิดทางให้เครื่อง `Display` ส่ง `anti-theft alert` ไปยังเครื่อง `Main` ผ่าน Firebase Realtime Database

## สิ่งที่ต้องแก้ใน Firebase Console

ต้องแก้แค่ `Realtime Database Rules`

ถ้า `cloud pairing` และ `cloud relay` ใช้งานได้อยู่แล้ว แปลว่า:
- Firebase project ใช้งานได้แล้ว
- Anonymous Authentication ใช้งานได้แล้ว
- ไม่ต้องสร้าง app ใหม่
- ไม่ต้องแก้ Authentication เพิ่มสำหรับงานนี้

## ไฟล์ rules ที่ต้องใช้

ใช้ไฟล์นี้เป็นตัวจริง:

`docs/firebase-realtime-database.rules.json`

## Step-by-step

1. เปิด Firebase Console
2. เข้าโปรเจกต์ `PHANToM GO`
3. ไปที่ `Build`
4. ไปที่ `Realtime Database`
5. เปิดแท็บ `Rules`
6. สำรอง rules เดิมไว้ก่อน
7. เปิดไฟล์ในโปรเจกต์ `docs/firebase-realtime-database.rules.json`
8. คัดลอกเนื้อหาทั้งหมดในไฟล์นั้น
9. วางทับ rules เดิมทั้งหมดใน Firebase Console
10. กด `Publish`

## หลัง Publish แล้วควรเช็กอะไร

1. เครื่อง `Main` และ `Display` ยัง pair กันได้ตามเดิม
2. การส่ง Google Maps / route relay เดิมยังใช้งานได้
3. เมื่อมี anti-theft event จากเครื่อง `Display` จะมีข้อมูลถูกเขียนใต้ path นี้:

`securityAlerts/byMain/{mainDeviceId}/{alertId}`

## โครงสร้าง path ใหม่

path ใหม่ที่เพิ่มคือ:

`securityAlerts/byMain/{mainDeviceId}/{alertId}`

ความหมาย:
- `Display` เป็นคนเขียน alert ไปให้ `Main`
- `Main` เป็นคนอ่าน alert ของตัวเอง
- หลัง `Main` รับแล้ว แอปจะลบ alert node นั้นออก

## ถ้า Publish แล้วขึ้น permission denied

ให้เช็กตามนี้:

1. rules ที่วางเป็นชุดล่าสุดจากไฟล์ `docs/firebase-realtime-database.rules.json` จริง
2. เครื่อง `Main` และ `Display` pair กันอยู่จริง
3. ทั้งสองเครื่องยัง sign in Firebase anonymous ได้ปกติ
4. path `pairings/byMain/{mainDeviceId}` มีข้อมูล `mainAuthUid`, `displayAuthUid`, `displayDeviceId`

## หมายเหตุสำคัญ

- งานนี้ `ไม่ใช้ path commands เดิม` สำหรับ anti-theft
- งานนี้แยก path ใหม่เพื่อไม่ให้ชนกับ flow ส่งแผนที่เดิม
- ถ้า rules ยังไม่ publish เวอร์ชันใหม่ ฟังก์ชัน anti-theft alert จะเขียน Firebase ไม่ได้
