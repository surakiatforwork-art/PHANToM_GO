# การย้าย GitHub Repository และส่งต่อ Release

วันที่: 2026-10-13

## Repository ที่ใช้ต่อจากนี้

- Repository: https://github.com/surakiatforwork-art/PHANToM_GO
- Branch หลัก: `main`
- Remote `origin`: `https://github.com/surakiatforwork-art/PHANToM_GO`
- Repository ใหม่เริ่มต้นว่าง ส่วน repository เดิมไม่สามารถเข้าถึงได้ในบัญชีที่ใช้งานขณะย้าย
- ย้ายประวัติของ `main` และ tags ที่มีอยู่ในเครื่อง โดยไม่เขียนประวัติ Git ใหม่
- Branch ทดลอง `codex/quick-route-capture-experiment` ยังเก็บอยู่เฉพาะในเครื่อง
- ไฟล์ untracked ส่วนตัวไม่ได้รวมเข้า commit หรือ push

## Release ที่นำขึ้น Repository ใหม่

- Stable: `v1.3.1`, source tag เดิม, `versionCode 31`
- Beta hotfix: `v1.3.2-beta.1`, `versionCode 32`
- Hotfix Auto Local LAN Relay ยังคงเป็น GitHub Pre-release ตามคำสั่งเดิม
- สร้าง APK ใหม่จาก source ของแต่ละรุ่น และตรวจ package, version และลายเซ็นก่อนอัปโหลด
- APK ยังคงใช้ debug signing key เดิมของเครื่อง เพื่อรักษาการอัปเดตทับรุ่นเดิม
- Landing Page และ README ใช้ลิงก์ดาวน์โหลด Stable จาก repository ใหม่
- README มีลิงก์ Beta แยกต่างหาก
- การย้าย tags ไม่ได้กู้คืนหน้า Release หรือ APK ของทุกเวอร์ชันย้อนหลังจาก repository เดิม

## ลิงก์ดาวน์โหลด

- Stable: https://github.com/surakiatforwork-art/PHANToM_GO/releases/download/v1.3.1/PHANToM_GO-v1.3.1.apk
- Beta: https://github.com/surakiatforwork-art/PHANToM_GO/releases/download/v1.3.2-beta.1/PHANToM_GO-v1.3.2-beta.1.apk

## การตรวจสอบก่อนเผยแพร่

- รัน Android build, unit tests และ lint สำหรับ Beta ล่าสุด
- Build Stable จาก checkout แยกของ tag `v1.3.1`
- ตรวจ APK ด้วย `aapt` และ `apksigner`
- คำนวณ SHA-256 และบันทึกไว้ใน GitHub Release ของแต่ละรุ่น
- ตรวจว่า commit บน `origin/main` ตรงกับ local `main`
- ตรวจว่า Stable เป็น Latest Release และ Beta เป็น Pre-release
- ตรวจลิงก์ดาวน์โหลดทั้งสองรุ่นหลังอัปโหลด
- ไม่มีอุปกรณ์ ADB เชื่อมต่อในตอนเริ่มงาน จึงยังไม่มีการทดสอบบนเครื่องจริงในรอบนี้

## ผลตรวจในรอบย้าย Repository

- Beta: `assembleDebug assembleRelease testDebugUnitTest lintDebug --stacktrace` สำเร็จ
- Stable: `assembleDebug --max-workers=2 --stacktrace` สำเร็จจาก tag `v1.3.1`
- APK ที่สร้างทั้งสองรุ่นมี SHA-256 ตรงกับไฟล์ release เดิมทุกไบต์
- Package ทั้งสองรุ่น: `com.phantom.carnavrelay`
- ทั้งสอง APK ผ่าน `apksigner verify` และใช้ certificate SHA-256 เดียวกัน: `2b3d3efff71526168656b137a2df5625c08bf488a0656ddacfbd54a0ff286a74`
- `git diff --check` ผ่าน; ไม่มีการแก้ source Android หรือ iOS
- พบไฟล์ `.code-workspace` ใน generated resource ที่ทำให้ build ครั้งแรกหยุด จึงย้ายเก็บไว้ใน `docs/build/repository-migration/openclaw_cookierun.code-workspace` ก่อน build ใหม่
- เก็บ APK ที่อัปโหลดไว้ใน `docs/build/repository-migration/` โดยโฟลเดอร์นี้ไม่ถูก push เข้า Git

| APK | SHA-256 |
| --- | --- |
| `PHANToM_GO-v1.3.1.apk` | `7cbcaf918082945a71b3eb9c3b627e61a0c2c2656e8b90294241308808e4d7f8` |
| `PHANToM_GO-v1.3.2-beta.1.apk` | `0cd9bace25c15ddc48af7fd9e3004e439cddaa48c5703395534b5e4827d5f8cc` |

## งานต่อสำหรับ Agent

- ใช้ repository ใหม่ในทุกคำสั่ง GitHub ห้ามอ้าง repository เดิมเพื่อเผยแพร่งาน
- ทดสอบ Beta hotfix บน Main และ Display จริง ตาม `AUTO_LOCAL_LAN_RELAY_HOTFIX_PLAN.md`
- เมื่อเจ้าของยืนยันให้เผยแพร่ Stable จึงเพิ่ม `versionCode` และเปลี่ยน `versionName` ตามคู่มือ `AGENT_WORKFLOW_GUIDE.md`
- ไม่ต้องแก้ Firebase Console สำหรับการย้าย repository ครั้งนี้
- หากเว็บเคย deploy โดยเชื่อม repository เดิม ต้องเปลี่ยน repository ในบริการ hosting เป็น repository ใหม่นี้
- ไม่แก้ source ใน `ios/` หรือไฟล์งานวิดีโอ
