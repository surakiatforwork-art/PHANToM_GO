import CoreImage.CIFilterBuiltins
import SwiftUI

enum QRCodeRenderer {
    static func image(from string: String) -> Image? {
        guard let uiImage = uiImage(from: string) else { return nil }
        return Image(uiImage: uiImage)
    }

    static func uiImage(from string: String) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else { return nil }
        let transformed = outputImage.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        guard let cgImage = context.createCGImage(transformed, from: transformed.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

