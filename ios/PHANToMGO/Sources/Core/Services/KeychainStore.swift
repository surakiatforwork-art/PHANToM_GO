import Foundation
import Security

enum KeychainStore {
    private static let service = "com.phantom.PHANToMGO"
    static var sharedGroup: String? {
        let ownProbe: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: "group-probe-v1"
        ]
        var item = ownProbe
        item[kSecValueData as String] = Data("scope".utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(item as CFDictionary, nil)
        guard added == errSecSuccess || added == errSecDuplicateItem else { return nil }
        var lookup = ownProbe
        lookup[kSecReturnAttributes as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var attributes: CFTypeRef?
        _ = SecItemCopyMatching(lookup as CFDictionary, &attributes)
        let ownGroup = (attributes as? [String: Any])?[kSecAttrAccessGroup as String] as? String
        let candidates = KeychainGroupPolicy.candidates(
            configured: Bundle.main.object(forInfoDictionaryKey: "PHANTOM_KEYCHAIN_GROUP") as? String,
            defaultGroup: ownGroup,
            suffix: Bundle.main.object(forInfoDictionaryKey: "PHANTOM_SHARE_GROUP_SUFFIX") as? String
        )
        // Probe only harmless markers; a declared group is not proof of a signing entitlement.
        for group in candidates {
            var probe = item
            probe[kSecAttrAccessGroup as String] = group
            let status = SecItemAdd(probe as CFDictionary, nil)
            if status == errSecSuccess || status == errSecDuplicateItem { return group }
        }
        return nil
    }
    private static func query(_ key: String, shared: Bool) -> [String: Any] {
        var result: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: key
        ]
        if shared, let group = sharedGroup { result[kSecAttrAccessGroup as String] = group }
        return result
    }
    static func data(_ key: String, shared: Bool = false) -> Data? {
        if shared && sharedGroup == nil { return nil }
        var request = query(key, shared: shared)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }
    @discardableResult static func set(_ data: Data, key: String, shared: Bool = false) -> Bool {
        if shared && sharedGroup == nil { return false }
        let request = query(key, shared: shared)
        let update = [kSecValueData as String: data]
        let status = SecItemUpdate(request as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var item = request
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }
    static func remove(_ key: String, shared: Bool = false) {
        if shared && sharedGroup == nil { return }
        SecItemDelete(query(key, shared: shared) as CFDictionary)
    }
}

struct AuthIdentity: Codable {
    let uid: String
    let idToken: String
    let refreshToken: String
    let expiresAt: Date
}

struct ShareSession: Codable {
    let mainDeviceId: String
    let deviceName: String
    let displayDeviceId: String
    let navMode: DisplayNavigationMode
    let openBehavior: DisplayOpenBehavior
    var autoSend: Bool? = nil

    static var current: ShareSession? {
        KeychainStore.data("share-session", shared: true)
            .flatMap { try? JSONDecoder().decode(ShareSession.self, from: $0) }
    }
    @discardableResult func save() -> Bool {
        guard let data = try? JSONEncoder().encode(self) else { return false }
        return KeychainStore.set(data, key: "share-session", shared: true)
    }
}
