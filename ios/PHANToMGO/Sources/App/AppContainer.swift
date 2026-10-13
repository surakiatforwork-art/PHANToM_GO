import Combine
import CoreLocation
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
    @Published var peerPresence: DisplayPresence?
    @Published var peerAddress = ""
    @Published var securityAlerts: [SecurityAlert] = []
    @Published var isDisplayModeEnabled: Bool
    @Published var navMode: DisplayNavigationMode
    @Published var openBehavior: DisplayOpenBehavior
    @Published var preferredMapApp: PreferredMapApp
    @Published var authSummary = "Connecting to cloud"
    @Published var isBusy = false
    @Published var toastMessage: String?
    @Published var scannerPresented = false
    @Published var manualPairPayload = ""
    @Published var debugLog: [String] = []
    @Published var pairCode: String
    @Published var isForeground = true
    @Published var pendingRoute: RoutePayload?
    @Published var dashcamPresented = false
    @Published var shareAvailable = false
    @Published var heartbeatDate = Date()

    let prefsStore: PrefsStore
    let firebaseService: FirebaseService
    let location = LocationService()
    let power = PowerService()
    let security = AntiTheftService()
    let localRelay = LocalRelayService()
    let dashcam = DashcamService()
    let deviceId: String
    let deviceName: String

    private var starting = false
    private var started = false
    private var heartbeat: Timer?
    private var routeTask: Task<Void, Never>?
    private var receiving = Set<String>()
    private var latestLocationSent = Date.distantPast
    private var deferredMapRequestId: String?
    private let geocoder = CLGeocoder()

    init(prefsStore: PrefsStore = .shared, firebaseService: FirebaseService = .shared) {
        self.prefsStore = prefsStore
        self.firebaseService = firebaseService
        deviceId = prefsStore.getOrCreateLocalDeviceId()
        pairCode = prefsStore.getOrCreatePairCode()
        deviceName = UIDevice.current.name
        selectedTab = prefsStore.selectedTab()
        routeInput = prefsStore.latestRouteInput()
        isDisplayModeEnabled = prefsStore.isDisplayModeEnabled()
        navMode = prefsStore.displayNavigationMode()
        openBehavior = prefsStore.displayOpenBehavior()
        preferredMapApp = prefsStore.preferredMapApp()
        latestCommand = prefsStore.load(RouteCommand.self, key: "latestCommand")
        mainPairing = prefsStore.load(PairingRecord.self, key: "mainPairing")
        displayPairing = prefsStore.load(PairingRecord.self, key: "displayPairing")
        pendingRoute = prefsStore.load(RoutePayload.self, key: "pendingRoute")
        securityAlerts = prefsStore.load([SecurityAlert].self, key: "securityAlerts") ?? []
        peerPresence = nil

        location.onLocation = { [weak self] location in
            guard let self, self.isDisplayModeEnabled,
                  Date().timeIntervalSince(self.latestLocationSent) >= 20 else { return }
            self.latestLocationSent = Date()
            Task {
                do {
                    try await self.firebaseService.updateLocation(
                        deviceId: self.deviceId, latitude: location.coordinate.latitude,
                        longitude: location.coordinate.longitude, accuracy: location.horizontalAccuracy,
                        speed: max(0, location.speed * 3.6), capturedAt: location.timestamp
                    )
                } catch { self.appendLog("Location sync waiting for connection") }
            }
        }
        localRelay.onRoute = { [weak self] url, requestId in
            guard let self, self.isDisplayModeEnabled, let pairing = self.displayPairing else { return false }
            let command = RouteCommand(id: "local_" + requestId, clientRequestId: requestId,
                                       fromMainDeviceId: pairing.mainDeviceId,
                                       originalText: url, resolvedURL: url,
                                       createdAt: Date().timeIntervalSince1970 * 1000, status: "pending")
            self.receive(command)
            return true
        }
        localRelay.onEndpointChange = { [weak self] in
            Task { @MainActor in await self?.publishPresenceAndQR() }
        }
        security.onEvent = { [weak self] type, attempts, sessionId in
            guard let self, let pairing = self.displayPairing else { return }
            Task {
                do {
                    let point = self.location.location
                    var presence = DisplayPresence()
                    presence.latitude = point?.coordinate.latitude
                    presence.longitude = point?.coordinate.longitude
                    presence.accuracy = point?.horizontalAccuracy
                    presence.locationDate = point?.timestamp
                    presence.speedKmh = self.location.speedKmh
                    try await self.firebaseService.sendSecurityAlert(
                        displayId: self.deviceId, mainId: pairing.mainDeviceId, name: self.deviceName,
                        type: type, sessionId: sessionId, attempts: attempts,
                        jackPlugged: self.security.jackConnected, state: self.security.status, presence: presence
                    )
                    self.appendLog("Security alert delivered")
                } catch { self.appendLog("Security alert could not reach Main; check internet") }
            }
        }
        security.onVerified = { [weak self] in
            guard let self, let deferred = self.deferredMapRequestId,
                  self.latestCommand?.clientRequestId == deferred else { return }
            self.deferredMapRequestId = nil
            self.scheduleMapOpening()
        }
        power.onPowerDisconnected = { [weak self] in
            guard let self, self.prefsStore.flag("powerAutomation") else { return }
            NotificationService.shared.post(id: "power-disconnected", title: "Display power disconnected",
                                            body: "Brightness was reduced. iOS does not allow this app to lock the device.")
        }
        #if DEBUG
        if ProcessInfo.processInfo.environment["PHANTOM_UI_TEST_MODE"] == "1" {
            isDisplayModeEnabled = true
            displayPairing = PairingRecord(
                displayDeviceId: deviceId, displayAuthUid: nil, displayName: "iOS Display",
                mainDeviceId: "test-main", mainAuthUid: nil, mainName: "Test Main",
                pairedAt: nil, updatedAt: nil, transportHint: "test"
            )
            location.setUITestSpeed(123)
        }
        #endif
    }

    var pairingPayload: String {
        RouteParser.buildCloudPairPayload(deviceId: deviceId, pairCode: pairCode, local: localRelay.endpoint)
    }
    var pairedDisplaySummary: String {
        mainPairing?.displayName ?? prefsStore.pairedDisplayName() ?? "Not paired"
    }
    var hasMainPair: Bool { mainPairing != nil || prefsStore.pairedDisplayDeviceId() != nil }
    var currentAppVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
    }

    func start() async {
        #if DEBUG
        if ProcessInfo.processInfo.environment["PHANTOM_UI_TEST_MODE"] == "1"
            || ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
            || NSClassFromString("XCTestCase") != nil {
            authSummary = "Simulator test mode"
            return
        }
        #endif
        guard !starting else { return }
        starting = true
        defer { starting = false }
        guard await firebaseService.configureIfNeeded() else {
            authSummary = "Firebase configuration missing from this build"
            return
        }
        do {
            _ = try await firebaseService.signInAnonymouslyIfNeeded()
            try await firebaseService.syncDeviceRecord(
                deviceId: deviceId, role: isDisplayModeEnabled ? "display" : "main",
                model: deviceName, appVersion: currentAppVersion, local: localRelay.endpoint
            )
            await observePairingState()
            if isDisplayModeEnabled {
                localRelay.start(token: prefsStore.localToken())
                await publishPresenceAndQR()
                await observeCommands()
            } else {
                localRelay.stop()
                await observeSecurityAlerts()
            }
            authSummary = "Cloud connected"
            started = true
            updateFeatureSettings()
            if heartbeat == nil {
                heartbeat = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
                    Task { @MainActor in
                        guard let self else { return }
                        self.heartbeatDate = Date()
                        if self.isForeground || self.backgroundLocationEnabled {
                            await self.publishPresenceAndQR()
                        }
                    }
                }
            }
            appendLog("Cloud connection ready")
        } catch {
            authSummary = "Offline; reopen or retry when internet is available"
            appendLog(error.localizedDescription)
        }
    }

    private var backgroundLocationEnabled: Bool {
        prefsStore.flag("backgroundLocation") && location.authorization == .authorizedAlways
    }

    func setForeground(_ active: Bool) async {
        isForeground = active
        #if DEBUG
        if ProcessInfo.processInfo.environment["PHANTOM_UI_TEST_MODE"] == "1" { return }
        #endif
        power.setState(display: isDisplayModeEnabled, foreground: active)
        security.refreshJack()
        if active {
            await start()
        } else {
            routeTask?.cancel()
            dashcam.stopForBackground()
            if !isDisplayModeEnabled || !backgroundLocationEnabled {
                await firebaseService.stopObservers()
                try? await firebaseService.syncDeviceRecord(
                    deviceId: deviceId, role: isDisplayModeEnabled ? "display" : "main",
                    model: deviceName, appVersion: currentAppVersion, connected: false
                )
            }
        }
    }

    func updateFeatureSettings() {
        location.setEnabled(isDisplayModeEnabled && prefsStore.flag("speedometer", default: true),
                            background: prefsStore.flag("backgroundLocation"))
        power.setState(display: isDisplayModeEnabled, foreground: isForeground)
        updateShareSession()
    }

    private func updateShareSession() {
        guard let display = mainPairing?.displayDeviceId ?? prefsStore.pairedDisplayDeviceId() else {
            KeychainStore.remove("share-session", shared: true)
            shareAvailable = false
            return
        }
        shareAvailable = ShareSession(
            mainDeviceId: deviceId, deviceName: deviceName, displayDeviceId: display,
            navMode: navMode, openBehavior: openBehavior
        ).save()
    }

    func handleScannedPairPayload(_ raw: String) async {
        manualPairPayload = raw
        await claimPairingFromManualPayload()
    }

    func claimPairingFromManualPayload() async {
        guard !isBusy else { return }
        guard !isDisplayModeEnabled, displayPairing == nil else {
            toastMessage = "Disconnect this device's Display pairing before using it as Main."
            return
        }
        guard let info = RouteParser.parsePairInfo(manualPairPayload), info.isCloudPairing else {
            toastMessage = "Scan the current Firebase QR from Display."
            return
        }
        isBusy = true
        defer { isBusy = false }
        do {
            let pairing = try await firebaseService.claimPairingCode(
                deviceId: deviceId, deviceName: deviceName, pairInfo: info
            )
            mainPairing = pairing
            prefsStore.saveCloudPairing(displayDeviceId: pairing.displayDeviceId, displayName: pairing.displayName)
            prefsStore.save(pairing, key: "mainPairing")
            if let ip = info.ip, let port = info.port, let token = info.token {
                prefsStore.save(LocalEndpoint(ip: ip, port: port, token: token), key: "ios_local_endpoint")
            }
            updateShareSession()
            await observePeerPresence(pairing.displayDeviceId)
            toastMessage = "Connected to Display"
            scannerPresented = false
        } catch {
            toastMessage = error.localizedDescription
            appendLog("Pairing failed: " + error.localizedDescription)
        }
    }

    func sendCurrentRoute(retryPending: Bool = false) async {
        guard !isBusy, !isDisplayModeEnabled else { return }
        guard let display = mainPairing?.displayDeviceId ?? prefsStore.pairedDisplayDeviceId() else {
            toastMessage = "Pair a Display device first."
            return
        }
        isBusy = true
        defer { isBusy = false }
        let payload = retryPending ? pendingRoute ?? RouteParser.parse(routeInput, navMode: navMode)
                                   : RouteParser.parse(routeInput, navMode: navMode)
        guard payload.resolvedURL != nil else {
            toastMessage = "Enter a map link, coordinates or destination name."
            return
        }
        latestPayload = payload
        prefsStore.setLatestRouteInput(routeInput)
        do {
            let local = prefsStore.load(LocalEndpoint.self, key: "ios_local_endpoint")
            let sentLocally: Bool
            if let local, let url = payload.resolvedURL {
                sentLocally = await LocalRelayService.send(url: url, requestId: payload.id, endpoint: local)
            } else { sentLocally = false }
            if !sentLocally {
                try await firebaseService.sendRouteCommand(
                    to: display, from: deviceId, mainName: deviceName,
                    payload: payload, openBehavior: openBehavior, requestId: payload.id
                )
            }
            pendingRoute = nil
            prefsStore.save(Optional<RoutePayload>.none, key: "pendingRoute")
            toastMessage = sentLocally ? "Display received the route over local Wi-Fi" : "Display acknowledged the route"
            appendLog("Route delivered")
        } catch {
            pendingRoute = payload
            prefsStore.save(payload, key: "pendingRoute")
            toastMessage = error.localizedDescription
            appendLog("Route remains available for retry")
        }
    }

    func setDisplayModeEnabled(_ enabled: Bool) async {
        guard !isBusy, enabled != isDisplayModeEnabled else { return }
        if enabled && hasMainPair {
            toastMessage = "Disconnect the paired Display on Home before changing this device to Display."
            return
        }
        if !enabled && security.isArmed {
            toastMessage = "Disarm anti-theft with your PIN before turning off Display."
            selectedTab = .security
            return
        }
        isDisplayModeEnabled = enabled
        prefsStore.setDisplayModeEnabled(enabled)
        if !enabled {
            localRelay.stop()
            routeTask?.cancel()
            await firebaseService.stopObserver(path: "commands/\(deviceId)")
            try? await firebaseService.removePairingCode(pairCode)
        }
        await start()
    }

    func openLatestRoute() async {
        guard let command = latestCommand, !security.challengeVisible, isForeground else { return }
        let original = RouteParser.parse(command.resolvedURL, navMode: navMode)
        let payload = await MapLaunchPlanner.resolve(original)
        guard !Task.isCancelled, isForeground, !security.challengeVisible,
              latestCommand?.clientRequestId == command.clientRequestId,
              let url = MapLaunchPlanner.url(for: payload, preferredMapApp: preferredMapApp, openBehavior: openBehavior) else { return }
        let opened: Bool = await withCheckedContinuation { continuation in
            UIApplication.shared.open(url) { continuation.resume(returning: $0) }
        }
        if opened {
            if !command.id.hasPrefix("local_") {
                try? await firebaseService.acknowledge(deviceId: deviceId, commandId: command.id, status: "opened")
            }
        } else {
            toastMessage = "Unable to open Maps. Install Google Maps or select Apple Maps."
        }
    }

    private func scheduleMapOpening() {
        routeTask?.cancel()
        routeTask = Task { await openLatestRoute() }
    }

    func handleIncomingURL(_ url: URL) {
        if url.scheme == "phantomgo", url.host == "pair" {
            Task { await handleScannedPairPayload(url.absoluteString) }
            return
        }
        let raw = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
            .first { $0.name == "url" }?.value ?? url.absoluteString
        guard RouteParser.parse(raw, navMode: navMode).resolvedURL != nil else {
            toastMessage = "Unsupported route link."
            return
        }
        routeInput = raw; selectedTab = .home
        prefsStore.setLatestRouteInput(raw)
    }

    func clearMainPairing() async {
        guard !isBusy else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            try await firebaseService.clearMainPairing(mainDeviceId: deviceId, displayDeviceId: mainPairing?.displayDeviceId)
            if let displayId = mainPairing?.displayDeviceId {
                await firebaseService.stopObserver(path: "presence/\(displayId)")
            }
            mainPairing = nil; peerPresence = nil
            prefsStore.clearPairing()
            prefsStore.save(Optional<PairingRecord>.none, key: "mainPairing")
            updateShareSession()
            toastMessage = "Disconnected"
        } catch { toastMessage = error.localizedDescription }
    }

    func clearDisplayPairing() async {
        guard !isBusy else { return }
        if security.isArmed {
            toastMessage = "Disarm anti-theft before resetting Display pairing."
            selectedTab = .security
            return
        }
        isBusy = true
        defer { isBusy = false }
        do {
            try await firebaseService.clearDisplayPairing(
                displayDeviceId: deviceId, mainDeviceId: displayPairing?.mainDeviceId, pairCode: pairCode
            )
            displayPairing = nil; latestCommand = nil
            prefsStore.save(Optional<PairingRecord>.none, key: "displayPairing")
            prefsStore.save(Optional<RouteCommand>.none, key: "latestCommand")
            localRelay.mainId = nil
            pairCode = prefsStore.refreshPairCode()
            await publishPresenceAndQR()
            toastMessage = "Pairing reset; scan the new QR"
        } catch { toastMessage = error.localizedDescription }
    }

    func updateSettings(navMode: DisplayNavigationMode? = nil, openBehavior: DisplayOpenBehavior? = nil,
                        preferredMapApp: PreferredMapApp? = nil) {
        if let navMode { self.navMode = navMode; prefsStore.setDisplayNavigationMode(navMode) }
        if let openBehavior { self.openBehavior = openBehavior; prefsStore.setDisplayOpenBehavior(openBehavior) }
        if let preferredMapApp { self.preferredMapApp = preferredMapApp; prefsStore.setPreferredMapApp(preferredMapApp) }
        updateFeatureSettings()
    }
    func switchTab(_ tab: AppTab) { selectedTab = tab; prefsStore.setSelectedTab(tab) }

    func refreshDisplayLocation() async {
        guard let displayId = mainPairing?.displayDeviceId ?? prefsStore.pairedDisplayDeviceId() else { return }
        do {
            peerPresence = DisplayPresence.decode(try await firebaseService.request(path: "presence/\(displayId)"))
            guard let presence = peerPresence, let lat = presence.latitude, let lng = presence.longitude else {
                toastMessage = "Display has not shared a location yet. Enable GPS on Display."
                return
            }
            geocoder.cancelGeocode()
            let marks = try await geocoder.reverseGeocodeLocation(CLLocation(latitude: lat, longitude: lng))
            if let mark = marks.first {
                peerAddress = [mark.name, mark.locality, mark.administrativeArea, mark.country].compactMap { $0 }.joined(separator: ", ")
            }
        } catch { appendLog("Address lookup unavailable; showing coordinates") }
    }

    private func observePairingState() async {
        await firebaseService.observe(path: "pairings/byMain/\(deviceId)", onChange: { [weak self] raw in
            Task { @MainActor in
                guard let self else { return }
                let pairing = PairingRecord.decode(raw)
                self.mainPairing = pairing
                self.prefsStore.save(pairing, key: "mainPairing")
                if let pairing {
                    self.prefsStore.saveCloudPairing(displayDeviceId: pairing.displayDeviceId, displayName: pairing.displayName)
                    await self.observePeerPresence(pairing.displayDeviceId)
                } else {
                    self.prefsStore.clearPairing()
                    self.peerPresence = nil
                }
                self.updateShareSession()
            }
        }, onError: listenerError)
        await firebaseService.observe(path: "pairings/byDisplay/\(deviceId)", onChange: { [weak self] raw in
            Task { @MainActor in
                guard let self else { return }
                self.displayPairing = PairingRecord.decode(raw)
                self.prefsStore.save(self.displayPairing, key: "displayPairing")
                self.localRelay.mainId = self.displayPairing?.mainDeviceId
                if self.isDisplayModeEnabled && self.displayPairing != nil {
                    await self.observeCommands()
                }
            }
        }, onError: listenerError)
    }

    private func observePeerPresence(_ id: String) async {
        await firebaseService.observe(path: "presence/\(id)", onChange: { [weak self] raw in
            Task { @MainActor in
                guard let self, self.mainPairing?.displayDeviceId == id else { return }
                self.peerPresence = DisplayPresence.decode(raw)
                if let presence = self.peerPresence, let ip = presence.localIP,
                   let port = presence.localPort, let token = presence.localToken {
                    self.prefsStore.save(LocalEndpoint(ip: ip, port: port, token: token), key: "ios_local_endpoint")
                }
            }
        }, onError: listenerError)
    }

    private func observeCommands() async {
        await firebaseService.observe(path: "commands/\(deviceId)", onChange: { [weak self] raw in
            Task { @MainActor in
                guard let self, self.isDisplayModeEnabled, let raw = raw as? [String: Any] else { return }
                let commands = raw.compactMap { RouteCommand.decode(id: $0.key, value: $0.value) }
                    .filter { $0.status == "pending" }
                    .sorted { ($0.createdAt ?? 0) < ($1.createdAt ?? 0) }
                guard let latest = commands.last else { return }
                for command in commands.dropLast() {
                    let status = command.clientRequestId == latest.clientRequestId ? "received" : "superseded"
                    try? await self.firebaseService.acknowledge(deviceId: self.deviceId, commandId: command.id, status: status)
                }
                self.receive(latest)
            }
        }, onError: listenerError)
    }

    private func receive(_ command: RouteCommand) {
        guard isDisplayModeEnabled, command.fromMainDeviceId == displayPairing?.mainDeviceId,
              !receiving.contains(command.id) else { return }
        receiving.insert(command.id)
        Task {
            defer { receiving.remove(command.id) }
            let duplicate = prefsStore.hasProcessed(command.clientRequestId)
            if !duplicate {
                guard RouteParser.parse(command.resolvedURL, navMode: navMode).resolvedURL != nil else { return }
                prefsStore.markProcessed(command.clientRequestId)
                latestCommand = command
                prefsStore.save(command, key: "latestCommand")
                selectedTab = .display
                if !isForeground {
                    NotificationService.shared.post(id: "route-" + command.clientRequestId,
                                                    title: "New route received", body: "Open PHANToM GO Display to view the destination.")
                }
            }
            if !command.id.hasPrefix("local_") {
                try? await firebaseService.acknowledge(deviceId: deviceId, commandId: command.id, status: "received")
            }
            if !duplicate && isForeground && prefsStore.flag("autoOpen", default: true) && !security.challengeVisible {
                scheduleMapOpening()
            } else if !duplicate && security.challengeVisible && prefsStore.flag("autoOpen", default: true) {
                deferredMapRequestId = command.clientRequestId
            }
        }
    }

    private func observeSecurityAlerts() async {
        await firebaseService.observe(path: "securityAlerts/byMain/\(deviceId)", onChange: { [weak self] raw in
            Task { @MainActor in
                guard let self, let raw = raw as? [String: Any] else { return }
                let previous = Set(self.securityAlerts.map(\.id))
                let alerts = raw.compactMap { SecurityAlert.decode(id: $0.key, value: $0.value) }
                    .sorted { $0.date > $1.date }
                for alert in alerts.prefix(20) where !previous.contains(alert.id) && abs(alert.date.timeIntervalSinceNow) < 300 {
                    NotificationService.shared.post(id: "security-" + alert.id, title: "Display security alert",
                                                    body: alert.displayName + ": " + alert.eventType)
                }
                self.securityAlerts = Array(alerts.prefix(50))
                self.prefsStore.save(self.securityAlerts, key: "securityAlerts")
            }
        }, onError: listenerError)
    }

    private var listenerError: (String) -> Void {
        { [weak self] error in Task { @MainActor in self?.authSummary = "Reconnecting"; self?.appendLog(error) } }
    }
    private func publishPresenceAndQR() async {
        guard started || starting else { return }
        do {
            try await firebaseService.syncDeviceRecord(
                deviceId: deviceId, role: isDisplayModeEnabled ? "display" : "main",
                model: deviceName, appVersion: currentAppVersion, local: localRelay.endpoint
            )
            if isDisplayModeEnabled && displayPairing == nil {
                try await firebaseService.publishPairingCode(
                    deviceId: deviceId, pairCode: pairCode, displayName: deviceName, local: localRelay.endpoint
                )
            }
            authSummary = "Cloud connected"
        } catch { authSummary = "Offline; cloud will retry"; appendLog("Presence sync waiting for network") }
    }
    func appendLog(_ text: String) {
        debugLog.append(text)
        if debugLog.count > 100 { debugLog.removeFirst(debugLog.count - 100) }
    }
    deinit { heartbeat?.invalidate(); routeTask?.cancel() }
}
