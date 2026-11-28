import AVFoundation
import SwiftUI
import UIKit

/// The back wide camera with AVFoundation, plus a light check for the live chip.
final class Camera: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "hadaq.camera")
    private var device: AVCaptureDevice?
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var exposureTimer: Timer?
    private var configured = false
    private var completion: ((Data?) -> Void)?

    /// "ok" | "dark" | "bright" (the contract's quality.exposure)
    @Published var exposure = "ok"
    /// nil until the camera permission is known
    @Published var authorized: Bool?
    @Published var busy = false

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            authorized = true
            configureAndRun()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    self.authorized = granted
                    if granted { self.configureAndRun() }
                }
            }
        default:
            authorized = false
        }
    }

    func stop() {
        exposureTimer?.invalidate()
        exposureTimer = nil
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    private func configureAndRun() {
        queue.async {
            if !self.configured {
                self.session.beginConfiguration()
                self.session.sessionPreset = .photo
                if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                   let input = try? AVCaptureDeviceInput(device: device),
                   self.session.canAddInput(input), self.session.canAddOutput(self.output) {
                    self.session.addInput(input)
                    self.session.addOutput(self.output)
                    self.device = device
                    // horizon-level rotation, so a wall shot in landscape is saved in landscape
                    self.rotation = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil)
                }
                self.session.commitConfiguration()
                self.configured = true
            }
            if !self.session.isRunning { self.session.startRunning() }
            DispatchQueue.main.async { self.startExposureCheck() }
        }
    }

    private func startExposureCheck() {
        exposureTimer?.invalidate()
        exposureTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self, let device = self.device else { return }
            let offset = device.exposureTargetOffset
            let bright = device.iso <= device.activeFormat.minISO * 1.2 && device.exposureDuration.seconds < 1.0 / 2000
            self.exposure = offset < -1 ? "dark" : (offset > 1 || bright ? "bright" : "ok")
        }
    }

    /// Takes one photo and returns the encoded file data (HEIC or JPEG, with its EXIF orientation).
    func shoot(_ done: @escaping (Data?) -> Void) {
        guard !busy else { return }
        busy = true
        let angle = rotation?.videoRotationAngleForHorizonLevelCapture
        queue.async {
            guard self.session.isRunning else {
                DispatchQueue.main.async { self.busy = false; done(nil) }
                return
            }
            if let angle, let connection = self.output.connection(with: .video), connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
            self.completion = done
            self.output.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let data = error == nil ? photo.fileDataRepresentation() : nil
        let done = completion
        completion = nil
        DispatchQueue.main.async {
            self.busy = false
            done?(data)
        }
    }
}

/// Live preview. The UI is portrait only, so the preview stays at 90°.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        if let connection = view.previewLayer.connection, connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        if let connection = view.previewLayer.connection, connection.videoRotationAngle != 90,
           connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
    }
}
