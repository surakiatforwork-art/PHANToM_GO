import Foundation
import UserNotifications
import UIKit

@MainActor
final class NotificationService: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationService()
    private var navigationHandler: ((NavigationNotificationIntent) -> Void)?
    private var pendingIntent: NavigationNotificationIntent?
    private override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }
    func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }
    func setNavigationHandler(_ handler: @escaping (NavigationNotificationIntent) -> Void) {
        navigationHandler = handler
        if let intent = pendingIntent {
            pendingIntent = nil
            handler(intent)
        }
    }
    func post(id: String, title: String, body: String, delay: TimeInterval? = nil,
              intent: NavigationNotificationIntent? = nil) {
        let content = UNMutableNotificationContent()
        content.title = title; content.body = body; content.sound = .default
        if let intent { content.userInfo = intent.userInfo }
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
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void) {
        if response.actionIdentifier == UNNotificationDefaultActionIdentifier,
           let intent = NavigationNotificationIntent.decode(response.notification.request.content.userInfo) {
            Task { @MainActor in
                if let handler = self.navigationHandler { handler(intent) }
                else { self.pendingIntent = intent }
            }
        }
        completionHandler()
    }
}

@MainActor
final class NotificationAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        _ = NotificationService.shared
        return true
    }
}
