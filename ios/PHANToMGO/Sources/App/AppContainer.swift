import Combine
import Foundation
import SwiftUI
import UIKit

@MainActor
final class AppContainer: ObservableObject {
    @Published var selectedTab: AppTab
    @Published var routeInput: String
    @Published var latestPayload: RoutePayload?
    @Published var latestCommand: RouteCommand?
    @Published var mainPairing: PairingRecord?
    @Published var displayPairing: PairingRecord?
    @Published var isDisplayModeEnabled: Bool
    @Published var navMode: DisplayNavigationMode
    @Published var openBehavior: DisplayOpenBehavior
    @Published var preferredMapApp: PreferredMapApp
    @Published var authSummary = "Not signed in"
    @Published var isBusy = false
    @Published var toastMessage: String?
    @Published var scannerPresented = false
    @Published var manualPairPayload = ""
    @Published var debugLog: [String] = []
    @Published var pairCode: String

    let prefsStore: PrefsStore
    let firebaseService: FirebaseService

    let deviceId: String
    let deviceName: String

    init(
        prefsStore: PrefsStore = .shared,
        firebaseService: FirebaseService = .shared
    ) {
        self.prefsStore = prefsStore
        self.firebaseService = firebaseService
        self.deviceId = prefsStore.getOrCreateLocalDeviceId()
        self.pairCode = prefsStore.getOrCreatePairCode()
        self.deviceName = UIDevice.current.name
        self.selectedTab = prefsStore.selectedTab()
        self.routeInput = prefsStore.latestRouteInput()
        self.isDisplayModeEnabled = prefsStore.isDisplayModeEnabled()
        self.navMode = prefsStore.displayNavigationMode()
        self.openBehavior = prefsStore.displayOpenBehavior()
        self.preferredMapApp = prefsStore.preferredMapApp()
    }

    var pairingPayload: String {
        RouteParser.buildCloudPairPayload(deviceId: deviceId, pairCode: pairCode)
    }

    var pairedDisplaySummary: String {
        if let mainPairing {
            return mainPairing.displayName ?? mainPairing.displayDeviceId
        }
        return prefsStore.pairedDisplayName() ?? "Not paired"
    }

    func start() async {
        guard firebaseService.configureIfNeeded() else {
            authSummary = "Firebase configuration is missing"
            appendLog("Firebase configuration is missing or invalid")
            return
        }
        await bootstrap()
    }

    func bootstrap() async {
        isBusy = true
        defer { isBusy = false }

        do {
            let user = try await firebaseService.signInAnonymouslyIfNeeded()
            authSummary = "Signed in as \(user.uid.prefix(8))…"
            try await firebaseService.syncDeviceRecord(
                deviceId: deviceId,
                role: isDisplayModeEnabled ? "display" : "main",
                model: UIDevice.current.model,
                appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
            )
            observePairingState()
            if isDisplayModeEnabled {
                try await firebaseService.publishPairingCode(
                    deviceId: deviceId,
                    pairCode: pairCode,
                    displayName: deviceName
                )
                observeCommands()
            }
            appendLog("Bootstrap complete")
        } catch {
            authSummary = "Firebase failed: \(error.localizedDescription)"
            appendLog("Bootstrap error: \(error.localizedDescription)")
        }
    }

    func handleScannedPairPayload(_ raw: String) async {
        manualPairPayload = raw
        await claimPairingFromManualPayload()
    }

    func claimPairingFromManualPayload() async {
        guard let pairInfo = RouteParser.parsePairInfo(manualPairPayload) else {
            toastMessage = "Unsupported pairing payload"
            return
        }

        isBusy = true
        defer { isBusy = false }

        do {
            let pairing = try await firebaseService.claimPairingCode(
                deviceId: deviceId,
                deviceName: deviceName,
                pairInfo: pairInfo
            )
            prefsStore.saveCloudPairing(displayDeviceId: pairing.displayDeviceId, displayName: pairing.displayName)
            mainPairing = pairing
            toastMessage = "Pairing complete"
            appendLog("Claimed pairing to \(pairing.displayDeviceId)")
        } catch {
            toastMessage = error.localizedDescription
            appendLog("Pairing failed: \(error.localizedDescription)")
        }
    }

    func sendCurrentRoute() async {
        let payload = RouteParser.parse(routeInput, navMode: navMode)
        latestPayload = payload
        prefsStore.setLatestRouteInput(routeInput)

        guard let displayId = mainPairing?.displayDeviceId ?? prefsStore.pairedDisplayDeviceId() else {
            toastMessage = "No paired display device"
            return
        }

        isBusy = true
        defer { isBusy = false }

        do {
            try await firebaseService.sendRouteCommand(
                to: displayId,
                from: deviceId,
                from: deviceName,
                payload: payload,
                openBehavior: openBehavior
            )
            toastMessage = "Route sent"
            appendLog("Route sent to \(displayId)")
        } catch {
            toastMessage = "Send failed: \(error.localizedDescription)"
            appendLog("Route send failed: \(error.localizedDescription)")
        }
    }

    func setDisplayModeEnabled(_ enabled: Bool) async {
        isDisplayModeEnabled = enabled
        prefsStore.setDisplayModeEnabled(enabled)
        do {
            try await firebaseService.syncDeviceRecord(
                deviceId: deviceId,
                role: enabled ? "display" : "main",
                model: UIDevice.current.model,
                appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
            )
            if enabled {
                try await firebaseService.publishPairingCode(
                    deviceId: deviceId,
                    pairCode: pairCode,
                    displayName: deviceName
                )
                observeCommands()
            } else {
                try await firebaseService.removePairingCode(pairCode)
                latestCommand = nil
            }
        } catch {
            appendLog("Display mode sync failed: \(error.localizedDescription)")
        }
    }

    func openLatestRoute() {
        guard let command = latestCommand else {
            toastMessage = "No route available"
            return
        }
        let payload = RoutePayload(
            originalText: command.originalText,
            sourceApp: "firebase",
            resolvedURL: command.resolvedURL,
            query: command.originalText,
            placeName: nil,
            navigationMode: DisplayNavigationMode(rawValue: command.navigationMode) ?? navMode,
            confidence: .medium,
            parserSource: .googleMapsUrl
        )

        guard let url = MapLaunchPlanner.url(
            for: payload,
            preferredMapApp: preferredMapApp,
            openBehavior: DisplayOpenBehavior(rawValue: command.openBehavior) ?? openBehavior
        ) else {
            toastMessage = "Unable to build map URL"
            return
        }

        UIApplication.shared.open(url)
        appendLog("Opened route in maps")
    }

    func clearMainPairing() async {
        guard let displayId = mainPairing?.displayDeviceId ?? prefsStore.pairedDisplayDeviceId() else {
            prefsStore.clearPairing()
            mainPairing = nil
            return
        }

        do {
            try await firebaseService.clearMainPairing(mainDeviceId: deviceId, displayDeviceId: displayId)
            prefsStore.clearPairing()
            mainPairing = nil
            toastMessage = "Pairing cleared"
        } catch {
            toastMessage = "Clear failed: \(error.localizedDescription)"
        }
    }

    func clearDisplayPairing() async {
        do {
            try await firebaseService.clearDisplayPairing(
                displayDeviceId: deviceId,
                mainDeviceId: displayPairing?.mainDeviceId,
                pairCode: pairCode
            )
            displayPairing = nil
            latestCommand = nil
            pairCode = prefsStore.refreshPairCode()
            if isDisplayModeEnabled {
                try await firebaseService.publishPairingCode(
                    deviceId: deviceId,
                    pairCode: pairCode,
                    displayName: deviceName
                )
            }
            toastMessage = "Display pairing reset"
        } catch {
            toastMessage = "Reset failed: \(error.localizedDescription)"
        }
    }

    func updateSettings(
        navMode: DisplayNavigationMode? = nil,
        openBehavior: DisplayOpenBehavior? = nil,
        preferredMapApp: PreferredMapApp? = nil
    ) {
        if let navMode {
            self.navMode = navMode
            prefsStore.setDisplayNavigationMode(navMode)
        }
        if let openBehavior {
            self.openBehavior = openBehavior
            prefsStore.setDisplayOpenBehavior(openBehavior)
        }
        if let preferredMapApp {
            self.preferredMapApp = preferredMapApp
            prefsStore.setPreferredMapApp(preferredMapApp)
        }
    }

    func switchTab(_ tab: AppTab) {
        selectedTab = tab
        prefsStore.setSelectedTab(tab)
    }

    private func observePairingState() {
        firebaseService.observeMainPairing(deviceId: deviceId) { [weak self] pairing in
            Task { @MainActor in
                self?.mainPairing = pairing
                if let pairing {
                    self?.prefsStore.saveCloudPairing(displayDeviceId: pairing.displayDeviceId, displayName: pairing.displayName)
                }
            }
        }

        firebaseService.observeDisplayPairing(deviceId: deviceId) { [weak self] pairing in
            Task { @MainActor in
                self?.displayPairing = pairing
            }
        }
    }

    private func observeCommands() {
        firebaseService.observeDisplayCommands(deviceId: deviceId) { [weak self] command in
            Task { @MainActor in
                self?.latestCommand = command
                if command != nil {
                    self?.toastMessage = "New route received"
                }
            }
        }
    }

    private func appendLog(_ line: String) {
        debugLog.append(line)
        if debugLog.count > 80 {
            debugLog.removeFirst(debugLog.count - 80)
        }
    }
}
