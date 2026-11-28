import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit
import Vision

/// What the phone does to every photo before it touches the disk: upright pixels, faces pixelated, blur score.
enum ImageChecks {
    /// The contract example is 4032 x 3024. Larger library photos (24 or 48 MP) are scaled down to this long edge,
    /// which keeps the JPEG well under Spring's 20 MB limit.
    static let maxLongEdge: CGFloat = 4032

    struct Prepared {
        let image: UIImage
        let facesHidden: Int
        let widthPx: Int
        let heightPx: Int
        let blurScore: Double
    }

    private static let context = CIContext(options: [.cacheIntermediates: false])

    /// Off the main thread: upright → hide faces → blur score. Only `image` (faces hidden) may be saved.
    static func prepare(image: UIImage) async -> Prepared? {
        await Task.detached(priority: .userInitiated) { () -> Prepared? in
            let up = upright(image)
            let (hidden, faces) = hideFaces(up)
            guard let cg = hidden.cgImage else { return nil }
            return Prepared(image: hidden, facesHidden: faces, widthPx: cg.width, heightPx: cg.height, blurScore: blurScore(hidden))
        }.value
    }

    static func prepare(data: Data) async -> Prepared? {
        guard let image = UIImage(data: data) else { return nil }
        return await prepare(image: image)
    }

    /// Camera and library photos usually come rotated by EXIF (orientation .right). Drawing them upright once means
    /// Vision's face boxes, the corner handles, image_width_px and corners_px all use the same pixels as the JPEG
    /// that is uploaded. (Applying Vision's oriented boxes to the raw sensor buffer would pixelate the wrong place.)
    static func upright(_ image: UIImage) -> UIImage {
        let pixelSize = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        let longEdge = max(pixelSize.width, pixelSize.height)
        let factor = longEdge > maxLongEdge ? maxLongEdge / longEdge : 1
        if image.imageOrientation == .up && image.scale == 1 && factor == 1 { return image }
        let size = CGSize(width: (pixelSize.width * factor).rounded(), height: (pixelSize.height * factor).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    /// Pixelates every face Vision finds (with a 20 % margin). Returns the new image and how many faces were hidden.
    /// Expects an upright image (see `upright`).
    static func hideFaces(_ image: UIImage) -> (UIImage, Int) {
        guard let cg = image.cgImage else { return (image, 0) }
        let request = VNDetectFaceRectanglesRequest()
        do {
            try VNImageRequestHandler(cgImage: cg, orientation: .up).perform([request])
        } catch {
            return (image, 0)
        }
        let faces = request.results ?? []
        guard !faces.isEmpty else { return (image, 0) }

        let source = CIImage(cgImage: cg)
        let w = source.extent.width, h = source.extent.height
        var out = source
        for face in faces {
            let r = face.boundingBox // normalised, origin bottom-left (the same as Core Image)
            let rect = CGRect(x: r.minX * w, y: r.minY * h, width: r.width * w, height: r.height * h)
                .insetBy(dx: -0.2 * r.width * w, dy: -0.2 * r.height * h)
                .intersection(source.extent)
            guard !rect.isNull, rect.width >= 1, rect.height >= 1 else { continue }
            let pixelated = source.clampedToExtent()
                .applyingFilter("CIPixellate", parameters: [
                    "inputScale": max(8, max(rect.width, rect.height) / 8),
                    kCIInputCenterKey: CIVector(x: rect.midX, y: rect.midY),
                ])
                .cropped(to: rect)
            out = pixelated.composited(over: out)
        }
        guard let result = context.createCGImage(out, from: source.extent) else { return (image, 0) }
        return (UIImage(cgImage: result, scale: 1, orientation: .up), faces.count)
    }

    /// Edge strength on a quarter-size copy. Higher = sharper. Calibrate the cut-off on the demo phone; the score is
    /// only recorded in quality.blur_score, it never blocks a shot.
    static func blurScore(_ image: UIImage) -> Double {
        guard let cg = image.cgImage else { return 0 }
        let small = CIImage(cgImage: cg).transformed(by: .init(scaleX: 0.25, y: 0.25))
        let edges = small.applyingFilter("CIEdges", parameters: [kCIInputIntensityKey: 4.0])
        let average = CIFilter.areaAverage()
        average.inputImage = edges
        average.extent = edges.extent
        guard let output = average.outputImage else { return 0 }
        var px = [UInt8](repeating: 0, count: 4)
        context.render(output, toBitmap: &px, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
                       format: .RGBA8, colorSpace: nil)
        let luminance = 0.299 * Double(px[0]) + 0.587 * Double(px[1]) + 0.114 * Double(px[2])
        return (luminance * 10 * 10).rounded() / 10
    }
}
