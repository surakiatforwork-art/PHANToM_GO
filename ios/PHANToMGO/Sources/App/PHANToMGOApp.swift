import SwiftUI

@main
@MainActor
struct PHANToMGOApp: App {
    @StateObject private var container = AppContainer()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environmentObject(container)
                .environmentObject(container.security)
                .task { await container.start() }
                .onOpenURL { container.handleIncomingURL($0) }
                .onChange(of: phase) { phase in
                    Task { await container.setForeground(phase == .active) }
                }
        }
    }
}

struct RootTabView: View {
    @EnvironmentObject private var container: AppContainer
    @EnvironmentObject private var security: AntiTheftService

    var body: some View {
        TabView(selection: Binding(
            get: { container.selectedTab }, set: { container.switchTab($0) }
        )) {
            HomeView().tabItem { Label(AppTab.home.title, systemImage: AppTab.home.symbol) }.tag(AppTab.home)
            DisplayView().tabItem { Label(AppTab.display.title, systemImage: AppTab.display.symbol) }.tag(AppTab.display)
            SettingsView().tabItem { Label(AppTab.settings.title, systemImage: AppTab.settings.symbol) }.tag(AppTab.settings)
            SecurityView().tabItem { Label(AppTab.security.title, systemImage: AppTab.security.symbol) }.tag(AppTab.security)
        }
        .tint(Theme.accent)
        .alert("PHANToM GO", isPresented: Binding(
            get: { container.toastMessage != nil && !security.challengeVisible && !container.scannerPresented },
            set: { if !$0 { container.toastMessage = nil } }
        )) {
            Button("OK", role: .cancel) { container.toastMessage = nil }
        } message: { Text(container.toastMessage ?? "") }
        .fullScreenCover(isPresented: $security.challengeVisible) {
            PINChallengeView().environmentObject(security)
        }
        .onChange(of: security.challengeVisible) { container.power.setSecurityVerificationActive($0) }
    }
}

