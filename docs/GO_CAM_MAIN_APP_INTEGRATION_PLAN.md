# GO Cam Main-App Integration Plan

Date: 2026-06-26

## Goal

นำ `GO cam` จากสถานะ "แอปแยกสำหรับทดสอบ" ไปเป็นฟีเจอร์หนึ่งในแอปหลัก `PHANToM GO` โดยยังรักษาความสามารถในการ build เป็น APK แยกไว้ได้ระหว่างช่วงเปลี่ยนผ่าน และเพิ่มหน้าดูคลิปย้อนหลังให้พร้อมใช้งานจริง

## Current State

- โปรเจกต์มี 2 โมดูลแยกกันอยู่แล้ว
  - `:app` = แอปหลัก `PHANToM GO`
  - `:gocam` = แอปแยก `GO cam`
- `GO cam` บันทึกไฟล์ลง `Movies/PHANToM GO Cam`
- การลบแบบ loop-by-storage ทำงานแล้ว โดยลบคลิปเก่าสุดก่อนเมื่อเกิน quota
- `GO cam` ยังไม่มีหน้ารวมคลิปย้อนหลัง และยังไม่มีปุ่ม `ดูวีดิโอทั้งหมด`
- หน้าตั้งค่าหลักของแอปอยู่ที่
  - `app/src/main/java/com/phantom/carnavrelay/SettingsScreenFragment.kt`
  - `app/src/main/res/layout/activity_settings.xml`
- หน้าความปลอดภัยหลักของแอปอยู่ที่
  - `app/src/main/java/com/phantom/carnavrelay/SecurityFragment.kt`
  - `app/src/main/res/layout/fragment_security.xml`

## Target UX

### 1. GO cam standalone

- เพิ่มปุ่ม `ดูวีดิโอทั้งหมด`
- เปิดหน้ารวมคลิปย้อนหลัง
- เรียงคลิปล่าสุดไว้บนสุด
- แต่ละรายการแสดงอย่างน้อย
  - ชื่อไฟล์
  - วันเวลา
  - ขนาดไฟล์
- แตะคลิปเพื่อเปิดดูย้อนหลังได้ทันที

### 2. PHANToM GO > Settings

- ย้ายการตั้งค่าวิดีโอมาไว้ในหน้า Settings ของแอปหลักใน card เดียว
- กลุ่มตั้งค่าที่ควรอยู่หน้านี้
  - โหมดกล้อง `Rear / Front / Dual`
  - เปิด/ปิด preview
  - ขนาดพื้นที่จัดเก็บสำหรับ loop recording
  - ปุ่ม `ดูวีดิโอทั้งหมด`

### 3. PHANToM GO > Security

- เพิ่ม card สำหรับฟีเจอร์ dashcam
- กลุ่มควบคุมที่ควรอยู่หน้านี้
  - เปิด/ปิดฟีเจอร์กล้องติดรถ
  - เปิด/ปิด `Auto record on charging`
  - แสดงสถานะปัจจุบัน เช่น รอชาร์จ / กำลังบันทึก / permission ยังไม่ครบ
  - shortcut ไปดูคลิปย้อนหลัง

## Recommended Architecture

เพื่อไม่ให้ต้องแก้ bug ซ้ำสองที่ `:gocam` และ `:app` ควรเปลี่ยนจาก "มีโค้ด dashcam อยู่ใน app แยก" ไปเป็น "มี shared feature ที่ทั้ง 2 app ใช้ร่วมกัน"

### Recommended module split

- `:gocam-core` หรือ `:dashcam-feature` = Android library
  - service, receiver, storage, state store, clip browser UI, shared resources
- `:gocam` = standalone shell app สำหรับทดสอบ
  - launch activity + binding กับ shared feature
- `:app` = main app
  - เรียกใช้ shared feature ผ่าน Settings และ Security

### Why this path

- ลดการ copy code ระหว่างแอปหลักกับแอปแยก
- bug fix เรื่อง recording / timestamp / storage / auto-record จะแก้ครั้งเดียว
- ยัง build APK แยกของ `GO cam` ต่อได้
- ทำให้ main app ใช้ฟีเจอร์เดียวกับที่ผ่านการทดสอบบนอุปกรณ์จริงแล้ว

## Implementation Phases

### Phase 1: Add Clip Browser To GO cam First

เป้าหมายคือทำของที่ผู้ใช้ขอทันทีให้เสร็จในแอปแยกก่อน แล้วค่อยย้ายขึ้น shared feature

### Work

- เพิ่ม model/query สำหรับดึงรายการคลิปจาก MediaStore แบบ `newest first`
- แยก `trim` กับ `list` ออกจากกัน
  - `trim` ยังคงใช้ oldest-first
  - `list` ใช้ newest-first
- สร้างหน้ารวมคลิป เช่น `DashcamClipsActivity` หรือ `DashcamClipsFragment`
- เพิ่ม list item ที่แสดง
  - ชื่อไฟล์
  - วันที่/เวลา
  - ขนาดไฟล์
- เพิ่ม action เมื่อกดคลิป
  - เปิดด้วย system player ก่อนเป็น baseline
- เพิ่มปุ่ม `ดูวีดิโอทั้งหมด` ใน `GO cam`

### Likely files

- `GO cam/src/main/java/com/phantom/gocam/DashcamClipStorageManager.kt`
- `GO cam/src/main/java/com/phantom/gocam/MainActivity.kt`
- `GO cam/src/main/res/layout/activity_main.xml`
- ไฟล์ใหม่สำหรับ clip list UI

### Phase 2: Extract Shared Dashcam Feature

เป้าหมายคือทำให้ logic เดิมของ dashcam ถูกใช้ซ้ำได้ทั้ง `GO cam` และ `PHANToM GO`

### Work

- ย้ายคลาส dashcam ที่ reusable ไป shared library
  - `DashcamRecordingService`
  - `PowerMonitoringService`
  - `PowerConnectionReceiver`
  - `AutoRecordPowerManager`
  - `DashcamClipStorageManager`
  - `DashcamStateStore`
  - `DashcamUiState`
  - `SurfaceRotationStore`
  - resources ที่เกี่ยวข้อง
- ปรับ manifest ให้ shared feature merge เข้า host app ได้
- จัด dependency กล้อง/CameraX ไว้ใน library module
- ทำ shell ของ `:gocam` ให้บางลง เหลือแค่ entry point สำหรับทดสอบ

### Important note

ปัจจุบัน `:gocam` เป็น `com.android.application` ถ้าจะให้ `:app` ใช้งานร่วมกันได้ตรง ๆ ต้องแยก shared code ออกก่อน ไม่เช่นนั้นจะลงเอยด้วยการ duplicate code

### Phase 3: Settings Integration In Main App

เป้าหมายคือย้าย "การตั้งค่าวิดีโอ" เข้า `PHANToM GO > Settings`

### Work

- เพิ่ม card `Dashcam / กล้องติดรถ` ใน `activity_settings.xml`
- bind control ใหม่ใน `SettingsScreenFragment.kt`
- ย้ายค่าต่อไปนี้มาอยู่ใน main settings
  - camera mode
  - preview enabled
  - storage quota
- เพิ่มปุ่ม `ดูวีดิโอทั้งหมด`
- เพิ่มข้อความสรุป เช่น quota ปัจจุบัน / จำนวนคลิป / คลิปล่าสุด

### Preference strategy

ควรมี abstraction กลาง เช่น `DashcamSettingsStore`

- standalone implementation ใช้ `DashcamPreferences`
- main-app implementation ใช้ `PrefsManager`

แบบนี้จะไม่ผูก shared feature เข้ากับ `PrefsManager` โดยตรง และทำให้ shell app ยังใช้งานได้

### Phase 4: Security Integration In Main App

เป้าหมายคือย้าย "การเปิด/ปิดฟีเจอร์" และ `Auto record` ไป `PHANToM GO > Security`

### Work

- เพิ่ม dashcam security card ใน `fragment_security.xml`
- bind view ใหม่ใน `SecurityFragment.kt`
- เพิ่ม toggle
  - `Dashcam enabled`
  - `Auto record on charging`
- แสดงสถานะ runtime
  - permission ครบหรือไม่
  - กำลังชาร์จหรือไม่
  - recording อยู่หรือไม่
  - background monitoring ทำงานหรือไม่
- เพิ่ม quick action
  - `ดูวีดิโอทั้งหมด`
  - optional: `เริ่มบันทึกทันที` / `หยุดบันทึก`

### Phase 5: Main-App Permission And Manifest Merge

### Work

- เติม permission ที่ dashcam ต้องใช้เข้า main app manifest
  - `CAMERA`
  - `RECORD_AUDIO`
  - `MODIFY_AUDIO_SETTINGS`
  - `POST_NOTIFICATIONS`
  - `SYSTEM_ALERT_WINDOW`
  - foreground service permissions ที่เกี่ยวข้อง
  - `WAKE_LOCK`
- merge activity/service/receiver ของ dashcam เข้า host app
- ตรวจสอบ compileSdk / targetSdk / CameraX versions ให้ตรงกัน

### Phase 6: QA On Real Device

### Standalone checks

- ปุ่ม `ดูวีดิโอทั้งหมด` เปิดได้
- คลิปเรียงล่าสุดก่อน
- ขนาดไฟล์แต่ละคลิปถูกต้อง
- แตะแล้วเล่นย้อนหลังได้

### Main-app checks

- ปรับค่ากล้องจากหน้า Settings แล้วมีผลจริง
- เปิด `Auto record on charging` จากหน้า Security แล้วทำงานจริง
- เมื่อเสียบสายชาร์จจากสภาพแอปปิดอยู่ ยังสามารถเข้าสู่ flow recording ได้
- คลิปที่เคยบันทึกไว้ใน `Movies/PHANToM GO Cam` มองเห็นจาก main app ได้
- quota trimming ยังลบคลิปเก่าสุดตามเดิม

## Key Risks

### 1. Standalone settings will not migrate automatically

ค่าใน `GO cam` ใช้ shared preferences ของ package `com.phantom.gocam`

เมื่อย้ายเข้า `PHANToM GO` ซึ่งเป็นคนละ package:

- ค่าวิดีโอเดิมจะไม่ไหลตามมาอัตโนมัติ
- ต้องยอมเริ่มค่าตั้งต้นใหม่ใน main app หรือทำ export/import ภายหลัง

### 2. Recorded videos are easier than settings

ไฟล์วิดีโอเก็บใน MediaStore ภายใต้ `Movies/PHANToM GO Cam`

ดังนั้น:

- main app สามารถเห็นคลิปเก่าที่ standalone app เคยบันทึกได้
- ส่วนนี้ไม่ติด package boundary แบบ shared preferences

### 3. Dual camera remains hardware-dependent

ต่อให้ UI และ integration พร้อม อุปกรณ์บางรุ่นอาจไม่รองรับ front+rear simultaneous recording ผ่าน CameraX/Camera2 ในระดับเดียวกัน

แผน integration นี้จึงไม่ควรผูกกับสมมติฐานว่า dual recording จะใช้ได้ทุกเครื่อง

### 4. Overlay / background launch behavior still needs device QA

แม้ auto-record on charging จะทำงานแล้วบนเครื่องทดสอบ แต่ยังต้องเช็คอีกครั้งหลังย้ายเข้า main app เพราะ package name, manifest และ launch flow จะเปลี่ยน

## Suggested Order Of Execution

1. ทำ clip browser ใน `GO cam` ให้เสร็จก่อน
2. แยก shared dashcam code ออกเป็น library
3. เชื่อม Settings ของ main app
4. เชื่อม Security ของ main app
5. ทดสอบบนอุปกรณ์จริงทั้ง standalone และ main app

## Definition Of Done

- `GO cam` มีปุ่ม `ดูวีดิโอทั้งหมด` และมีหน้าคลิปย้อนหลังเรียงล่าสุดก่อน
- แต่ละคลิปแสดงขนาดไฟล์ได้
- `PHANToM GO > Settings` คุมค่ากล้องหลักได้
- `PHANToM GO > Security` คุมการเปิดใช้ฟีเจอร์และ auto-record ได้
- main app เรียกดูคลิปย้อนหลังชุดเดียวกับที่บันทึกไว้จริงใน MediaStore ได้
- standalone `GO cam` ยัง build และใช้เป็น test shell ได้
