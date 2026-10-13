import Foundation

actor FirebaseService {
    static let shared = FirebaseService()
    private var observers: [String: Task<Void, Never>] = [:]
    private let session: URLSession
    private let authProvider: @Sendable (Bool) async throws -> AuthIdentity

    init(session: URLSession = .shared,
         authProvider: @escaping @Sendable (Bool) async throws -> AuthIdentity = {
             try await AuthSession.shared.current(allowCreate: $0)
         }) {
        self.session = session
        self.authProvider = authProvider
    }

    func configureIfNeeded() -> Bool { !RuntimeConfiguration.apiKey.isEmpty }
    func signInAnonymouslyIfNeeded() async throws -> AuthIdentity { try await authProvider(true) }

    private func makeRequest(path: String, method: String = "GET", value: Any? = nil) async throws -> URLRequest {
        let identity = try await authProvider(true)
        var components = URLComponents(string: RuntimeConfiguration.databaseURL + "/" + path + ".json")!
        components.queryItems = [URLQueryItem(name: "auth", value: identity.idToken)]
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.timeoutInterval = 15
        if method != "GET" {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: value ?? NSNull(), options: [.fragmentsAllowed])
        }
        return request
    }

    func request(path: String, method: String = "GET", value: Any? = nil, timeout: TimeInterval = 15) async throws -> Any? {
        var request = try await makeRequest(path: path, method: method, value: value)
        request.timeoutInterval = max(0.5, timeout)
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw RelayError.message("The server did not return a response.")
        }
        guard (200..<300).contains(response.statusCode) else {
            if response.statusCode == 401 || response.statusCode == 403 {
                throw RelayError.message("Permission denied. Disconnect the previous pair on Display and scan its new QR.")
            }
            throw RelayError.message("Cloud connection failed (HTTP \(response.statusCode)).")
        }
        if data.isEmpty { return nil }
        let result = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        return result is NSNull ? nil : result
    }

    func syncDeviceRecord(deviceId: String, role: String, model: String, appVersion: String,
                          local: LocalEndpoint? = nil, connected: Bool = true) async throws {
        let identity = try await authProvider(true)
        let record: [String: Any] = [
            "deviceId": deviceId, "authUid": identity.uid, "role": role, "platform": "ios",
            "manufacturer": "Apple", "model": model, "appVersion": appVersion,
            "updatedAt": Self.timestamp, "lastSeenAt": Self.timestamp
        ]
        var presence = record
        presence["connected"] = connected
        if let local, role == "display" {
            presence["localIp"] = local.ip
            presence["localPort"] = local.port
            presence["localToken"] = local.token
            presence["localTokenHint"] = local.tokenHint
        }
        _ = try await request(path: "devices/\(deviceId)", method: "PATCH", value: record)
        _ = try await request(path: "presence/\(deviceId)", method: "PATCH", value: presence)
    }

    func publishPairingCode(deviceId: String, pairCode: String, displayName: String,
                            local: LocalEndpoint? = nil) async throws {
        let identity = try await authProvider(true)
        var record: [String: Any] = [
            "pairCode": pairCode, "displayDeviceId": deviceId, "displayAuthUid": identity.uid,
            "displayName": displayName, "platform": "ios", "updatedAt": Self.timestamp
        ]
        if let local {
            record["localIp"] = local.ip
            record["localPort"] = local.port
            record["localToken"] = local.token
        }
        _ = try await request(path: "pairingCodes/\(pairCode)", method: "PUT", value: record)
    }

    func removePairingCode(_ code: String) async throws {
        _ = try await request(path: "pairingCodes/\(code)", method: "DELETE")
    }

    func claimPairingCode(deviceId: String, deviceName: String, pairInfo: PairInfo) async throws -> PairingRecord {
        guard let displayId = pairInfo.displayDeviceId, let code = pairInfo.pairCode,
              displayId != deviceId, Self.validKey(displayId), Self.validKey(code) else {
            throw RelayError.message("Scan a valid cloud QR from another Display device.")
        }
        let identity = try await authProvider(true)
        guard let record = try await request(path: "pairingCodes/\(code)") as? [String: Any],
              record["displayDeviceId"] as? String == displayId,
              let displayUid = record["displayAuthUid"] as? String, !displayUid.isEmpty else {
            throw RelayError.message("Pairing QR expired or was reset. Scan the current Display QR.")
        }
        if let existing = PairingRecord.decode(try await request(path: "pairings/byDisplay/\(displayId)")),
           existing.mainDeviceId != deviceId {
            throw RelayError.message("Display is already paired. Disconnect its current Main on Display first.")
        }
        let previous = PairingRecord.decode(try await request(path: "pairings/byMain/\(deviceId)"))
        let name = record["displayName"] as? String ?? displayId
        let pair: [String: Any] = [
            "displayDeviceId": displayId, "displayAuthUid": displayUid, "displayName": name,
            "mainDeviceId": deviceId, "mainAuthUid": identity.uid, "mainName": deviceName,
            "pairedAt": Self.timestamp, "updatedAt": Self.timestamp, "transportHint": "cloud"
        ]
        var updates: [String: Any] = [
            "pairings/byMain/\(deviceId)": pair, "pairings/byDisplay/\(displayId)": pair,
            "pairingCodes/\(code)/lastClaimedBy": deviceId, "pairingCodes/\(code)/lastClaimedAt": Self.timestamp
        ]
        if let previous, previous.displayDeviceId != displayId {
            let oldDisplay = PairingRecord.decode(try await request(path: "pairings/byDisplay/\(previous.displayDeviceId)"))
            if oldDisplay?.mainDeviceId == deviceId {
                updates["pairings/byDisplay/\(previous.displayDeviceId)"] = NSNull()
            }
        }
        _ = try await request(path: "", method: "PATCH", value: updates)
        return PairingRecord.decode(pair)!
    }

    func clearMainPairing(mainDeviceId: String, displayDeviceId: String?) async throws {
        let current = PairingRecord.decode(try await request(path: "pairings/byMain/\(mainDeviceId)"))
        var updates: [String: Any] = ["pairings/byMain/\(mainDeviceId)": NSNull()]
        if let current {
            let peer = PairingRecord.decode(try await request(path: "pairings/byDisplay/\(current.displayDeviceId)"))
            if peer?.mainDeviceId == mainDeviceId {
                updates["pairings/byDisplay/\(current.displayDeviceId)"] = NSNull()
            }
        }
        _ = try await request(path: "", method: "PATCH", value: updates)
    }

    func clearDisplayPairing(displayDeviceId: String, mainDeviceId: String?, pairCode: String?) async throws {
        let current = PairingRecord.decode(try await request(path: "pairings/byDisplay/\(displayDeviceId)"))
        var updates: [String: Any] = ["pairings/byDisplay/\(displayDeviceId)": NSNull()]
        if let current {
            let peer = PairingRecord.decode(try await request(path: "pairings/byMain/\(current.mainDeviceId)"))
            if peer?.displayDeviceId == displayDeviceId {
                updates["pairings/byMain/\(current.mainDeviceId)"] = NSNull()
            }
        }
        if let code = pairCode { updates["pairingCodes/\(code)"] = NSNull() }
        _ = try await request(path: "", method: "PATCH", value: updates)
    }

    func sendRouteCommand(to displayId: String, from mainId: String, mainName: String,
                          payload: RoutePayload, openBehavior: DisplayOpenBehavior,
                          requestId: String = UUID().uuidString,
                          maxAttempts: Int = 2, acknowledgementTimeout: TimeInterval = 3) async throws -> RouteDeliveryResult {
        let identity = try await authProvider(false)
        guard let url = payload.resolvedURL, Self.validKey(displayId), Self.validKey(mainId) else {
            throw RelayError.message("Enter a Google Maps link, coordinates or a destination.")
        }
        var pendingIds: [String] = []
        for attempt in 1...max(1, min(3, maxAttempts)) {
            try Task.checkCancellation()
            let commandId = UUID().uuidString.lowercased()
            let command = RouteDeliveryPolicy.command(
                id: commandId, requestId: requestId, mainId: mainId, uid: identity.uid,
                url: url, attempt: attempt, timestamp: Self.timestamp
            )
            do {
                _ = try await request(path: "", method: "PATCH", value: [
                    "commands/\(displayId)/\(commandId)": command,
                    "pairings/byMain/\(mainId)/lastActivityAt": Self.timestamp,
                    "pairings/byDisplay/\(displayId)/lastActivityAt": Self.timestamp
                ])
                pendingIds.append(commandId)
            } catch let error as URLError {
                try Task.checkCancellation()
                if let accepted = pendingIds.last { return .queued(commandId: accepted) }
                throw error
            }
            let deadline = Date().addingTimeInterval(max(0, acknowledgementTimeout))
            while Date() < deadline {
                try Task.checkCancellation()
                for id in pendingIds {
                    let remaining = deadline.timeIntervalSinceNow
                    guard remaining > 0 else { break }
                    do {
                        if let data = try await request(path: "commands/\(displayId)/\(id)", timeout: min(2, remaining)) as? [String: Any],
                           RouteDeliveryPolicy.isAcknowledged(data["status"] as? String) {
                            return .acknowledged
                        }
                    } catch is URLError {
                        try Task.checkCancellation()
                        return .queued(commandId: pendingIds.last!)
                    }
                }
                let remaining = deadline.timeIntervalSinceNow
                if remaining > 0 { try await Task.sleep(nanoseconds: UInt64(min(0.4, remaining) * 1_000_000_000)) }
            }
        }
        return .queued(commandId: pendingIds.last!)
    }

    func acknowledge(deviceId: String, commandId: String, status: String) async throws {
        _ = try await request(path: "commands/\(deviceId)/\(commandId)", method: "PATCH",
                              value: ["status": status, "processedAt": Self.timestamp])
    }

    func updateLocation(deviceId: String, latitude: Double, longitude: Double, accuracy: Double,
                        speed: Double, capturedAt: Date) async throws {
        _ = try await request(path: "presence/\(deviceId)", method: "PATCH", value: [
            "locationAvailable": true, "locationLat": latitude, "locationLng": longitude,
            "locationAccuracyMeters": accuracy, "locationSpeedKmh": Int(speed.rounded()),
            "locationCapturedAt": capturedAt.timeIntervalSince1970 * 1000,
            "locationProvider": "corelocation", "updatedAt": Self.timestamp
        ])
    }

    func sendSecurityAlert(displayId: String, mainId: String, name: String, type: String,
                           sessionId: String, attempts: Int, jackPlugged: Bool, state: String,
                           presence: DisplayPresence?) async throws {
        let identity = try await authProvider(true)
        let id = UUID().uuidString.lowercased()
        var alert: [String: Any] = [
            "alertId": id, "eventType": type, "fromDisplayDeviceId": displayId,
            "fromDisplayAuthUid": identity.uid, "displayName": name, "toMainDeviceId": mainId,
            "sessionId": sessionId, "wrongAttemptCount": attempts, "jackPlugged": jackPlugged,
            "alarmState": state, "platform": "ios", "createdAt": Self.timestamp,
            "eventAt": Date().timeIntervalSince1970 * 1000, "locationAvailable": false
        ]
        if let presence, let lat = presence.latitude, let lng = presence.longitude {
            alert["locationAvailable"] = true
            alert["lat"] = lat; alert["lng"] = lng
            alert["locationCapturedAt"] = (presence.locationDate ?? Date()).timeIntervalSince1970 * 1000
            alert["accuracyMeters"] = presence.accuracy
            alert["speedKmh"] = presence.speedKmh
        }
        _ = try await request(path: "securityAlerts/byMain/\(mainId)/\(id)", method: "PUT", value: alert)
    }

    func observe(path: String, onChange: @escaping (Any?) -> Void,
                 onError: @escaping (String) -> Void) {
        guard observers[path] == nil else { return }
        observers[path] = Task { [weak self] in
            guard let self else { return }
            var retry: UInt64 = 1
            while !Task.isCancelled {
                do {
                    var request = try await self.makeRequest(path: path)
                    request.timeoutInterval = 3600
                    request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                    let (bytes, response) = try await self.session.bytes(for: request)
                    guard let response = response as? HTTPURLResponse,
                          response.statusCode == 200 else {
                        throw RelayError.message("Cloud listener unavailable. Check connection and pairing.")
                    }
                    var tree = FirebaseStreamTree()
                    var event = ""
                    retry = 1
                    for try await line in bytes.lines {
                        try Task.checkCancellation()
                        if line.hasPrefix("event:") { event = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces) }
                        if line.hasPrefix("data:") {
                            let eventData = String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
                            defer { event = "" }
                            if event == "cancel" || event == "auth_revoked" {
                                throw RelayError.message("Cloud access changed. Reopen Display or pair again.")
                            }
                            guard event == "put" || event == "patch",
                                  let encoded = eventData.data(using: .utf8),
                                  let update = try JSONSerialization.jsonObject(with: encoded) as? [String: Any],
                                  let updatePath = update["path"] as? String else { continue }
                            tree.apply(event: event, path: updatePath, value: update["data"])
                            if path.split(separator: "/").count == 2 && path.hasPrefix("commands/") {
                                tree.keepPendingCommands()
                            }
                            onChange(tree.value)
                        }
                    }
                } catch {
                    if Task.isCancelled { return }
                    onError(error.localizedDescription)
                }
                try? await Task.sleep(nanoseconds: retry * 1_000_000_000)
                retry = min(retry * 2, 15)
            }
        }
    }

    func stopObserver(path: String) { observers.removeValue(forKey: path)?.cancel() }
    func stopObservers() {
        observers.values.forEach { $0.cancel() }
        observers.removeAll()
    }
    static var timestamp: [String: String] { [".sv": "timestamp"] }
    static func validKey(_ value: String) -> Bool {
        FirebaseKeyValidation.valid(value)
    }
}

enum RouteDeliveryPolicy {
    static func isAcknowledged(_ status: String?) -> Bool { status == "received" || status == "opened" }
    static func command(id: String, requestId: String, mainId: String, uid: String, url: String,
                        attempt: Int, timestamp: Any) -> [String: Any] {
        [
            "commandId": id, "clientRequestId": requestId, "attempt": attempt,
            "fromMainDeviceId": mainId, "fromMainAuthUid": uid, "url": url,
            "transport": "firebase_rtdb", "status": "pending", "createdAt": timestamp
        ]
    }
}

struct FirebaseStreamTree {
    private(set) var value: Any?
    mutating func keepPendingCommands() {
        guard let commands = value as? [String: Any] else { return }
        let pending = commands.filter { ($0.value as? [String: Any])?["status"] as? String == "pending" }
            .sorted {
                (($0.value as? [String: Any])?["createdAt"] as? NSNumber)?.doubleValue ?? 0
                    > (($1.value as? [String: Any])?["createdAt"] as? NSNumber)?.doubleValue ?? 0
            }
        value = Dictionary(uniqueKeysWithValues: pending.prefix(200).map { ($0.key, $0.value) })
    }
    mutating func apply(event: String, path: String, value incoming: Any?) {
        if event == "patch", let dictionary = incoming as? [String: Any] {
            for (key, child) in dictionary { put(path: path + "/" + key, value: child) }
        } else {
            put(path: path, value: incoming)
        }
    }
    private mutating func put(path: String, value incoming: Any?) {
        let keys = path.split(separator: "/").map(String.init)
        value = Self.replace(root: value, keys: ArraySlice(keys), incoming: incoming)
    }
    private static func replace(root: Any?, keys: ArraySlice<String>, incoming: Any?) -> Any? {
        guard let key = keys.first else { return incoming is NSNull ? nil : incoming }
        var dictionary = root as? [String: Any] ?? [:]
        dictionary[key] = replace(root: dictionary[key], keys: keys.dropFirst(), incoming: incoming)
        return dictionary
    }
}
