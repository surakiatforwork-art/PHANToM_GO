import Foundation
import XCTest
@testable import PHANToMGO

final class RouteInteractionTests: XCTestCase {
    private func command(_ id: String, main: String = "main", uid: String = "main-uid",
                         time: Double, status: String = "pending") -> RouteCommand {
        RouteCommand(id: id, clientRequestId: id, fromMainDeviceId: main,
                     originalText: "13.75,100.5", resolvedURL: "https://www.google.com/maps/?q=13.75,100.5",
                     createdAt: time, status: status, fromMainAuthUid: uid)
    }
    func testNotificationRequiresDeviceAndRequestIdentifiers() {
        XCTAssertNil(NavigationNotificationIntent.decode(["phantomEvent": "route"]))
        XCTAssertNil(NavigationNotificationIntent.decode([
            "phantomEvent": "route", "displayDeviceId": "a/b", "clientRequestId": "request"
        ]))
        XCTAssertNil(NavigationNotificationIntent.decode(["phantomEvent": "unknown"]))
    }
    func testNotificationNeverCarriesAnExecutableMapURL() {
        let intent = NavigationNotificationIntent.decode([
            "phantomEvent": "route", "displayDeviceId": "display", "clientRequestId": "request",
            "url": "https://malicious.example/"
        ])
        XCTAssertEqual(intent?.displayDeviceId, "display")
        XCTAssertNil(intent?.userInfo["url"])
    }
    func testSecurityNotificationRoutesWithoutMapIdentifiers() {
        XCTAssertEqual(NavigationNotificationIntent.decode(["phantomEvent": "security"])?.destination, .security)
    }
    func testLatestRouteIgnoresOldPeerEvenWithFutureTimestamp() {
        let latest = RouteInboxPolicy.latest([
            command("old-peer", main: "previous", time: 5000), command("current", time: 100)
        ], mainId: "main", mainAuthUid: "main-uid")
        XCTAssertEqual(latest?.id, "current")
    }
    func testLatestRouteIgnoresOldAuthenticationIdentity() {
        let latest = RouteInboxPolicy.latest([
            command("old-uid", uid: "previous-uid", time: 5000), command("current", time: 100)
        ], mainId: "main", mainAuthUid: "main-uid")
        XCTAssertEqual(latest?.id, "current")
    }
    func testPendingHistoryCannotReplaceNewerCachedRoute() {
        let latest = RouteInboxPolicy.latest([command("old", time: 100)], mainId: "main",
                                             cached: command("cached", time: 200, status: "received"))
        XCTAssertEqual(latest?.id, "cached")
    }
    func testInboxExcludesSupersededAndErrorCommands() {
        XCTAssertNil(RouteInboxPolicy.latest([
            command("superseded", time: 200, status: "superseded"),
            command("error", time: 300, status: "error")
        ], mainId: "main"))
    }
    func testResignedKeychainGroupPrefersActualSigningPrefix() {
        XCTAssertEqual(KeychainGroupPolicy.candidates(
            configured: "OLDTEAM123.com.phantom.PHANToMGO.shared",
            defaultGroup: "NEWTEAM123.com.phantom.PHANToMGO",
            suffix: "com.phantom.PHANToMGO.shared"
        ).first, "NEWTEAM123.com.phantom.PHANToMGO.shared")
    }
    func testUnsignedKeychainGroupCanResolveWithActualPrefix() {
        XCTAssertEqual(KeychainGroupPolicy.candidates(
            configured: "com.phantom.PHANToMGO.shared",
            defaultGroup: "NEWTEAM123.com.phantom.PHANToMGO.Share",
            suffix: "com.phantom.PHANToMGO.shared"
        ).first, "NEWTEAM123.com.phantom.PHANToMGO.shared")
    }
    func testUnexpandedKeychainGroupIsNotUsedAsLiteralEntitlement() {
        XCTAssertTrue(KeychainGroupPolicy.candidates(configured: "$(AppIdentifierPrefix)group",
                                                     defaultGroup: nil, suffix: nil).isEmpty)
    }
    func testQueuedDeliveryIsNotAnAcknowledgement() {
        XCTAssertNotEqual(RouteDeliveryResult.queued(commandId: "command"), .acknowledged)
    }
}

private final class RelayURLProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var handler: ((URLRequest) throws -> (Int, Any))?
    private static var captured: [URLRequest] = []
    static func configure(_ action: @escaping (URLRequest) throws -> (Int, Any)) {
        lock.lock(); defer { lock.unlock() }
        captured = []; handler = action
    }
    static var requests: [URLRequest] {
        lock.lock(); defer { lock.unlock() }
        return captured
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        Self.captured.append(request)
        let action = Self.handler
        Self.lock.unlock()
        do {
            guard let action else { throw URLError(.unsupportedURL) }
            let (code, value) = try action(request)
            let data = try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
            let response = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

final class RouteDeliveryHTTPTests: XCTestCase {
    private func service() -> FirebaseService {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RelayURLProtocol.self]
        return FirebaseService(session: URLSession(configuration: configuration), authProvider: { _ in
            AuthIdentity(uid: "test-uid", idToken: "test-token", refreshToken: "test-refresh",
                         expiresAt: Date().addingTimeInterval(3600))
        })
    }
    private func payload() -> RoutePayload { RouteParser.parse("13.75,100.5", navMode: .driving) }

    func testServerAcceptanceWithoutAckReturnsQueued() async throws {
        RelayURLProtocol.configure { _ in (200, NSNull()) }
        let result = try await service().sendRouteCommand(
            to: "display", from: "main", mainName: "Main", payload: payload(), openBehavior: .previewRoute,
            maxAttempts: 1, acknowledgementTimeout: 0
        )
        guard case .queued = result else { return XCTFail("Acceptance is not delivery") }
        XCTAssertEqual(RelayURLProtocol.requests.count, 1)
        XCTAssertEqual(RelayURLProtocol.requests.first?.httpMethod, "PATCH")
    }
    func testDisplayAckReturnsAcknowledged() async throws {
        RelayURLProtocol.configure { request in
            if request.httpMethod == "GET" { return (200, ["status": "received"]) }
            return (200, NSNull())
        }
        let result = try await service().sendRouteCommand(
            to: "display", from: "main", mainName: "Main", payload: payload(), openBehavior: .previewRoute, maxAttempts: 1
        )
        XCTAssertEqual(result, .acknowledged)
    }
    func testNetworkFailureBeforeAcceptanceRemainsFailure() async {
        RelayURLProtocol.configure { _ in throw URLError(.notConnectedToInternet) }
        do {
            _ = try await service().sendRouteCommand(to: "display", from: "main", mainName: "Main", payload: payload(), openBehavior: .previewRoute)
            XCTFail("No server acceptance must not be reported as queued")
        } catch { XCTAssertTrue(error is URLError) }
    }
    func testNetworkFailureDuringAckPreservesQueuedAcceptance() async throws {
        RelayURLProtocol.configure { request in
            if request.httpMethod == "GET" { throw URLError(.timedOut) }
            return (200, NSNull())
        }
        let result = try await service().sendRouteCommand(
            to: "display", from: "main", mainName: "Main", payload: payload(), openBehavior: .previewRoute, maxAttempts: 1
        )
        guard case .queued = result else { return XCTFail("Accepted command must remain queued") }
    }
    func testPermissionDeniedIsNotReportedAsQueued() async {
        RelayURLProtocol.configure { _ in (403, ["error": "Permission denied"]) }
        do {
            _ = try await service().sendRouteCommand(to: "display", from: "main", mainName: "Main", payload: payload(), openBehavior: .previewRoute)
            XCTFail("Permission denial must remain an error")
        } catch { XCTAssertTrue(error.localizedDescription.contains("Permission denied")) }
    }
}
