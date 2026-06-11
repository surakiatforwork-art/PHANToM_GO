import FirebaseAuth
import FirebaseCore
import FirebaseDatabase
import Foundation

final class FirebaseService {
    static let shared = FirebaseService()

    private let databaseURL = "https://phantom-go-default-rtdb.asia-southeast1.firebasedatabase.app"
    private let pathDevices = "devices"
    private let pathPresence = "presence"
    private let pathPairingCodes = "pairingCodes"
    private let pathPairingsByDisplay = "pairings/byDisplay"
    private let pathPairingsByMain = "pairings/byMain"
    private let pathCommands = "commands"

    private var database: Database {
        Database.database(url: databaseURL)
    }

    private var displayPairingHandle: DatabaseHandle?
    private var displayPairingRef: DatabaseReference?
    private var commandHandle: DatabaseHandle?
    private var commandRef: DatabaseReference?
    private var mainPairingHandle: DatabaseHandle?
    private var mainPairingRef: DatabaseReference?

    private init() {}

    func configureIfNeeded() {
        guard FirebaseApp.app() == nil else { return }
        FirebaseApp.configure()
        Database.database(url: databaseURL).isPersistenceEnabled = true
    }

    func signInAnonymouslyIfNeeded() async throws -> User {
        if let user = Auth.auth().currentUser {
            return user
        }

        return try await withCheckedThrowingContinuation { continuation in
            Auth.auth().signInAnonymously { authResult, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                guard let user = authResult?.user else {
                    continuation.resume(throwing: NSError(domain: "PHANToMGO", code: -1, userInfo: [NSLocalizedDescriptionKey: "Anonymous sign-in returned no user"]))
                    return
                }

                continuation.resume(returning: user)
            }
        }
    }

    func syncDeviceRecord(
        deviceId: String,
        role: String,
        model: String,
        appVersion: String
    ) async throws {
        let user = try await signInAnonymouslyIfNeeded()
        let payload: [String: Any?] = [
            "deviceId": deviceId,
            "authUid": user.uid,
            "role": role,
            "platform": "ios",
            "manufacturer": "Apple",
            "model": model,
            "appVersion": appVersion,
            "updatedAt": ServerValue.timestamp(),
            "lastSeenAt": ServerValue.timestamp()
        ]

        try await updateChildren(path: "\(pathDevices)/\(deviceId)", payload: payload)
        try await updateChildren(path: "\(pathPresence)/\(deviceId)", payload: [
            "deviceId": deviceId,
            "authUid": user.uid,
            "role": role,
            "platform": "ios",
            "model": model,
            "updatedAt": ServerValue.timestamp()
        ])
    }

    func publishPairingCode(
        deviceId: String,
        pairCode: String,
        displayName: String
    ) async throws {
        let user = try await signInAnonymouslyIfNeeded()
        let payload: [String: Any?] = [
            "pairCode": pairCode,
            "displayDeviceId": deviceId,
            "displayAuthUid": user.uid,
            "displayName": displayName,
            "platform": "ios",
            "updatedAt": ServerValue.timestamp()
        ]
        try await setValue(path: "\(pathPairingCodes)/\(pairCode)", value: payload)
    }

    func removePairingCode(_ pairCode: String) async throws {
        try await setValue(path: "\(pathPairingCodes)/\(pairCode)", value: nil)
    }

    func claimPairingCode(
        deviceId: String,
        deviceName: String,
        pairInfo: PairInfo
    ) async throws -> PairingRecord {
        let user = try await signInAnonymouslyIfNeeded()
        guard let displayDeviceId = pairInfo.displayDeviceId, let pairCode = pairInfo.pairCode else {
            throw NSError(domain: "PHANToMGO", code: -2, userInfo: [NSLocalizedDescriptionKey: "Invalid cloud pairing payload"])
        }

        let snapshot = try await getValue(path: "\(pathPairingCodes)/\(pairCode)")
        guard snapshot.exists() else {
            throw NSError(domain: "PHANToMGO", code: -3, userInfo: [NSLocalizedDescriptionKey: "Pairing code not found"])
        }

        let codeDisplayId = snapshot.childSnapshot(forPath: "displayDeviceId").value as? String
        guard codeDisplayId == displayDeviceId else {
            throw NSError(domain: "PHANToMGO", code: -4, userInfo: [NSLocalizedDescriptionKey: "QR code does not match display device"])
        }

        let displayName = snapshot.childSnapshot(forPath: "displayName").value as? String
        let displayAuthUid = snapshot.childSnapshot(forPath: "displayAuthUid").value as? String

        let payload: [String: Any?] = [
            "displayDeviceId": displayDeviceId,
            "displayAuthUid": displayAuthUid,
            "displayName": displayName,
            "mainDeviceId": deviceId,
            "mainAuthUid": user.uid,
            "mainName": deviceName,
            "pairedAt": ServerValue.timestamp(),
            "updatedAt": ServerValue.timestamp(),
            "transportHint": "cloud_pending"
        ]

        let updates: [String: Any?] = [
            "\(pathPairingsByDisplay)/\(displayDeviceId)": payload,
            "\(pathPairingsByMain)/\(deviceId)": payload,
            "\(pathPairingCodes)/\(pairCode)/lastClaimedBy": deviceId,
            "\(pathPairingCodes)/\(pairCode)/lastClaimedAt": ServerValue.timestamp()
        ]
        try await updateRoot(payload: updates)

        return PairingRecord(
            displayDeviceId: displayDeviceId,
            displayAuthUid: displayAuthUid,
            displayName: displayName,
            mainDeviceId: deviceId,
            mainAuthUid: user.uid,
            mainName: deviceName,
            pairedAt: nil,
            updatedAt: nil,
            transportHint: "cloud_pending"
        )
    }

    func clearMainPairing(mainDeviceId: String, displayDeviceId: String?) async throws {
        var updates: [String: Any?] = [
            "\(pathPairingsByMain)/\(mainDeviceId)": nil
        ]
        if let displayDeviceId {
            updates["\(pathPairingsByDisplay)/\(displayDeviceId)"] = nil
        }
        try await updateRoot(payload: updates)
    }

    func clearDisplayPairing(displayDeviceId: String, mainDeviceId: String?, pairCode: String?) async throws {
        var updates: [String: Any?] = [
            "\(pathPairingsByDisplay)/\(displayDeviceId)": nil
        ]
        if let mainDeviceId {
            updates["\(pathPairingsByMain)/\(mainDeviceId)"] = nil
        }
        if let pairCode {
            updates["\(pathPairingCodes)/\(pairCode)"] = nil
        }
        try await updateRoot(payload: updates)
    }

    func sendRouteCommand(
        to displayDeviceId: String,
        from mainDeviceId: String,
        from mainName: String,
        payload: RoutePayload,
        openBehavior: DisplayOpenBehavior
    ) async throws {
        let commandId = UUID().uuidString.lowercased()
        let command: [String: Any?] = [
            "id": commandId,
            "fromMainDeviceId": mainDeviceId,
            "fromMainName": mainName,
            "originalText": payload.originalText,
            "resolvedURL": payload.resolvedURL ?? payload.originalText,
            "navigationMode": payload.navigationMode.rawValue,
            "openBehavior": openBehavior.rawValue,
            "createdAt": ServerValue.timestamp(),
            "status": "pending"
        ]
        try await setValue(path: "\(pathCommands)/\(displayDeviceId)/\(commandId)", value: command)
    }

    func observeDisplayPairing(deviceId: String, onChange: @escaping (PairingRecord?) -> Void) {
        displayPairingRef?.removeAllObservers()
        let ref = database.reference(withPath: "\(pathPairingsByDisplay)/\(deviceId)")
        displayPairingRef = ref
        displayPairingHandle = ref.observe(.value) { snapshot in
            onChange(Self.decodePairing(snapshot))
        }
    }

    func observeMainPairing(deviceId: String, onChange: @escaping (PairingRecord?) -> Void) {
        mainPairingRef?.removeAllObservers()
        let ref = database.reference(withPath: "\(pathPairingsByMain)/\(deviceId)")
        mainPairingRef = ref
        mainPairingHandle = ref.observe(.value) { snapshot in
            onChange(Self.decodePairing(snapshot))
        }
    }

    func observeDisplayCommands(deviceId: String, onCommand: @escaping (RouteCommand?) -> Void) {
        commandRef?.removeAllObservers()
        let ref = database.reference(withPath: "\(pathCommands)/\(deviceId)")
        commandRef = ref
        commandHandle = ref.observe(.childAdded) { snapshot in
            onCommand(Self.decodeCommand(snapshot))
        }
    }

    func stopObservers() {
        if let displayPairingHandle, let displayPairingRef {
            displayPairingRef.removeObserver(withHandle: displayPairingHandle)
        }
        if let mainPairingHandle, let mainPairingRef {
            mainPairingRef.removeObserver(withHandle: mainPairingHandle)
        }
        if let commandHandle, let commandRef {
            commandRef.removeObserver(withHandle: commandHandle)
        }
        displayPairingHandle = nil
        displayPairingRef = nil
        mainPairingHandle = nil
        mainPairingRef = nil
        commandHandle = nil
        commandRef = nil
    }

    private func getValue(path: String) async throws -> DataSnapshot {
        try await withCheckedThrowingContinuation { continuation in
            database.reference(withPath: path).getData { error, snapshot in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let snapshot else {
                    continuation.resume(throwing: NSError(domain: "PHANToMGO", code: -10, userInfo: [NSLocalizedDescriptionKey: "Missing snapshot"]))
                    return
                }
                continuation.resume(returning: snapshot)
            }
        }
    }

    private func setValue(path: String, value: Any?) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            database.reference(withPath: path).setValue(value) { error, _ in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    private func updateChildren(path: String, payload: [String: Any?]) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            database.reference(withPath: path).updateChildValues(payload.compactMapValues { $0 }) { error, _ in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    private func updateRoot(payload: [String: Any?]) async throws {
        let normalized = payload.reduce(into: [String: Any]()) { result, entry in
            result[entry.key] = entry.value ?? NSNull()
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            database.reference().updateChildValues(normalized) { error, _ in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    private static func decodePairing(_ snapshot: DataSnapshot) -> PairingRecord? {
        guard snapshot.exists(), let value = snapshot.value as? [String: Any] else { return nil }
        return PairingRecord(
            displayDeviceId: value["displayDeviceId"] as? String ?? "",
            displayAuthUid: value["displayAuthUid"] as? String,
            displayName: value["displayName"] as? String,
            mainDeviceId: value["mainDeviceId"] as? String ?? "",
            mainAuthUid: value["mainAuthUid"] as? String,
            mainName: value["mainName"] as? String,
            pairedAt: value["pairedAt"] as? TimeInterval,
            updatedAt: value["updatedAt"] as? TimeInterval,
            transportHint: value["transportHint"] as? String
        )
    }

    private static func decodeCommand(_ snapshot: DataSnapshot) -> RouteCommand? {
        guard snapshot.exists(), let value = snapshot.value as? [String: Any] else { return nil }
        return RouteCommand(
            id: value["id"] as? String ?? snapshot.key,
            fromMainDeviceId: value["fromMainDeviceId"] as? String ?? "",
            fromMainName: value["fromMainName"] as? String,
            originalText: value["originalText"] as? String ?? "",
            resolvedURL: value["resolvedURL"] as? String ?? "",
            navigationMode: value["navigationMode"] as? String ?? DisplayNavigationMode.driving.rawValue,
            openBehavior: value["openBehavior"] as? String ?? DisplayOpenBehavior.previewRoute.rawValue,
            createdAt: value["createdAt"] as? TimeInterval,
            status: value["status"] as? String
        )
    }
}
