import Foundation

enum RouteDeliveryResult: Equatable {
    case acknowledged
    case queued(commandId: String)
}

struct QueuedRouteReceipt: Codable, Equatable {
    let displayDeviceId: String
    let commandId: String
    let requestId: String
    var path: String { "commands/\(displayDeviceId)/\(commandId)" }
}

struct NavigationNotificationIntent: Equatable, Sendable {
    enum Destination: String, Sendable { case route, security }
    let destination: Destination
    let displayDeviceId: String?
    let requestId: String?

    static func decode(_ data: [AnyHashable: Any]) -> Self? {
        guard let event = data["phantomEvent"] as? String,
              let destination = Destination(rawValue: event) else { return nil }
        if destination == .security {
            return Self(destination: .security, displayDeviceId: nil, requestId: nil)
        }
        guard let device = data["displayDeviceId"] as? String, FirebaseKeyValidation.valid(device),
              let request = data["clientRequestId"] as? String, FirebaseKeyValidation.valid(request) else { return nil }
        return Self(destination: .route, displayDeviceId: device, requestId: request)
    }

    var userInfo: [String: String] {
        var data = ["phantomEvent": destination.rawValue]
        data["displayDeviceId"] = displayDeviceId
        data["clientRequestId"] = requestId
        return data
    }
}

enum RouteInboxPolicy {
    static func latest(_ commands: [RouteCommand], mainId: String, mainAuthUid: String? = nil,
                       cached: RouteCommand? = nil) -> RouteCommand? {
        (commands + (cached.map { [$0] } ?? []))
            .filter {
                $0.fromMainDeviceId == mainId && (mainAuthUid == nil || $0.fromMainAuthUid == mainAuthUid)
                    && ["pending", "received", "opened"].contains($0.status ?? "")
                    && RouteParser.parse($0.resolvedURL, navMode: .driving).resolvedURL != nil
            }
            .max {
                let left = $0.createdAt ?? 0, right = $1.createdAt ?? 0
                return left == right ? $0.id < $1.id : left < right
            }
    }
}

enum KeychainGroupPolicy {
    static func candidates(configured: String?, defaultGroup: String?, suffix: String?) -> [String] {
        let usable = configured.flatMap { $0.isEmpty || $0.contains("$(") ? nil : $0 }
        let prefix = defaultGroup?.split(separator: ".").first.map(String.init)
        let qualifiedSuffix = suffix.flatMap { $0.isEmpty || $0.contains("$(") ? nil : $0 }
            ?? usable.map { group in
                let parts = group.split(separator: ".", maxSplits: 1)
                return parts.count == 2 && parts[0].count == 10 ? String(parts[1]) : group
            }
        var result: [String] = []
        if let prefix, let qualifiedSuffix { result.append(prefix + "." + qualifiedSuffix) }
        if let usable, !result.contains(usable) { result.append(usable) }
        return result
    }
}
