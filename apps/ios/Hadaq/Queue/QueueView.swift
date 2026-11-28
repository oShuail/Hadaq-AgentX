import SwiftData
import SwiftUI

/// The photos on the phone: offline banner, status per item, findings count and the urgent badge.
struct QueueView: View {
    @Query(sort: \QueuedCapture.createdAt, order: .reverse) private var items: [QueuedCapture]
    @ObservedObject private var uploader = Uploader.shared

    var body: some View {
        List {
            if !uploader.online {
                Label("بدون اتصال: الصور محفوظة وسترفع تلقائياً", systemImage: "wifi.slash")
                    .foregroundStyle(HadaqTheme.goldText).hadaqPanel()
            }
            if items.isEmpty {
                ContentUnavailableView("لا توجد صور", systemImage: "camera",
                                       description: Text("صوّر جداراً من تبويب المهام"))
                    .hadaqPanel()
            }
            ForEach(items) { item in
                NavigationLink { CaptureDetailView(item: item) } label: { QueueRow(item: item) }
                    .hadaqPanel()
            }
            .onDelete { offsets in offsets.map { items[$0] }.forEach { CaptureStore.shared.delete($0) } }
            if let last = uploader.lastSync {
                Text("آخر مزامنة \(last.formatted(date: .omitted, time: .standard))")
                    .font(.caption2).foregroundStyle(HadaqTheme.muted).hadaqPanel()
            }
        }
        .hadaqList()
        .safeAreaInset(edge: .top, spacing: 0) { HadaqHeader(title: "الصور", subtitle: "CAPTURE QUEUE · OFFLINE READY") }
        .navigationTitle("الصور")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            await Uploader.shared.drain(force: true)
            await TaskStore.shared.refresh()
        }
    }
}

struct QueueRow: View {
    let item: QueuedCapture

    var body: some View {
        HStack(spacing: 12) {
            Thumbnail(url: item.imageURL).frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(HadaqTheme.line))
            VStack(alignment: .leading, spacing: 2) {
                Text("\(item.buildingId) · \(item.wallId) · \(item.segment)").bold()
                Text(item.queueStatus.labelAr).font(.caption).foregroundStyle(color)
                if let error = item.lastError {
                    Text(error).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            Spacer()
            if item.urgent {
                Text("عاجل").font(.caption.bold()).padding(6)
                    .background(HadaqTheme.rose).foregroundStyle(HadaqTheme.background).clipShape(Capsule())
            }
            if item.queueStatus == .analyzed {
                Text("\(item.findingsCount) تلف").font(.caption)
            }
            if item.queueStatus == .uploading || item.queueStatus.needsPolling {
                ProgressView()
            }
            if item.queueStatus == .failed || item.queueStatus == .rejected {
                Button("إعادة") { Task { await Uploader.shared.retry(item) } }
                    .buttonStyle(.bordered)
            }
        }
    }

    private var color: Color {
        switch item.queueStatus {
        case .analyzed: return HadaqTheme.sage
        case .failed, .rejected: return HadaqTheme.rose
        case .queued: return HadaqTheme.goldText
        default: return .secondary
        }
    }
}

/// Small image from disk, decoded off the main thread.
struct Thumbnail: View {
    let url: URL
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.secondary.opacity(0.15)
            if let image { Image(uiImage: image).resizable().scaledToFill() }
        }
        .task(id: url) {
            image = await Task.detached(priority: .utility) { () -> UIImage? in
                guard let data = try? Data(contentsOf: url), let full = UIImage(data: data) else { return nil }
                return full.preparingThumbnail(of: CGSize(width: 160, height: 160))
            }.value
        }
    }
}

/// The photo exactly as stored (faces already hidden) and the meta JSON that is sent with it.
struct CaptureDetailView: View {
    let item: QueuedCapture
    @State private var image: UIImage?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let image {
                    Image(uiImage: image).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 8))
                }
                LabeledContent("الحالة", value: item.queueStatus.labelAr)
                LabeledContent("الوجوه المخفية", value: "\(item.facesHidden)")
                if let id = item.captureId { LabeledContent("capture_id", value: id) }
                LabeledContent("المحاولات", value: "\(item.attempts)")
                if let error = item.lastError { Text(error).font(.caption).foregroundStyle(.red) }
                Text("meta").font(.headline)
                Text(prettyMeta).font(.system(.caption2, design: .monospaced)).textSelection(.enabled)
                    .environment(\.layoutDirection, .leftToRight)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding()
        }
        .background(HadaqTheme.background.ignoresSafeArea())
        .navigationTitle(item.clientUuid.prefix(8))
        .task { image = UIImage(contentsOfFile: item.imageURL.path) }
    }

    private var prettyMeta: String {
        guard let object = try? JSONSerialization.jsonObject(with: item.metaJSON),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]) else {
            return String(data: item.metaJSON, encoding: .utf8) ?? ""
        }
        return String(data: data, encoding: .utf8) ?? ""
    }
}
