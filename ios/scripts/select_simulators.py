import json
import subprocess

devices = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"]))
for runtime, candidates in sorted(devices.get("devices", {}).items(), reverse=True):
    if "iOS" not in runtime:
        continue
    phone = next((item for item in candidates if item.get("isAvailable") and "iPhone" in item["name"]), None)
    pad = next((item for item in candidates if item.get("isAvailable") and "iPad" in item["name"]), None)
    if phone and pad:
        print(f"IOS_PHONE_DESTINATION=platform=iOS Simulator,id={phone['udid']}")
        print(f"IOS_PAD_DESTINATION=platform=iOS Simulator,id={pad['udid']}")
        break
else:
    raise SystemExit("An available iPhone and iPad simulator are required.")
