import Foundation
import UserNotifications

@MainActor
final class NotificationService: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationService()
    private override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }
    func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }
    func post(id: String, title: String, body: String, delay: TimeInterval? = nil) {
        let content = UNMutableNotificationContent()
        content.title = title; content.body = body; content.sound = .default
        let trigger = delay.map { UNTimeIntervalNotificationTrigger(timeInterval: max(1, $0), repeats: false) }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }
    func cancel(ids: [String]) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
