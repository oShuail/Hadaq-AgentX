// Requests to the Spring core API (contracts/README.md "v2", base http://<laptop-ip>:8080/api/v2).
// Building the requests is kept apart from sending them, so the exact bytes can be unit-tested.

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// multipart/form-data body, the same shape as Spring's own test (V2ApiTest.upload):
/// part `file` (image/jpeg, with a filename) and part `meta` (application/json, no filename).
struct MultipartBody {
    let boundary: String
    private(set) var data = Data()

    init(boundary: String = "hadaq-\(UUID().uuidString)") {
        self.boundary = boundary
    }

    var contentType: String { "multipart/form-data; boundary=\(boundary)" }

    mutating func add(name: String, filename: String? = nil, type: String, content: Data) {
        var head = "--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\""
        if let filename { head += "; filename=\"\(filename)\"" }
        head += "\r\nContent-Type: \(type)\r\n\r\n"
        data.append(Data(head.utf8))
        data.append(content)
        data.append(Data("\r\n".utf8))
    }

    func finished() -> Data {
        var out = data
        out.append(Data("--\(boundary)--\r\n".utf8))
        return out
    }
}

enum HadaqAPIError: LocalizedError, Equatable {
    case badURL(String)
    /// The server answered with an error. 4xx (other than 408 and 429) means the request itself is wrong,
    /// so retrying the same bytes will not help.
    case http(status: Int, message: String?)

    var isPermanent: Bool {
        if case let .http(status, _) = self { return (400..<500).contains(status) && status != 408 && status != 429 }
        return false
    }

    var errorDescription: String? {
        switch self {
        case let .badURL(url): return "عنوان الخادم غير صالح: \(url)"
        case let .http(status, message): return "HTTP \(status)" + (message.map { ": \($0)" } ?? "")
        }
    }
}

struct HadaqAPI {
    /// e.g. http://192.168.43.10:8080 (the laptop on the hotspot), without /api/v2
    var serverURL: String
    /// X-Hadaq-User: surveyor | conservator | site_manager
    var user: String

    static let userHeader = "X-Hadaq-User"

    func url(_ path: String) throws -> URL {
        var base = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        guard let url = URL(string: base + path), let scheme = url.scheme, ["http", "https"].contains(scheme), url.host != nil else {
            throw HadaqAPIError.badURL(base + path)
        }
        return url
    }

    func get(_ path: String, timeout: TimeInterval = 15) throws -> URLRequest {
        var req = URLRequest(url: try url(path))
        req.httpMethod = "GET"
        req.timeoutInterval = timeout
        req.setValue(user, forHTTPHeaderField: Self.userHeader)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        return req
    }

    func post(_ path: String, timeout: TimeInterval = 15) throws -> URLRequest {
        var req = try get(path, timeout: timeout)
        req.httpMethod = "POST"
        return req
    }

    /// POST /api/v2/captures. Returns the request and the body to send with URLSession.upload(for:from:).
    func uploadCapture(jpeg: Data, filename: String, metaJSON: Data, boundary: String = "hadaq-\(UUID().uuidString)") throws -> (URLRequest, Data) {
        var req = try post("/api/v2/captures", timeout: 60)
        var body = MultipartBody(boundary: boundary)
        body.add(name: "file", filename: filename, type: "image/jpeg", content: jpeg)
        body.add(name: "meta", type: "application/json", content: metaJSON)
        req.setValue(body.contentType, forHTTPHeaderField: "Content-Type")
        return (req, body.finished())
    }

    func captureStatus(_ captureId: String) throws -> URLRequest { try get("/api/v2/captures/\(captureId)") }
    /// Sends a capture Spring marked `failed` to the AI worker again (409 for any other status).
    func retryCapture(_ captureId: String) throws -> URLRequest { try post("/api/v2/captures/\(captureId)/retry") }
    func registry() throws -> URLRequest { try get("/api/v2/registry") }
    func surveys(assignee: String = "surveyor") throws -> URLRequest { try get("/api/v2/surveys?assignee=\(assignee)") }
    func health() throws -> URLRequest { try get("/health", timeout: 5) }

    /// Throws HadaqAPIError.http for a non-2xx answer, with Spring's {"message"} when there is one.
    static func check(_ data: Data, _ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw HadaqAPIError.http(status: 0, message: nil) }
        guard (200..<300).contains(http.statusCode) else {
            let message = (try? HadaqJSON.decoder.decode(ServerMessage.self, from: data))?.message
            throw HadaqAPIError.http(status: http.statusCode, message: message)
        }
    }
}
