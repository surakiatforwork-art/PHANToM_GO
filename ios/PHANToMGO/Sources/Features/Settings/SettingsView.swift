import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        NavigationStack {
            Form {
                Section("Display Navigation") {
                    Picker("Navigation Mode", selection: Binding(
                        get: { container.navMode },
                        set: { container.updateSettings(navMode: $0) }
                    )) {
                        ForEach(DisplayNavigationMode.allCases) { mode in
                            Text(mode.title).tag(mode)
                        }
                    }

                    Picker("Open Behavior", selection: Binding(
                        get: { container.openBehavior },
                        set: { container.updateSettings(openBehavior: $0) }
                    )) {
                        ForEach(DisplayOpenBehavior.allCases) { behavior in
                            Text(behavior.title).tag(behavior)
                        }
                    }

                    Picker("Preferred Map App", selection: Binding(
                        get: { container.preferredMapApp },
                        set: { container.updateSettings(preferredMapApp: $0) }
                    )) {
                        ForEach(PreferredMapApp.allCases) { app in
                            Text(app.title).tag(app)
                        }
                    }
                }

                Section("Connection") {
                    LabeledContent("Device ID", value: container.deviceId)
                    LabeledContent("Pair Code", value: container.pairCode)
                    LabeledContent("Auth", value: container.authSummary)
                }

                Section("Diagnostics") {
                    if container.debugLog.isEmpty {
                        Text("No log entries yet")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(Array(container.debugLog.enumerated()), id: \.offset) { _, line in
                            Text(line)
                                .font(.caption.monospaced())
                        }
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}

