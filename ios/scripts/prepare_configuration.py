"""Generate public Firebase REST configuration for the native iOS targets."""
import json
import pathlib
import plistlib
import re

root = pathlib.Path(__file__).resolve().parents[2]
web = (root / "docs/web/webapp/index.html").read_text(encoding="utf-8")
key = re.search(r'apiKey:\s*"([^"]+)"', web)
database = re.search(r'databaseURL:\s*"([^"]+)"', web)
if not key or not database:
    raise SystemExit("Existing Web App Firebase configuration was not found.")
config = {"API_KEY": key.group(1), "DATABASE_URL": database.group(1), "PROJECT_ID": "phantom-go"}
target = root / "ios/PHANToMGO/Resources/FirebaseConfiguration.plist"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(plistlib.dumps(config))
print("Prepared public Firebase REST configuration; no credentials or tokens were printed.")
