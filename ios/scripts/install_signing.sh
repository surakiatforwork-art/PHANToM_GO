#!/bin/bash
set -euo pipefail
for name in BUILD_CERTIFICATE_BASE64 P12_PASSWORD BUILD_PROVISION_PROFILE_BASE64 BUILD_SHARE_PROVISION_PROFILE_BASE64 KEYCHAIN_PASSWORD APPLE_TEAM_ID; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing signing secret: $name"
    exit 1
  fi
done
export KEYCHAIN_PATH="$RUNNER_TEMP/phantom-signing.keychain-db"
export CERTIFICATE_PATH="$RUNNER_TEMP/phantom-certificate.p12"
export MAIN_PROFILE_PATH="$RUNNER_TEMP/phantom-main.mobileprovision"
export SHARE_PROFILE_PATH="$RUNNER_TEMP/phantom-share.mobileprovision"
python3 - <<'PY'
import base64
import os
import pathlib
for source, target in [
    ("BUILD_CERTIFICATE_BASE64", "CERTIFICATE_PATH"),
    ("BUILD_PROVISION_PROFILE_BASE64", "MAIN_PROFILE_PATH"),
    ("BUILD_SHARE_PROVISION_PROFILE_BASE64", "SHARE_PROFILE_PATH"),
]:
    pathlib.Path(os.environ[target]).write_bytes(base64.b64decode(os.environ[source]))
PY
security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
security set-keychain-settings -lut 21600 "$KEYCHAIN_PATH"
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
security import "$CERTIFICATE_PATH" -P "$P12_PASSWORD" -A -t cert -f pkcs12 -k "$KEYCHAIN_PATH"
security set-key-partition-list -S apple-tool:,apple: -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
security list-keychains -d user -s "$KEYCHAIN_PATH" login.keychain-db
mkdir -p "$HOME/Library/MobileDevice/Provisioning Profiles"
for kind in MAIN SHARE; do
  if [[ "$kind" == "MAIN" ]]; then path="$MAIN_PROFILE_PATH"; else path="$SHARE_PROFILE_PATH"; fi
  decoded="$RUNNER_TEMP/phantom-$kind-profile.plist"
  security cms -D -i "$path" > "$decoded"
  name=$(/usr/libexec/PlistBuddy -c "Print :Name" "$decoded")
  uuid=$(/usr/libexec/PlistBuddy -c "Print :UUID" "$decoded")
  cp "$path" "$HOME/Library/MobileDevice/Provisioning Profiles/$uuid.mobileprovision"
  if [[ "$kind" == "MAIN" ]]; then
    printf 'PROFILE_NAME=%s\nPROFILE_UUID=%s\n' "$name" "$uuid" >> "$GITHUB_ENV"
  else
    printf 'SHARE_PROFILE_NAME=%s\nSHARE_PROFILE_UUID=%s\n' "$name" "$uuid" >> "$GITHUB_ENV"
  fi
done
echo "KEYCHAIN_PATH=$KEYCHAIN_PATH" >> "$GITHUB_ENV"
