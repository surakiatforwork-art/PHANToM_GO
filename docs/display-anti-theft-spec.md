# PHANToM GO Display Anti-Theft Specification

Last updated: 2026-06-10  
Status: Planning only, not implemented

## 1. Purpose

เอกสารนี้กำหนดสเปกสำหรับฟังก์ชันกันขโมยฝั่ง `Display Device` ของ PHANToM GO โดยใช้การถอดสาย `3.5 mm analog headset jack` เป็นสัญญาณ trigger หลัก

เป้าหมายของฟังก์ชันนี้คือ:

- ตรวจจับการถอดสาย 3.5 mm ที่ผู้ใช้ตั้งใจใช้เป็นสาย trigger
- บังคับให้ผู้ที่ถือเครื่องอยู่ต้องใส่ PIN เพื่อยืนยันสิทธิ์
- แจ้งเตือนเครื่องหลักที่จับคู่ไว้เมื่อมีเหตุผิดปกติ
- เข้าสู่โหมด alarm และ full-screen lock ภายในแอปหากไม่สามารถยืนยันตัวตนได้

ฟังก์ชันนี้เป็น `anti-theft deterrent / tamper alarm` ไม่ใช่การล็อกระบบ Android ระดับ system

## 2. Product Scope

### 2.1 In scope

- ฟังก์ชันแสดงเฉพาะฝั่ง `Display`
- ใช้ได้เฉพาะอุปกรณ์ที่มีช่องเสียบ `3.5 mm analog jack`
- เปิด/ปิดฟังก์ชันจากหน้า Display และตั้งค่าเชิงลึกจาก Settings
- ตั้ง PIN, เปลี่ยน PIN, ตรวจ PIN
- Trigger เมื่อถอดสาย
- Trigger เมื่อเสียบสายกลับระหว่าง challenge แรก
- ส่ง alert ไปยัง Main device ที่จับคู่ไว้
- แนบตำแหน่งของเครื่อง Display ไปกับ alert หากหา location ได้
- เล่นเสียง alarm แบบวนซ้ำ
- เล่นเสียงพูดคำว่า `ขโมย` แบบวนซ้ำ
- ยกระดับเป็น full-screen PIN screen หลัง alarm เล่นครบ 30 วินาที
- ใช้ `local disarm` เท่านั้น

### 2.2 Out of scope

- ล็อกเครื่อง Android ทั้งระบบ
- ป้องกันการปิดเครื่อง, safe mode, force stop, uninstall
- รองรับ USB-C audio dongle
- รองรับ Bluetooth headset, HDMI audio, dock audio
- ใช้ Device Admin เป็นฐานของฟังก์ชัน v1
- บังคับเส้นทางเสียงไปลำโพงเครื่องได้ 100% ทุกยี่ห้อ
- บังคับ full-screen intent notification ได้ 100% ทุก Android version / OEM

## 3. Primary Design Decision

ฟังก์ชันนี้ยึด model ดังนี้:

- `3.5 jack only`
- `Display only`
- `Local disarm only`
- Alert ไป Main ทุกครั้งที่มีเหตุสำคัญ
- ถ้า location ใช้ได้ ต้องแนบ location ทุก alert
- ไม่อิง Device Admin ในเวอร์ชันแรก

## 4. Supported Environment

### 4.1 Device assumptions

- Display device เป็น Android phone/tablet ที่ติดตั้ง PHANToM GO
- มี `physical 3.5 mm analog headset jack`
- เปิด `Display Mode` ได้ตามปกติ
- ได้รับสิทธิ์ location ตามที่แอปใช้อยู่แล้วสำหรับ speedometer
- จับคู่กับ Main device แล้วอย่างน้อย 1 เครื่อง

### 4.2 OS constraints

- โปรเจกต์ปัจจุบัน target `Android 14 / targetSdk 34`
- การปลุกหน้าจอทำได้ผ่าน `Activity.setTurnScreenOn()` / `showWhenLocked`
- การ dismiss secure keyguard ไม่รับประกันว่าจะสำเร็จโดยไม่ให้ผู้ใช้ยืนยันตัวตน
- full-screen notification behavior ถูกจำกัดมากขึ้นบน Android 14+
- event `ACTION_HEADSET_PLUG` รับได้เฉพาะ receiver ที่ register ตอน runtime

## 5. Existing Project Integration Points

ฟีเจอร์นี้ควรต่อเข้ากับโครงสร้างเดิม ไม่สร้างระบบคู่ขนาน

### 5.1 Display UI

- `app/src/main/java/com/phantom/carnavrelay/DisplayScreenFragment.kt`
- `app/src/main/res/layout/activity_display.xml`

หน้าที่:

- แสดง card anti-theft เมื่อเข้าเงื่อนไข
- แสดงสถานะ `Ready / Armed / Challenge / Alarm`
- ให้ผู้ใช้กด arm/disarm
- เปิด PIN dialog / activity

### 5.2 Background runtime

- `app/src/main/java/com/phantom/carnavrelay/DisplayServerService.kt`

หน้าที่:

- register runtime receiver / audio route monitor
- ถือ state machine กลาง
- จัดการ timer
- จัดการเสียง alarm
- จัดการ escalation
- ส่ง alert ไป main

### 5.3 Settings

- `app/src/main/java/com/phantom/carnavrelay/SettingsScreenFragment.kt`
- `app/src/main/res/layout/activity_settings.xml`

หน้าที่:

- เปิด/ปิด feature globally
- ตั้ง PIN / เปลี่ยน PIN
- เลือกเสียง alarm
- ทดสอบเสียง
- เปิด/ปิดแนบ location

### 5.4 Persistent state

- `app/src/main/java/com/phantom/carnavrelay/PrefsManager.kt`

หน้าที่:

- เก็บ config
- เก็บ armed state
- เก็บ PIN hash
- เก็บ alarm session metadata

### 5.5 Main / Cloud delivery

- `app/src/main/java/com/phantom/carnavrelay/FirebaseManager.kt`
- `app/src/main/java/com/phantom/carnavrelay/NotificationHelper.kt`

หน้าที่:

- ส่ง anti-theft alert ไป Main device
- ทำ pending / retry ถ้าจำเป็น

## 6. User Experience Summary

### 6.1 Setup flow

1. ผู้ใช้เปิดหน้า Display
2. ถ้ามีสาย 3.5 mm เสียบอยู่ จะแสดง card `Anti-Theft`
3. ถ้ายังไม่เคยตั้ง PIN:
   - เมื่อกดเปิดใช้งาน ต้องบังคับตั้ง PIN ก่อน
4. ถ้าตั้ง PIN แล้ว:
   - กด `Arm`
   - ระบบเริ่ม monitor การถอดสาย

### 6.2 Normal armed flow

1. ขณะ armed ผู้ใช้ใช้งาน Display mode ตามปกติ
2. ถ้าสายยังเสียบอยู่ จะไม่มี popup
3. ถ้าสายถูกถอด:
   - เด้ง PIN challenge ครั้งแรก
   - มีเวลา 15 วินาที

### 6.3 Challenge outcomes

- ใส่ PIN ถูกภายในเวลา:
  - จบเหตุการณ์
  - กลับไป monitor ต่อ ถ้ายังเสียบสายอยู่
- ไม่ใส่ PIN ภายใน 15 วินาที:
  - ส่ง alert ไป Main
  - เริ่ม post-alert timer 15 วินาที
- ระหว่าง challenge ครั้งแรกมีการเสียบสายกลับ:
  - ถือว่า tamper
  - ส่ง alert ไป Main
  - เริ่ม post-alert timer 15 วินาที
- ใส่ PIN ผิดครั้งที่ 1:
  - ส่ง alert ไป Main ทันที
  - เปิด challenge รอบสอง
- ใส่ PIN ผิดครั้งที่ 2:
  - เริ่ม alarm ทันที

### 6.4 Alarm escalation

- เมื่อ alarm เริ่ม:
  - เล่นเสียงแจ้งเตือนที่เลือก
  - เล่นเสียงพูด `ขโมย`
  - พยายามเร่งเสียงซ้ำเป็นระยะ
- เมื่อ alarm เล่นครบ 30 วินาที:
  - เปิด full-screen PIN lock activity
  - ผู้ใช้ต้องใส่ PIN เพื่อหยุด alarm

## 7. Functional Requirements

## 7.1 Feature availability

ฟังก์ชันนี้ต้องแสดงเมื่อ:

- เครื่องอยู่ใน `Display mode`
- `DisplayServerService` ทำงาน
- ตรวจพบสาย 3.5 mm ตาม runtime monitor
- ฟังก์ชัน anti-theft global setting ถูกเปิดใช้งาน

ฟังก์ชันนี้ต้องซ่อนเมื่อ:

- ไม่ได้อยู่ใน Display mode
- ไม่พบสาย 3.5 mm
- ผู้ใช้ปิด feature globally

### 7.1.1 Product rule for 3.5 mm only

ฟังก์ชันนี้ต้องประกาศในเอกสารและ UI ว่า:

- รองรับเฉพาะ `physical 3.5 mm analog headset jack`
- ไม่รับประกันการทำงานบน USB-C audio dongle
- ไม่ใช้กับ Bluetooth / USB audio / HDMI / dock audio

## 7.2 PIN requirements

### 7.2.1 PIN format

- รองรับ PIN แบบตัวเลขเท่านั้น
- ความยาวเริ่มต้นแนะนำ `4-6 digits`
- ค่า default ที่แนะนำสำหรับ v1 คือ `4 digits minimum`

### 7.2.2 PIN storage

- ห้ามเก็บ PIN แบบ plain text
- ต้องเก็บแบบ:
  - `salt`
  - `hash`
  - `algorithmVersion`
- ถ้าเป็นไปได้ให้ใช้ Android Keystore ช่วยป้องกัน key material

### 7.2.3 PIN lifecycle

รองรับ:

- Set PIN ครั้งแรก
- Change PIN
- Verify PIN

ยังไม่รวมใน v1:

- Forgot PIN recovery
- Remote PIN reset จาก Main

## 7.3 Arming and disarming

### 7.3.1 Arm preconditions

จะ arm ได้ต่อเมื่อ:

- anti-theft global setting เปิดอยู่
- ตรวจพบสาย 3.5 mm
- มี PIN แล้ว
- Display service ทำงานอยู่

### 7.3.2 Disarm rule

- ใช้ `local disarm only`
- ถ้าผู้ใช้กดปิดฟังก์ชันขณะ armed:
  - ต้องใส่ PIN
- ไม่ต้องขอ PIN จาก Main

## 7.4 Trigger source

### 7.4.1 Primary trigger

ใช้ runtime monitor จาก wired headset events เป็นหลัก

Implementation target:

- register runtime receiver สำหรับ `ACTION_HEADSET_PLUG`
- เสริมด้วยการอ่าน current audio devices เพื่อช่วย confirm state ถ้าจำเป็น

### 7.4.2 Trigger interpretation

- `state = 1` = plugged
- `state = 0` = unplugged

### 7.4.3 Debounce

เพื่อกันสายหลวม:

- ทุก plug/unplug event ต้องผ่าน debounce `500-1000 ms`
- ค่าที่แนะนำสำหรับ v1: `750 ms`

## 7.5 First challenge window

เมื่อ armed และตรวจพบ unplug:

1. เปลี่ยน state เป็น `Challenge1`
2. ปลุกจอถ้าจอดับ
3. แสดง popup ใส่ PIN
4. เริ่ม countdown 15 วินาที

### 7.5.1 Challenge1 timeout

ถ้าหมดเวลา 15 วินาทีและยังไม่ใส่ PIN ถูก:

- ส่ง `THEFT_ALERT_TIMEOUT_1` ไป Main
- เปลี่ยน state เป็น `PostAlertCountdown`
- เริ่ม countdown อีก 15 วินาที

### 7.5.2 Re-plug during Challenge1

ถ้ามีการเสียบสายกลับในช่วง Challenge1:

- ห้ามถือว่าเหตุการณ์จบ
- ส่ง `THEFT_ALERT_REPLUG_DURING_CHALLENGE_1` ไป Main
- เปลี่ยน state เป็น `PostAlertCountdown`
- เริ่ม countdown 15 วินาที

## 7.6 Wrong PIN policy

### 7.6.1 Wrong PIN #1

เมื่อใส่ PIN ผิดครั้งแรก:

- ส่ง `THEFT_ALERT_WRONG_PIN_1` ไป Main ทันที
- เพิ่ม `wrongAttemptCount = 1`
- เปิด challenge รอบสอง
- challenge รอบสองควรมี timeout 15 วินาทีเท่ากัน

### 7.6.2 Wrong PIN #2

เมื่อใส่ PIN ผิดครั้งที่ 2:

- ส่ง `THEFT_ALERT_WRONG_PIN_2` ไป Main ทันที
- เริ่ม alarm ทันที

## 7.7 Alarm behavior

### 7.7.1 Alarm start conditions

Alarm ต้องเริ่มเมื่อเกิดกรณีใดกรณีหนึ่ง:

- Post-alert countdown ครบ 15 วินาที
- Wrong PIN ครั้งที่ 2

### 7.7.2 Alarm output

เมื่อ alarm เริ่ม:

- เล่นเสียง alarm ที่ผู้ใช้เลือก
- เล่นเสียงพูดคำว่า `ขโมย`
- loop ต่อเนื่อง
- เปิด vibration ถ้าเครื่องรองรับ
- พยายามเร่ง media/alarm volume เป็นค่าสูงสุดเป็นช่วงๆ

### 7.7.3 Alarm audio source

แนวทางที่ต้องใช้:

- ใช้ `AudioAttributes` แบบ alarm-oriented
- พยายาม route ไป built-in speaker หากทำได้
- ถ้า route บังคับไม่ได้ ให้ยังเล่นเสียงต่อโดยใช้ route ที่ระบบเลือก

ข้อกำหนดสำคัญ:

- ระบบต้องพยายามให้ได้ยินจากลำโพงเครื่อง
- แต่ต้องระบุในเอกสาร implement ว่าไม่รับประกัน 100% ทุก OEM/ทุก Android version

### 7.7.4 Volume reinforcement

ระหว่าง alarm:

- ทุก `2 วินาที` ให้พยายามตั้งระดับเสียง relevant stream เป็นค่าสูงสุด
- บันทึก log ถ้าการตั้งเสียงล้มเหลว

## 7.8 Full-screen lock escalation

### 7.8.1 Escalation threshold

เมื่อ alarm เล่นครบ `30 วินาที`

- ต้องเปิด full-screen lock activity
- activity นี้ต้อง:
  - `turnScreenOn = true`
  - `showWhenLocked = true`
  - เป็น task foreground
  - ซ่อน UI อื่นของแอปทั้งหมด

### 7.8.2 Full-screen lock behavior

หน้าจอนี้ต้อง:

- แสดงเฉพาะ:
  - หัวข้อเตือน
  - ช่องใส่ PIN
  - ปุ่มยืนยัน
- ไม่ให้ scroll เนื้อหา
- ไม่ให้ dismiss ด้วย back
- ไม่ให้แตะส่วนอื่นของแอป
- ถ้าใส่ PIN ผิด ให้ยังคงอยู่หน้าจอนี้

ข้อจำกัด:

- ไม่รับประกันการบล็อก Home / Recents / notification shade ทุกเครื่อง
- เป้าหมายคือบดบังภายในแอปและยึด focus ให้มากที่สุด ไม่ใช่ system kiosk mode

## 7.9 Correct PIN behavior

เมื่อใส่ PIN ถูก:

- หยุดเสียงทั้งหมด
- หยุด vibration
- ยกเลิก timer ทั้งหมด
- ปิด challenge / full-screen lock
- clear alarm session state

### 7.9.1 Re-arm behavior after correct PIN

ถ้าหลังปลดแล้วสายยังเสียบอยู่:

- กลับไป state `Armed`

ถ้าหลังปลดแล้วสายไม่เสียบ:

- ไป state `ReadyUnplugged`
- ห้าม re-arm ทันทีจนกว่าจะมีการเสียบสายและผู้ใช้ยืนยันสถานะ

## 7.10 Location payload requirement

ทุก alert ที่ส่งไป Main ต้องพยายามแนบ location ของ Display

### 7.10.1 Location source priority

1. fresh GPS/network location ณ ตอนเกิด event
2. last recent location snapshot ที่ยังไม่เก่าเกิน threshold
3. ถ้าไม่มี ให้ส่งสถานะ location unavailable

### 7.10.2 Freshness rule

ค่าที่แนะนำ:

- location ถือว่า usable ถ้า `age <= 30 seconds`

### 7.10.3 Required location payload fields

- `lat`
- `lng`
- `accuracyMeters`
- `provider`
- `capturedAt`
- `ageMs`
- `locationAvailable`

### 7.10.4 Privacy rule

- location ใช้เฉพาะตอนเกิด anti-theft event
- ต้องมีคำอธิบายใน settings ว่า alert anti-theft อาจส่ง location ไป Main device

## 7.11 Main device alert handling

เมื่อ Main ได้รับ anti-theft alert:

- ต้องแสดง notification ทันที
- ถ้า app อยู่ foreground ควรแสดง in-app alert banner/card ด้วย
- ควรแสดง:
  - ชื่อ display device
  - ประเภทเหตุการณ์
  - เวลา
  - location ถ้ามี
  - ปุ่มเปิด map ไปตำแหน่ง display

ประเภท event อย่างน้อย:

- `timeout_1`
- `replug_during_challenge_1`
- `wrong_pin_1`
- `wrong_pin_2`
- `alarm_started`
- `alarm_escalated_fullscreen`
- `disarmed_success`

## 7.12 Logging

ต้องมี local log สำหรับ debug และ support

แต่ละ event ต้องบันทึกอย่างน้อย:

- eventName
- stateBefore
- stateAfter
- timestamp
- jackState
- wrongAttemptCount
- locationAvailable
- alertDeliveryResult

## 8. State Machine

## 8.1 States

- `Disabled`
- `UnavailableNoJack`
- `Ready`
- `Armed`
- `Challenge1`
- `Challenge2`
- `PostAlertCountdown`
- `AlarmActive`
- `FullscreenLock`
- `ReadyUnplugged`

## 8.2 State descriptions

### Disabled

- feature global setting ปิดอยู่

### UnavailableNoJack

- feature เปิดอยู่ แต่ไม่พบสาย 3.5 mm

### Ready

- feature พร้อมใช้
- พบสาย 3.5 mm
- มี PIN
- ยังไม่ได้ arm

### Armed

- เริ่ม monitor unplug event

### Challenge1

- unplug detected
- popup PIN ครั้งแรก
- timer 15 วินาที

### Challenge2

- wrong PIN ครั้งแรกแล้ว
- popup PIN รอบสอง
- timer 15 วินาที

### PostAlertCountdown

- ส่ง alert ไป Main แล้ว
- รอ 15 วินาทีก่อนเริ่ม alarm

### AlarmActive

- เล่นเสียง alarm + speech loop

### FullscreenLock

- alarm เกิน 30 วินาที
- ยกระดับเป็น full-screen PIN screen

### ReadyUnplugged

- ปลด alarm สำเร็จ แต่ยังไม่มีสายเสียบกลับ

## 8.3 Transition summary

- `Disabled -> UnavailableNoJack`
  - feature opened, no jack
- `Disabled -> Ready`
  - feature opened, jack plugged, PIN exists
- `Ready -> Armed`
  - user taps Arm
- `Armed -> Challenge1`
  - unplug detected
- `Challenge1 -> Armed`
  - correct PIN and jack restored
- `Challenge1 -> ReadyUnplugged`
  - correct PIN but jack not restored
- `Challenge1 -> Challenge2`
  - wrong PIN #1
- `Challenge1 -> PostAlertCountdown`
  - timeout 15 sec
- `Challenge1 -> PostAlertCountdown`
  - replug during first challenge
- `Challenge2 -> Armed`
  - correct PIN and jack restored
- `Challenge2 -> ReadyUnplugged`
  - correct PIN but jack not restored
- `Challenge2 -> AlarmActive`
  - wrong PIN #2
- `Challenge2 -> AlarmActive`
  - timeout second window
- `PostAlertCountdown -> AlarmActive`
  - 15 sec elapsed
- `AlarmActive -> FullscreenLock`
  - alarm elapsed 30 sec
- `AlarmActive -> Armed`
  - correct PIN and jack restored
- `AlarmActive -> ReadyUnplugged`
  - correct PIN but jack not restored
- `FullscreenLock -> Armed`
  - correct PIN and jack restored
- `FullscreenLock -> ReadyUnplugged`
  - correct PIN but jack not restored
- `ReadyUnplugged -> Ready`
  - jack plugged again

## 9. Timing Table

| Event | Delay | Result |
|---|---:|---|
| Challenge1 timeout | 15 sec | send alert to main, move to PostAlertCountdown |
| Post-alert countdown | 15 sec | start alarm |
| Challenge2 timeout | 15 sec | start alarm |
| Volume reinforcement | every 2 sec | set volume to max again |
| Alarm escalation | 30 sec from alarm start | launch fullscreen lock |
| Jack debounce | 750 ms recommended | confirm state before transition |

## 10. Data Model

## 10.1 Prefs additions

Recommended keys in `PrefsManager`:

- `anti_theft_enabled`
- `anti_theft_pin_hash`
- `anti_theft_pin_salt`
- `anti_theft_pin_algo_version`
- `anti_theft_armed`
- `anti_theft_alarm_sound_uri`
- `anti_theft_speech_enabled`
- `anti_theft_location_attach_enabled`
- `anti_theft_last_known_jack_state`
- `anti_theft_last_state`
- `anti_theft_wrong_attempt_count`
- `anti_theft_active_session_id`
- `anti_theft_alarm_started_at`
- `anti_theft_last_alert_at`
- `anti_theft_last_alert_type`

## 10.2 Session model

Recommended runtime model:

```kotlin
data class AntiTheftSession(
    val sessionId: String,
    val state: AntiTheftState,
    val armedAt: Long?,
    val triggerAt: Long?,
    val wrongAttemptCount: Int,
    val alarmStartedAt: Long?,
    val fullscreenEscalatedAt: Long?,
    val jackState: JackState,
    val lastLocation: AntiTheftLocationSnapshot?
)
```

## 10.3 Location snapshot model

```kotlin
data class AntiTheftLocationSnapshot(
    val lat: Double?,
    val lng: Double?,
    val accuracyMeters: Float?,
    val provider: String?,
    val capturedAt: Long?,
    val ageMs: Long?,
    val available: Boolean
)
```

## 11. Firebase / Transport Model

## 11.1 Delivery strategy

Priority order:

1. Firebase cloud alert to paired Main device
2. local transport fallback if architecture allows
3. local persistence for retry/log if both fail

## 11.2 Recommended Firebase path

ควรแยก path ใหม่ ไม่ปนกับ navigation command เดิม

Recommended path:

- `securityAlerts/byMain/{mainDeviceId}/{alertId}`

Alternative mirror path:

- `securityAlerts/byDisplay/{displayDeviceId}/{alertId}`

## 11.3 Alert payload

```json
{
  "alertId": "string",
  "displayDeviceId": "string",
  "displayName": "string",
  "mainDeviceId": "string",
  "eventType": "timeout_1 | replug_during_challenge_1 | wrong_pin_1 | wrong_pin_2 | alarm_started | alarm_escalated_fullscreen | disarmed_success",
  "eventAt": 0,
  "sessionId": "string",
  "jackState": "PLUGGED | UNPLUGGED",
  "wrongAttemptCount": 0,
  "location": {
    "available": true,
    "lat": 0.0,
    "lng": 0.0,
    "accuracyMeters": 0.0,
    "provider": "gps",
    "capturedAt": 0,
    "ageMs": 0
  },
  "speedKmh": 0,
  "appVersion": "1.0.12",
  "platform": "android"
}
```

## 12. UI / UX Specification

## 12.1 Display screen card

บนหน้า Display ควรมี anti-theft card เฉพาะเมื่อพบสาย 3.5 mm

Card ต้องมี:

- title: `Anti-Theft`
- current state text
- Armed / Disarmed indicator
- ปุ่ม `Arm` หรือ `Disarm`
- ปุ่ม `Set PIN` หรือ `Change PIN`
- ข้อความเตือนสั้น:
  - `Works only with 3.5 mm jack trigger`

## 12.2 Challenge popup

Popup ครั้งแรกและครั้งที่สองต้อง:

- เด้งทันทีเมื่อ trigger
- มี countdown ชัดเจน
- มีช่อง PIN
- มีข้อความเตือนว่า:
  - ถ้าไม่ยืนยันจะส่งแจ้งเตือนไปเครื่องหลัก

## 12.3 Full-screen lock screen

หน้าจอ full-screen ต้อง:

- พื้นหลังเข้ม
- ตัวอักษรใหญ่ อ่านง่าย
- ใส่ PIN ได้เร็ว
- ไม่ซับซ้อน
- ไม่มีปุ่มอื่นนอกจาก confirm

## 12.4 Settings entries

ควรเพิ่มใน Display settings section:

- `Enable Display Anti-Theft`
- `Set / Change PIN`
- `Attach location to theft alerts`
- `Alarm sound`
- `Speech loop ("ขโมย")`
- `Test alarm`

## 13. Android Platform Constraints

## 13.1 Headset trigger constraint

การรับ event wired headset:

- ต้อง register receiver ตอน runtime
- ห้ามพึ่ง manifest-declared receiver สำหรับ event นี้

## 13.2 Wake / lockscreen constraint

สามารถ:

- ปลุกหน้าจอ
- แสดง activity ทับ lock screen ได้

ไม่รับประกัน:

- dismiss secure lock screen โดยไม่ให้ผู้ใช้ยืนยันตัวตน

## 13.3 Audio routing constraint

ระบบต้องพยายาม route ไป built-in speaker แต่:

- OEM บางราย override audio routing
- บางรุ่นอาจยัง route ไปอุปกรณ์ audio ที่ระบบถือว่า active
- ห้ามอ้างใน product copy ว่า guaranteed 100%

## 13.4 Full-screen intent constraint

Android 14+ จำกัดการใช้ full-screen notification มากขึ้น:

- ห้ามพึ่ง full-screen notification เป็นแกนหลักของการ lock flow
- ควรใช้ explicit activity launch ภายใน app flow เป็นหลัก

## 14. Security and Privacy Requirements

## 14.1 PIN security

- ไม่เก็บ plain PIN
- log ห้ามพิมพ์ PIN
- clipboard ห้ามใช้กับ PIN

## 14.2 Location privacy

- location ใช้เฉพาะ anti-theft event
- อธิบายให้ผู้ใช้รู้ล่วงหน้าใน settings
- ถ้าผู้ใช้ปิด location attach ได้ ต้องยังใช้ anti-theft ต่อได้ แต่ alert จะไม่มีพิกัด

## 14.3 Abuse prevention

เพื่อกันเจ้าของโดน lock เอง:

- ต้องมี `Test mode`
- ต้องมี UI บอกสถานะ Armed ชัดเจน
- ต้องไม่ arm อัตโนมัติหลังติดตั้งทันที

## 15. Failure Handling

## 15.1 If alert delivery fails

- เก็บ log local
- เก็บ pending record เพื่อ retry ถ้ามีระบบรองรับ
- alarm ฝั่ง display ต้องยังทำงานต่อ ห้ามรอ network

## 15.2 If location unavailable

- ส่ง alert ต่อได้
- ใส่ `location.available = false`

## 15.3 If audio playback fails

- retry playback
- fallback เป็น vibration + fullscreen lock

## 15.4 If service restarts mid-session

- restore session state จาก prefs
- ถ้าอยู่ใน `AlarmActive` หรือ `FullscreenLock` ก่อนตาย:
  - ต้องพยายามกลับเข้าระบบ alarm ต่อ

## 16. Acceptance Criteria

ฟีเจอร์ถือว่าใช้ได้เมื่อผ่านอย่างน้อย:

1. เมื่อไม่มีสาย 3.5 mm ฟีเจอร์ไม่แสดง
2. เมื่อมีสาย 3.5 mm และยังไม่มี PIN ระบบบังคับตั้ง PIN ก่อน arm
3. เมื่อ arm แล้วถอดสาย ต้องเด้ง PIN challenge ภายใน 1 วินาที
4. ถ้าไม่ใส่ PIN ภายใน 15 วินาที ต้องส่ง alert ไป Main
5. ถ้าเสียบสายกลับระหว่าง challenge แรก ต้องส่ง alert ไป Main
6. ถ้าใส่ PIN ผิดครั้งที่ 1 ต้องส่ง alert ไป Main
7. ถ้าใส่ PIN ผิดครั้งที่ 2 ต้องเริ่ม alarm
8. หลัง alarm 30 วินาที ต้องเปิด full-screen lock screen
9. ถ้าใส่ PIN ถูก ต้องหยุด alarm และ clear state
10. ทุก alert ที่ส่งไป Main ต้องแนบ location ถ้า location ใช้ได้

## 17. Recommended Implementation Plan

ลำดับ implement ที่แนะนำ:

### Phase 1

- เพิ่ม pref keys
- เพิ่ม anti-theft card บน display
- เพิ่ม PIN setup / verify flow
- เพิ่ม runtime jack monitor
- เพิ่ม state machine เบื้องต้น

### Phase 2

- เพิ่ม alert transport ไป Main
- เพิ่ม location payload
- เพิ่ม alarm audio loop
- เพิ่ม volume reinforcement

### Phase 3

- เพิ่ม full-screen lock activity
- เพิ่ม session restore
- เพิ่ม test mode
- เพิ่ม logs / diagnostics

## 18. Explicit Non-Claims

ทีมต้องไม่อธิบายฟีเจอร์นี้กับผู้ใช้ในลักษณะต่อไปนี้:

- `ขโมยปิดไม่ได้แน่นอน`
- `บังคับเสียงออกลำโพงเครื่องได้ทุกเครื่อง`
- `ล็อก Android ทั้งระบบ`
- `ใช้ได้กับทุก USB-C audio adapter`

คำอธิบายที่ถูกต้องคือ:

- `ช่วยตรวจจับการถอดสาย trigger และส่งสัญญาณเตือนเมื่อมีความผิดปกติ`
- `ออกแบบเป็น deterrent alarm สำหรับอุปกรณ์ Display`

## 19. Open Technical Questions

ประเด็นที่ยังควรตัดสินใจก่อน implementation:

1. จะให้ challenge รอบสองมี timeout 15 วินาทีเหมือนรอบแรกหรือไม่  
   Recommendation: ใช่

2. จะเปิดให้ผู้ใช้ปิด `attach location` ได้หรือบังคับเปิดตลอด  
   Recommendation: เปิดให้ปิดได้ แต่ default เป็นเปิด

3. จะให้ใช้ไฟล์เสียง alarm ในแอปอย่างเดียว หรือรองรับเลือกไฟล์ภายนอก  
   Recommendation: เริ่มจาก preset ในแอปก่อน

4. จะใช้ voice asset prerecorded คำว่า `ขโมย` หรือ TTS  
   Recommendation: ใช้ prerecorded asset เป็นตัวหลัก, TTS เป็น fallback

5. หลัง reboot ถ้า feature เคย Armed จะให้กลับมา Armed อัตโนมัติหรือไม่  
   Recommendation: ยังไม่ auto-arm ใน v1

## 20. Reference Notes

ข้อจำกัด Android ที่มีผลกับสเปกนี้อ้างอิงจากเอกสารทางการ:

- `ACTION_HEADSET_PLUG` runtime receiver only  
  https://developer.android.com/reference/android/media/AudioManager
- audio route API changes / deprecations  
  https://developer.android.com/develop/connectivity/telecom/voip-app/api-updates
- wake lock / `ACQUIRE_CAUSES_WAKEUP` deprecation  
  https://developer.android.com/reference/kotlin/android/os/PowerManager
- keyguard dismissal / `showWhenLocked` / `turnScreenOn`  
  https://developer.android.com/reference/android/app/KeyguardManager
- full-screen intent restrictions on Android 10+ and 14+  
  https://developer.android.com/about/versions/10/behavior-changes-10  
  https://developer.android.com/about/versions/14/behavior-changes-14
