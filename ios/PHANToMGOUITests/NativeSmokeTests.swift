import XCTest

final class NativeSmokeTests: XCTestCase {
    private func launch() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["PHANTOM_UI_TEST_MODE"] = "1"
        app.launch()
        return app
    }
    private func snapshot(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: XCUIDevice.shared.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
    func testAllMainTabsStayResponsive() {
        let app = launch()
        for tab in ["Home", "Display", "Settings", "Security", "Home"] {
            let button = app.buttons[tab].firstMatch
            XCTAssertTrue(button.waitForExistence(timeout: 10))
            button.tap()
            XCTAssertEqual(app.state, .runningForeground)
            snapshot(tab, app: app)
        }
    }
    func testThreeDigitSpeedInPortraitAndLandscape() {
        let app = launch()
        app.buttons["Display"].firstMatch.tap()
        let speed = app.staticTexts["speed-value"]
        XCTAssertTrue(speed.waitForExistence(timeout: 10))
        XCTAssertEqual(speed.label, "123")
        XCTAssertGreaterThan(speed.frame.width, 0)
        XCTAssertLessThanOrEqual(speed.frame.maxX, app.windows.firstMatch.frame.maxX)
        snapshot("Display-portrait-123", app: app)
        XCUIDevice.shared.orientation = .landscapeLeft
        let landscape = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "frame.size.width > frame.size.height"), object: app.windows.firstMatch
        )
        XCTAssertEqual(XCTWaiter.wait(for: [landscape], timeout: 10), .completed)
        XCTAssertTrue(speed.waitForExistence(timeout: 10))
        XCTAssertEqual(speed.label, "123")
        XCTAssertGreaterThan(speed.frame.width, 0)
        XCTAssertLessThanOrEqual(speed.frame.maxX, app.windows.firstMatch.frame.maxX)
        snapshot("Display-landscape-123", app: app)
        XCUIDevice.shared.orientation = .portrait
    }
    func testSettingsScrollRemainsResponsive() {
        let app = launch()
        app.buttons["Settings"].firstMatch.tap()
        app.swipeUp()
        app.swipeUp()
        app.swipeDown()
        XCTAssertEqual(app.state, .runningForeground)
        snapshot("Settings-scrolled", app: app)
    }
    func testDisplayHasExplicitLatestRouteAction() {
        let app = launch()
        app.buttons["Display"].firstMatch.tap()
        let action = app.buttons["check-latest-route"]
        for _ in 0..<5 where !action.isHittable { app.swipeUp() }
        XCTAssertTrue(action.waitForExistence(timeout: 10))
        XCTAssertTrue(action.isEnabled)
        snapshot("Display-latest-route-action", app: app)
    }
}
