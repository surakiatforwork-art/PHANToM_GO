import Foundation

struct DeviceRecord: Codable, Equatable {
    let deviceId: String
    let authUid: String
    let role: String
    let platform: String
    let manufacturer: String
    let model: String
    let appVersion: String
    let updatedAt: TimeInterval?
    let lastSeenAt: TimeInterval?
}

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
}

struct RouteCommand: Identifiable, Codable, Equatable {
    let id: String
    let fromMainDeviceId: String
    let fromMainName: String?
    let originalText: String
    let resolvedURL: String
    let navigationMode: String
    let openBehavior: String
    let createdAt: TimeInterval?
    let status: String?
}

