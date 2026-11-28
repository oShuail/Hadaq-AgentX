import Foundation
import SwiftData
import UIKit

/// Saves a finished capture into the offline queue and clears it for demo runs.
@MainActor
final class CaptureStore {
    static let shared = CaptureStore()
    /// Set by HadaqApp to the container's main context.
    var context: ModelContext!

    /// Documents/captures. Visible in the Files app (UIFileSharingEnabled), so the saved JPEG and JSON can be checked.
    nonisolated static var folder: URL {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("captures", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    enum SaveError: LocalizedError {
        case encode
        var errorDescription: String? { "تعذر حفظ الصورة على الهاتف" }
    }

    /// `image` must already have its faces hidden (ImageChecks.prepare); nothing else is written to disk.
    func add(image: UIImage, meta: CaptureMeta) throws {
        guard let jpeg = image.jpegData(compressionQuality: 0.9) else { throw SaveError.encode }
        let metaJSON = try HadaqJSON.encoder.encode(meta)
        let name = "\(meta.clientUuid).jpg"
        try jpeg.write(to: Self.folder.appendingPathComponent(name), options: .atomic)
        // The same JSON as the upload's meta part, next to the photo, so it can be read in the Files app.
        try? metaJSON.write(to: Self.folder.appendingPathComponent("\(meta.clientUuid).json"), options: .atomic)
        context.insert(QueuedCapture(meta: meta, metaJSON: metaJSON, imageFile: name))
        try context.save()
        Uploader.shared.refreshCounts()
        Task { await Uploader.shared.drain(force: true) }
    }

    /// An upload cut off by the app closing would stay "uploading" forever: put it back in the queue.
    /// Sending it again is safe because the client_uuid is the same.
    func recoverInterruptedUploads() {
        let stuck = (try? context.fetch(FetchDescriptor<QueuedCapture>(predicate: #Predicate { $0.status == "uploading" }))) ?? []
        for item in stuck { item.queueStatus = .queued }
        try? context.save()
    }

    func items(buildingId: String, wallId: String) -> [QueuedCapture] {
        (try? context.fetch(FetchDescriptor<QueuedCapture>(predicate: #Predicate { $0.buildingId == buildingId && $0.wallId == wallId }))) ?? []
    }

    func delete(_ item: QueuedCapture) {
        removeFiles(of: item)
        context.delete(item)
        try? context.save()
        Uploader.shared.refreshCounts()
    }

    /// Settings > Reset: every queued row and its files, for a clean demo run. The server keeps its copies.
    func reset() {
        let all = (try? context.fetch(FetchDescriptor<QueuedCapture>())) ?? []
        for item in all {
            removeFiles(of: item)
            context.delete(item)
        }
        try? context.save()
        if let leftovers = try? FileManager.default.contentsOfDirectory(at: Self.folder, includingPropertiesForKeys: nil) {
            leftovers.forEach { try? FileManager.default.removeItem(at: $0) }
        }
        Uploader.shared.refreshCounts()
    }

    private func removeFiles(of item: QueuedCapture) {
        try? FileManager.default.removeItem(at: item.imageURL)
        try? FileManager.default.removeItem(at: Self.folder.appendingPathComponent("\(item.clientUuid).json"))
    }
}
