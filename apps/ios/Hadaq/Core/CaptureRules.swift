// Rules of the capture flow that do not need the camera or the UI: quality warnings, corner order,
// the queue's status machine, wall progress, EXIF dates. Foundation only (unit-tested in Tests/HadaqCoreTests).

import Foundation

// MARK: - Quality at the shutter

enum QualityRules {
    /// Degrees away from upright that still count as level (task S1: "within 5°").
    static let maxTiltDeg = 5.0

    /// Warnings never block a shot; they go into quality.warnings for the AI worker and the conservator.
    static func quality(tiltDeg: Double?, steady: Bool?, exposure: String?, blurScore: Double?, scaleRef: Bool) -> CaptureQuality {
        var warnings: [String] = []
        let tiltOk = tiltDeg.map { $0 <= maxTiltDeg }
        if tiltOk == false { warnings.append("not level") }
        if steady == false { warnings.append("moving") }
        if let exposure, exposure != "ok" { warnings.append("light: \(exposure)") }
        if !scaleRef { warnings.append("no scale reference") }
        return CaptureQuality(tiltOk: tiltOk, exposure: exposure, blurScore: blurScore, steady: steady,
                              scaleRef: scaleRef, warnings: warnings)
    }

    /// Tilt from upright, in degrees, from the gravity vector of CMDeviceMotion (portrait upright ≈ (0, -1, 0)).
    /// Portrait and landscape both count, so a wide wall can be shot sideways.
    static func tiltFromUpright(gx: Double, gy: Double) -> Double {
        let portrait = acos(min(1, max(-1, -gy))) * 180 / .pi
        let landscape = acos(min(1, abs(gx))) * 180 / .pi
        return min(portrait, landscape)
    }
}

// MARK: - Corners

enum Corners {
    /// Puts 4 points in the contract order: top-left, top-right, bottom-right, bottom-left (y grows downwards).
    /// So a surveyor who drags a handle across another one still sends a valid quadrilateral.
    static func ordered(_ points: [(x: Double, y: Double)]) -> [(x: Double, y: Double)] {
        guard points.count == 4 else { return points }
        let byY = points.sorted { $0.y < $1.y }
        let top = byY.prefix(2).sorted { $0.x < $1.x }
        let bottom = byY.suffix(2).sorted { $0.x < $1.x }
        return [top[0], top[1], bottom[1], bottom[0]]
    }

    /// Normalised (0...1) handle positions to image pixels, in contract order, rounded to 0.1 px.
    static func pixels(normalised points: [(x: Double, y: Double)], width: Int, height: Int) -> [[Double]] {
        ordered(points).map { p in
            [(min(1, max(0, p.x)) * Double(width) * 10).rounded() / 10,
             (min(1, max(0, p.y)) * Double(height) * 10).rounded() / 10]
        }
    }
}

// MARK: - The offline queue

/// Status of one photo on the phone. `queued` and `rejected` exist only on the phone;
/// uploaded, processing, analyzed and failed come from Spring (contracts v2 "Capture status").
enum QueueStatus: String, CaseIterable {
    case queued, uploading, uploaded, processing, analyzed, failed, rejected

    /// The four states the task names, plus the error ones.
    var labelAr: String {
        switch self {
        case .queued: return "بانتظار الاتصال"
        case .uploading: return "جاري الرفع"
        case .uploaded: return "تم الرفع"
        case .processing: return "قيد التحليل"
        case .analyzed: return "تم التحليل"
        case .failed: return "فشل التحليل"
        case .rejected: return "رفضها الخادم"
        }
    }

    /// Still on the phone and waiting to be sent.
    var needsUpload: Bool { self == .queued }
    /// On the server and not finished: poll GET /captures/{id}.
    var needsPolling: Bool { self == .uploaded || self == .processing }
    var isFinal: Bool { self == .analyzed }
}

enum QueueRules {
    /// What a failed upload attempt leaves behind. Network errors and 5xx stay queued and are retried with the
    /// same client_uuid; a 4xx means Spring refused this meta, so it stops until the surveyor taps retry.
    static func statusAfterUploadError(_ error: Error) -> QueueStatus {
        if let api = error as? HadaqAPIError, api.isPermanent { return .rejected }
        return .queued
    }

    /// A status string from Spring, mapped onto the phone's states (an unknown value keeps polling).
    static func status(fromServer value: String) -> QueueStatus {
        QueueStatus(rawValue: value) ?? .processing
    }
}

// MARK: - Wall progress in the task list

enum WallProgress: Equatable {
    case notStarted
    /// Some shots taken; `missing` lists the required shots with no photo yet.
    case partial(missing: [String])
    /// Every required shot has a photo on the phone, some not analysed yet.
    case captured
    /// Every required shot is analysed by Spring (or the server already counts them done).
    case synced

    var labelAr: String {
        switch self {
        case .notStarted: return "لم يبدأ"
        case .partial: return "ناقص"
        case .captured: return "مصوّر، بانتظار المزامنة"
        case .synced: return "تمت المزامنة"
        }
    }

    /// required: the task's required_shots. serverDone: done_shots from the cached survey.
    /// local: (segment, status) of the photos for this wall in the phone's queue.
    static func of(required: [String], serverDone: [String], local: [(segment: String, status: QueueStatus)]) -> WallProgress {
        let shots = required.isEmpty ? ["full"] : required
        let localShots = Set(local.filter { $0.status != .rejected }.map { $0.segment })
        let analysedShots = Set(local.filter { $0.status == .analyzed }.map { $0.segment })
        let taken = localShots.union(serverDone)
        let missing = shots.filter { !taken.contains($0) }
        if taken.isEmpty { return .notStarted }
        if !missing.isEmpty { return .partial(missing: missing) }
        let synced = shots.allSatisfy { analysedShots.contains($0) || (serverDone.contains($0) && !localShots.contains($0)) }
        let anyOpen = local.contains { $0.status != .analyzed && $0.status != .rejected }
        return synced && !anyOpen ? .synced : .captured
    }

    /// The shots still missing, for the red text under a wall.
    var missing: [String] {
        if case let .partial(missing) = self { return missing }
        return []
    }
}

// MARK: - Photos imported from the library

enum ExifDate {
    /// EXIF DateTimeOriginal ("2026:11:20 10:15:00") with OffsetTimeOriginal ("+03:00") when the camera wrote one.
    /// Without an offset the site-visit photos are read as Riyadh time.
    static func parse(_ value: String, offset: String? = nil) -> Date? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        if let offset, let zone = timeZone(offset) {
            f.timeZone = zone
        } else {
            f.timeZone = HadaqJSON.riyadh
        }
        f.dateFormat = "yyyy:MM:dd HH:mm:ss"
        return f.date(from: value.trimmingCharacters(in: .whitespaces))
    }

    static func timeZone(_ offset: String) -> TimeZone? {
        let s = offset.trimmingCharacters(in: .whitespaces)
        guard s.count == 6, let sign = s.first, sign == "+" || sign == "-",
              let h = Int(s.dropFirst().prefix(2)), let m = Int(s.suffix(2)) else { return nil }
        let seconds = (h * 3600 + m * 60) * (sign == "-" ? -1 : 1)
        return TimeZone(secondsFromGMT: seconds)
    }

    /// EXIF GPS (unsigned degrees + N/S, E/W refs) to a signed fix.
    static func gps(lat: Double?, latRef: String?, lon: Double?, lonRef: String?, accuracyM: Double? = nil) -> GeoFix? {
        guard let lat, let lon else { return nil }
        let signedLat = (latRef?.uppercased() == "S") ? -abs(lat) : abs(lat)
        let signedLon = (lonRef?.uppercased() == "W") ? -abs(lon) : abs(lon)
        return GeoFix(lat: signedLat, lon: signedLon, accuracyM: accuracyM)
    }
}
