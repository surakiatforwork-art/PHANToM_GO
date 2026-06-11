import XCTest
@testable import PHANToMGO

final class RouteParserTests: XCTestCase {
    func testCloudPairPayloadRoundTrip() {
        let payload = RouteParser.buildCloudPairPayload(deviceId: "ios_test_device", pairCode: "abc123def456")
        let pairInfo = RouteParser.parsePairInfo(payload)

        XCTAssertNotNil(pairInfo)
        XCTAssertEqual(pairInfo?.displayDeviceId, "ios_test_device")
        XCTAssertEqual(pairInfo?.pairCode, "abc123def456")
        XCTAssertTrue(pairInfo?.isCloudPairing ?? false)
    }

    func testLocalTransportPayloadParsing() {
        let pairInfo = RouteParser.parsePairInfo("192.168.1.50:8787|token-xyz")

        XCTAssertNotNil(pairInfo)
        XCTAssertEqual(pairInfo?.ip, "192.168.1.50")
        XCTAssertEqual(pairInfo?.port, 8787)
        XCTAssertEqual(pairInfo?.token, "token-xyz")
        XCTAssertTrue(pairInfo?.hasLocalTransport ?? false)
    }

    func testParseCoordinatePayload() throws {
        let payload = RouteParser.parse("13.7563,100.5018", navMode: .driving)
        let latitude = try XCTUnwrap(payload.latitude)
        let longitude = try XCTUnwrap(payload.longitude)

        XCTAssertEqual(payload.parserSource, .coordinates)
        XCTAssertEqual(latitude, 13.7563, accuracy: 0.0001)
        XCTAssertEqual(longitude, 100.5018, accuracy: 0.0001)
        XCTAssertEqual(payload.navigationMode, .driving)
    }

    func testParseGeoQueryPayload() {
        let payload = RouteParser.parse("geo:0,0?q=Bangkok", navMode: .motorcycle)

        XCTAssertEqual(payload.parserSource, .geoScheme)
        XCTAssertEqual(payload.query, "Bangkok")
        XCTAssertEqual(payload.placeName, "Bangkok")
        XCTAssertEqual(payload.navigationMode, .motorcycle)
    }

    func testParseGoogleNavigationPayload() {
        let payload = RouteParser.parse("google.navigation:q=CentralWorld", navMode: .driving)

        XCTAssertEqual(payload.parserSource, .googleNavigationScheme)
        XCTAssertEqual(payload.query, "CentralWorld")
        XCTAssertTrue(payload.resolvedURL?.contains("destination=") ?? false)
    }

    func testParseMapsShortLinkPayload() {
        let payload = RouteParser.parse("https://maps.app.goo.gl/8mqH34fCETAnpjzW9", navMode: .driving)

        XCTAssertEqual(payload.parserSource, .mapsAppShortLink)
        XCTAssertEqual(payload.resolvedURL, "https://maps.app.goo.gl/8mqH34fCETAnpjzW9")
    }

    func testParsePlaceNameFallback() {
        let payload = RouteParser.parse("Victory Monument Bangkok", navMode: .driving)

        XCTAssertEqual(payload.parserSource, .placeName)
        XCTAssertEqual(payload.placeName, "Victory Monument Bangkok")
        XCTAssertTrue(payload.resolvedURL?.contains("google.com/maps/search") ?? false)
    }
}
