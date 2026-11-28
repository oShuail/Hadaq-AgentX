// JSON coding that matches the Spring core API: snake_case keys, ISO 8601 times.
// Spring writes times with microseconds (2026-11-28T10:15:00.123456Z). Swift's built-in .iso8601 strategy rejects
// fractional seconds, so decoding uses HadaqJSON.parseDate, which accepts both forms (and epoch numbers).

import Foundation

enum HadaqJSON {
    /// Times are written in Riyadh time with the offset, like the contract examples (2026-11-29T09:41:12+03:00).
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.keyEncodingStrategy = .convertToSnakeCase
        e.dateEncodingStrategy = .custom { date, encoder in
            var c = encoder.singleValueContainer()
            try c.encode(formatDate(date))
        }
        e.outputFormatting = [.sortedKeys]
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        d.dateDecodingStrategy = .custom { decoder in
            let c = try decoder.singleValueContainer()
            if let seconds = try? c.decode(Double.self) {
                return Date(timeIntervalSince1970: seconds)
            }
            let text = try c.decode(String.self)
            guard let date = parseDate(text) else {
                throw DecodingError.dataCorruptedError(in: c, debugDescription: "Not an ISO 8601 time: \(text)")
            }
            return date
        }
        return d
    }()

    static let riyadh = TimeZone(identifier: "Asia/Riyadh") ?? TimeZone(secondsFromGMT: 3 * 3600)!

    static func formatDate(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = riyadh
        return f.string(from: date)
    }

    /// ISO 8601 with or without fractional seconds (any number of digits), with Z or an offset.
    /// A time without an offset is read as UTC, the same rule as Spring's Times.parse.
    static func parseDate(_ raw: String) -> Date? {
        var text = raw.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "T")
        var fraction = 0.0
        if let t = text.firstIndex(of: "T"), let dot = text[t...].firstIndex(of: ".") {
            var end = text.index(after: dot)
            while end < text.endIndex, text[end].isNumber { end = text.index(after: end) }
            fraction = Double("0" + text[dot..<end]) ?? 0
            text.removeSubrange(dot..<end)
        }
        if let t = text.firstIndex(of: "T") {
            let timePart = text[text.index(after: t)...]
            let hasOffset = timePart.contains("Z") || timePart.contains("+") || timePart.contains("-")
            if !hasOffset { text += "Z" }
        }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: text).map { $0.addingTimeInterval(fraction) }
    }
}
