// Checks the app's models against the real contract files in contracts/v2/ (run: cd apps/ios && swift test).
import Foundation
import XCTest
@testable import HadaqCore

final class ContractTests: XCTestCase {
    /// contracts/v2 in this repo, found from this file's path (apps/ios/Tests/HadaqCoreTests/ContractTests.swift).
    static let contracts = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("contracts/v2")

    func example(_ name: String) throws -> Data {
        try Data(contentsOf: Self.contracts.appendingPathComponent("examples/\(name).json"))
    }

    func schemaProperties(_ name: String, def: String? = nil) throws -> (all: Set<String>, required: Set<String>) {
        let data = try Data(contentsOf: Self.contracts.appendingPathComponent("schemas/\(name).json"))
        var schema = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        if let def { schema = try XCTUnwrap((schema["$defs"] as? [String: Any])?[def] as? [String: Any]) }
        let props = try XCTUnwrap(schema["properties"] as? [String: Any])
        return (Set(props.keys), Set(schema["required"] as? [String] ?? []))
    }

    func keys(_ data: Data) throws -> Set<String> {
        Set(try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any]).keys)
    }

    // MARK: decoding the examples

    func testDecodesEveryExampleTheAppReads() throws {
        let meta = try HadaqJSON.decoder.decode(CaptureMeta.self, from: example("capture_meta"))
        XCTAssertEqual(meta.cornersPx?.count, 4)
        XCTAssertEqual(meta.quality.warnings, ["no scale reference"])
        XCTAssertEqual(meta.gps?.accuracyM, 8.0)

        let accepted = try HadaqJSON.decoder.decode(CaptureAccepted.self, from: example("capture_accepted"))
        XCTAssertEqual(accepted, CaptureAccepted(captureId: "cap_9e8d7c6b5a41", status: "uploaded", duplicate: false))

        let registry = try HadaqJSON.decoder.decode(Registry.self, from: example("registry"))
        XCTAssertEqual(registry.wall("B01", "W01")?.widthM, 32.8)
        XCTAssertEqual(registry.building("B01")?.nameAr, "قصر سلوى")

        let survey = try HadaqJSON.decoder.decode(Survey.self, from: example("survey"))
        XCTAssertEqual(survey.tasks.first?.requiredShots, ["full"])
        XCTAssertNotNil(survey.due)
    }

    /// The bundled offline copies are the contract examples (S1: "bundle registry.json and survey.json").
    func testBundledCopiesMatchTheExamples() throws {
        let resources = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Hadaq/Resources")
        for name in ["registry", "survey"] {
            let bundled = try Data(contentsOf: resources.appendingPathComponent("\(name).json"))
            XCTAssertEqual(try JSONSerialization.jsonObject(with: bundled) as? NSDictionary,
                           try JSONSerialization.jsonObject(with: example(name)) as? NSDictionary, name)
        }
    }

    /// A Capture as Spring returns it: microsecond times, every field of the schema.
    func testDecodesSpringCaptureWithMicroseconds() throws {
        let json = """
        {"client_uuid":"6f1c2b9a-4e7d","survey_id":null,"building_id":"B01","wall_id":"W01","segment":"full",
         "captured_at":"2026-11-29T06:41:12.123456Z","surveyor":"surveyor","device":null,"source":"camera","gps":null,
         "heading_deg":null,"tilt_deg":2.4,"quality":{"tilt_ok":true,"exposure":"ok","blur_score":412.5,"steady":true,
         "scale_ref":false,"warnings":[]},"corners_px":null,"image_width_px":4032,"image_height_px":3024,"faces_hidden":1,
         "provenance":"measured","capture_id":"cap_1","status":"analyzed","inspection_id":"ins_1",
         "file_url":"http://x/api/v2/captures/cap_1/file","rectified_url":null,"px_per_m":null,"scale":"relative",
         "faces_hidden_server":0,"findings_count":3,"urgent":true,"error":null,"created_at":"2026-11-29T06:41:13.5Z"}
        """
        let state = try HadaqJSON.decoder.decode(CaptureState.self, from: Data(json.utf8))
        XCTAssertEqual(state.status, "analyzed")
        XCTAssertEqual(state.findingsCount, 3)
        XCTAssertTrue(state.urgent)
        XCTAssertEqual(QueueRules.status(fromServer: state.status), .analyzed)
    }

    // MARK: encoding the upload meta

    func testEncodedMetaUsesOnlyContractFieldsAndAllRequiredOnes() throws {
        var meta = CaptureMeta(surveyId: "srv_1", buildingId: "B01", wallId: "W01", surveyor: "surveyor", device: "iPhone")
        meta.gps = GeoFix(lat: 24.7335, lon: 46.5722, accuracyM: 5)
        meta.headingDeg = 92
        meta.tiltDeg = 2.4
        meta.quality = QualityRules.quality(tiltDeg: 2.4, steady: true, exposure: "ok", blurScore: 400, scaleRef: false)
        meta.cornersPx = [[1, 2], [3, 4], [5, 6], [7, 8]]
        meta.imageWidthPx = 4032
        meta.imageHeightPx = 3024
        meta.facesHidden = 1
        let data = try HadaqJSON.encoder.encode(meta)

        let schema = try schemaProperties("CaptureMeta")
        let sent = try keys(data)
        XCTAssertTrue(sent.isSubset(of: schema.all), "not in the schema: \(sent.subtracting(schema.all))")
        XCTAssertTrue(schema.required.isSubset(of: sent), "missing: \(schema.required.subtracting(sent))")
        // with every optional set, the app sends every field of the contract example
        XCTAssertEqual(sent, try keys(example("capture_meta")))

        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let quality = try XCTUnwrap(object["quality"] as? [String: Any])
        XCTAssertTrue(Set(quality.keys).isSubset(of: try schemaProperties("CaptureMeta", def: "CaptureQuality").all))
        let gpsKeys = (object["gps"] as? [String: Any]).map { Set($0.keys) } ?? []
        XCTAssertEqual(gpsKeys, ["lat", "lon", "accuracy_m"])
        XCTAssertEqual(object["provenance"] as? String, "measured")
        XCTAssertEqual(object["source"] as? String, "camera")

        // Spring's Times.parse reads "2026-11-29T09:41:12+03:00"
        let captured = try XCTUnwrap(object["captured_at"] as? String)
        XCTAssertNotNil(captured.range(of: #"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+03:00$"#, options: .regularExpression), captured)
    }

    func testMetaRoundTripsUnchanged() throws {
        var meta = CaptureMeta(buildingId: "B01", wallId: "W01", surveyor: "surveyor")
        meta.capturedAt = Date(timeIntervalSince1970: 1_796_000_000)
        meta.facesHidden = 0
        let data = try HadaqJSON.encoder.encode(meta)
        XCTAssertEqual(try HadaqJSON.decoder.decode(CaptureMeta.self, from: data), meta)
        // the same bytes on every retry: encoding is deterministic (sorted keys)
        XCTAssertEqual(try HadaqJSON.encoder.encode(meta), data)
    }

    func testNilOptionalsAreLeftOut() throws {
        let meta = CaptureMeta(buildingId: "B01", wallId: "W01", surveyor: "surveyor")
        let sent = try keys(HadaqJSON.encoder.encode(meta))
        XCTAssertFalse(sent.contains("survey_id"))
        XCTAssertFalse(sent.contains("corners_px"))
        XCTAssertTrue(sent.contains("client_uuid"))
        XCTAssertGreaterThanOrEqual(meta.clientUuid.count, 8) // Spring: 8 to 80 characters
        XCTAssertLessThanOrEqual(meta.clientUuid.count, 80)
    }
}
