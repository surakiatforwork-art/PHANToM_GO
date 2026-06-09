import Foundation

struct RoutePayload: Identifiable, Codable, Equatable {
    enum Confidence: String, Codable {
        case high
        case medium
        case low
    }

    enum ParserSource: String, Codable {
        case mapsAppShortLink
        case googleMapsUrl
        case geoScheme
        case googleNavigationScheme
        case coordinates
        case placeName
        case manualInput
    }

    let id: String
    let originalText: String
    let sourceApp: String?
    let resolvedURL: String?
    let latitude: Double?
    let longitude: Double?
    let query: String?
    let placeName: String?
    let navigationMode: DisplayNavigationMode
    let timestamp: Date
    let confidence: Confidence
    let parserSource: ParserSource

    init(
        id: String = UUID().uuidString,
        originalText: String,
        sourceApp: String? = nil,
        resolvedURL: String? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        query: String? = nil,
        placeName: String? = nil,
        navigationMode: DisplayNavigationMode = .driving,
        timestamp: Date = Date(),
        confidence: Confidence,
        parserSource: ParserSource
    ) {
        self.id = id
        self.originalText = originalText
        self.sourceApp = sourceApp
        self.resolvedURL = resolvedURL
        self.latitude = latitude
        self.longitude = longitude
        self.query = query
        self.placeName = placeName
        self.navigationMode = navigationMode
        self.timestamp = timestamp
        self.confidence = confidence
        self.parserSource = parserSource
    }
}

