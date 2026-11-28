import Foundation
import SwiftData

/// One photo waiting on the phone or already on the server. The JPEG lives in Documents/captures/<client_uuid>.jpg
/// (only the face-hidden version is ever written), with the meta JSON next to it as <client_uuid>.json.
@Model
final class QueuedCapture {
    @Attribute(.unique) var clientUuid: String
    var buildingId: String
    var wallId: String
    var segment: String
    /// CaptureMeta encoded with HadaqJSON.encoder (snake_case). Sent unchanged on every retry.
    var metaJSON: Data
    /// File name inside Documents/captures
    var imageFile: String
    /// QueueStatus raw value: queued | uploading | uploaded | processing | analyzed | failed | rejected
    var status: String
    var captureId: String?
    var findingsCount: Int = 0
    var urgent: Bool = false
    var facesHidden: Int = 0
    var lastError: String?
    var attempts: Int = 0
    var createdAt: Date = Date.now
    var uploadedAt: Date?
    var analyzedAt: Date?

    init(meta: CaptureMeta, metaJSON: Data, imageFile: String) {
        clientUuid = meta.clientUuid
        buildingId = meta.buildingId
        wallId = meta.wallId
        segment = meta.segment
        self.metaJSON = metaJSON
        self.imageFile = imageFile
        facesHidden = meta.facesHidden ?? 0
        status = QueueStatus.queued.rawValue
        createdAt = meta.capturedAt
    }

    var queueStatus: QueueStatus {
        get { QueueStatus(rawValue: status) ?? .queued }
        set { status = newValue.rawValue }
    }

    var meta: CaptureMeta? { try? HadaqJSON.decoder.decode(CaptureMeta.self, from: metaJSON) }

    var imageURL: URL { CaptureStore.folder.appendingPathComponent(imageFile) }
}
