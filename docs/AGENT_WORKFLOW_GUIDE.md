# PHANToM GO - คู่มือคำสั่งงานสำหรับ Agent

เอกสารนี้กำหนดความหมายและขั้นตอนมาตรฐานเมื่อเจ้าของโปรเจกต์สั่งงาน agent เพื่อให้ทุกคนทำงานในรูปแบบเดียวกัน ลดความเสี่ยงจากการ push ไฟล์ผิด การสร้าง release ที่ติดตั้งไม่ได้ หรือการแก้ไขส่วนที่ไม่ได้รับอนุญาต

## 1. หลักการสำคัญของโปรเจกต์

- ตรวจสอบโค้ดและสถานะ Git ก่อนแก้ไขเสมอ
- ห้ามลบ ย้อนกลับ หรือ stage ไฟล์ที่ผู้ใช้กำลังแก้เองและไม่เกี่ยวกับงาน
- ห้ามใช้ `git add -A` หรือ `git add .` เมื่อ working tree มีไฟล์อื่นปะปน
- ต้อง stage เฉพาะไฟล์ที่เกี่ยวข้องโดยระบุ path ชัดเจน
- ห้ามแก้ไขส่วน `ios/` เว้นแต่ผู้ใช้สั่งให้ทำงานฝั่ง iOS โดยตรง
- การแก้ Android ต้องรักษา pairing, Firebase relay, Display mode, anti-theft และ permission flow เดิม
- ก่อนส่งมอบงานต้องตรวจสอบ build และทดสอบส่วนที่แก้จริง
- ถ้ามีอุปกรณ์ ADB เชื่อมต่ออยู่ ให้ติดตั้งและตรวจ log บนอุปกรณ์จริงเมื่อเหมาะสม
- ห้ามอ้างว่าทดสอบผ่าน หากยังไม่ได้รันการทดสอบนั้นจริง

## 2. ความหมายของคำสั่งที่ใช้บ่อย

### “ตรวจสอบ” หรือ “เช็ค”

หมายถึง:

1. อ่านโค้ดและ flow ที่เกี่ยวข้องก่อน
2. ตรวจ log, setting, permission หรือสถานะอุปกรณ์ที่เกี่ยวข้อง
3. ระบุต้นเหตุจากหลักฐาน ไม่เดาเพียงอย่างเดียว
4. ยังไม่แก้ไขไฟล์ หากผู้ใช้ระบุชัดว่าให้วิเคราะห์หรือวางแผนก่อน

### “แก้ไข”

หมายถึง:

1. ตรวจต้นเหตุและผลกระทบ
2. แก้เฉพาะส่วนที่จำเป็น
3. ไม่ rewrite ฟังก์ชันเดิมที่ทำงานอยู่
4. Build, lint และทดสอบ regression ที่เกี่ยวข้อง
5. แจ้งไฟล์ที่แก้ ผลทดสอบ และข้อจำกัดที่ยังเหลือ

### “ติดตั้ง”

หมายถึง:

1. ตรวจว่า ADB เห็นอุปกรณ์ด้วย `adb devices`
2. Build APK ล่าสุดก่อนติดตั้ง
3. ใช้ `adb install -r` เพื่อรักษาข้อมูลและการตั้งค่าเดิม ถ้าไม่จำเป็นต้องล้างข้อมูล
4. เปิดแอปและตรวจ log หลังติดตั้ง
5. ห้าม uninstall แอปโดยพลการ เพราะอาจทำให้ pairing, PIN, Device Admin และค่าตั้งค่าหาย

### “Push”

หมายถึงการนำ source code ที่ตรวจสอบแล้วขึ้น GitHub แต่ยังไม่จำเป็นต้องสร้าง Release

ขั้นตอนมาตรฐาน:

1. รัน `git status --short --branch`
2. อ่าน diff และยืนยันว่าไม่มีไฟล์อื่นปะปน
3. รันการตรวจสอบที่เกี่ยวข้อง
4. Stage เฉพาะไฟล์ของงานด้วย `git add <path>`
5. ตรวจ staged diff ด้วย `git diff --cached --check` และ `git diff --cached`
6. Commit ด้วยข้อความสั้นที่อธิบายผลลัพธ์
7. Push ไป branch ที่ตกลงไว้ โดยปกติโปรเจกต์นี้ใช้ `main`
8. ตรวจว่า local commit และ `origin/main` ตรงกัน

คำว่า Push ไม่ได้หมายถึง:

- สร้าง GitHub Release
- อัปโหลด APK
- เปลี่ยน version
- แก้ Firebase Console

เว้นแต่ผู้ใช้สั่งเพิ่มอย่างชัดเจน

### “Push และสร้าง Release”

หมายถึงทำทั้งการเผยแพร่ source code และสร้างหน้า GitHub Release พร้อม APK ที่ติดตั้งได้จริง

ถ้าผู้ใช้ไม่ระบุว่าเป็น Beta ให้สร้าง **Stable Release** รุ่นถัดไป

ขั้นตอนมาตรฐาน:

1. ตรวจ release และ tag ล่าสุด
2. เลือก version ใหม่ตาม Semantic Versioning
3. เพิ่ม `versionCode` ทุกครั้ง และแก้ `versionName`
4. อัปเดตลิงก์ดาวน์โหลดตรงใน README และ Landing Page ให้ชี้ version ใหม่
5. Build และตรวจสอบ APK
6. ตรวจ package name, version และลายเซ็น APK
7. ตั้งชื่อไฟล์ APK เป็น `PHANToM_GO-vX.Y.Z.apk`
8. คำนวณ SHA-256
9. Commit และ push source code
10. สร้าง tag และ GitHub Release จาก commit ที่ push แล้ว
11. อัปโหลด APK ที่ติดตั้งได้จริงเป็น release asset
12. ตรวจลิงก์ดาวน์โหลดตรงและสถานะ Latest Release

ตัวอย่างชื่อ:

```text
Tag: v1.0.14
Release title: PHANToM GO v1.0.14
APK: PHANToM_GO-v1.0.14.apk
Direct download:
https://github.com/Phantomguy7845/PHANToM_GO/releases/download/v1.0.14/PHANToM_GO-v1.0.14.apk
```

### “สร้าง Beta Release”

หมายถึง release สำหรับทดลองก่อน Stable:

- ใช้ tag เช่น `v1.0.15-beta.1`
- เปิดสถานะ GitHub Pre-release
- APK ใช้ชื่อ เช่น `PHANToM_GO-v1.0.15-beta.1.apk`
- ไม่เปลี่ยนลิงก์ Stable Download บน Landing Page ไปหา beta เว้นแต่ผู้ใช้สั่ง
- Release note ต้องระบุสิ่งที่ต้องทดสอบและ known limitations

### “Release ปกติ” หรือ “Stable Release”

หมายถึง:

- ไม่เป็น Draft
- ไม่เป็น Pre-release
- เป็น Latest Release
- APK ต้องติดตั้งได้จริง
- หน้าเว็บและ README ต้องชี้ไปยัง Stable รุ่นนี้

## 3. Versioning

รูปแบบ version ใช้ `MAJOR.MINOR.PATCH`

- `PATCH`: แก้บัคหรือปรับปรุงโดยไม่เปลี่ยน flow หลัก เช่น `1.0.13` เป็น `1.0.14`
- `MINOR`: เพิ่มฟีเจอร์ใหม่ที่ผู้ใช้เห็น แต่ยังรองรับของเดิม เช่น `1.0.14` เป็น `1.1.0`
- `MAJOR`: เปลี่ยน architecture หรือ compatibility สำคัญ เช่น `1.x.x` เป็น `2.0.0`
- `versionCode` ต้องเพิ่มขึ้นเสมอทุก APK ที่ต้องการให้อัปเดตทับเวอร์ชันเดิม

## 4. Android Verification Checklist

คำสั่งพื้นฐานบน Windows:

```powershell
.\gradlew.bat assembleDebug assembleRelease lintDebug testDebugUnitTest --stacktrace
```

รายการที่ต้องตรวจ:

- Build สำเร็จ
- Lint ไม่มี Error ที่หยุด release
- Unit-test task ผ่าน แม้บางครั้งจะแสดง `NO-SOURCE`
- APK มี package `com.phantom.carnavrelay`
- Version ตรงกับ release
- APK มีลายเซ็นและติดตั้งได้
- ฟังก์ชันที่แก้มีหลักฐานจาก log หรือการทดสอบจริง

ติดตั้ง debug APK โดยรักษาข้อมูลเดิม:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

ดู log หลัก:

```powershell
adb logcat -d -v time PHANTOM_GO:D '*:S'
```

## 5. Git Safety Checklist

ก่อน commit:

```powershell
git status --short --branch
git diff --check
git diff
```

ก่อน push:

```powershell
git diff --cached --check
git diff --cached
git log -1 --oneline
```

หลัง push:

```powershell
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
```

ถ้ามี untracked หรือ modified file ที่ไม่เกี่ยวข้อง ต้องปล่อยไว้และห้ามรวมเข้า commit

## 6. Release Notes ที่ดีต้องมีอะไร

- สรุปปัญหาเดิม
- อธิบายสิ่งที่แก้ในภาษาที่ผู้ใช้เข้าใจ
- ระบุผลกระทบต่อผู้ใช้
- ระบุวิธีทดสอบที่รันจริง
- ระบุข้อจำกัดหรือ fallback ที่ยังมี
- ระบุ SHA-256 ของ APK

## 7. Firebase Console

- การ push Git ไม่ได้ publish Firebase Rules ให้อัตโนมัติ
- ถ้ามีการเปลี่ยน `firebase-realtime-database.rules.json` ต้องแจ้งผู้ใช้ชัดเจนว่าต้อง publish rules
- ถ้าไม่จำเป็นต้องแก้ Firebase ห้ามให้ผู้ใช้เปลี่ยน rules หรือเปิด `.read/.write` เป็น `true`
- ห้ามใส่ secret, service-account key หรือข้อมูลส่วนตัวลง Git

## 8. รูปแบบรายงานหลังทำงานเสร็จ

Agent ควรรายงานอย่างน้อย:

1. แก้ไขอะไรและต้นเหตุคืออะไร
2. ทดสอบอะไรผ่านบ้าง
3. Commit และ tag ที่สร้าง
4. ลิงก์ GitHub Release
5. ลิงก์ดาวน์โหลด APK โดยตรง
6. SHA-256 ของ APK
7. สิ่งที่ไม่ได้แตะหรือข้อจำกัดที่ยังเหลือ

## 9. ข้อควรระวังเฉพาะ PHANToM GO

- Display service เป็นแกนกลางของ cloud command listener, speedometer, display location และ power automation
- Anti-theft ต้องมี priority สูงกว่าการเปิดแผนที่และ power automation
- การจับคู่ใหม่แทนที่คู่เดิม จึงต้องระวังข้อมูล pairing ทั้ง Main และ Display
- Browser Web App มีข้อจำกัดมากกว่า APK และไม่ควรถูกอธิบายว่าทำงานเบื้องหลังได้เท่ากัน
- Android แต่ละแบรนด์อาจจำกัด background execution ต่างกัน ต้องทดสอบบนอุปกรณ์จริงเมื่อแก้ service หรือ receiver
- ห้ามแตะ `ios/` จนกว่าผู้ใช้จะสั่งให้ทำงานฝั่ง iOS อีกครั้ง
