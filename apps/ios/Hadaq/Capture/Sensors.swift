import Combine
import CoreLocation
import CoreMotion

/// Level and steadiness from CoreMotion, for the live chips. Level = within 5° of upright (portrait or landscape).
final class LevelMonitor: ObservableObject {
    private let motion = CMMotionManager()
    @Published var tiltDeg: Double = 90
    @Published var steady = false
    @Published var available = true

    var ok: Bool { tiltDeg <= QualityRules.maxTiltDeg }

    func start() {
        guard motion.isDeviceMotionAvailable else { available = false; return }
        motion.deviceMotionUpdateInterval = 1.0 / 20
        motion.startDeviceMotionUpdates(to: .main) { [weak self] m, _ in
            guard let self, let m else { return }
            self.tiltDeg = QualityRules.tiltFromUpright(gx: m.gravity.x, gy: m.gravity.y)
            let r = m.rotationRate
            self.steady = abs(r.x) + abs(r.y) + abs(r.z) < 0.15
        }
    }

    func stop() { motion.stopDeviceMotionUpdates() }
}

/// GPS fix and compass heading while the camera is open; read at the shutter.
final class LocationMonitor: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    @Published private(set) var location: CLLocation?
    @Published private(set) var headingDeg: Double?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.headingFilter = 1
    }

    func start() {
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        manager.startUpdatingLocation()
        if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
    }

    /// nil when there is no fix, or the last fix is older than 2 minutes.
    var fix: GeoFix? {
        guard let l = location, l.horizontalAccuracy >= 0, Date().timeIntervalSince(l.timestamp) < 120 else { return nil }
        return GeoFix(lat: l.coordinate.latitude, lon: l.coordinate.longitude, accuracyM: (l.horizontalAccuracy * 10).rounded() / 10)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        location = locations.last
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let value = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        headingDeg = value < 0 ? nil : (value * 10).rounded() / 10
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // no fix (indoors, denied): gps stays null in the meta
    }
}
