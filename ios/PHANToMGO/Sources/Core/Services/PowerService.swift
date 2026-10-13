import Combine
import Foundation
import UIKit

@MainActor
final class PowerService: ObservableObject {
    @Published private(set) var isCharging = false
    @Published private(set) var batteryLevel: Float = 0
    var onPowerDisconnected: (() -> Void)?
    private var originalBrightness: CGFloat?
    private var observers: [NSObjectProtocol] = []
    private var foreground = true
    private var displayEnabled = false
    private var securityVerificationActive = false

    init() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        for name in [UIDevice.batteryStateDidChangeNotification, UIDevice.batteryLevelDidChangeNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
            })
        }
        refresh()
    }
    func setState(display: Bool, foreground: Bool) {
        displayEnabled = display
        self.foreground = foreground
        apply()
    }
    func refresh() {
        let previous = isCharging
        isCharging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
        batteryLevel = max(0, UIDevice.current.batteryLevel)
        if previous && !isCharging && displayEnabled { onPowerDisconnected?() }
        apply()
    }
    func setSecurityVerificationActive(_ active: Bool) {
        securityVerificationActive = active
        apply()
    }
    func apply() {
        let prefs = PrefsStore.shared
        let active = displayEnabled && foreground
        UIApplication.shared.isIdleTimerDisabled = active && (prefs.flag("keepAwake") || securityVerificationActive)
        if active && securityVerificationActive {
            if originalBrightness == nil { originalBrightness = UIScreen.main.brightness }
            UIScreen.main.brightness = max(0.5, UIScreen.main.brightness)
            return
        }
        guard active && prefs.flag("powerAutomation") else {
            restoreBrightness()
            return
        }
        if originalBrightness == nil { originalBrightness = UIScreen.main.brightness }
        let hour = Calendar.current.component(.hour, from: Date())
        let daytime = hour >= 7 && hour < 18
        let brightness = isCharging
            ? prefs.number(daytime ? "dayBrightness" : "nightBrightness", default: daytime ? 0.8 : 0.3)
            : prefs.number("unpluggedBrightness", default: 0.08)
        UIScreen.main.brightness = CGFloat(min(1, max(0.01, brightness)))
    }
    private func restoreBrightness() {
        if let originalBrightness { UIScreen.main.brightness = originalBrightness }
        originalBrightness = nil
    }
    deinit { observers.forEach(NotificationCenter.default.removeObserver) }
}
