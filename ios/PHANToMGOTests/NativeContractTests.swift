import Foundation
import XCTest
@testable import PHANToMGO

final class NativeContractTests: XCTestCase {
    func testAndroidCommandContractHasAuthorizationAndURL() {
        let command = RouteDeliveryPolicy.command(
            id: "cmd", requestId: "request", mainId: "main", uid: "auth",
            url: "https://maps.app.goo.gl/test", attempt: 2, timestamp: [".sv": "timestamp"]
        )
        XCTAssertEqual(command["url"] as? String, "https://maps.app.goo.gl/test")
        XCTAssertEqual(command["fromMainAuthUid"] as? String, "auth")
        XCTAssertEqual(command["fromMainDeviceId"] as? String, "main")
        XCTAssertEqual(command["clientRequestId"] as? String, "request")
        XCTAssertEqual(command["attempt"] as? Int, 2)
        XCTAssertEqual(command["status"] as? String, "pending")
        XCTAssertTrue(JSONSerialization.isValidJSONObject(command))
    }
    func testOnlyReceivedOrOpenedIsAcknowledgement() {
        XCTAssertTrue(RouteDeliveryPolicy.isAcknowledged("received"))
        XCTAssertTrue(RouteDeliveryPolicy.isAcknowledged("opened"))
        for status in [nil, "pending", "superseded", "error"] {
            XCTAssertFalse(RouteDeliveryPolicy.isAcknowledged(status))
        }
    }
    func testStreamPatchPreservesImmutableCommandFields() {
        var tree = FirebaseStreamTree()
        tree.apply(event: "put", path: "/", value: ["a": ["url": "map", "status": "pending"]])
        tree.apply(event: "patch", path: "/a", value: ["status": "received"])
        let root = tree.value as? [String: [String: String]]
        XCTAssertEqual(root?["a"]?["url"], "map")
        XCTAssertEqual(root?["a"]?["status"], "received")
    }
    func testStreamDeletionDoesNotRemoveAnotherPeer() {
        var tree = FirebaseStreamTree()
        tree.apply(event: "put", path: "/", value: ["a": ["name": "A"], "b": ["name": "B"]])
        tree.apply(event: "put", path: "/a", value: NSNull())
        let root = tree.value as? [String: Any]
        XCTAssertNil(root?["a"])
        XCTAssertNotNil(root?["b"])
    }
    func testLocalEndpointsNeverAllowPublicNetworkOrLoopback() {
        for ip in ["0.0.0.0", "127.0.0.1", "8.8.8.8", "169.254.1.1", "192.168.1.255", "10.0.0.0"] {
            XCTAssertFalse(LocalEndpoint(ip: ip, port: 8765, token: "token").isUsable)
        }
        XCTAssertTrue(LocalEndpoint(ip: "192.168.1.20", port: 8765, token: "token").isUsable)
        XCTAssertTrue(LocalEndpoint(ip: "172.16.0.2", port: 8765, token: "token").isUsable)
    }
    func testReceivedCommandUsesAndroidURLField() {
        let command = RouteCommand.decode(id: "key", value: [
            "url": "https://www.google.com/maps/?q=Bangkok", "status": "pending",
            "fromMainDeviceId": "main", "clientRequestId": "request"
        ])
        XCTAssertEqual(command?.resolvedURL, "https://www.google.com/maps/?q=Bangkok")
        XCTAssertEqual(command?.clientRequestId, "request")
        XCTAssertNil(RouteCommand.decode(id: "bad", value: ["status": "pending"]))
    }
    func testOfflinePresenceUsesTimestampAndLastKnownLocation() {
        let old = Date().addingTimeInterval(-300).timeIntervalSince1970 * 1000
        let presence = DisplayPresence.decode([
            "connected": true, "updatedAt": old, "locationLat": 13.75, "locationLng": 100.5
        ])
        XCTAssertFalse(presence?.isOnline ?? true)
        XCTAssertEqual(presence?.latitude, 13.75)
    }
    func testDedupePersistsAcrossStoreRecreation() {
        let name = "test-dedupe-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        PrefsStore(defaults: defaults).markProcessed("request")
        XCTAssertTrue(PrefsStore(defaults: defaults).hasProcessed("request"))
        XCTAssertFalse(PrefsStore(defaults: defaults).hasProcessed("another-request"))
    }
    func testResetLocalTokenInvalidatesPreviousBearerToken() {
        let name = "test-token-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let store = PrefsStore(defaults: defaults)
        let previous = store.localToken()
        let next = store.refreshLocalToken()
        XCTAssertNotEqual(previous, next)
        XCTAssertEqual(store.localToken(), next)
    }
    func testCommandCacheDoesNotRetainAcknowledgedHistory() {
        var tree = FirebaseStreamTree()
        tree.apply(event: "put", path: "/", value: [
            "old": ["status": "received", "url": "old"],
            "new": ["status": "pending", "url": "new"]
        ])
        tree.keepPendingCommands()
        let root = tree.value as? [String: Any]
        XCTAssertNil(root?["old"])
        XCTAssertNotNil(root?["new"])
    }
    func testAntiTheftTimingAndWrongAttemptThresholds() {
        let initial = Date(timeIntervalSince1970: 1000)
        XCTAssertFalse(AntiTheftPolicy.shouldStartAlarm(detachedAt: initial, now: initial.addingTimeInterval(29), wrongAttempts: 1))
        XCTAssertTrue(AntiTheftPolicy.shouldStartAlarm(detachedAt: initial, now: initial.addingTimeInterval(30), wrongAttempts: 0))
        XCTAssertTrue(AntiTheftPolicy.shouldStartAlarm(detachedAt: initial, now: initial, wrongAttempts: 2))
        XCTAssertFalse(AntiTheftPolicy.isBlocked(alarmStartedAt: nil, now: initial))
        XCTAssertTrue(AntiTheftPolicy.isBlocked(alarmStartedAt: initial, now: initial.addingTimeInterval(30)))
    }
    func testDisarmAlarmCanEscalateWithoutJackRemoval() {
        let now = Date(timeIntervalSince1970: 1000)
        XCTAssertFalse(AntiTheftPolicy.shouldStartAlarm(detachedAt: nil, now: now, wrongAttempts: 1))
        XCTAssertTrue(AntiTheftPolicy.shouldStartAlarm(detachedAt: nil, now: now, wrongAttempts: 2))
        XCTAssertFalse(AntiTheftPolicy.isBlocked(alarmStartedAt: now, now: now.addingTimeInterval(29)))
        XCTAssertTrue(AntiTheftPolicy.isBlocked(alarmStartedAt: now, now: now.addingTimeInterval(30)))
    }
    func testPINValidationAcceptsOnlyFourToEightASCIIDigits() {
        XCTAssertTrue(PINStore.valid("1234"))
        XCTAssertTrue(PINStore.valid("12345678"))
        for pin in ["", "123", "123456789", "12a4", "１２３４"] { XCTAssertFalse(PINStore.valid(pin)) }
    }
    func testMapDestinationDoesNotEncodeEntireShortURLAsAddress() {
        let payload = RouteParser.parse("https://maps.app.goo.gl/abcd", navMode: .motorcycle)
        let url = MapLaunchPlanner.url(for: payload, preferredMapApp: .googleMaps, openBehavior: .startNavigation)
        XCTAssertEqual(url?.absoluteString, "https://maps.app.goo.gl/abcd")
    }
    func testNavigationAndVehicleModeAreIndependentParameters() {
        let payload = RouteParser.parse("13.75,100.5", navMode: .motorcycle)
        let navigation = MapLaunchPlanner.url(for: payload, preferredMapApp: .googleMaps, openBehavior: .startNavigation)!
        let items = URLComponents(url: navigation, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(items.first { $0.name == "travelmode" }?.value, "two-wheeler")
        XCTAssertEqual(items.first { $0.name == "dir_action" }?.value, "navigate")
        let preview = MapLaunchPlanner.url(for: payload, preferredMapApp: .googleMaps, openBehavior: .previewRoute)!
        XCTAssertFalse(preview.absoluteString.contains("dir_action"))
    }
    func testViewportIsNotUsedAsMapsDestination() {
        let payload = RouteParser.parse("https://www.google.com/maps/place/A/@13.1,100.1,12z/data=!3d13.7!4d100.6", navMode: .driving)
        XCTAssertEqual(payload.latitude, 13.7)
        XCTAssertEqual(payload.longitude, 100.6)
    }
    func testSharedTextExtractsOnlySupportedMapsLink() {
        let payload = RouteParser.parse("Meet here: https://maps.app.goo.gl/abcd", navMode: .driving)
        XCTAssertEqual(payload.resolvedURL, "https://maps.app.goo.gl/abcd")
        XCTAssertNil(RouteParser.parse("https://example.com/maps", navMode: .driving).resolvedURL)
    }
    func testCoordinateBoundsAndPairKeysAreValidated() {
        XCTAssertNil(RouteParser.parse("91,181", navMode: .driving).resolvedURL)
        XCTAssertNil(RouteParser.parsePairInfo("phantomgo://pair?deviceId=a/b&pairCode=abc"))
    }
}
