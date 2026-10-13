import Foundation

final class PrefsStore {
    static let shared = PrefsStore()
    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func getOrCreateLocalDeviceId() -> String {
        if let id = defaults.string(forKey: "ios_local_device_id"), !id.isEmpty { return id }
        let id = "ios_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        defaults.set(id, forKey: "ios_local_device_id")
        return id
    }
    func getOrCreatePairCode() -> String {
        if let code = defaults.string(forKey: "ios_cloud_pair_code"), !code.isEmpty { return code }
        return refreshPairCode()
    }
    func refreshPairCode() -> String {
        let code = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        defaults.set(code, forKey: "ios_cloud_pair_code")
        return code
    }
    func localToken() -> String {
        if let token = defaults.string(forKey: "ios_local_token") { return token }
        let token = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        defaults.set(token, forKey: "ios_local_token")
        return token
    }
    func refreshLocalToken() -> String {
        let token = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        defaults.set(token, forKey: "ios_local_token")
        return token
    }
    func selectedTab() -> AppTab { AppTab(rawValue: defaults.string(forKey: "ios_selected_tab") ?? "") ?? .home }
    func setSelectedTab(_ tab: AppTab) { defaults.set(tab.rawValue, forKey: "ios_selected_tab") }
    func isDisplayModeEnabled() -> Bool { defaults.bool(forKey: "ios_display_mode_enabled") }
    func setDisplayModeEnabled(_ value: Bool) { defaults.set(value, forKey: "ios_display_mode_enabled") }
    func displayNavigationMode() -> DisplayNavigationMode {
        DisplayNavigationMode(rawValue: defaults.string(forKey: "ios_nav_mode") ?? "") ?? .driving
    }
    func setDisplayNavigationMode(_ value: DisplayNavigationMode) { defaults.set(value.rawValue, forKey: "ios_nav_mode") }
    func displayOpenBehavior() -> DisplayOpenBehavior {
        DisplayOpenBehavior(rawValue: defaults.string(forKey: "ios_open_behavior") ?? "") ?? .previewRoute
    }
    func setDisplayOpenBehavior(_ value: DisplayOpenBehavior) { defaults.set(value.rawValue, forKey: "ios_open_behavior") }
    func preferredMapApp() -> PreferredMapApp {
        PreferredMapApp(rawValue: defaults.string(forKey: "ios_preferred_map_app") ?? "") ?? .googleMaps
    }
    func setPreferredMapApp(_ value: PreferredMapApp) { defaults.set(value.rawValue, forKey: "ios_preferred_map_app") }
    func saveCloudPairing(displayDeviceId: String, displayName: String?) {
        defaults.set(displayDeviceId, forKey: "ios_paired_display_device_id")
        defaults.set(displayName, forKey: "ios_paired_display_name")
    }
    func clearPairing() {
        defaults.removeObject(forKey: "ios_paired_display_device_id")
        defaults.removeObject(forKey: "ios_paired_display_name")
        defaults.removeObject(forKey: "ios_local_endpoint")
    }
    func pairedDisplayDeviceId() -> String? { defaults.string(forKey: "ios_paired_display_device_id") }
    func pairedDisplayName() -> String? { defaults.string(forKey: "ios_paired_display_name") }
    func latestRouteInput() -> String { defaults.string(forKey: "ios_latest_route_input") ?? "" }
    func setLatestRouteInput(_ value: String) { defaults.set(value, forKey: "ios_latest_route_input") }
    func flag(_ key: String, default fallback: Bool = false) -> Bool {
        defaults.object(forKey: key) == nil ? fallback : defaults.bool(forKey: key)
    }
    func setFlag(_ key: String, _ value: Bool) { defaults.set(value, forKey: key) }
    func number(_ key: String, default fallback: Double) -> Double {
        defaults.object(forKey: key) == nil ? fallback : defaults.double(forKey: key)
    }
    func setNumber(_ key: String, _ value: Double) { defaults.set(value, forKey: key) }

    func save<T: Encodable>(_ value: T?, key: String) {
        defaults.set(value.flatMap { try? JSONEncoder().encode($0) }, forKey: key)
    }
    func load<T: Decodable>(_ type: T.Type, key: String) -> T? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(type, from: $0) }
    }
    func hasProcessed(_ key: String) -> Bool {
        let records = defaults.dictionary(forKey: "ios_route_dedupe") as? [String: Double] ?? [:]
        return records[key].map { Date().timeIntervalSince1970 - $0 < 86400 } ?? false
    }
    func markProcessed(_ key: String) {
        let now = Date().timeIntervalSince1970
        var records = (defaults.dictionary(forKey: "ios_route_dedupe") as? [String: Double] ?? [:])
            .filter { now - $0.value < 86400 }
        records[key] = now
        if records.count > 256 {
            records = Dictionary(uniqueKeysWithValues: records.sorted { $0.value > $1.value }.prefix(256).map { ($0.key, $0.value) })
        }
        defaults.set(records, forKey: "ios_route_dedupe")
    }
}

