import Foundation

enum RuntimeConfiguration {
    static var apiKey: String {
        configuration["API_KEY"] as? String ?? ""
    }
    static var databaseURL: String {
        configuration["DATABASE_URL"] as? String
            ?? "https://phantom-go-default-rtdb.asia-southeast1.firebasedatabase.app"
    }
    private static var configuration: [String: Any] {
        guard let url = Bundle.main.url(forResource: "FirebaseConfiguration", withExtension: "plist"),
              let data = try? Data(contentsOf: url),
              let value = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any] else {
            return [:]
        }
        return value
    }
}

actor AuthSession {
    static let shared = AuthSession()
    private var identity: AuthIdentity?
    private var inFlight: Task<AuthIdentity, Error>?

    func current(allowCreate: Bool = true) async throws -> AuthIdentity {
        if let task = inFlight { return try await task.value }
        let shared = KeychainStore.data("firebase-auth", shared: true)
            .flatMap { try? JSONDecoder().decode(AuthIdentity.self, from: $0) }
        let own = KeychainStore.data("firebase-auth")
            .flatMap { try? JSONDecoder().decode(AuthIdentity.self, from: $0) }
        let isExtension = Bundle.main.bundleURL.pathExtension == "appex"
        let stored = isExtension ? shared : identity ?? own ?? shared
        if let stored, stored.expiresAt.timeIntervalSinceNow > 120 {
            identity = stored
            if !isExtension, shared?.idToken != stored.idToken, let data = try? JSONEncoder().encode(stored) {
                _ = KeychainStore.set(data, key: "firebase-auth", shared: true)
            }
            return stored
        }
        guard !RuntimeConfiguration.apiKey.isEmpty else {
            throw RelayError.message("Firebase configuration is missing from this build.")
        }
        if stored == nil && !allowCreate {
            throw RelayError.message("Open PHANToM GO and pair a Display first. Your signing tool must support the shared Keychain for Share relay.")
        }
        let task = Task { try await Self.authenticate(previous: stored) }
        inFlight = task
        defer { inFlight = nil }
        let result = try await task.value
        guard let encoded = try? JSONEncoder().encode(result),
              KeychainStore.set(encoded, key: "firebase-auth") else {
            throw RelayError.message("Unable to securely store the Firebase session.")
        }
        if KeychainStore.sharedGroup != nil {
            _ = KeychainStore.set(encoded, key: "firebase-auth", shared: true)
        }
        identity = result
        return result
    }

    private static func authenticate(previous: AuthIdentity?) async throws -> AuthIdentity {
        let base = previous == nil
            ? "https://identitytoolkit.googleapis.com/v1/accounts:signUp"
            : "https://securetoken.googleapis.com/v1/token"
        var components = URLComponents(string: base)!
        components.queryItems = [URLQueryItem(name: "key", value: RuntimeConfiguration.apiKey)]
        var request = URLRequest(url: components.url!)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        if let previous {
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            var form = URLComponents()
            form.queryItems = [
                URLQueryItem(name: "grant_type", value: "refresh_token"),
                URLQueryItem(name: "refresh_token", value: previous.refreshToken)
            ]
            request.httpBody = form.percentEncodedQuery?.data(using: .utf8)
        } else {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data(#"{"returnSecureToken":true}"#.utf8)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw RelayError.message("Firebase sign-in failed. Check internet and Anonymous Authentication.")
        }
        guard let uid = (json["localId"] ?? json["user_id"]) as? String,
              let token = (json["idToken"] ?? json["id_token"]) as? String,
              let refresh = (json["refreshToken"] ?? json["refresh_token"]) as? String else {
            throw RelayError.message("Firebase returned an invalid authentication session.")
        }
        let lifetime = Double((json["expiresIn"] ?? json["expires_in"]) as? String ?? "3600") ?? 3600
        return AuthIdentity(uid: uid, idToken: token, refreshToken: refresh,
                            expiresAt: Date().addingTimeInterval(lifetime))
    }
}
