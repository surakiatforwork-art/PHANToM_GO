# Native iOS IPA Pipeline

อัปเดต: 2026-10-13

## Workflow

- `.github/workflows/ios.yml`: unit tests และ iPhone/iPad UI tests
- `.github/workflows/ios-device.yml`: native arm64 iPhoneOS Release build

## Unsigned mode

ไม่ต้องมี Apple signing secrets

1. Run workflow `iOS Device Build`
2. เลือก `signing_mode=unsigned`
3. Runner เตรียม public Firebase REST config จาก Web App เดิมและสร้าง native app icon
4. XcodeGen สร้าง Xcode project
5. Xcode compile สำหรับ `generic/platform=iOS` ด้วย SDK `iphoneos`
6. เก็บ native app และ Share Extension ใน `Payload/`
7. Package เป็น `PHANToM_GO-iOS-vX.Y.Z-unsigned.ipa`
8. ตรวจ Mach-O arm64, iPhoneOS platform, app version, Share Extension และสร้าง SHA256SUMS
9. Upload artifact `PHANToMGO-ios-device-ipa-unsigned`

ไฟล์นี้ต้องใช้ Sideloadly/เครื่องมือ signing ที่เหมาะสมก่อนติดตั้ง

## Signed mode

ต้องมี Apple signing material ที่ออกให้ bundle IDs ของทั้งสอง targets:

- Main: `com.phantom.PHANToMGO`
- Share: `com.phantom.PHANToMGO.Share`
- Shared Keychain: `TEAM_PREFIX.com.phantom.PHANToMGO.shared`

Secrets:

| ชื่อ | ความหมาย |
| --- | --- |
| BUILD_CERTIFICATE_BASE64 | Base64 ของ certificate .p12 |
| P12_PASSWORD | รหัสของ .p12 |
| BUILD_PROVISION_PROFILE_BASE64 | Main app provisioning profile |
| BUILD_SHARE_PROVISION_PROFILE_BASE64 | Share Extension provisioning profile |
| KEYCHAIN_PASSWORD | รหัสชั่วคราวของ runner keychain |
| APPLE_TEAM_ID | Apple Developer Team ID |
| IOS_BUNDLE_IDENTIFIER | Optional; default `com.phantom.PHANToMGO` |
| CODE_SIGN_IDENTITY | Optional; Apple Development/Distribution ตาม profile |

ไม่ต้องมี `IOS_FIREBASE_PLIST_BASE64` สำหรับ Firebase REST implementation นี้

Workflow import signing material ลง temporary keychain, archive ทั้งสอง targets โดยใช้ profile ของแต่ละ target, export แล้วล้าง signing material หลังงานจบ

Export methods ของ Xcode ปัจจุบัน:

- `debugging`: development testing
- `release-testing`: ad-hoc testing สำหรับอุปกรณ์ที่ profile อนุญาต
- `app-store-connect`: export เพื่อนำไป TestFlight/App Store Connect; pipeline ยังไม่ได้ upload ไป Apple

## กฎเผยแพร่

- iOS tags ใช้ `ios-vX.Y.Z` เพื่อแยกจาก Android
- iOS release ใช้ `--latest=false` เพื่อรักษา Android Latest Stable
- ห้ามเอา simulator app มา rename เป็น IPA แล้วอ้างว่าติดตั้งบนเครื่องจริงได้
- Release notes ต้องระบุ unsigned/signed, ข้อจำกัด iOS และผลทดสอบที่รันจริง
- มี SHA-256 และตรวจ URL/asset ก่อนรายงานผู้ใช้
