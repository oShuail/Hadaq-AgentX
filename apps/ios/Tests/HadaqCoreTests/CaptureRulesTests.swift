import Foundation
import XCTest
@testable import HadaqCore

final class CaptureRulesTests: XCTestCase {
    // MARK: times

    func testParsesSpringAndContractTimes() throws {
        let base = try XCTUnwrap(HadaqJSON.parseDate("2026-11-29T06:41:12Z"))
        XCTAssertEqual(HadaqJSON.parseDate("2026-11-29T09:41:12+03:00"), base)
        XCTAssertEqual(try XCTUnwrap(HadaqJSON.parseDate("2026-11-29T06:41:12.123456Z")).timeIntervalSince(base), 0.123456, accuracy: 1e-6)
        XCTAssertEqual(try XCTUnwrap(HadaqJSON.parseDate("2026-11-29T09:41:12.5+03:00")).timeIntervalSince(base), 0.5, accuracy: 1e-6)
        XCTAssertEqual(HadaqJSON.parseDate("2026-11-29T06:41:12"), base, "no offset = UTC, like Spring")
        XCTAssertEqual(HadaqJSON.parseDate("2026-11-29 06:41:12Z"), base)
        XCTAssertNil(HadaqJSON.parseDate("yesterday"))
    }

    func testFormatsInRiyadhTime() {
        XCTAssertEqual(HadaqJSON.formatDate(HadaqJSON.parseDate("2026-11-29T06:41:12Z")!), "2026-11-29T09:41:12+03:00")
    }

    // MARK: quality

    func testQualityWarningsNeverBlockAndAreRecorded() {
        let good = QualityRules.quality(tiltDeg: 2, steady: true, exposure: "ok", blurScore: 300, scaleRef: true)
        XCTAssertEqual(good.tiltOk, true)
        XCTAssertEqual(good.warnings, [])
        let bad = QualityRules.quality(tiltDeg: 12, steady: false, exposure: "dark", blurScore: 20, scaleRef: false)
        XCTAssertEqual(bad.tiltOk, false)
        XCTAssertEqual(bad.warnings, ["not level", "moving", "light: dark", "no scale reference"])
        let imported = QualityRules.quality(tiltDeg: nil, steady: nil, exposure: nil, blurScore: 100, scaleRef: false)
        XCTAssertNil(imported.tiltOk)
        XCTAssertEqual(imported.warnings, ["no scale reference"])
    }

    func testTiltFromGravity() {
        XCTAssertEqual(QualityRules.tiltFromUpright(gx: 0, gy: -1), 0, accuracy: 1e-9)          // upright portrait
        XCTAssertEqual(QualityRules.tiltFromUpright(gx: 1, gy: 0), 0, accuracy: 1e-9)           // landscape
        XCTAssertEqual(QualityRules.tiltFromUpright(gx: 0, gy: -cos(.pi / 36)), 5, accuracy: 1e-6) // 5° back
        XCTAssertEqual(QualityRules.tiltFromUpright(gx: 0, gy: 0), 90, accuracy: 1e-9)          // flat on a table
    }

    // MARK: corners

    func testCornersComeOutInContractOrder() {
        // dragged handles end up crossed: TR left of TL
        let px = Corners.pixels(normalised: [(x: 0.9, y: 0.1), (x: 0.1, y: 0.12), (x: 0.08, y: 0.9), (x: 0.92, y: 0.88)],
                                width: 4000, height: 3000)
        XCTAssertEqual(px, [[400, 360], [3600, 300], [3680, 2640], [320, 2700]])
    }

    func testCornersAreClampedToThePhoto() {
        let px = Corners.pixels(normalised: [(x: -0.1, y: 0), (x: 1.2, y: 0), (x: 1, y: 1), (x: 0, y: 1)], width: 100, height: 50)
        XCTAssertEqual(px, [[0, 0], [100, 0], [100, 50], [0, 50]])
    }

    // MARK: the queue

    func testUploadErrorsKeepOrStopThePhoto() {
        XCTAssertEqual(QueueRules.statusAfterUploadError(URLError(.notConnectedToInternet)), .queued)
        XCTAssertEqual(QueueRules.statusAfterUploadError(URLError(.timedOut)), .queued)
        XCTAssertEqual(QueueRules.statusAfterUploadError(HadaqAPIError.http(status: 503, message: nil)), .queued)
        XCTAssertEqual(QueueRules.statusAfterUploadError(HadaqAPIError.http(status: 429, message: nil)), .queued)
        XCTAssertEqual(QueueRules.statusAfterUploadError(HadaqAPIError.http(status: 400, message: "wall_id is required")), .rejected)
        XCTAssertEqual(QueueRules.statusAfterUploadError(HadaqAPIError.http(status: 404, message: "Unknown building")), .rejected)
    }

    func testServerStatuses() {
        for s in ["uploaded", "processing", "analyzed", "failed"] {
            XCTAssertEqual(QueueRules.status(fromServer: s).rawValue, s)
        }
        XCTAssertEqual(QueueRules.status(fromServer: "something_new"), .processing)
        XCTAssertTrue(QueueStatus.uploaded.needsPolling)
        XCTAssertTrue(QueueStatus.processing.needsPolling)
        XCTAssertFalse(QueueStatus.analyzed.needsPolling)
        XCTAssertTrue(QueueStatus.queued.needsUpload)
    }

    func testArabicLabelsOfTheTask() {
        XCTAssertEqual(QueueStatus.queued.labelAr, "بانتظار الاتصال")
        XCTAssertEqual(QueueStatus.uploading.labelAr, "جاري الرفع")
        XCTAssertEqual(QueueStatus.processing.labelAr, "قيد التحليل")
        XCTAssertEqual(QueueStatus.analyzed.labelAr, "تم التحليل")
    }

    // MARK: wall progress

    func testWallProgress() {
        XCTAssertEqual(WallProgress.of(required: ["full"], serverDone: [], local: []), .notStarted)
        XCTAssertEqual(WallProgress.of(required: ["C1-C4", "C5-C8"], serverDone: [], local: [("C1-C4", .queued)]),
                       .partial(missing: ["C5-C8"]))
        XCTAssertEqual(WallProgress.of(required: ["full"], serverDone: [], local: [("full", .queued)]), .captured)
        XCTAssertEqual(WallProgress.of(required: ["full"], serverDone: [], local: [("full", .analyzed)]), .synced)
        XCTAssertEqual(WallProgress.of(required: ["full"], serverDone: ["full"], local: []), .synced)
        XCTAssertEqual(WallProgress.of(required: ["full"], serverDone: [], local: [("full", .rejected)]), .notStarted)
        XCTAssertEqual(WallProgress.of(required: [], serverDone: [], local: [("full", .analyzed), ("full", .uploading)]), .captured)
    }

    // MARK: imported photos

    func testExifDates() throws {
        XCTAssertEqual(ExifDate.parse("2026:11:20 10:15:00"), HadaqJSON.parseDate("2026-11-20T10:15:00+03:00"))
        XCTAssertEqual(ExifDate.parse("2026:11:20 10:15:00", offset: "+00:00"), HadaqJSON.parseDate("2026-11-20T10:15:00Z"))
        XCTAssertEqual(ExifDate.parse("2026:11:20 10:15:00", offset: "-05:30"), HadaqJSON.parseDate("2026-11-20T15:45:00Z"))
        XCTAssertNil(ExifDate.parse("not a date"))
        XCTAssertEqual(ExifDate.gps(lat: 24.73, latRef: "N", lon: 46.57, lonRef: "E"), GeoFix(lat: 24.73, lon: 46.57))
        XCTAssertEqual(ExifDate.gps(lat: 33.9, latRef: "S", lon: 18.4, lonRef: "W")?.lon, -18.4)
        XCTAssertNil(ExifDate.gps(lat: nil, latRef: nil, lon: 1, lonRef: "E"))
    }

    // MARK: requests

    func testUploadRequestMatchesSpringsMultipart() throws {
        let api = HadaqAPI(serverURL: "http://192.168.43.10:8080/", user: "surveyor")
        let (req, body) = try api.uploadCapture(jpeg: Data([0xFF, 0xD8, 0xFF]), filename: "abc.jpg",
                                                metaJSON: Data(#"{"client_uuid":"abcdefgh"}"#.utf8), boundary: "B")
        XCTAssertEqual(req.url?.absoluteString, "http://192.168.43.10:8080/api/v2/captures")
        XCTAssertEqual(req.httpMethod, "POST")
        XCTAssertEqual(req.value(forHTTPHeaderField: "X-Hadaq-User"), "surveyor")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Content-Type"), "multipart/form-data; boundary=B")
        var expected = Data("--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"abc.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".utf8)
        expected.append(Data([0xFF, 0xD8, 0xFF]))
        expected.append(Data("\r\n--B\r\nContent-Disposition: form-data; name=\"meta\"\r\nContent-Type: application/json\r\n\r\n{\"client_uuid\":\"abcdefgh\"}\r\n--B--\r\n".utf8))
        XCTAssertEqual(body, expected)
    }

    func testEveryRequestCarriesTheUserHeader() throws {
        let api = HadaqAPI(serverURL: "http://10.0.0.2:8080", user: "conservator")
        for req in [try api.registry(), try api.surveys(), try api.captureStatus("cap_1"), try api.retryCapture("cap_1"), try api.health()] {
            XCTAssertEqual(req.value(forHTTPHeaderField: "X-Hadaq-User"), "conservator", req.url?.absoluteString ?? "")
        }
        XCTAssertEqual(try api.surveys().url?.absoluteString, "http://10.0.0.2:8080/api/v2/surveys?assignee=surveyor")
        XCTAssertEqual(try api.retryCapture("cap_1").httpMethod, "POST")
        XCTAssertEqual(try api.health().url?.path, "/health")
    }

    func testBadServerURL() {
        XCTAssertThrowsError(try HadaqAPI(serverURL: "192.168.1.5:8080", user: "surveyor").registry())
        XCTAssertThrowsError(try HadaqAPI(serverURL: "", user: "surveyor").registry())
    }
}
