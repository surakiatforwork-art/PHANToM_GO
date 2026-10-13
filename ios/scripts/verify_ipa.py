"""Verify that a packaged IPA contains real iPhoneOS arm64 native executables."""
import pathlib
import plistlib
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

for name in sys.argv[1:]:
    path = pathlib.Path(name)
    with zipfile.ZipFile(path) as package:
        info_paths = [name for name in package.namelist() if name.startswith("Payload/") and name.count("/") == 2 and name.endswith("/Info.plist")]
        if len(info_paths) != 1:
            raise SystemExit("IPA must contain exactly one main app.")
        info_path = info_paths[0]
        info = plistlib.loads(package.read(info_path))
        if not re.fullmatch(r"\d+\.\d+\.\d+", info.get("CFBundleShortVersionString", "")):
            raise SystemExit("IPA must contain the configured semantic app version.")
        if not info.get("CFBundleVersion", "").isdigit():
            raise SystemExit("IPA build number must be numeric.")
        if info.get("CFBundleSupportedPlatforms") != ["iPhoneOS"]:
            raise SystemExit("Package is not an iPhoneOS app.")
        if info.get("MinimumOSVersion") != "16.0":
            raise SystemExit("Unexpected minimum iOS version.")
        for key in ["CFBundleIcons", "CFBundleIcons~ipad"]:
            primary = info.get(key, {}).get("CFBundlePrimaryIcon", {})
            if primary.get("CFBundleIconName") != "AppIcon" or not primary.get("CFBundleIconFiles"):
                raise SystemExit(f"Compiled app icon metadata missing: {key}")
        if not any(entry.startswith(info_path.removesuffix("Info.plist") + "AppIcon") and entry.endswith(".png") for entry in package.namelist()):
            raise SystemExit("Compiled launcher icon PNG is missing.")
        executable_path = info_path.removesuffix("Info.plist") + info["CFBundleExecutable"]
        binary = package.read(executable_path)
        magic, cpu = struct.unpack_from("<II", binary)
        if magic != 0xFEEDFACF or cpu != 0x0100000C:
            raise SystemExit("Executable must be a native 64-bit ARM Mach-O.")
        if not any("PlugIns/PHANToMGOShare.appex/Info.plist" in entry for entry in package.namelist()):
            raise SystemExit("Share extension is missing.")
        with tempfile.TemporaryDirectory() as folder:
            executable = pathlib.Path(folder) / "PHANToMGO"
            executable.write_bytes(binary)
            if sys.platform == "darwin":
                commands = subprocess.check_output(["otool", "-l", str(executable)], text=True)
                if "platform 2" not in commands and "platform IOS" not in commands:
                    raise SystemExit("Mach-O platform must be iOS, not Simulator.")
        print(f"PASS {path.name}: {info['CFBundleIdentifier']} {info['CFBundleShortVersionString']} ({info['CFBundleVersion']}), iPhoneOS arm64")
