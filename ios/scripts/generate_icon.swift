import AppKit
import Foundation

let size = NSSize(width: 1024, height: 1024)
let image = NSImage(size: size)
image.lockFocus()
NSColor(red: 0.05, green: 0.08, blue: 0.14, alpha: 1).setFill()
NSBezierPath(rect: NSRect(origin: .zero, size: size)).fill()
let gradient = NSGradient(colors: [
    NSColor(red: 0.55, green: 0.87, blue: 0.98, alpha: 1),
    NSColor(red: 0.12, green: 0.50, blue: 0.75, alpha: 1)
])!
let panel = NSBezierPath(roundedRect: NSRect(x: 95, y: 95, width: 834, height: 834), xRadius: 190, yRadius: 190)
gradient.draw(in: panel, angle: -35)
let paragraph = NSMutableParagraphStyle()
paragraph.alignment = .center
("GO" as NSString).draw(in: NSRect(x: 110, y: 365, width: 804, height: 300), withAttributes: [
    .font: NSFont.monospacedSystemFont(ofSize: 280, weight: .heavy),
    .foregroundColor: NSColor(red: 0.05, green: 0.10, blue: 0.16, alpha: 1),
    .paragraphStyle: paragraph
])
("PHANToM" as NSString).draw(in: NSRect(x: 110, y: 240, width: 804, height: 90), withAttributes: [
    .font: NSFont.systemFont(ofSize: 66, weight: .bold),
    .foregroundColor: NSColor(red: 0.05, green: 0.10, blue: 0.16, alpha: 1),
    .paragraphStyle: paragraph
])
image.unlockFocus()
let bitmap = NSBitmapImageRep(data: image.tiffRepresentation!)!
let png = bitmap.representation(using: .png, properties: [:])!
let path = URL(fileURLWithPath: "PHANToMGO/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
try FileManager.default.createDirectory(at: path.deletingLastPathComponent(), withIntermediateDirectories: true)
try png.write(to: path)
print("Generated native app icon")
