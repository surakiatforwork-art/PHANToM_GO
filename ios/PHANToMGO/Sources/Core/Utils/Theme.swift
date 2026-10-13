import SwiftUI
import UIKit

enum Theme {
    static let accent = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.55, green: 0.87, blue: 0.98, alpha: 1)
            : UIColor(red: 0.05, green: 0.34, blue: 0.54, alpha: 1)
    })
    static let button = Color(red: 0.55, green: 0.87, blue: 0.98)
    static let buttonText = Color(red: 0.05, green: 0.10, blue: 0.16)
    static let background = Color(UIColor.systemGroupedBackground)
}

struct ScreenCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 16) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            .background(Color(UIColor.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24))
            .overlay(RoundedRectangle(cornerRadius: 24).stroke(Theme.accent.opacity(0.18), lineWidth: 1))
    }
}

struct PrimaryActionStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.headline)
            .frame(maxWidth: .infinity, minHeight: 50)
            .foregroundStyle(Theme.buttonText)
            .background(Theme.button.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 14))
            .opacity(enabled ? 1 : 0.5)
    }
}
