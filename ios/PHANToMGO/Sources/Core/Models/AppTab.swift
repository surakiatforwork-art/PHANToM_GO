import Foundation

enum AppTab: String, CaseIterable, Identifiable, Codable, Hashable {
    case home, display, settings, security
    var id: String { rawValue }
    var title: String {
        switch self {
        case .home: return "Home"
        case .display: return "Display"
        case .settings: return "Settings"
        case .security: return "Security"
        }
    }
    var symbol: String {
        switch self {
        case .home: return "house.fill"
        case .display: return "car.fill"
        case .settings: return "gearshape.fill"
        case .security: return "shield.lefthalf.filled"
        }
    }
}

