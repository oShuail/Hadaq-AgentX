import PhotosUI
import SwiftData
import SwiftUI

/// One wall: what to shoot, the camera, import from Photos, and the photos already taken of it.
struct WallView: View {
    let building: Building
    let wall: Wall

    @AppStorage(AppSettings.userKey) private var user = AppSettings.defaultUser
    @ObservedObject private var store = TaskStore.shared
    @Query(sort: \QueuedCapture.createdAt, order: .reverse) private var allItems: [QueuedCapture]
    @State private var segment = "full"
    @State private var showCamera = false
    @State private var picked: PhotosPickerItem?
    @State private var imported: Shot?
    @State private var importing = false
    @State private var importError: String?

    private var task: SurveyTask? { store.task(buildingId: building.id, wallId: wall.id) }
    private var shots: [String] { task?.requiredShots.isEmpty == false ? task!.requiredShots : ["full"] }
    private var items: [QueuedCapture] { allItems.filter { $0.buildingId == building.id && $0.wallId == wall.id } }

    var body: some View {
        List {
            Section("الجدار") {
                LabeledContent("المبنى", value: "\(building.nameAr) (\(building.id))")
                LabeledContent("الجدار", value: "\(wall.id): \(wall.name)")
                if let w = wall.widthM, let h = wall.heightM {
                    LabeledContent("المقاس", value: String(format: "%.1f × %.1f م", w, h))
                }
            }

            Section("اللقطة") {
                if shots.count > 1 {
                    Picker("اللقطة", selection: $segment) {
                        ForEach(shots, id: \.self) { Text($0).tag($0) }
                    }
                }
                ForEach(shots, id: \.self) { shot in
                    let done = items.contains { $0.segment == shot && $0.queueStatus != .rejected } || (task?.doneShots.contains(shot) ?? false)
                    Label(shot, systemImage: done ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(done ? .green : .red)
                }
            }

            Section {
                Button { showCamera = true } label: { Label("التقط صورة", systemImage: "camera.fill") }
                PhotosPicker(selection: $picked, matching: .images) {
                    Label(importing ? "جاري الاستيراد..." : "استيراد من الصور", systemImage: "photo.on.rectangle")
                }
                .disabled(importing)
                if let importError { Text(importError).font(.caption).foregroundStyle(.red) }
            } footer: {
                Text("صوّر عمودياً على الواجهة، بإضاءة متساوية بلا ظل الظهيرة الحاد، ومع مقياس غير ملصق على الجدار إن أمكن.")
            }

            if !items.isEmpty {
                Section("صور هذا الجدار") {
                    ForEach(items) { item in
                        NavigationLink { CaptureDetailView(item: item) } label: { QueueRow(item: item) }
                    }
                }
            }
        }
        .navigationTitle(wall.id)
        .onAppear { segment = firstMissingShot() }
        .fullScreenCover(isPresented: $showCamera) {
            CaptureView(buildingId: building.id, wallId: wall.id, segment: segment,
                        surveyId: store.surveyId(buildingId: building.id, wallId: wall.id))
        }
        .sheet(item: $imported) { shot in CornersView(image: shot.image, meta: shot.meta) }
        .onChange(of: picked) { _, item in
            guard let item else { return }
            Task { await importPhoto(item) }
        }
    }

    private func firstMissingShot() -> String {
        shots.first { shot in !items.contains { $0.segment == shot } && !(task?.doneShots.contains(shot) ?? false) } ?? shots[0]
    }

    /// S1 step 9: keep the EXIF date (and GPS when the photo has it), hide faces, then the corners screen.
    private func importPhoto(_ item: PhotosPickerItem) async {
        importing = true
        importError = nil
        defer { importing = false; picked = nil }
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            importError = "تعذر قراءة الصورة"
            return
        }
        var meta = CaptureMeta(surveyId: store.surveyId(buildingId: building.id, wallId: wall.id),
                               buildingId: building.id, wallId: wall.id, segment: segment,
                               surveyor: user, device: DeviceInfo.description, source: "import")
        let info = ImageImport.metadata(of: data)
        if let date = info.capturedAt { meta.capturedAt = date }
        meta.gps = info.gps
        guard let prepared = await ImageChecks.prepare(data: data) else {
            importError = "تعذر فتح الصورة"
            return
        }
        meta.facesHidden = prepared.facesHidden
        meta.imageWidthPx = prepared.widthPx
        meta.imageHeightPx = prepared.heightPx
        meta.quality = QualityRules.quality(tiltDeg: nil, steady: nil, exposure: nil,
                                            blurScore: prepared.blurScore, scaleRef: false)
        imported = Shot(image: prepared.image, meta: meta)
    }
}

/// A photo with its faces hidden and its meta, on its way to the corners screen.
struct Shot: Identifiable {
    let id = UUID()
    let image: UIImage
    let meta: CaptureMeta
}
