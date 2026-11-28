// Contract v2 models used by the surveyor app (contracts/v2/schemas/).
// Swift property names are camelCase; HadaqJSON converts them to and from the snake_case on the wire.
// Foundation only, so `swift test` can check them on any Mac (see Package.swift).

import Foundation

// MARK: - Upload: CaptureMeta (the `meta` part of POST /api/v2/captures)

/// Checks made by the app at the shutter. Warnings never block a shot; they are recorded.
struct CaptureQuality: Codable, Equatable {
    var tiltOk: Bool?
    /// "ok" | "dark" | "bright"
    var exposure: String?
    var blurScore: Double?
    var steady: Bool?
    var scaleRef: Bool = false
    var warnings: [String] = []
}

struct GeoFix: Codable, Equatable {
    var lat: Double
    var lon: Double
    var accuracyM: Double?
}

/// contracts/v2/schemas/CaptureMeta.json. Written once per photo on the phone and sent unchanged on every retry.
struct CaptureMeta: Codable, Equatable {
    /// Made on the phone. The same value on every retry, so Spring stores the photo exactly once.
    var clientUuid: String = UUID().uuidString.lowercased()
    /// Only set when the survey came from the server (a bundled example id would open a fake inspection).
    var surveyId: String?
    var buildingId: String
    var wallId: String
    var segment: String = "full"
    var capturedAt: Date = Date()
    var surveyor: String
    var device: String?
    /// "camera" | "import"
    var source: String = "camera"
    var gps: GeoFix?
    var headingDeg: Double?
    var tiltDeg: Double?
    var quality = CaptureQuality()
    /// The wall's 4 corners in image pixels: top-left, top-right, bottom-right, bottom-left. nil = whole photo.
    var cornersPx: [[Double]]?
    var imageWidthPx: Int?
    var imageHeightPx: Int?
    /// Faces the phone hid. nil would tell Spring to check the photo itself; the app always sets it.
    var facesHidden: Int?
    /// A real photo of the wall is "measured". Never change this for demo data that is not real.
    var provenance: String = "measured"

    init(surveyId: String? = nil, buildingId: String, wallId: String, segment: String = "full",
         surveyor: String, device: String? = nil, source: String = "camera") {
        self.surveyId = surveyId
        self.buildingId = buildingId
        self.wallId = wallId
        self.segment = segment
        self.surveyor = surveyor
        self.device = device
        self.source = source
    }
}

// MARK: - Upload answers

/// 202 of POST /api/v2/captures (also for a repeated client_uuid, with duplicate = true).
struct CaptureAccepted: Codable, Equatable {
    let captureId: String
    let status: String
    let duplicate: Bool
}

/// The part of GET /api/v2/captures/{id} (`Capture`) the app reads. Other fields are ignored.
struct CaptureState: Codable, Equatable {
    let captureId: String
    let status: String
    var findingsCount: Int = 0
    var urgent: Bool = false
    var error: String?
    var inspectionId: String?
    var facesHiddenServer: Int?
}

/// Spring's error body: {"message": "..."}.
struct ServerMessage: Codable {
    let message: String?
}

// MARK: - Registry and surveys (cached for offline use)

struct LonLat: Codable, Equatable {
    let lon: Double
    let lat: Double
}

struct BaseLine: Codable, Equatable {
    let start: LonLat
    let end: LonLat
}

struct Wall: Codable, Equatable, Identifiable {
    let id: String
    let buildingId: String
    let name: String
    var azimuthDeg: Double?
    var widthM: Double?
    var heightM: Double?
    var rows: Int?
    var cols: Int?
    var baseLine: BaseLine?
    var mainFacade: Bool?
    var visitorRoute: Bool?
    var originalFabric: String?
    var provenance: String?
}

struct Building: Codable, Equatable, Identifiable {
    let id: String
    let nameAr: String
    let nameEn: String
    var lon: Double?
    var lat: Double?
    var hasModel: Bool?
    var walls: [Wall]
}

struct Registry: Codable, Equatable {
    let siteId: String
    let buildings: [Building]

    func wall(_ buildingId: String, _ wallId: String) -> Wall? {
        buildings.first { $0.id == buildingId }?.walls.first { $0.id == wallId }
    }

    func building(_ id: String) -> Building? {
        buildings.first { $0.id == id }
    }
}

struct SurveyTask: Codable, Equatable {
    let buildingId: String
    let wallId: String
    var requiredShots: [String] = ["full"]
    var doneShots: [String] = []
    /// not_started | partial | done | synced (server view)
    var status: String = "not_started"
}

struct Survey: Codable, Equatable, Identifiable {
    let surveyId: String
    let title: String
    var assignee: String?
    var due: Date?
    var tasks: [SurveyTask]

    var id: String { surveyId }
}
