import Combine
import CoreLocation
import Foundation

@MainActor
final class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var speedKmh: Double?
    @Published private(set) var location: CLLocation?
    @Published private(set) var status = "Location not enabled"
    @Published private(set) var authorization: CLAuthorizationStatus = .notDetermined
    var onLocation: ((CLLocation) -> Void)?
    private let manager = CLLocationManager()
    private var wanted = false
    private var background = false
    #if DEBUG
    private var isUITestSample = false
    #endif

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .automotiveNavigation
        manager.distanceFilter = 3
        manager.pausesLocationUpdatesAutomatically = true
        authorization = manager.authorizationStatus
    }
    func requestPermission(always: Bool = false) {
        if always && manager.authorizationStatus == .authorizedWhenInUse {
            manager.requestAlwaysAuthorization()
        } else {
            manager.requestWhenInUseAuthorization()
        }
    }
    func setEnabled(_ enabled: Bool, background: Bool) {
        wanted = enabled
        self.background = background
        manager.allowsBackgroundLocationUpdates = background && manager.authorizationStatus == .authorizedAlways
        manager.showsBackgroundLocationIndicator = manager.allowsBackgroundLocationUpdates
        if enabled && [.authorizedAlways, .authorizedWhenInUse].contains(manager.authorizationStatus) {
            manager.startUpdatingLocation()
            status = "Waiting for GPS"
        } else {
            manager.stopUpdatingLocation()
            speedKmh = nil
            status = enabled ? "Allow location in Settings to enable GPS" : "GPS paused"
        }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        #if DEBUG
        if isUITestSample { return }
        #endif
        authorization = manager.authorizationStatus
        setEnabled(wanted, background: background)
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let current = locations.last, current.horizontalAccuracy >= 0,
              current.horizontalAccuracy < 150, abs(current.timestamp.timeIntervalSinceNow) < 15 else {
            speedKmh = nil
            status = "GPS signal is weak"
            return
        }
        location = current
        speedKmh = current.speed >= 0 ? max(0, current.speed * 3.6) : nil
        status = "GPS accuracy ±\(Int(current.horizontalAccuracy)) m"
        onLocation?(current)
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        status = error.localizedDescription
        speedKmh = nil
    }
    #if DEBUG
    func setUITestSpeed(_ speed: Double) {
        isUITestSample = true
        speedKmh = speed
        status = "Simulator test sample"
    }
    #endif
}
