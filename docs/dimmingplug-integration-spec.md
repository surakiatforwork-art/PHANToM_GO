# DimmingPlug Integration Spec for PHANToM GO Display

เอกสารนี้ใช้กำหนดสเปกสำหรับการรวมความสามารถจากโปรเจกต์ `DimmingPlug` เข้ามาใน `PHANToM GO` ฝั่ง `Display`

สถานะเอกสาร:
- ใช้สำหรับวางแผนก่อนเริ่มแก้ไขไฟล์
- ยังไม่ใช่ implementation
- ใช้เป็น baseline สำหรับคอนเฟิร์ม scope ก่อนลงมือ merge

---

## 1. เป้าหมาย

รวมความสามารถด้าน `display power / brightness / wake / lock automation` จาก `DimmingPlug` เข้ามาใน `PHANToM GO` ฝั่ง `Display` เพื่อให้แอปควบคุมลำดับการทำงานได้ใน process เดียว

เป้าหมายหลัก:
- เมื่อฝั่ง `Display` ได้รับ route / navigation จากเครื่อง `Main`
  - ปลุกหน้าจอ
  - ปรับความสว่างตามโปรไฟล์
  - ทำให้เครื่องอยู่ใน state พร้อมใช้งาน
  - ค่อยเปิดลิงก์ Google Maps
- ลดปัญหา race condition ที่เกิดจากการใช้สองแอปแยกกัน
- ทำให้ logic ทั้งหมดอยู่ภายใต้ `DisplayServerService` ของ `PHANToM GO`

---

## 2. เหตุผลที่ต้องรวม

ปัญหาปัจจุบันจากการใช้ `PHANToM GO` + `DimmingPlug` แยกกัน:
- route arrival กับ screen wake เป็นคนละ process
- บางครั้ง intent เปิด Google Maps ทำงานก่อนที่จอจะสว่างและพร้อมใช้งาน
- ผู้ใช้จึงต้องแชร์ route ซ้ำจากเครื่อง `Main`

เมื่อรวมเข้ามาใน `PHANToM GO`:
- แอปเดียวควบคุมลำดับทั้งหมดได้
- ลด race condition
- ลดจำนวน permission flow ที่ผู้ใช้ต้องจัดการหลายแอป
- debug ง่ายกว่า
- UI/UX ของฝั่ง Display สอดคล้องกันมากขึ้น

---

## 3. Source of Truth

### 3.1 โปรเจกต์ต้นทาง

โปรเจกต์ต้นทางที่ใช้ศึกษา:

`C:\Project_winsurf\Automagics\DimmingPlug`

ไฟล์สำคัญ:
- `README.md`
- `app/src/main/java/com/phantom/dimmingplug/FixedFlow.java`
- `app/src/main/java/com/phantom/dimmingplug/FlowEngine.java`
- `app/src/main/java/com/phantom/dimmingplug/SystemActions.java`
- `app/src/main/java/com/phantom/dimmingplug/PowerStateReceiver.java`
- `app/src/main/java/com/phantom/dimmingplug/MapNotificationListenerService.java`
- `app/src/main/java/com/phantom/dimmingplug/AppState.java`
- `app/src/main/java/com/phantom/dimmingplug/PermissionStatus.java`

### 3.2 โปรเจกต์ปลายทาง

ฝั่งที่ต้องถูกรวมคือ:

`C:\Project_winsurf\PHANToM GO`

ไฟล์หลักฝั่ง Display:
- [DisplayServerService.kt](/C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/DisplayServerService.kt)
- [DisplayScreenFragment.kt](/C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/DisplayScreenFragment.kt)
- [DisplayActivity.kt](/C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/DisplayActivity.kt)
- [PrefsManager.kt](/C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/PrefsManager.kt)
- [PermissionSetupActivity.kt](/C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/PermissionSetupActivity.kt)
- [SettingsScreenFragment.kt](/C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/SettingsScreenFragment.kt)
- [DisplayAntiTheftManager.kt](/C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/DisplayAntiTheftManager.kt)

---

## 4. พฤติกรรมจาก DimmingPlug ที่ต้องการเก็บไว้

### 4.1 เมื่อมีไฟเข้าอุปกรณ์

Trigger:
- AC connected
- USB connected
- wireless charging connected

Behavior ที่ต้องพิจารณารวม:
- ปรับ brightness ตามช่วงเวลา
- ปลุกหน้าจอ
- พยายาม dismiss เฉพาะ swipe/none keyguard แบบ best-effort

### 4.2 เมื่อไฟออกจากอุปกรณ์

Trigger:
- AC disconnected
- USB disconnected
- wireless charging disconnected

Behavior ที่ต้องพิจารณารวม:
- ปรับ brightness ลง
- lock device

### 4.3 เมื่อ route / navigation มาถึง

เดิมใน DimmingPlug ใช้:
- Notification listener
- จับ notification จาก package `com.phantom.carnavrelay`

ใน `PHANToM GO` ไม่ต้องใช้วิธีนี้

เหตุผล:
- `PHANToM GO` รู้ event route arrival อยู่แล้วภายใน [DisplayServerService.kt](/C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/DisplayServerService.kt)
- จึงควรเรียก wake/brightness flow โดยตรงจากภายใน ไม่ใช่ไปฟัง notification ของตัวเอง

---

## 5. ขอบเขตที่ “ไม่เอา” จาก DimmingPlug

สิ่งที่ตัดออกจากแผนรวม:
- `NotificationListenerService`
- `MapNotificationListenerService`
- `AppState` แบบแอปแยก
- หน้า `MainActivity` ของ DimmingPlug
- `AutomationService` แบบ standalone
- `screen timeout = Never`

ข้อกำหนดล็อกแล้ว:
- **ห้ามเอา logic `set screen timeout = never` เข้ามา**
- ไม่ว่าจะกรณี:
  - power connected
  - power disconnected
  - navigation received

เหตุผล:
- ผู้ใช้ยืนยันแล้วว่าไม่ต้องการ behavior นี้
- ไม่จำเป็นต่อการแก้ปัญหา race condition ระหว่าง wake screen กับ open maps

---

## 6. สรุปทิศทางสถาปัตยกรรม

### 6.1 แนวทางที่ถูกต้อง

ย้ายเฉพาะ `behavior + system actions` เข้ามาใน `PHANToM GO`

ไม่ย้ายทั้งแอปแยก

### 6.2 Host หลักของระบบ

ระบบใหม่ควรผูกอยู่กับ:

[DisplayServerService.kt](/C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/DisplayServerService.kt)

เพราะ service นี้:
- รันในฝั่ง Display อยู่แล้ว
- รับ route จาก Main อยู่แล้ว
- คุมการเปิด Google Maps อยู่แล้ว
- มี foreground lifecycle ที่เหมาะกับ runtime receiver

### 6.3 โมดูลใหม่ที่ควรมี

แนะนำให้เพิ่ม subsystem ใหม่:
- `DisplayPowerAutomationManager`
- `DisplaySystemActions`
- `DisplayPowerReceiver`

และใช้ `PrefsManager` เก็บ config/state

---

## 7. Behavior ใหม่ที่ต้องเป็นจริงหลังรวม

### 7.1 Navigation Receive Pipeline

เมื่อฝั่ง Display ได้ route ใหม่จาก Main:

1. รับ route ใน `DisplayServerService`
2. เช็ก state ของ `anti-theft`
3. ถ้า anti-theft อยู่ใน state ที่ block อยู่:
   - ห้ามเปิด Google Maps ทันที
   - ต้องรอจนระบบ security อนุญาต
4. ถ้าไม่ถูก block:
   - เช็กก่อนว่าเครื่อง `พร้อมเปิด Google Maps ทันที` หรือไม่
   - ถ้าพร้อมแล้ว:
     - เปิด Google Maps ทันที
   - ถ้ายังไม่พร้อม:
     - ปลุกหน้าจอ
     - ปรับ brightness ตาม profile
     - พยายามให้เครื่องพร้อมใช้งาน
     - หน่วงสั้นแบบ deterministic
     - ค่อยเปิด Google Maps
5. mark command ว่า opened หลังจาก launch maps flow เริ่มจริง

### 7.1.1 Ready State Definition

คำว่า `จอพร้อมอยู่แล้ว` สำหรับ fast path ต้องครบทั้งสองข้อ:

1. `หน้าจอเปิดอยู่และไม่ได้ล็อก`
2. `ความสว่างหน้าจออยู่ในระดับที่ถูกต้องตามช่วงเวลาที่ตั้งไว้แล้ว`

การตีความเชิง implementation สำหรับรอบแรก:
- `หน้าจอเปิดอยู่` หมายถึงระบบอยู่ในสถานะ interactive / screen on
- `ไม่ได้ล็อก` หมายถึงไม่มี keyguard ที่ block การใช้งานอยู่ในขณะนั้น
- `ความสว่างถูกต้องแล้ว` หมายถึงค่า brightness ปัจจุบันตรงกับ profile ที่ควรเป็นในช่วงเวลานั้น หรืออยู่ใน tolerance เล็กน้อยที่ไม่จำเป็นต้องเขียนทับซ้ำ

ตัวอย่าง:
- ถ้าเวลานั้นอยู่ในช่วง `day profile` และหน้าจอเปิดใช้งานอยู่แล้ว พร้อม brightness day อยู่แล้ว -> เปิด Google Maps ทันที
- ถ้าหน้าจอเปิดอยู่แต่ brightness ยังเป็นค่ากลางคืน -> ยังไม่ถือว่าพร้อม ต้องเข้า preparation flow ก่อน
- ถ้าหน้าจอเปิดอยู่แต่ยังติด keyguard -> ยังไม่ถือว่าพร้อม ต้องเข้า preparation flow ก่อน

ถ้าครบทั้งสองข้อ:
- ห้ามหน่วงเพิ่มโดยไม่จำเป็น
- ห้าม wake screen ซ้ำ
- ห้ามเขียน brightness ซ้ำโดยไม่จำเป็น
- ให้เปิด Google Maps ทันที

ถ้าขาดข้อใดข้อหนึ่ง:
- ถือว่า `ยังไม่พร้อม`
- ต้องเข้า wake/brightness preparation flow ก่อน

### 7.2 Power Connected Pipeline

เมื่ออุปกรณ์เสียบไฟ:
- ปรับ brightness ตาม time profile
- ปลุกหน้าจอ
- พยายาม dismiss swipe/none keyguard แบบ best-effort
- **ไม่แตะ screen timeout**

### 7.3 Power Disconnected Pipeline

เมื่ออุปกรณ์ถอดไฟ:
- ปรับ brightness ตาม policy disconnect
- lock device ถ้า permission พร้อม
- **ไม่แตะ screen timeout**

---

## 8. Brightness Policy

จาก DimmingPlug เดิม:
- 18:30 ถึง 04:59 -> brightness `125`
- เวลาอื่น -> brightness `255`
- unplug -> brightness `0`

สำหรับรอบแรกของการ merge:
- ใช้ policy เดิมนี้ได้ก่อน
- แต่ต้องวางให้เป็น config ใน `PrefsManager` หรืออย่างน้อย constant รวมศูนย์

ค่ารอบแรกที่แนะนำ:
- `nightBrightness = 125`
- `dayBrightness = 255`
- `disconnectedBrightness = 0`
- `nightStart = 18:30`
- `nightEnd = 05:00`

---

## 9. Screen Wake Policy

สิ่งที่ควรทำ:
- ปลุกหน้าจอด้วย wake flow แบบ best-effort
- ใช้แนวทางสมัยใหม่เท่าที่ทำได้ใน Android ปัจจุบัน
- ถ้าจำเป็นค่อย fallback ไปยังแนวทางเก่าบางส่วน

สิ่งที่ไม่ควรสัญญา:
- dismiss secure PIN/pattern/password ได้ทุกเครื่อง
- ปลุกหน้าจอและเปิด Maps โดยไม่มี OEM variance เลย

ข้อกำหนด:
- ถ้า keyguard เป็น `Swipe / None` ให้พยายาม dismiss
- ถ้า keyguard เป็น secure keyguard:
  - ปลุกจอได้
  - แต่ต้องยอมรับว่าอาจ dismiss ไม่ได้

### 9.1 Fast Path vs Preparation Path

ระบบต้องมี 2 เส้นทางชัดเจน:

#### Fast Path

ใช้เมื่อ:
- จอเปิดอยู่
- เครื่องไม่ได้ล็อก
- brightness ถูกต้องอยู่แล้วตามช่วงเวลา

ผลลัพธ์:
- เปิด Google Maps ทันที
- ไม่เรียก wake flow ซ้ำ
- ไม่เรียก brightness update ซ้ำ
- ไม่หน่วง readiness window เพิ่ม

#### Preparation Path

ใช้เมื่อ:
- จอดับ
- หรือเครื่องยังล็อกอยู่
- หรือ brightness ยังไม่ตรง profile

ผลลัพธ์:
- wake screen
- ปรับ brightness
- best-effort dismiss swipe/none keyguard
- รอ readiness window สั้น ๆ
- ค่อยเปิด Google Maps

### 9.2 Readiness Evaluation Order

ทุกครั้งที่ route ใหม่มาถึง ระบบต้องประเมินตามลำดับนี้:

1. route ใหม่ถูกส่งเข้ามาที่ `DisplayServerService`
2. เช็กก่อนว่ามี anti-theft state ที่ block อยู่หรือไม่
3. ถ้าไม่ถูก block ให้ประเมิน `ready state`
4. ถ้า `ready state = true`
   - เปิด Google Maps ทันที
5. ถ้า `ready state = false`
   - เข้า preparation path

ข้อกำหนดเพิ่ม:
- ห้ามบังคับให้ทุก route ผ่าน preparation path เสมอ
- ห้ามหน่วงแบบคงที่ ถ้าจอพร้อมอยู่แล้วจริง
- fast path ต้องเป็นเส้นทางหลักสำหรับกรณีที่จอเปิดค้างใช้งานอยู่แล้ว

---

## 10. Lock Policy on Power Disconnect

เงื่อนไข:
- ใช้ `Device Admin` เท่านั้น
- ถ้าไม่ได้เปิด Device Admin:
  - ข้าม lock
  - แต่ยังทำ brightness flow ได้

ข้อกำหนด:
- ต้อง lock แบบ local เท่านั้น
- ไม่เกี่ยวกับ Firebase
- ไม่เกี่ยวกับ Main device

---

## 11. Priority Rules

ลำดับความสำคัญของระบบหลังรวม:

1. `Anti-theft fullscreen lock`
2. `Security verification challenge`
3. `Navigation receive wake/brightness pipeline`
4. `Power disconnect dim/lock`
5. `Speedometer/location update`

ความหมาย:
- ถ้า anti-theft อยู่ใน state lock/challenge ห้าม navigation flow เปิด Maps แซง
- power disconnect ห้ามทำให้ anti-theft flow เสีย state
- speed/location เป็น observer layer ไม่ใช่ controller layer

---

## 12. การชนกันของระบบที่ต้องป้องกัน

### 12.1 Route มาในจังหวะจอเพิ่งดับ

เป้าหมาย:
- route ใหม่ต้องไม่ถูกยิงไป Maps ก่อน wake/brightness เสร็จ

วิธี:
- รวม flow ใน `DisplayServerService`
- ใช้ ordered pipeline

### 12.2 เสียบ/ถอดไฟขณะ anti-theft กำลัง challenge

เป้าหมาย:
- power automation ห้ามรีเซ็ตหรือกลบ anti-theft UI

วิธี:
- anti-theft state มี priority สูงกว่า

### 12.3 Route update เข้ามาถี่

เป้าหมาย:
- ไม่เปิด Maps ซ้ำแบบถี่เกินจน UX พัง

วิธี:
- ต้องมี debounce / replace policy สำหรับ navigation launch

### 12.4 Device admin ไม่พร้อม

เป้าหมาย:
- ระบบยังใช้งานต่อได้แบบ degrade gracefully

วิธี:
- lock device เป็น optional branch
- ถ้าไม่มี admin ให้ log และ skip

---

## 13. Permission Model ที่ต้องเพิ่มใน PHANToM GO

จาก DimmingPlug มี permission/capability ที่เกี่ยวข้อง:
- `WRITE_SETTINGS`
- `WAKE_LOCK`
- `RECEIVE_BOOT_COMPLETED`
- `Device Admin / force-lock`

สำหรับรอบแรกของการรวม:

ควรเพิ่ม/เปิดใช้:
- `WRITE_SETTINGS`
- `Device Admin`

ยังไม่จำเป็นต้องเปิด scope นี้ทันที:
- `RECEIVE_BOOT_COMPLETED`

เหตุผล:
- รอบแรกเน้นแก้ `navigation ordering`
- ยังไม่จำเป็นต้อง auto-restore flow หลัง reboot

---

## 14. UI / Settings ที่ควรเพิ่ม

ควรอยู่ในหน้า `Settings` ฝั่ง Display

หัวข้อใหม่ที่แนะนำ:
- `Display Power Automation`

ตัวเลือกขั้นต่ำ:
- `Enable Display Power Automation`
- `Use day/night brightness profile`
- `Brightness at night`
- `Brightness in daytime`
- `Brightness on power disconnect`
- `Lock device on power disconnect`
- `Wake screen before opening Maps`

สถานะ permission ที่ต้องแสดง:
- `Modify system settings`
- `Device administrator`

---

## 15. Permission Onboarding ที่ควรเพิ่ม

ใน [PermissionSetupActivity.kt](/C:/Project_winsurf/PHANToM%20GO/app/src/main/java/com/phantom/carnavrelay/PermissionSetupActivity.kt)

ควรเพิ่ม:
- ตรวจ `WRITE_SETTINGS`
- ปุ่มเปิดหน้า `Manage system settings`
- ตรวจ `Device Admin`
- ปุ่มเปิดหน้า add device admin

สิ่งที่ไม่ควรเพิ่มในรอบแรก:
- notification listener access

เหตุผล:
- ไม่ได้ใช้แล้วในสถาปัตยกรรมใหม่

---

## 16. Firebase Impact

สเปกนี้ **ไม่ต้องเพิ่ม Firebase schema ใหม่**

เหตุผล:
- automation นี้เป็น local display behavior
- trigger หลักมาจาก:
  - power state ในเครื่อง
  - route arrival ที่มีอยู่แล้วใน display service

ดังนั้น:
- ไม่ต้องเพิ่ม Firebase rules ใหม่
- ไม่ต้องแก้ Firebase console สำหรับงานนี้

ยกเว้นในอนาคตถ้าต้องการ:
- ให้ Main เห็นสถานะ charging/disconnected
- ให้ Main สั่ง override brightness
- ให้ Main เห็น state ของ power automation แบบ realtime

กรณีนั้นค่อยออกแบบ Firebase เพิ่มภายหลัง

---

## 17. ลำดับการ merge ที่แนะนำ

### Phase 1

ย้ายเฉพาะ navigation wake pipeline:
- เมื่อ route มาถึง
- wake screen
- set brightness
- wait short readiness window
- open Google Maps

### Phase 2

เพิ่ม power receiver:
- power connected
- power disconnected

### Phase 3

เพิ่ม settings + permission onboarding:
- write settings
- device admin

### Phase 4

ค่อยพิจารณา behavior เสริม:
- boot restore
- advanced profiles
- charging type specific logic

---

## 18. สิ่งที่ต้อง “ไม่ทำ” ระหว่าง merge

- ไม่ใช้ `NotificationListenerService`
- ไม่ copy app แยกเข้ามาทั้งก้อน
- ไม่เพิ่ม `screen timeout = Never`
- ไม่ให้ power automation ไปแทรก anti-theft fullscreen flow
- ไม่ให้ navigation เปิด Maps แซง wake/brightness

---

## 19. Test Checklist สำหรับรอบ implement

### Navigation ordering
- route มาในตอนหน้าจอดับ -> จอต้องตื่นก่อนแล้วค่อยเปิด Maps
- route มาในตอนหน้าจอล็อกแบบ swipe -> ต้องเปิด flow ได้
- route มาในตอน screen already on -> ต้องไม่ delay เกินจำเป็น

### Power automation
- เสียบไฟ -> brightness ถูก profile
- ถอดไฟ -> brightness ลงตาม policy
- ถอดไฟ + มี device admin -> lock device
- ถอดไฟ + ไม่มี device admin -> skip lock แบบไม่ crash

### Interaction with anti-theft
- anti-theft challenge อยู่ -> route ใหม่ต้องไม่กลบ challenge
- anti-theft fullscreen lock อยู่ -> Maps ต้องไม่เปิดแซง
- power event ระหว่าง anti-theft -> state ไม่พัง

### Settings / permissions
- write settings ยังไม่ได้ grant -> แสดงคำอธิบายชัด
- device admin ยังไม่ได้เปิด -> แสดงคำอธิบายชัด

---

## 20. Final Scope Lock

สรุป scope ที่ล็อกไว้สำหรับงานนี้:

- รวม `DimmingPlug` เข้า `PHANToM GO` เฉพาะฝั่ง Display
- ใช้ `DisplayServerService` เป็น host หลัก
- ไม่ใช้ notification listener
- ใช้ route event ภายในระบบโดยตรง
- ต้อง wake screen + set brightness ก่อนเปิด Google Maps
- รองรับ power connected / disconnected automation
- ไม่ใช้ `screen timeout = Never`
- ไม่ต้องแก้ Firebase console

---

## 21. ข้อสรุปพร้อมเริ่มงาน

หลังจากตรวจซ้ำกับโค้ดทั้งสองโปรเจกต์แล้ว แนวทางที่ถูกต้องที่สุดคือ:

- **ย้าย logic**
- **ไม่ย้ายแอป**
- **ผสานเข้ากับ DisplayServerService**
- **ใช้ navigation event เดิมของ PHANToM GO**
- **ตัด timeout-never ออกทั้งระบบ**

เอกสารนี้พร้อมใช้เป็นจุดคอนเฟิร์มก่อนเริ่มแก้ไขไฟล์จริง
