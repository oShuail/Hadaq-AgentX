import SwiftUI

/// The camera screen: live chips for level, steady, light and the scale reference toggle.
/// Warnings never block the shot; they go into quality.warnings.
struct CaptureView: View {
    let buildingId: String
    let wallId: String
    let segment: String
    let surveyId: String?

    @AppStorage(AppSettings.userKey) private var user = AppSettings.defaultUser
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = Camera()
    @StateObject private var level = LevelMonitor()
    @StateObject private var location = LocationMonitor()
    @State private var scaleRef = false
    @State private var shot: Shot?
    @State private var processing = false
    @State private var error: String?
    @State private var savedCount = 0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if camera.authorized == false {
                VStack(spacing: 12) {
                    Text("لا يوجد إذن للكاميرا").font(.headline).foregroundStyle(.white)
                    Text("اسمح بالكاميرا من الإعدادات، أو استخدم «استيراد من الصور».").foregroundStyle(.white.opacity(0.8))
                    Button("فتح الإعدادات") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }.padding()
            } else {
                CameraPreview(session: camera.session).ignoresSafeArea()
            }

            VStack {
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark").font(.title2.bold()).padding(10).background(.black.opacity(0.5)).clipShape(Circle())
                    }
                    Spacer()
                    Text("\(buildingId) · \(wallId) · \(segment)").font(.caption.bold()).padding(8)
                        .background(.black.opacity(0.5)).clipShape(Capsule())
                    if savedCount > 0 {
                        Text("حُفظت \(savedCount)").font(.caption.bold()).padding(8).background(.green.opacity(0.8)).clipShape(Capsule())
                    }
                }
                .foregroundStyle(.white)
                .padding()

                Spacer()

                if let error { Text(error).font(.caption).padding(8).background(.red.opacity(0.85)).foregroundStyle(.white).clipShape(Capsule()) }

                HStack(spacing: 8) {
                    chip(level.available ? "مستوي \(Int(level.tiltDeg.rounded()))°" : "مستوي ؟", ok: level.ok)
                    chip("ثابت", ok: level.steady)
                    chip(lightLabel, ok: camera.exposure == "ok")
                    Toggle(isOn: $scaleRef) { Text("مقياس").font(.caption.bold()) }
                        .toggleStyle(.button)
                        .tint(scaleRef ? Color.green : Color.orange)
                }
                .foregroundStyle(.white)

                Button(action: shoot) {
                    ZStack {
                        Circle().stroke(.white, lineWidth: 4).frame(width: 80, height: 80)
                        Circle().fill(level.ok ? Color.white : Color.orange).frame(width: 66, height: 66)
                        if processing || camera.busy { ProgressView().tint(.black) }
                    }
                }
                .disabled(processing || camera.busy || camera.authorized != true)
                .padding(.vertical, 24)
            }
        }
        .onAppear {
            camera.start()
            level.start()
            location.start()
        }
        .onDisappear {
            camera.stop()
            level.stop()
            location.stop()
        }
        .sheet(item: $shot) { s in
            CornersView(image: s.image, meta: s.meta) { savedCount += 1 }
        }
    }

    private var lightLabel: String {
        switch camera.exposure {
        case "dark": return "الإضاءة: معتمة"
        case "bright": return "الإضاءة: ساطعة"
        default: return "الإضاءة"
        }
    }

    private func chip(_ title: String, ok: Bool) -> some View {
        Text(title).font(.caption.bold()).padding(8)
            .background(ok ? Color.green.opacity(0.8) : Color.orange.opacity(0.85))
            .clipShape(Capsule())
    }

    private func shoot() {
        error = nil
        // the readings at the moment of the shutter, not after processing
        let tilt = (level.tiltDeg * 10).rounded() / 10
        let steady = level.steady
        let exposure = camera.exposure
        let scale = scaleRef
        let gps = location.fix
        let heading = location.headingDeg
        let motionAvailable = level.available
        let shutterAt = Date()
        camera.shoot { data in
            guard let data else { error = "تعذر التقاط الصورة"; return }
            processing = true
            Task {
                defer { processing = false }
                guard let prepared = await ImageChecks.prepare(data: data) else { error = "تعذر معالجة الصورة"; return }
                var meta = CaptureMeta(surveyId: surveyId, buildingId: buildingId, wallId: wallId, segment: segment,
                                       surveyor: user, device: DeviceInfo.description, source: "camera")
                meta.capturedAt = shutterAt
                meta.tiltDeg = motionAvailable ? tilt : nil
                meta.gps = gps
                meta.headingDeg = heading
                meta.quality = QualityRules.quality(tiltDeg: motionAvailable ? tilt : nil, steady: motionAvailable ? steady : nil,
                                                    exposure: exposure, blurScore: prepared.blurScore, scaleRef: scale)
                meta.facesHidden = prepared.facesHidden
                meta.imageWidthPx = prepared.widthPx
                meta.imageHeightPx = prepared.heightPx
                shot = Shot(image: prepared.image, meta: meta)
            }
        }
    }
}
