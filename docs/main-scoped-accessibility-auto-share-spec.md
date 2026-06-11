# PHANToM GO Main Scoped Accessibility Auto Share Spec

Last updated: 2026-06-11
Status: Implemented in v1.0.15-beta.1

## 1. Purpose

เอกสารนี้กำหนดสเปกสำหรับฟังก์ชัน `Auto Share from Google Maps` ฝั่ง `Main Device`
ของ PHANToM GO โดยยึด workflow จาก Automagic flow `Maps Auto Sharing` เป็นต้นแบบ
และย้ายแนวคิดมาอยู่ในแอป PHANToM GO เอง

เป้าหมายหลัก:

- ลดจำนวนขั้นตอนที่ผู้ใช้ต้องกดเมื่อแอปต้นทางเปิด Google Maps นำทางทันที
- ทำให้ฝั่ง `Main` ส่งเส้นทางไป `Display` อัตโนมัติให้ใกล้ประสบการณ์ CarPlay /
  Android Auto มากที่สุด
- จำกัดผลกระทบต่อแอปอื่นบนเครื่องโดยใช้ `Scoped Accessibility Assist`
  แทนการเปิด automation แบบกว้าง
- ใช้ `notification reading` เป็นตัวเริ่มต้น flow และใช้ accessibility
  เฉพาะเมื่อจำเป็นต้องโต้ตอบกับ UI ของ Google Maps

## 2. Product Positioning

ฟีเจอร์นี้ไม่ใช่ระบบ share ปกติของ PHANToM GO

ระบบ share ปกติที่มีอยู่แล้ว:

- รับ `ACTION_SEND`
- รับ `ACTION_VIEW`
- รับ Maps URL / geo / google.navigation
- ส่ง route ไปยัง Display

ฟีเจอร์ในเอกสารนี้คือชั้น automation เพิ่มเติมสำหรับเคสที่:

- แอปต้นทางเปิด Google Maps ตรงทันที
- ผู้ใช้กดเริ่มนำทางบนมือถือ
- ผู้ใช้ไม่อยากกด `แชร์ > PHANToM GO` เองทุกครั้ง

## 3. Design Principles

- จำกัดขอบเขตให้ทำงานกับ `Google Maps` เท่านั้น
- ไม่สแกนหรือกด UI ของแอปอื่น
- ไม่ใช้ข้อความล้วนเป็นตัวตัดสิน
- ใช้หลายสัญญาณร่วมกัน: package, notification, node role, actions, text
- เมื่อมั่นใจไม่พอ ให้ fallback เป็นกึ่งอัตโนมัติ ไม่กดสุ่ม
- สถานะเปิด/ปิดของฟีเจอร์ยึดจาก `PHANToM GO Accessibility Service`
  เป็น source of truth เดียว

## 4. Source of Truth

ฟีเจอร์นี้ไม่มีสวิตช์เปิด/ปิดภายในแอปแบบแยกสถานะถาวร

สถานะของฟีเจอร์:

- ถ้า `PHANToM GO Accessibility Service` เปิดอยู่
  - ฟีเจอร์นี้ถือว่า `Active`
  - notification trigger ทำงาน
  - scoped accessibility orchestration ทำงาน
- ถ้า `PHANToM GO Accessibility Service` ปิดอยู่
  - ฟีเจอร์นี้ถือว่า `Inactive`
  - orchestration ทั้งหมดหยุด

ผลตามมา:

- ถ้าผู้ใช้กดปิดฟีเจอร์ แล้วกลับจาก Settings โดยยังไม่ปิด accessibility จริง
  ฟีเจอร์จะกลับมาทำงานต่อทันที
- ถ้าปิด accessibility จริง ฟีเจอร์จะหยุดทันที

## 5. User Experience

## 5.1 Main screen entry point

ฝั่ง `Main` ต้องมี card หรือ action เด่นบนหน้า Home

ชื่อที่แนะนำ:

- `Auto Share from Maps`
- `Google Maps Auto Share`

องค์ประกอบที่ควรมี:

- status text
- short explanation
- primary button

สถานะที่แสดง:

- `Inactive`
- `Active`
- `Limited`

ความหมาย:

- `Inactive`: accessibility service ปิดอยู่
- `Active`: accessibility service เปิดอยู่ และระบบพร้อมใช้งาน
- `Limited`: accessibility service เปิดอยู่ แต่ signal บางส่วนไม่พร้อม เช่น notification
  access ไม่พร้อมหรือ matcher confidence ไม่พอ

## 5.2 Enable flow

1. ผู้ใช้กด `Open Accessibility Settings`
2. แอปพาไปหน้า Accessibility Settings
3. ผู้ใช้เปิด `PHANToM GO Accessibility Service`
4. เมื่อกลับเข้าแอป:
   - ตรวจสถานะ accessibility service
   - ถ้าเปิดแล้ว ให้เริ่มฟีเจอร์ทันที

## 5.3 Disable flow

1. ผู้ใช้กด `Disable Auto Share`
2. แอปพาไปหน้า Accessibility Settings
3. ถ้าผู้ใช้ปิด service จริง:
   - ฟีเจอร์หยุด
   - status เป็น `Inactive`
4. ถ้าผู้ใช้ไม่ปิด service จริง:
   - ฟีเจอร์กลับมาทำงานต่อ
   - status เป็น `Active` หรือ `Limited`

หมายเหตุ:

- ไม่ต้องพาไป Notification Listener settings ตอนปิด
- ไม่ใช้สวิตช์ปิด logic ภายในแบบถาวรแยกจาก accessibility state

## 6. Existing Project Integration Points

ฟีเจอร์นี้ต้อง reuse ของเดิม ไม่สร้างระบบส่ง route ชุดใหม่

### 6.1 Existing route intake and relay

- [ShareReceiverActivity.kt](</C:/Project_winsurf/PHANToM GO/app/src/main/java/com/phantom/carnavrelay/ShareReceiverActivity.kt>)
- [SilentShareReceiverActivity.kt](</C:/Project_winsurf/PHANToM GO/app/src/main/java/com/phantom/carnavrelay/SilentShareReceiverActivity.kt>)
- [RelayActivity.kt](</C:/Project_winsurf/PHANToM GO/app/src/main/java/com/phantom/carnavrelay/RelayActivity.kt>)
- [MainSender.kt](</C:/Project_winsurf/PHANToM GO/app/src/main/java/com/phantom/carnavrelay/MainSender.kt>)

หน้าที่:

- รับ route input
- normalize route
- ส่งไป Display

### 6.2 Main UI

- [HomeFragment.kt](</C:/Project_winsurf/PHANToM GO/app/src/main/java/com/phantom/carnavrelay/HomeFragment.kt>)

หน้าที่:

- แสดงสถานะฟีเจอร์
- พาผู้ใช้ไปหน้า Accessibility Settings
- แสดง diagnostic / limited state

### 6.3 Persistent state

- [PrefsManager.kt](</C:/Project_winsurf/PHANToM GO/app/src/main/java/com/phantom/carnavrelay/PrefsManager.kt>)

หน้าที่:

- เก็บ state ที่ไม่ใช่ source of truth เช่น
  - last successful session timestamp
  - last maps package/version seen
  - matcher debug preference
  - rate limit / cooldown timestamps

### 6.4 Background helpers

คาดว่าจะต้องมีโมดูลใหม่ฝั่ง Main เช่น:

- `MapsAutoShareAccessibilityService`
- `MapsAutoShareCoordinator`
- `MapsNotificationSignalReceiver`
- `MapsUiMatcher`
- `MapsShareExecutor`
- `MapsAutoShareDiagnostics`

## 7. Required Capabilities

## 7.1 Required

- Accessibility Service

เหตุผล:

- ใช้ตรวจ package ปัจจุบัน
- ใช้อ่าน node tree ของ Google Maps
- ใช้กด `แชร์` และเลือก `PHANToM GO`

## 7.2 Strongly recommended

- Notification reading / notification signal access

เหตุผล:

- ใช้เป็น start signal ที่เบาและเร็วกว่า polling UI
- ลดการต้องอ่าน screen tree ตลอดเวลา

## 7.3 Explicit non-goals

- ไม่ใช้ root
- ไม่ spoof package
- ไม่ hook Google Maps
- ไม่ใช้ fake Google Maps identity
- ไม่ automation แอปธนาคารหรือแอปอื่น

## 8. Runtime Model

## 8.1 High-level flow

```text
Google Maps notification signal
-> coordinator wakes current session
-> verify active package is Google Maps
-> inspect current accessibility tree
-> fast-check closed-destination warning: Exit + Continue
-> if warning exists, click Exit and immediately scan for Share
-> detect route screen state
-> choose action path
-> open share sheet
-> select PHANToM GO
-> existing PHANToM GO share receiver handles relay
```

## 8.3 Fastest-path priority

ลำดับการทำงานล่าสุดต้องเป็นดังนี้:

1. รับ trigger จาก notification หรือ Maps accessibility event ที่มีความมั่นใจสูง
2. ตรวจว่ามีปุ่มกลุ่ม `ออก / Exit` และ `ต่อไป / Continue` พร้อมกันหรือไม่
3. ถ้าพบทั้งคู่ ให้กด `ออก / Exit`
4. เริ่ม scan หา `แชร์ / Share` ต่อทันทีในรอบถัดไป โดยไม่มี fixed delay หรือ `sleep`
5. ถ้าพบ Share ให้เปิด share sheet แล้วเลือก `PHANToM GO`
6. ถ้าไม่พบ Share แต่พบ `ปิดการนำทาง / Stop navigation`
   ให้เข้า fallback branch: ลองเปิด route preview ถ้ามี แล้วจบ navigation เพื่อเปิดหน้า
   ที่มี Share
7. ถ้าไม่พบ control ที่มั่นใจ ห้ามกดสุ่ม ให้หมดเวลาและเข้า cooldown

การไม่มี fixed delay หลังปุ่ม `ออก` ไม่ได้หมายถึง busy-loop:

- ใช้ accessibility window/content events เป็นตัวกระตุ้น scan รอบใหม่
- coordinator สามารถ request scan รอบถัดไปด้วย main-loop callback แบบ `0 ms`
- polling สำรองใช้ช่วงสั้นและมี timeout เสมอ

## 8.2 Scoped behavior

ระบบจะทำงานเฉพาะเมื่อ:

- accessibility service เปิดอยู่
- foreground package เป็น `com.google.android.apps.maps`
- มี start signal จาก notification หรือ UI state ที่สอดคล้อง

ระบบจะไม่ทำงานเมื่อ:

- foreground package ไม่ใช่ Google Maps
- มี active cooldown
- มี automation session อื่นค้างอยู่

Accessibility service config จำกัด package event ตั้งแต่ระดับระบบให้เหลือ:

- Google Maps
- Android/system share sheet
- resolver package ที่รองรับและอยู่ใน allowlist

จากนั้น coordinator ยังตรวจ allowlist ซ้ำในโค้ดก่อนกดทุกครั้ง

## 9. Matching Strategy

ข้ออ่อนของ Automagic flow เดิมคือพึ่งข้อความไทยมากเกินไป เช่น:

- `เริ่มต้นการนำทาง…`
- `แชร์`
- `ปิดการนำทาง`
- `ตัวอย่างเส้นทาง`

ใน PHANToM GO ต้องใช้ `multi-signal matcher`

ลำดับความน่าเชื่อถือ:

1. package name
2. node role / class
3. clickability / available actions
4. view id resource name ถ้ามี
5. content description
6. text
7. bounds / position

## 9.1 Trigger signal

Primary:

- notification from Google Maps that strongly implies route start or active navigation

Secondary:

- accessibility event on Google Maps that contains a strong route/navigation start signal

ระบบห้ามเริ่ม session จากการเห็น `ปิดการนำทาง / Stop navigation` เพียงอย่างเดียว
เพราะจะทำให้แชร์ route เดิมซ้ำเมื่อผู้ใช้กลับเข้า Google Maps ระหว่างการนำทางเดิม

Notification trigger ต้อง dedupe ตาม notification key:

- notification key เดิมที่เริ่ม session แล้วต้องไม่เริ่มซ้ำจาก notification update
- เมื่อ Maps ลบ notification นั้น ให้ล้าง key เพื่อรองรับ navigation session ถัดไป

## 9.2 Share button candidate

Primary signals:

- clickable button node
- likely toolbar or action area position
- action list contains click
- contentDescription or text matches share family

Share family examples:

- `แชร์`
- `Share`
- locale variants discovered later

## 9.3 Stop navigation candidate

Primary signals:

- clickable node
- route active context already detected
- contentDescription or text matches stop/end/cancel navigation family

Examples:

- `ปิดการนำทาง`
- `End`
- `Stop`

## 9.4 Route preview candidate

Primary signals:

- clickable node in route card / preview area
- contentDescription or text matches preview/route summary family

Examples:

- `ตัวอย่างเส้นทาง`
- route overview / preview wording

## 9.5 PHANToM GO share target candidate

Primary signals:

- share sheet visible
- candidate app label starts with `PHANToM GO`

Example matcher:

- `PHANToM GO*`

## 9.6 Closed-destination warning candidate

ถือว่าเป็น warning ที่กดออกได้เฉพาะเมื่อพบ candidate สองกลุ่มพร้อมกัน:

- exit family: `ออก`, `Exit`
- continue family: `ต่อไป`, `Continue`, `Proceed`

ข้อกำหนด:

- candidate ต้อง clickable หรือมี clickable ancestor
- ต้องอยู่ใน Google Maps package
- ห้ามตัดสินจากคำว่า `ออก` เพียงคำเดียว
- เมื่อกด Exit สำเร็จ ให้เริ่มหา Share ทันทีโดยไม่มี fixed post-click delay

## 10. Refactored Legacy Flow Baseline

ด้านล่างคือ refactored baseline script สำหรับ Automagic โดยลดโค้ดซ้ำ แต่ยังใช้ syntax
ใกล้เคียงของเดิมเพื่อให้ความเสี่ยงต่ำ

```javascript
if(global_Share != 1){
    global_Share = 1;

    Exit = getBounds("ออก");
    Continue = getBounds("ต่อไป");
    if(Exit != null and Continue != null){
        click("ออก");
    }

    Stop = getBounds("ปิดการนำทาง");
    Share = getBounds("แชร์");
    shareDone = false;
    cc = 0;

    while(cc <= 50 and (Stop == null and Share == null)){
        cc = cc + 1;
        Stop = getBounds("ปิดการนำทาง");
        Share = getBounds("แชร์");
        sleep(40);
    }

    step = 0;
    while(step <= 1 and shareDone == false){
        if(step == 1 and Stop != null){
            Preview = getBounds("ตัวอย่างเส้นทาง");
            if(Preview != null){
                click("ตัวอย่างเส้นทาง");
                sleep(80);
            }

            Stop = getBounds("ปิดการนำทาง");
            if(Stop != null){
                click("ปิดการนำทาง");
            }

            Share = null;
            ctc = 0;
            while(ctc <= 50 and Share == null){
                ctc = ctc + 1;
                Share = getBounds("แชร์");
                sleep(40);
            }
        }

        if(Share != null){
            click("แชร์");
            cs = 0;
            PPGO = getBounds("PHANToM GO*");

            while(cs <= 40 and PPGO == null){
                cs = cs + 1;
                PPGO = getBounds("PHANToM GO*");
                sleep(40);
            }

            if(PPGO != null){
                click("PHANToM GO*");
                shareDone = true;
            }
        }

        if(shareDone == false and step == 0 and Stop != null){
            step = 1;
            Share = null;
        }
        else{
            step = 2;
        }
    }

    global_Share = 0;
}
else if(global_Share >= 1){
    global_Share = global_Share + 1;
}

if(global_Share >= 5){
    global_Share = 0;
}
```

## 10.1 Behavioral meaning of the baseline

1. ถ้าเจอ warning ที่มีทั้ง `ออก` และ `ต่อไป` ให้กด `ออก`
   แล้วเริ่มหา `แชร์` ต่อทันทีโดยไม่รอ fixed delay
2. ถ้าเจอ `แชร์` ให้แชร์ทันที
3. ถ้ายังแชร์ไม่ได้แต่เจอ `ปิดการนำทาง`
   - ลองแตะ `ตัวอย่างเส้นทาง`
   - แล้วกด `ปิดการนำทาง`
   - จากนั้นรอ `แชร์`
4. เมื่อ share sheet เปิดขึ้น:
   - หา `PHANToM GO`
   - กดเลือก

## 11. PHANToM GO Implementation Mapping

Automagic baseline ข้างบนต้องถูกแปลงเป็นโครงสร้างที่ปลอดภัยกว่าใน PHANToM GO

### 11.1 Coordinator state

ตัวอย่าง state:

- `IDLE`
- `AWAITING_MAPS_SIGNAL`
- `INSPECTING_UI`
- `DISMISS_CLOSED_WARNING`
- `TRY_DIRECT_SHARE`
- `TRY_PREVIEW_THEN_STOP`
- `AWAITING_SHARE_TARGET`
- `HANDOFF_COMPLETE`
- `FAILED_COOLDOWN`

### 11.2 Session guards

ต้องมี guard เหมือน `global_Share`

ตัวอย่าง:

- `activeSessionId`
- `sessionStartedAt`
- `attemptCount`
- `cooldownUntil`

### 11.3 Timeouts

ค่าตั้งต้นที่แนะนำ:

- wait for route controls: 2.5s
- wait for share sheet target: 2.0s
- post-closed-warning delay: none; event-driven immediate rescan
- fallback preview scans: event-driven with short bounded polling
- automation cooldown after failure: 5s to 10s

## 12. Failure Handling

ถ้าหา `แชร์` ไม่เจอ:

- log matcher summary
- ไม่กดสุ่ม
- เข้า cooldown ชั่วคราว

ถ้าหา `PHANToM GO` ใน share sheet ไม่เจอ:

- log candidate app labels แบบ sanitize
- fallback เป็น notification หรือ toast ให้ผู้ใช้กดเอง

ถ้า confidence ไม่พอ:

- ไม่ auto click
- แสดง prompt เช่น `Send latest route to Display`

## 13. Diagnostics

ควรมี debug mode แยกสำหรับฟีเจอร์นี้

ข้อมูลที่ควรเก็บ:

- active package
- session start reason
- matcher phase
- candidate counts
- selected action path
- elapsed time
- failure reason

ไม่ควรเก็บ:

- ข้อความ notification ทั้งหมดแบบดิบถ้าไม่จำเป็น
- route details ที่มีข้อมูลส่วนตัวเกินจำเป็น
- window dump เต็มแบบถาวร

## 14. Known Limitations

- Google Maps เปลี่ยน wording หรือ layout ได้
- บางเครื่อง/OEM จำกัด accessibility timing ต่างกัน
- บางภาษาอาจใช้คำไม่ตรงกับ matcher เริ่มต้น
- ถ้า share sheet แสดงช้าหรือถูกแทนด้วยระบบ OEM อื่น ต้องใช้ fallback
- การเปิด accessibility service อาจยังส่งผลกับนโยบายของแอปธนาคารบางแอป ตราบใดที่
  service ยังเปิดในระบบ
- Android 13 ขึ้นไปอาจบล็อก Accessibility ของ APK ที่ติดตั้งนอก Store จนกว่าผู้ใช้จะ
  เปิด `Allow restricted settings` ใน App info
- การเผยแพร่ผ่าน Play Store ต้องเตรียม Accessibility API declaration และอธิบาย
  จุดประสงค์ต่อผู้ใช้อย่างชัดเจน

## 15. Recommendation

แนวทางที่เหมาะที่สุดสำหรับ PHANToM GO v1 ของฟีเจอร์นี้:

- ใช้ accessibility state เป็น on/off source of truth
- ใช้ notification เป็น start signal
- จำกัด automation ให้ทำงานเฉพาะ Google Maps
- ใช้ multi-signal matcher
- fallback เป็น semi-auto เมื่อ confidence ไม่พอ
- หลีกเลี่ยงการพึ่งข้อความไทยล้วน

## 16. Out of Scope for v1

- รองรับหลายแอปนำทางพร้อมกัน
- ทำ selector update ผ่าน remote config
- self-healing ML matcher
- auto-enable / auto-disable notification access
- root / hook / LSPosed integration
