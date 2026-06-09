import Foundation

enum DisplayNavigationMode: String, CaseIterable, Identifiable, Codable {
    case driving = "DRIVING"
    case motorcycle = "MOTORCYCLE"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .driving: return "Car"
        case .motorcycle: return "Motorcycle"
        }
    }
}

enum DisplayOpenBehavior: String, CaseIterable, Identifiable, Codable {
    case previewRoute = "PREVIEW_ROUTE"
    case startNavigation = "START_NAVIGATION"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .previewRoute: return "Preview Route"
        case .startNavigation: return "Start Navigation"
        }
    }
}

enum PreferredMapApp: String, CaseIterable, Identifiable, Codable {
    case googleMaps
    case appleMaps

    var id: String { rawValue }

    var title: String {
        switch self {
        case .googleMaps: return "Google Maps"
        case .appleMaps: return "Apple Maps"
        }
    }
}

