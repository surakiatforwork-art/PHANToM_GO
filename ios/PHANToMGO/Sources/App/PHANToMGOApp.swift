import SwiftUI

@main
struct PHANToMGOApp: App {
    @StateObject private var container = AppContainer()

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environmentObject(container)
                .task {
                    await container.start()
                }
        }
    }
}

struct RootTabView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        TabView(selection: Binding(
            get: { container.selectedTab },
            set: { container.switchTab($0) }
        )) {
            HomeView()
                .tabItem {
                    Label("Home", systemImage: "house.fill")
                }
                .tag(AppTab.home)

            DisplayView()
                .tabItem {
                    Label("Display", systemImage: "display")
                }
                .tag(AppTab.display)

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape.fill")
                }
                .tag(AppTab.settings)
        }
        .tint(.cyan)
    }
}

