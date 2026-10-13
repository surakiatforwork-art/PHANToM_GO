import Foundation

struct PairingRecord: Codable, Equatable {
    let displayDeviceId: String
    let displayAuthUid: String?
    let displayName: String?
    let mainDeviceId: String
    let mainAuthUid: String?
    let mainName: String?
    let pairedAt: TimeInterval?
    let updatedAt: TimeInterval?
    let transportHint: String?

    static func decode(_ value: Any?) -> PairingRecord? {
        guard let value = value as? [String: Any],
              let display = value["displayDeviceId"] as? String,
              let main = value["mainDeviceId"] as? String,
              !display.isEmpty, !main.isEmpty else { return nil }
        return PairingRecord(
            displayDeviceId: display, displayAuthUid: value["displayAuthUid"] as? String,
            displayName: value["displayName"] as? String, mainDeviceId: main,
            mainAuthUid: value["mainAuthUid"] as? String, mainName: value["mainName"] as? String,
            pairedAt: (value["pairedAt"] as? NSNumber)?.doubleValue,
            updatedAt: (value["updatedAt"] as? NSNumber)?.doubleValue,
            transportHint: value["transportHint"] as? String
        )
    }
}

struct RouteCommand: Identifiable, Codable, Equatable {
    let id: String
    let clientRequestId: String
    let fromMainDeviceId: String
    let originalText: String
    let resolvedURL: String
    let createdAt: TimeInterval?
    let status: String?

    static func decode(id: String, value: Any?) -> RouteCommand? {
        guard let value = value as? [String: Any],
              let url = value["url"] as? String, !url.isEmpty else { return nil }
        return RouteCommand(
            id: id, clientRequestId: value["clientRequestId"] as? String ?? id,
            fromMainDeviceId: value["fromMainDeviceId"] as? String ?? "",
            originalText: value["originalText"] as? String ?? url, resolvedURL: url,
            createdAt: (value["createdAt"] as? NSNumber)?.doubleValue,
            status: value["status"] as? String
        )
    }
}

struct DisplayPresence: Equatable {
    var name = ""
    var connected = false
    var updatedAt: Date?
    var latitude: Double?
    var longitude: Double?
    var locationDate: Date?
    var accuracy: Double?
    var speedKmh: Double?
    var localIP: String?
    var localPort: Int?
    var localToken: String?

    var isOnline: Bool {
        connected && (updatedAt.map { Date().timeIntervalSince($0) < 90 } ?? false)
    }

    static func decode(_ raw: Any?) -> DisplayPresence? {
        guard let raw = raw as? [String: Any] else { return nil }
        func number(_ key: String) -> Double? { (raw[key] as? NSNumber)?.doubleValue }
        func date(_ key: String) -> Date? { number(key).map { Date(timeIntervalSince1970: $0 / 1000) } }
        return DisplayPresence(
            name: raw["model"] as? String ?? "",
            connected: raw["connected"] as? Bool ?? false, updatedAt: date("updatedAt"),
            latitude: number("locationLat"), longitude: number("locationLng"),
            locationDate: date("locationCapturedAt"), accuracy: number("locationAccuracyMeters"),
            speedKmh: number("locationSpeedKmh"), localIP: raw["localIp"] as? String,
            localPort: (raw["localPort"] as? NSNumber)?.intValue, localToken: raw["localToken"] as? String
        )
    }
}

struct SecurityAlert: Identifiable, Codable, Equatable {
    let id: String
    let eventType: String
    let displayName: String
    let latitude: Double?
    let longitude: Double?
    let date: Date

    static func decode(id: String, value: Any?) -> SecurityAlert? {
        guard let value = value as? [String: Any],
              let event = value["eventType"] as? String else { return nil }
        let timestamp = (value["createdAt"] as? NSNumber)?.doubleValue
            ?? (value["eventAt"] as? NSNumber)?.doubleValue ?? 0
        return SecurityAlert(
            id: id, eventType: event, displayName: value["displayName"] as? String ?? "Display",
            latitude: (value["lat"] as? NSNumber)?.doubleValue,
            longitude: (value["lng"] as? NSNumber)?.doubleValue,
            date: Date(timeIntervalSince1970: timestamp / 1000)
        )
    }
}

enum RelayError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let text): return text }
    }
}

