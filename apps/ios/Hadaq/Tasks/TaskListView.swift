import SwiftData
import SwiftUI

/// Building, then its walls, each with a status and its missing shots in red. The question at the bottom is the
/// surveyor's last check before leaving the site.
struct TaskListView: View {
    @ObservedObject private var store = TaskStore.shared
    @ObservedObject private var uploader = Uploader.shared
    @Query private var items: [QueuedCapture]

    var body: some View {
        List {
            if let survey = store.currentSurvey {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(survey.title).font(.headline)
                        if let due = survey.due {
                            Text("الموعد: \(due.formatted(date: .abbreviated, time: .shortened))").font(.caption).foregroundStyle(.secondary)
                        }
                        if !store.surveysFromServer {
                            Text("نسخة مدمجة للتجربة، لم يتم الاتصال بالخادم بعد").font(.caption).foregroundStyle(.orange)
                        }
                    }
                }
            }
            if !uploader.online {
                Label("بدون اتصال: يمكنك التصوير، والصور تُرفع لاحقاً", systemImage: "wifi.slash").foregroundStyle(.orange)
            }
            ForEach(store.buildingsToSurvey) { entry in
                Section(entry.building.nameAr + " · " + entry.building.id) {
                    ForEach(entry.walls) { wall in
                        NavigationLink {
                            WallView(building: entry.building, wall: wall)
                        } label: {
                            WallRow(wall: wall, progress: progress(entry.building.id, wall.id), count: local(entry.building.id, wall.id).count)
                        }
                    }
                }
            }
            Section {
                LeaveSiteCheck(missing: missingSummary)
            }
        }
        .navigationTitle("مهام المسح")
        .refreshable { await store.refresh() }
    }

    private func local(_ buildingId: String, _ wallId: String) -> [QueuedCapture] {
        items.filter { $0.buildingId == buildingId && $0.wallId == wallId }
    }

    private func progress(_ buildingId: String, _ wallId: String) -> WallProgress {
        let task = store.task(buildingId: buildingId, wallId: wallId)
        return WallProgress.of(required: task?.requiredShots ?? ["full"],
                               serverDone: task?.doneShots ?? [],
                               local: local(buildingId, wallId).map { (segment: $0.segment, status: $0.queueStatus) })
    }

    /// "B01 W01: full" for every wall that still misses a shot.
    private var missingSummary: [String] {
        store.buildingsToSurvey.flatMap { entry in
            entry.walls.compactMap { wall in
                let p = progress(entry.building.id, wall.id)
                let missing = p == .notStarted ? (store.task(buildingId: entry.building.id, wallId: wall.id)?.requiredShots ?? ["full"]) : p.missing
                return missing.isEmpty ? nil : "\(entry.building.id) \(wall.id): \(missing.joined(separator: "، "))"
            }
        }
    }
}

struct WallRow: View {
    let wall: Wall
    let progress: WallProgress
    let count: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(wall.id).bold()
                Text(wall.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                Spacer()
                Text(progress.labelAr).font(.caption.bold()).foregroundStyle(color)
            }
            if !progress.missing.isEmpty || progress == .notStarted {
                Text("ناقص: " + (progress == .notStarted ? "كل اللقطات" : progress.missing.joined(separator: "، ")))
                    .font(.caption).foregroundStyle(.red)
            }
            if count > 0 { Text("\(count) صورة على الهاتف").font(.caption2).foregroundStyle(.secondary) }
        }
    }

    private var color: Color {
        switch progress {
        case .synced: return .green
        case .captured: return .blue
        case .partial: return .orange
        case .notStarted: return .red
        }
    }
}

/// هل صوّرت كل شيء قبل أغادر الموقع؟
struct LeaveSiteCheck: View {
    let missing: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("هل صوّرت كل شيء قبل أغادر الموقع؟").font(.headline)
            if missing.isEmpty {
                Label("نعم، كل اللقطات المطلوبة مصوّرة", systemImage: "checkmark.seal.fill").foregroundStyle(.green)
            } else {
                Label("لا، باقي \(missing.count):", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red)
                ForEach(missing, id: \.self) { Text($0).font(.caption).foregroundStyle(.red) }
            }
        }
        .padding(.vertical, 4)
    }
}
