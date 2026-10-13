import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        NavigationStack {
            Form {
                Section("Permissions") {
                    Button("Allow notifications") {
                        Task {
                            let enabled = await NotificationService.shared.requestPermission()
                            container.toastMessage = enabled ? "Notifications enabled" : "Notifications are not enabled."
                        }
                    }
                    Button("Allow GPS location") { container.location.requestPermission() }
                    Button("Open iOS app settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }
                Section("Display navigation") {
                    Picker("Vehicle", selection: Binding(
                        get: { container.navMode }, set: { container.updateSettings(navMode: $0) }
                    )) { ForEach(DisplayNavigationMode.allCases) { Text($0.title).tag($0) } }
                    Picker("Opening behavior", selection: Binding(
                        get: { container.openBehavior }, set: { container.updateSettings(openBehavior: $0) }
                    )) { ForEach(DisplayOpenBehavior.allCases) { Text($0.title).tag($0) } }
                    Picker("Map app", selection: Binding(
                        get: { container.preferredMapApp }, set: { container.updateSettings(preferredMapApp: $0) }
                    )) { ForEach(PreferredMapApp.allCases) { Text($0.title).tag($0) } }
                    Toggle("Open received routes automatically", isOn: flag("autoOpen"))
                    Text("Tap to open is the default to avoid switching away from Display unexpectedly. Optional automatic opening works only while this app is active. iOS may require Start in Maps. Apple Maps has no separate motorcycle routing mode.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Display GPS & power") {
                    Toggle("Speedometer & location sharing", isOn: flag("speedometer", default: true))
                    Toggle("Background GPS", isOn: Binding(
                        get: { container.prefsStore.flag("backgroundLocation") },
                        set: { value in
                            container.prefsStore.setFlag("backgroundLocation", value)
                            if value { container.location.requestPermission(always: true) }
                            container.updateFeatureSettings()
                        }
                    ))
                    Toggle("Keep Display awake in this app", isOn: flag("keepAwake"))
                    if container.prefsStore.flag("backgroundLocation") {
                        Text("Background GPS runs only when Location permission is Always. You can change this in iOS app settings.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Toggle("Automatic brightness on power changes", isOn: flag("powerAutomation"))
                    if container.prefsStore.flag("powerAutomation") {
                        brightness("Day brightness", key: "dayBrightness", fallback: 0.8)
                        brightness("Night brightness", key: "nightBrightness", fallback: 0.3)
                        brightness("Unplugged brightness", key: "unpluggedBrightness", fallback: 0.08)
                    }
                    Text("iOS allows brightness changes within this app. It does not allow automatic device lock or waking the locked screen.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("GO Cam") {
                    Toggle("1080p recording", isOn: flag("dashcam1080p"))
                    Toggle("Record microphone audio", isOn: flag("dashcamAudio"))
                    Picker("Loop storage limit", selection: Binding(
                        get: { container.prefsStore.number("dashcamStorageGB", default: 2) },
                        set: { container.prefsStore.setNumber("dashcamStorageGB", $0); container.objectWillChange.send() }
                    )) {
                        Text("1 GB").tag(1.0); Text("2 GB").tag(2.0); Text("4 GB").tag(4.0)
                    }
                    Text("Old loop recordings are automatically removed at the storage limit. Exported clips are kept separately. Camera settings apply next time the camera starts.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Main & sharing") {
                    Toggle("Send automatically from Share", isOn: flag("shareAutoSend", default: true))
                    Toggle("Paste and send immediately", isOn: flag("pasteAndSend", default: true))
                    LabeledContent("Share relay", value: container.shareAvailable ? "Shared session available" : "Use Copy/Paste if signing does not enable sharing")
                    Text("Notification reading, Android Accessibility automation and overlays are not available on iOS.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Text("Share relay needs the same permitted Keychain group in the app and extension. Free-account signing may not preserve it; Copy/Paste stays available. Saved to cloud is not confirmation of delivery.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Background notifications") {
                    Text("Remote push is not configured in this build.").font(.headline)
                    Text("Local notifications require this app to be running. When Display is in Maps or suspended, return here to receive the latest route. Native APNs requires supported Apple signing and a notification backend; granting notification permission alone does not enable it.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Connection") {
                    LabeledContent("Cloud", value: container.authSummary)
                    LabeledContent("Local LAN", value: container.localRelay.status)
                    LabeledContent("Version", value: container.currentAppVersion)
                    Button("Reconnect cloud") { Task { await container.start() } }
                    DisclosureGroup("Device information") {
                        Text(container.deviceId).font(.caption.monospaced()).textSelection(.enabled)
                    }
                }
                Section("Diagnostics") {
                    DisclosureGroup("Recent logs") {
                        ForEach(Array(container.debugLog.suffix(30).enumerated()), id: \.offset) { _, line in
                            Text(line).font(.caption.monospaced())
                        }
                    }
                    Text("PINs and authentication tokens are never included in logs. Location is shared only with the paired Main when Display GPS is enabled.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
    }
    private func flag(_ key: String, default fallback: Bool = false) -> Binding<Bool> {
        Binding(get: { container.prefsStore.flag(key, default: fallback) },
                set: { container.prefsStore.setFlag(key, $0); container.updateFeatureSettings(); container.objectWillChange.send() })
    }
    private func brightness(_ title: String, key: String, fallback: Double) -> some View {
        VStack(alignment: .leading) {
            Text(title).font(.footnote)
            Slider(value: Binding(
                get: { container.prefsStore.number(key, default: fallback) },
                set: { container.prefsStore.setNumber(key, $0); container.power.apply(); container.objectWillChange.send() }
            ), in: 0.01...1)
        }
    }
}

