import Foundation

/// The registry (buildings and walls) and the surveyor's surveys, cached on disk for offline use.
///
/// Reading order: the last copy from the server (Documents/cache), else the copies bundled from
/// contracts/v2/examples/ (registry.json, survey.json) so the app works before Spring is up.
/// One building of the survey with the walls to photograph.
struct BuildingEntry: Identifiable {
    let building: Building
    var walls: [Wall]
    var id: String { building.id }
}

@MainActor
final class TaskStore: ObservableObject {
    static let shared = TaskStore()

    @Published private(set) var registry: Registry?
    @Published private(set) var surveys: [Survey] = []
    /// true when the surveys came from Spring (then their survey_id is sent with each capture).
    @Published private(set) var surveysFromServer = false
    @Published private(set) var lastRefresh: Date?
    @Published private(set) var lastError: String?

    private static var cacheFolder: URL {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private init() { loadFromDisk() }

    /// The survey whose tasks the list shows: the first open one for this user.
    var currentSurvey: Survey? { surveys.first }

    /// survey_id to put in a capture's meta, only when the survey is a real one on the server.
    func surveyId(buildingId: String, wallId: String) -> String? {
        guard surveysFromServer else { return nil }
        return surveys.first { $0.tasks.contains { $0.buildingId == buildingId && $0.wallId == wallId } }?.surveyId
    }

    func task(buildingId: String, wallId: String) -> SurveyTask? {
        currentSurvey?.tasks.first { $0.buildingId == buildingId && $0.wallId == wallId }
    }

    /// Buildings with the walls to photograph: the survey's tasks, or every wall in the registry when there is no survey.
    var buildingsToSurvey: [BuildingEntry] {
        guard let registry else { return [] }
        guard let survey = currentSurvey else { return registry.buildings.map { BuildingEntry(building: $0, walls: $0.walls) } }
        var result: [BuildingEntry] = []
        for task in survey.tasks {
            let building = registry.building(task.buildingId)
                ?? Building(id: task.buildingId, nameAr: task.buildingId, nameEn: task.buildingId, walls: [])
            let wall = registry.wall(task.buildingId, task.wallId)
                ?? Wall(id: task.wallId, buildingId: task.buildingId, name: task.wallId)
            if let i = result.firstIndex(where: { $0.building.id == building.id }) {
                result[i].walls.append(wall)
            } else {
                result.append(BuildingEntry(building: building, walls: [wall]))
            }
        }
        return result
    }

    /// GET /api/v2/registry and /api/v2/surveys?assignee=surveyor; each answer is saved as the offline copy.
    func refresh() async {
        let api = AppSettings.api
        var errors: [String] = []
        do {
            let data = try await fetch(try api.registry())
            registry = try HadaqJSON.decoder.decode(Registry.self, from: data)
            try? data.write(to: Self.cacheFolder.appendingPathComponent("registry.json"), options: .atomic)
        } catch {
            errors.append("registry: \(error.localizedDescription)")
        }
        do {
            let data = try await fetch(try api.surveys(assignee: "surveyor"))
            surveys = try HadaqJSON.decoder.decode([Survey].self, from: data)
            surveysFromServer = true
            try? data.write(to: Self.cacheFolder.appendingPathComponent("surveys.json"), options: .atomic)
        } catch {
            errors.append("surveys: \(error.localizedDescription)")
        }
        lastError = errors.isEmpty ? nil : errors.joined(separator: "\n")
        if errors.isEmpty { lastRefresh = .now }
    }

    private func fetch(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        try HadaqAPI.check(data, response)
        return data
    }

    private func loadFromDisk() {
        let cachedRegistry = try? Data(contentsOf: Self.cacheFolder.appendingPathComponent("registry.json"))
        registry = (cachedRegistry ?? bundled("registry")).flatMap { try? HadaqJSON.decoder.decode(Registry.self, from: $0) }

        if let cached = try? Data(contentsOf: Self.cacheFolder.appendingPathComponent("surveys.json")),
           let list = try? HadaqJSON.decoder.decode([Survey].self, from: cached) {
            surveys = list
            surveysFromServer = true
        } else if let data = bundled("survey"), let one = try? HadaqJSON.decoder.decode(Survey.self, from: data) {
            // contracts/v2/examples/survey.json: one survey with an example id that Spring does not know
            surveys = [one]
            surveysFromServer = false
        }
    }

    private func bundled(_ name: String) -> Data? {
        Bundle.main.url(forResource: name, withExtension: "json").flatMap { try? Data(contentsOf: $0) }
    }
}
