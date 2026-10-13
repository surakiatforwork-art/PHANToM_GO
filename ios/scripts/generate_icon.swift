import CoreGraphics
import Foundation
import ImageIO

// Preserve the APK's ghost/goggles/route mark in an opaque, unmasked iOS icon.
let size = 1024
let navy: UInt32 = 0x0A0A1A
let cyan: UInt32 = 0x22D3EE
let white: UInt32 = 0xF0F0FF
func color(_ hex: UInt32) -> CGColor {
    CGColor(red: CGFloat((hex >> 16) & 255) / 255,
            green: CGFloat((hex >> 8) & 255) / 255,
            blue: CGFloat(hex & 255) / 255, alpha: 1)
}
func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }
guard let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                              bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
    fatalError("Cannot allocate app icon bitmap")
}
context.setFillColor(color(navy))
context.fill(CGRect(x: 0, y: 0, width: CGFloat(size), height: CGFloat(size)))
context.translateBy(x: 0, y: CGFloat(size))
context.scaleBy(x: CGFloat(size) / 92, y: -CGFloat(size) / 92)
context.translateBy(x: -8, y: -10)

func fill(_ path: CGPath, _ hex: UInt32) {
    context.addPath(path); context.setFillColor(color(hex)); context.fillPath()
}
func stroke(_ path: CGPath, _ hex: UInt32, _ width: CGFloat) {
    context.addPath(path); context.setStrokeColor(color(hex)); context.setLineWidth(width)
    context.setLineCap(.round); context.setLineJoin(.round); context.strokePath()
}

let body = CGMutablePath()
body.move(to: point(54, 28))
body.addCurve(to: point(28, 52), control1: point(39, 28), control2: point(28, 38))
body.addCurve(to: point(36, 72), control1: point(28, 60), control2: point(31, 67))
body.addCurve(to: point(44, 78), control1: point(38, 74), control2: point(41, 76))
body.addCurve(to: point(54, 81), control1: point(47, 80), control2: point(50, 81))
body.addCurve(to: point(64, 78), control1: point(58, 81), control2: point(61, 80))
body.addCurve(to: point(72, 72), control1: point(67, 76), control2: point(70, 74))
body.addCurve(to: point(80, 52), control1: point(77, 67), control2: point(80, 60))
body.addCurve(to: point(54, 28), control1: point(80, 38), control2: point(69, 28))
body.closeSubpath()
fill(body, cyan)

let tail = CGMutablePath()
tail.move(to: point(28, 72))
tail.addCurve(to: point(28, 82), control1: point(26, 75), control2: point(26, 79))
tail.addCurve(to: point(35, 82), control1: point(30, 85), control2: point(33, 85))
tail.addCurve(to: point(41, 82), control1: point(37, 80), control2: point(39, 80))
tail.addCurve(to: point(48, 82), control1: point(43, 85), control2: point(46, 85))
tail.addCurve(to: point(54, 82), control1: point(50, 80), control2: point(52, 80))
tail.addCurve(to: point(61, 82), control1: point(56, 85), control2: point(59, 85))
tail.addCurve(to: point(67, 82), control1: point(63, 80), control2: point(65, 80))
tail.addCurve(to: point(74, 82), control1: point(69, 85), control2: point(72, 85))
tail.addCurve(to: point(74, 72), control1: point(76, 79), control2: point(76, 75))
tail.closeSubpath()
fill(tail, cyan)

for (rect, radius, hex) in [
    (CGRect(x: 34, y: 42, width: 40, height: 16), CGFloat(4), navy),
    (CGRect(x: 40, y: 46, width: 16, height: 8), CGFloat(2), white),
    (CGRect(x: 56, y: 46, width: 14, height: 8), CGFloat(2), white)
] {
    let lens = CGMutablePath()
    lens.addRoundedRect(in: rect, cornerWidth: radius, cornerHeight: radius)
    fill(lens, hex)
}
let grid = CGMutablePath()
grid.move(to: point(20, 82)); grid.addLine(to: point(88, 82))
for x in [CGFloat(35), CGFloat(54), CGFloat(73)] {
    grid.move(to: point(x, 75)); grid.addLine(to: point(x, 88))
}
stroke(grid, cyan, 2)
let route = CGMutablePath()
route.addLines(between: [point(30, 85), point(42, 85), point(48, 78), point(60, 78), point(66, 85), point(80, 85)])
stroke(route, navy, 3)

let bubble = CGMutablePath()
bubble.move(to: point(72, 24))
bubble.addCurve(to: point(62, 33), control1: point(66, 24), control2: point(62, 28))
bubble.addCurve(to: point(64, 39), control1: point(62, 35), control2: point(63, 37))
bubble.addLine(to: point(62, 43)); bubble.addLine(to: point(67, 42))
bubble.addCurve(to: point(74, 44), control1: point(69, 43), control2: point(71, 44))
bubble.addCurve(to: point(84, 35), control1: point(80, 44), control2: point(84, 40))
bubble.addCurve(to: point(74, 26), control1: point(84, 30), control2: point(80, 26))
bubble.closeSubpath()
fill(bubble, white)
let link = CGMutablePath()
link.move(to: point(70, 28)); link.addLine(to: point(73, 31))
link.move(to: point(76, 26))
link.addCurve(to: point(79.5, 28), control1: point(77.5, 26), control2: point(79, 26.5))
link.addCurve(to: point(78, 32), control1: point(80, 29.5), control2: point(79.5, 31))
link.addLine(to: point(73, 31))
link.move(to: point(70, 34))
link.addCurve(to: point(66.5, 32), control1: point(68.5, 34), control2: point(67, 33.5))
link.addCurve(to: point(68, 28), control1: point(66, 30.5), control2: point(66.5, 29))
link.addLine(to: point(70, 28))
stroke(link, navy, 2.5)
let smile = CGMutablePath()
smile.move(to: point(48, 62)); smile.addQuadCurve(to: point(60, 62), control: point(54, 66))
stroke(smile, navy, 2)

let output = URL(fileURLWithPath: "PHANToMGO/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
guard let image = context.makeImage(),
      let destination = CGImageDestinationCreateWithURL(output as CFURL, "public.png" as CFString, 1, nil) else {
    fatalError("Cannot create app icon PNG")
}
CGImageDestinationAddImage(destination, image, nil)
guard CGImageDestinationFinalize(destination) else { fatalError("Cannot save app icon PNG") }
print("Generated opaque 1024x1024 PHANToM GO ghost app icon")
