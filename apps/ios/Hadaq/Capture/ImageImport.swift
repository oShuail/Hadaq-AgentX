import Foundation
import ImageIO
import UIKit

/// Metadata of a photo picked from the library (our site-visit photos): the original date and, when present, GPS.
enum ImageImport {
    struct Info {
        var capturedAt: Date?
        var gps: GeoFix?
    }

    static func metadata(of data: Data) -> Info {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any] else { return Info() }
        var info = Info()
        if let exif = props[kCGImagePropertyExifDictionary as String] as? [String: Any],
           let original = exif[kCGImagePropertyExifDateTimeOriginal as String] as? String {
            info.capturedAt = ExifDate.parse(original, offset: exif[kCGImagePropertyExifOffsetTimeOriginal as String] as? String)
        }
        if let gps = props[kCGImagePropertyGPSDictionary as String] as? [String: Any] {
            info.gps = ExifDate.gps(lat: (gps[kCGImagePropertyGPSLatitude as String] as? NSNumber)?.doubleValue,
                                    latRef: gps[kCGImagePropertyGPSLatitudeRef as String] as? String,
                                    lon: (gps[kCGImagePropertyGPSLongitude as String] as? NSNumber)?.doubleValue,
                                    lonRef: gps[kCGImagePropertyGPSLongitudeRef as String] as? String,
                                    accuracyM: (gps[kCGImagePropertyGPSHPositioningError as String] as? NSNumber)?.doubleValue)
        }
        return info
    }
}

enum DeviceInfo {
    /// "iPhone (iPhone16,1), iOS 18.2". The model code is more useful than "iPhone" for calibrating the blur score.
    static let description: String = {
        var system = utsname()
        uname(&system)
        let code = withUnsafeBytes(of: &system.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return "\(UIDevice.current.model) (\(code)), iOS \(UIDevice.current.systemVersion)"
    }()
}
