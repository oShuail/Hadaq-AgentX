import SwiftData
import SwiftUI

@main
struct HadaqApp: App {
    let container: ModelContainer

    init() {
        do {
            container = try ModelContainer(for: QueuedCapture.self)
        } catch {
            fatalError("Could not open the capture queue: \(error)")
        }
        CaptureStore.shared.context = container.mainContext
        CaptureStore.shared.recoverInterruptedUploads()
        Uploader.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                // Arabic-first: right-to-left everywhere except the photo canvas (CornersView forces LTR there).
                .environment(\.layoutDirection, .rightToLeft)
                .environment(\.locale, Locale(identifier: "ar@numbers=latn"))
                .task { await TaskStore.shared.refresh() }
        }
        .modelContainer(container)
    }
}

struct RootView: View {
    @ObservedObject private var uploader = Uploader.shared

    var body: some View {
        TabView {
            NavigationStack { TaskListView() }
                .tabItem { Label("المهام", systemImage: "checklist") }
            NavigationStack { QueueView() }
                .tabItem { Label("الصور", systemImage: "photo.stack") }
                .badge(uploader.pendingCount)
            NavigationStack { SettingsView() }
                .tabItem { Label("الإعدادات", systemImage: "gear") }
        }
    }
}
