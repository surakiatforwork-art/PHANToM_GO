import AVFoundation
import Combine
import CommonCrypto
import Darwin
import Foundation
import Security
import UIKit

enum AntiTheftPolicy {
    static func shouldStartAlarm(detachedAt: Date, now: Date, wrongAttempts: Int) -> Bool {
        wrongAttempts >= 2 || now.timeIntervalSince(detachedAt) >= 30
    }
    static func isBlocked(alarmStartedAt: Date?, now: Date) -> Bool {
        alarmStartedAt.map { now.timeIntervalSince($0) >= 30 } ?? false
    }
}

enum PINStore {
    private struct Record: Codable { let salt: Data; let hash: Data }
    static var hasPIN: Bool { KeychainStore.data("anti-theft-pin") != nil }
    static func set(_ pin: String) -> Bool {
        guard valid(pin) else { return false }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { return false }
        let salt = Data(bytes)
        guard let hash = derive(pin, salt: salt),
              let data = try? JSONEncoder().encode(Record(salt: salt, hash: hash)) else { return false }
        return KeychainStore.set(data, key: "anti-theft-pin")
    }
    static func verify(_ pin: String) -> Bool {
        guard valid(pin), let data = KeychainStore.data("anti-theft-pin"),
              let record = try? JSONDecoder().decode(Record.self, from: data),
              let actual = derive(pin, salt: record.salt), actual.count == record.hash.count else { return false }
        return zip(actual, record.hash).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
    static func valid(_ pin: String) -> Bool {
        (4...8).contains(pin.count) && pin.utf8.allSatisfy { (48...57).contains($0) }
    }
    private static func derive(_ pin: String, salt: Data) -> Data? {
        let password = Array(pin.utf8)
        var output = [UInt8](repeating: 0, count: 32)
        let status = salt.withUnsafeBytes { buffer in
            pin.withCString { pointer in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2), pointer, password.count,
                    buffer.bindMemory(to: UInt8.self).baseAddress, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), 120_000, &output, 32
                )
            }
        }
        return status == kCCSuccess ? Data(output) : nil
    }
}

@MainActor
final class AntiTheftService: ObservableObject {
    @Published private(set) var isArmed = false
    @Published private(set) var jackConnected = false
    @Published var challengeVisible = false
    @Published private(set) var alarmActive = false
    @Published private(set) var isBlocked = false
    @Published private(set) var wrongAttempts = 0
    @Published private(set) var status = "Anti-theft off"
    @Published private(set) var isVerifying = false
    var onEvent: ((String, Int, String) -> Void)?
    var onVerified: (() -> Void)?
    private var detachedAt: Date?
    private var alarmStartedAt: Date?
    private var sessionId = UUID().uuidString
    private var requestedDisarm = false
    private var routeObserver: NSObjectProtocol?
    private var timer: Timer?
    private var alarmPlayer: AVAudioPlayer?
    private let speech = AVSpeechSynthesizer()
    private var lastSpeech = Date.distantPast
    private var timeoutNotified = false

    var hardwareSupported: Bool {
        var machine = utsname()
        uname(&machine)
        let machineSize = MemoryLayout.size(ofValue: machine.machine)
        let model = withUnsafePointer(to: &machine.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: machineSize) {
                String(cString: $0)
            }
        }
        let jackModels = [
            "iPad6,3", "iPad6,4", "iPad6,7", "iPad6,8", "iPad7,1", "iPad7,2", "iPad7,3", "iPad7,4",
            "iPad7,5", "iPad7,6", "iPad7,11", "iPad7,12", "iPad11,1", "iPad11,2",
            "iPad11,3", "iPad11,4", "iPad11,6", "iPad11,7", "iPad12,1", "iPad12,2"
        ]
        return jackModels.contains(model)
    }

    init() {
        isArmed = PrefsStore.shared.flag("antiTheftArmed") && PINStore.hasPIN
        jackConnected = AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .headphones }
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in Task { @MainActor in self?.refreshJack() } }
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        status = isArmed ? "Armed; keep this app active for reliable monitoring" : "Anti-theft off"
    }

    func refreshJack() {
        let wasConnected = jackConnected
        jackConnected = AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .headphones }
        if isArmed && hardwareSupported && wasConnected && !jackConnected && detachedAt == nil {
            beginChallenge()
        }
        if isArmed && jackConnected && detachedAt == nil {
            status = "Armed; wired jack connected"
        }
        tick()
    }
    func arm() throws {
        guard hardwareSupported && jackConnected else {
            throw RelayError.message("Anti-theft requires a device with a built-in 3.5 mm jack and connected wired headphones.")
        }
        guard PINStore.hasPIN else { throw RelayError.message("Set a 4-8 digit PIN before arming.") }
        isArmed = true
        PrefsStore.shared.setFlag("antiTheftArmed", true)
        status = "Armed; wired jack connected"
    }
    func requestDisarm() {
        guard isArmed else { return }
        requestedDisarm = true
        challengeVisible = true
        status = "Enter PIN to disarm"
    }
    private func beginChallenge() {
        detachedAt = Date()
        sessionId = UUID().uuidString
        wrongAttempts = 0; timeoutNotified = false
        challengeVisible = true
        status = "Wired jack removed; enter PIN"
        onEvent?("jack_removed", 0, sessionId)
        NotificationService.shared.post(id: "security-check", title: "Security Verification",
                                        body: "Open the app and verify your PIN.", delay: 15)
        NotificationService.shared.post(id: "security-reminder", title: "Security Verification",
                                        body: "Verification is still required.", delay: 30)
    }
    func verify(_ pin: String) async -> Bool {
        guard !isVerifying else { return false }
        isVerifying = true
        defer { isVerifying = false }
        let matches = await Task.detached(priority: .userInitiated) { PINStore.verify(pin) }.value
        guard matches else {
            if detachedAt == nil && !requestedDisarm { detachedAt = Date() }
            wrongAttempts += 1
            onEvent?(wrongAttempts == 1 ? "pin_wrong_first" : "alarm_started", wrongAttempts, sessionId)
            if wrongAttempts >= 2 { startAlarm(at: Date()) }
            status = "Incorrect PIN"
            return false
        }
        let disarming = requestedDisarm
        stopAlarm()
        detachedAt = nil; alarmStartedAt = nil; wrongAttempts = 0
        challengeVisible = false; requestedDisarm = false; timeoutNotified = false
        NotificationService.shared.cancel(ids: ["security-check", "security-reminder"])
        if disarming {
            isArmed = false; PrefsStore.shared.setFlag("antiTheftArmed", false)
            status = "Anti-theft off"
        } else {
            status = jackConnected ? "Armed; wired jack connected" : "Verified; waiting for jack reconnect"
        }
        onEvent?(disarming ? "disarmed" : "pin_verified", 0, sessionId)
        onVerified?()
        return true
    }
    func tick() {
        guard let detachedAt else { return }
        let now = Date()
        if now.timeIntervalSince(detachedAt) >= 15 && !timeoutNotified {
            timeoutNotified = true
            onEvent?("pin_timeout", wrongAttempts, sessionId)
        }
        if AntiTheftPolicy.shouldStartAlarm(detachedAt: detachedAt, now: now, wrongAttempts: wrongAttempts) && !alarmActive {
            startAlarm(at: detachedAt.addingTimeInterval(30))
        }
        isBlocked = AntiTheftPolicy.isBlocked(alarmStartedAt: alarmStartedAt, now: now)
        if alarmActive && now.timeIntervalSince(lastSpeech) >= 4 && !speech.isSpeaking {
            let utterance = AVSpeechUtterance(string: "ขโมย")
            utterance.voice = AVSpeechSynthesisVoice(language: "th-TH")
            speech.speak(utterance)
            lastSpeech = now
        }
    }
    private func startAlarm(at date: Date) {
        guard !alarmActive else { return }
        alarmActive = true; alarmStartedAt = date; challengeVisible = true
        status = "Alarm active; enter PIN to stop"
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.duckOthers])
            try AVAudioSession.sharedInstance().setActive(true)
            alarmPlayer = try AVAudioPlayer(data: Self.alarmWAV())
            alarmPlayer?.numberOfLoops = -1
            alarmPlayer?.play()
        } catch {
            status = "Alarm audio unavailable; verification still required"
        }
        onEvent?("alarm_started", wrongAttempts, sessionId)
    }
    private func stopAlarm() {
        alarmPlayer?.stop(); alarmPlayer = nil
        speech.stopSpeaking(at: .immediate)
        alarmActive = false; isBlocked = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    private static func alarmWAV() -> Data {
        let rate = 22050
        var samples = Data()
        for index in 0..<(rate * 2) {
            let frequency = (index / (rate / 4)) % 2 == 0 ? 900.0 : 1400.0
            var sample = Int16(sin(2 * Double.pi * frequency * Double(index) / Double(rate)) * 24000).littleEndian
            Swift.withUnsafeBytes(of: &sample) { samples.append(contentsOf: $0) }
        }
        var result = Data("RIFF".utf8)
        func append<T: FixedWidthInteger>(_ value: T) {
            var value = value.littleEndian
            Swift.withUnsafeBytes(of: &value) { result.append(contentsOf: $0) }
        }
        append(UInt32(samples.count + 36))
        result.append(Data("WAVEfmt ".utf8)); append(UInt32(16)); append(UInt16(1)); append(UInt16(1))
        append(UInt32(rate)); append(UInt32(rate * 2)); append(UInt16(2)); append(UInt16(16))
        result.append(Data("data".utf8)); append(UInt32(samples.count)); result.append(samples)
        return result
    }
    deinit {
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
        timer?.invalidate()
    }
}
