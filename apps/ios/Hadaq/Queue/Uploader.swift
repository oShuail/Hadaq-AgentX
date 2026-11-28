import Foundation
import Network
import SwiftData

/// Drains the offline queue to POST /api/v2/captures and follows each capture until Spring has analysed it.
///
/// - Every retry sends the same client_uuid and the same meta bytes, so Spring stores each photo exactly once
///   (it answers a repeat with duplicate = true and the first capture's id).
/// - The queue is drained when the network comes back, right after a capture is saved, and every 15 s.
/// - While a capture is uploaded or processing, its status is polled every 3 s (shutter-to-findings target ≈ 10 s).
@MainActor
final class Uploader: ObservableObject {
    static let shared = Uploader()

    @Published private(set) var online = true
    @Published private(set) var pendingCount = 0
    @Published private(set) var lastSync: Date?

    static let drainInterval: TimeInterval = 15
    static let pollInterval: TimeInterval = 3

    private let monitor = NWPathMonitor()
    private var draining = false
    private var polling = false
    private var lastDrain = Date.distantPast
    private var timer: Timer?
    private var started = false

    private var context: ModelContext? { CaptureStore.shared.context }

    func start() {
        guard !started else { return }
        started = true
        monitor.pathUpdateHandler = { path in
            Task { @MainActor in
                let wasOnline = Uploader.shared.online
                Uploader.shared.online = path.status == .satisfied
                if Uploader.shared.online && !wasOnline {
                    await TaskStore.shared.refresh()
                    await Uploader.shared.drain(force: true)
                }
            }
        }
        monitor.start(queue: DispatchQueue(label: "hadaq.network"))
        timer = Timer.scheduledTimer(withTimeInterval: Self.pollInterval, repeats: true) { _ in
            Task { @MainActor in await Uploader.shared.tick() }
        }
        refreshCounts()
        Task { await drain(force: true) }
    }

    private func tick() async {
        if Date().timeIntervalSince(lastDrain) >= Self.drainInterval { await drain() }
        await refreshResults()
    }

    func refreshCounts() {
        let open = (try? context?.fetch(FetchDescriptor<QueuedCapture>(predicate: #Predicate { $0.status == "queued" || $0.status == "uploading" }))) ?? []
        pendingCount = open.count
    }

    // MARK: Upload

    /// Uploads every queued photo, one at a time. `force` skips the 15 s spacing (network back, new photo, retry).
    func drain(force: Bool = false) async {
        guard let ctx = context, !draining else { return }
        if !force && Date().timeIntervalSince(lastDrain) < Self.drainInterval { return }
        lastDrain = Date()
        guard online else { refreshCounts(); return }
        draining = true
        defer { draining = false; refreshCounts() }

        let pending = (try? ctx.fetch(FetchDescriptor<QueuedCapture>(
            predicate: #Predicate { $0.status == "queued" },
            sortBy: [SortDescriptor(\.createdAt)]))) ?? []
        for item in pending {
            item.queueStatus = .uploading
            item.attempts += 1
            try? ctx.save()
            do {
                let accepted = try await upload(item)
                item.captureId = accepted.captureId
                item.queueStatus = QueueRules.status(fromServer: accepted.status)
                item.uploadedAt = .now
                item.lastError = accepted.duplicate ? "كانت مرفوعة من قبل (نفس المعرّف)" : nil
                lastSync = .now
            } catch {
                item.queueStatus = QueueRules.statusAfterUploadError(error)
                item.lastError = error.localizedDescription
                try? ctx.save()
                // The server is not reachable: keep the rest queued and try again on the next tick.
                if item.queueStatus == .queued { break }
            }
            try? ctx.save()
        }
        await refreshResults()
    }

    private func upload(_ item: QueuedCapture) async throws -> CaptureAccepted {
        let jpeg = try Data(contentsOf: item.imageURL)
        let (request, body) = try AppSettings.api.uploadCapture(jpeg: jpeg, filename: item.imageFile, metaJSON: item.metaJSON)
        let (data, response) = try await URLSession.shared.upload(for: request, from: body)
        try HadaqAPI.check(data, response)
        return try HadaqJSON.decoder.decode(CaptureAccepted.self, from: data)
    }

    // MARK: Results

    /// Polls the captures Spring has not finished, and copies status, findings count and the urgent flag.
    func refreshResults() async {
        guard let ctx = context, online, !polling else { return }
        polling = true
        defer { polling = false }
        let open = (try? ctx.fetch(FetchDescriptor<QueuedCapture>(predicate: #Predicate { $0.status == "uploaded" || $0.status == "processing" }))) ?? []
        guard !open.isEmpty else { return }
        let api = AppSettings.api
        for item in open {
            guard let id = item.captureId else { item.queueStatus = .queued; continue }
            do {
                let (data, response) = try await URLSession.shared.data(for: try api.captureStatus(id))
                try HadaqAPI.check(data, response)
                let state = try HadaqJSON.decoder.decode(CaptureState.self, from: data)
                apply(state, to: item)
            } catch HadaqAPIError.http(404, _) {
                // The server's database was reset: send the photo again (Spring sees a new client_uuid record).
                item.captureId = nil
                item.queueStatus = .queued
                item.lastError = "الخادم لا يعرف هذه الصورة، ستُرفع من جديد"
            } catch {
                break // not reachable right now; the next tick tries again
            }
        }
        try? ctx.save()
        refreshCounts()
    }

    private func apply(_ state: CaptureState, to item: QueuedCapture) {
        let status = QueueRules.status(fromServer: state.status)
        item.queueStatus = status
        item.findingsCount = state.findingsCount
        item.urgent = state.urgent
        item.lastError = status == .failed ? (state.error ?? "تعذر التحليل") : nil
        if status == .analyzed, item.analyzedAt == nil { item.analyzedAt = .now }
        lastSync = .now
    }

    // MARK: Retry buttons

    /// Retry from the queue screen: a phone-side failure is uploaded again (same client_uuid);
    /// a capture Spring marked failed is sent to the AI worker again (POST /captures/{id}/retry).
    func retry(_ item: QueuedCapture) async {
        switch item.queueStatus {
        case .failed:
            guard let id = item.captureId else { item.queueStatus = .queued; break }
            do {
                let (data, response) = try await URLSession.shared.data(for: try AppSettings.api.retryCapture(id))
                try HadaqAPI.check(data, response)
                if let state = try? HadaqJSON.decoder.decode(CaptureState.self, from: data) { apply(state, to: item) }
                else { item.queueStatus = .uploaded }
            } catch {
                item.lastError = error.localizedDescription
            }
        default:
            item.queueStatus = .queued
            item.lastError = nil
        }
        try? context?.save()
        await drain(force: true)
    }
}
