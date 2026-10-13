"""Reject incorrect-size or alpha-channel iOS app icons before asset compilation."""
import pathlib
import struct
import sys

path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "PHANToMGO/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
data = path.read_bytes()
if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
    raise SystemExit("Icon must be PNG.")
width, height, depth, color_type = struct.unpack_from(">IIBB", data, 16)
if (width, height, depth, color_type) != (1024, 1024, 8, 2):
    raise SystemExit(f"Icon must be opaque RGB 1024x1024/8-bit: got {(width, height, depth, color_type)}")
print("PASS app icon: 1024x1024 RGB without alpha")
