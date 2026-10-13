import os
import pathlib
import plistlib

bundle = os.environ["IOS_BUNDLE_IDENTIFIER"]
options = {
    "method": os.environ["EXPORT_METHOD"],
    "signingStyle": "manual",
    "teamID": os.environ["APPLE_TEAM_ID"],
    "provisioningProfiles": {
        bundle: os.environ["PROFILE_NAME"],
        bundle + ".Share": os.environ["SHARE_PROFILE_NAME"],
    },
    "stripSwiftSymbols": True,
}
target = pathlib.Path("build/export/ExportOptions.plist")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(plistlib.dumps(options))
