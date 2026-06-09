import Foundation

final class PrefsStore {
    static let shared = PrefsStore()

    private let defaults = UserDefaults.standard

    private enum Key {
        static let localDeviceId = "ios_local_device_id"
        static let pairCode = "ios_cloud_pair_code"
        static let selectedTab = "ios_selected_tab"
        static let displayModeEnabled = "ios_display_mode_enabled"
        static let navMode = "ios_nav_mode"
        static let openBehavior = "ios_open_behavior"
        static let preferredMapApp = "ios_preferred_map_app"
        static let pairedDisplayDeviceId = "ios_paired_display_device_id"
        static let pairedDisplayName = "ios_paired_display_name"
        static let pairedViaCloud = "ios_paired_via_cloud"
        static let latestRouteInput = "ios_latest_route_input"
    }

    private init() {}

    func getOrCreateLocalDeviceId() -> String {
        if let existing = defaults.string(forKey: Key.localDeviceId), !existing.isEmpty {
            return existing
        }
        let newValue = "ios_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        defaults.set(newValue, forKey: Key.localDeviceId)
        return newValue
    }

    func getOrCreatePairCode() -> String {
        if let existing = defaults.string(forKey: Key.pairCode), !existing.isEmpty {
            return existing
        }
        let newValue = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12)
        let code = String(newValue)
        defaults.set(code, forKey: Key.pairCode)
        return code
    }

    func refreshPairCode() -> String {
        let code = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12))
        defaults.set(code, forKey: Key.pairCode)
        return code
    }

    func selectedTab() -> AppTab {
        guard let raw = defaults.string(forKey: Key.selectedTab) else { return .home }
        switch raw {
        case "display": return .display
        case "settings": return .settings
        default: return .home
        }
    }

    func setSelectedTab(_ tab: AppTab) {
        let raw: String
        switch tab {
        case .home: raw = "home"
        case .display: raw = "display"
        case .settings: raw = "settings"
        }
        defaults.set(raw, forKey: Key.selectedTab)
    }

    func isDisplayModeEnabled() -> Bool {
        defaults.bool(forKey: Key.displayModeEnabled)
    }

    func setDisplayModeEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Key.displayModeEnabled)
    }

    func displayNavigationMode() -> DisplayNavigationMode {
        guard let raw = defaults.string(forKey: Key.navMode),
              let mode = DisplayNavigationMode(rawValue: raw) else {
            return .driving
        }
        return mode
    }

    func setDisplayNavigationMode(_ mode: DisplayNavigationMode) {
        defaults.set(mode.rawValue, forKey: Key.navMode)
    }

    func displayOpenBehavior() -> DisplayOpenBehavior {
        guard let raw = defaults.string(forKey: Key.openBehavior),
              let behavior = DisplayOpenBehavior(rawValue: raw) else {
            return .previewRoute
        }
        return behavior
    }

    func setDisplayOpenBehavior(_ behavior: DisplayOpenBehavior) {
        defaults.set(behavior.rawValue, forKey: Key.openBehavior)
    }

    func preferredMapApp() -> PreferredMapApp {
        guard let raw = defaults.string(forKey: Key.preferredMapApp),
              let app = PreferredMapApp(rawValue: raw) else {
            return .googleMaps
        }
        return app
    }

    func setPreferredMapApp(_ app: PreferredMapApp) {
        defaults.set(app.rawValue, forKey: Key.preferredMapApp)
    }

    func saveCloudPairing(displayDeviceId: String, displayName: String?) {
        defaults.set(displayDeviceId, forKey: Key.pairedDisplayDeviceId)
        defaults.set(displayName, forKey: Key.pairedDisplayName)
        defaults.set(true, forKey: Key.pairedViaCloud)
    }

    func clearPairing() {
        defaults.removeObject(forKey: Key.pairedDisplayDeviceId)
        defaults.removeObject(forKey: Key.pairedDisplayName)
        defaults.set(false, forKey: Key.pairedViaCloud)
    }

    func pairedDisplayDeviceId() -> String? {
        defaults.string(forKey: Key.pairedDisplayDeviceId)
    }

    func pairedDisplayName() -> String? {
        defaults.string(forKey: Key.pairedDisplayName)
    }

    func isCloudPaired() -> Bool {
        defaults.bool(forKey: Key.pairedViaCloud) && pairedDisplayDeviceId() != nil
    }

    func latestRouteInput() -> String {
        defaults.string(forKey: Key.latestRouteInput) ?? ""
    }

    func setLatestRouteInput(_ input: String) {
        defaults.set(input, forKey: Key.latestRouteInput)
    }
}

