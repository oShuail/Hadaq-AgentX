import Foundation

/// The two settings the task names, kept in UserDefaults so @AppStorage and the uploader read the same values.
enum AppSettings {
    static let serverURLKey = "serverURL"
    static let userKey = "user"

    /// The laptop on the personal hotspot. Change it in Settings on the day (Windows: `ipconfig`, Mac: `ipconfig getifaddr en0`).
    static let defaultServerURL = "http://192.168.43.10:8080"
    static let defaultUser = "surveyor"

    static var serverURL: String { UserDefaults.standard.string(forKey: serverURLKey) ?? defaultServerURL }
    static var user: String { UserDefaults.standard.string(forKey: userKey) ?? defaultUser }

    static var api: HadaqAPI { HadaqAPI(serverURL: serverURL, user: user) }

    static let roles: [(id: String, nameAr: String)] = [
        ("surveyor", "المساح الميداني"),
        ("conservator", "مهندس الترميم"),
        ("site_manager", "مدير الموقع"),
    ]
}
