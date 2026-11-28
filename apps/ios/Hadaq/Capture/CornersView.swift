import SwiftUI

/// Four draggable handles on the wall's corners, saved as corners_px (top-left, top-right, bottom-right,
/// bottom-left in image pixels). Saving puts the photo and its meta into the offline queue.
struct CornersView: View {
    let image: UIImage
    @State var meta: CaptureMeta
    var onSaved: () -> Void = {}

    @Environment(\.dismiss) private var dismiss
    /// TL, TR, BR, BL, normalised 0...1 over the photo
    @State private var pts: [CGPoint] = [CGPoint(x: 0.08, y: 0.1), CGPoint(x: 0.92, y: 0.1),
                                         CGPoint(x: 0.92, y: 0.9), CGPoint(x: 0.08, y: 0.9)]
    @State private var error: String?
    @State private var saving = false

    private let labels = ["1", "2", "3", "4"]

    init(image: UIImage, meta: CaptureMeta, onSaved: @escaping () -> Void = {}) {
        self.image = image
        _meta = State(initialValue: meta)
        self.onSaved = onSaved
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Text("اسحب النقاط الأربع إلى زوايا الجدار").font(.headline).foregroundStyle(HadaqTheme.goldText)
                Text("1 أعلى اليسار (طرف C1)، 2 أعلى اليمين، 3 أسفل اليمين، 4 أسفل اليسار")
                    .font(.caption).foregroundStyle(.secondary)

                GeometryReader { geo in
                    let size = fittedSize(image.size, in: geo.size)
                    ZStack(alignment: .topLeading) {
                        Image(uiImage: image).resizable().frame(width: size.width, height: size.height)
                        Path { p in
                            p.addLines(pts.map { CGPoint(x: $0.x * size.width, y: $0.y * size.height) })
                            p.closeSubpath()
                        }
                        .stroke(HadaqTheme.goldText, lineWidth: 2)
                        ForEach(0..<4, id: \.self) { i in
                            ZStack {
                                Circle().fill(HadaqTheme.gold.opacity(0.35)).frame(width: 44, height: 44)
                                Circle().stroke(HadaqTheme.goldText, lineWidth: 3).frame(width: 28, height: 28)
                                Text(labels[i]).font(.caption2.bold()).foregroundStyle(HadaqTheme.background)
                            }
                            .position(x: pts[i].x * size.width, y: pts[i].y * size.height)
                            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .named("photo")).onChanged { v in
                                pts[i] = CGPoint(x: min(1, max(0, v.location.x / size.width)),
                                                 y: min(1, max(0, v.location.y / size.height)))
                            })
                        }
                    }
                    .frame(width: size.width, height: size.height)
                    .coordinateSpace(name: "photo")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                // Image pixels run left to right whatever the UI language, so the canvas never mirrors.
                .environment(\.layoutDirection, .leftToRight)

                if (meta.facesHidden ?? 0) > 0 {
                    Label("أُخفي \(meta.facesHidden ?? 0) وجه قبل الحفظ", systemImage: "eye.slash").font(.caption)
                }
                if !meta.quality.warnings.isEmpty {
                    Text("ملاحظات الجودة: " + meta.quality.warnings.joined(separator: "، "))
                        .font(.caption).foregroundStyle(.orange)
                }
                if let error { Text(error).font(.caption).foregroundStyle(.red) }

                HStack {
                    Button("كامل الصورة") { save(useCorners: false) }
                        .buttonStyle(.bordered)
                    Button("حفظ") { save(useCorners: true) }
                        .buttonStyle(.borderedProminent).tint(HadaqTheme.gold)
                }
                .disabled(saving)
            }
            .padding()
            .background(HadaqTheme.background.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("إلغاء") { dismiss() } }
            }
        }
        .interactiveDismissDisabled()
    }

    private func fittedSize(_ img: CGSize, in box: CGSize) -> CGSize {
        guard img.width > 0, img.height > 0 else { return .zero }
        let s = min(box.width / img.width, box.height / img.height)
        return CGSize(width: img.width * s, height: img.height * s)
    }

    /// useCorners = false sends corners_px = null (the whole photo; Spring then marks the scale "relative").
    private func save(useCorners: Bool) {
        saving = true
        defer { saving = false }
        let w = meta.imageWidthPx ?? Int(image.size.width * image.scale)
        let h = meta.imageHeightPx ?? Int(image.size.height * image.scale)
        meta.imageWidthPx = w
        meta.imageHeightPx = h
        meta.cornersPx = useCorners ? Corners.pixels(normalised: pts.map { (x: Double($0.x), y: Double($0.y)) }, width: w, height: h) : nil
        do {
            try CaptureStore.shared.add(image: image, meta: meta)
            onSaved()
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
