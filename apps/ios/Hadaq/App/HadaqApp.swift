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
                .tint(HadaqTheme.gold)
                .preferredColorScheme(.dark)
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
        .toolbarBackground(HadaqTheme.panel, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
    }
}

/// The field app uses the same palette as the web site's Courtyard HUD.
enum HadaqTheme {
    static let background = Color(red: 27/255, green: 19/255, blue: 14/255)
    static let panel = Color(red: 36/255, green: 24/255, blue: 15/255)
    static let line = Color(red: 90/255, green: 60/255, blue: 40/255)
    static let gold = Color(red: 201/255, green: 162/255, blue: 77/255)
    static let goldText = Color(red: 224/255, green: 184/255, blue: 102/255)
    static let text = Color(red: 244/255, green: 232/255, blue: 214/255)
    static let muted = Color(red: 227/255, green: 210/255, blue: 184/255)
    static let rose = Color(red: 192/255, green: 122/255, blue: 122/255)
    static let sage = Color(red: 107/255, green: 158/255, blue: 120/255)
}

struct HadaqHeader: View {
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Text("حَدَق")
                .font(.system(size: 30, weight: .bold, design: .serif))
                .foregroundStyle(HadaqTheme.gold)
            Rectangle().fill(HadaqTheme.line).frame(width: 1, height: 38)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).foregroundStyle(HadaqTheme.text)
                Text(subtitle).font(.caption2).foregroundStyle(HadaqTheme.muted)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .background(HadaqTheme.panel)
        .overlay(alignment: .bottom) { HadaqTheme.line.frame(height: 1) }
    }
}

struct HadaqPanel: ViewModifier {
    func body(content: Content) -> some View {
        content
            .listRowBackground(HadaqTheme.panel)
            .listRowSeparatorTint(HadaqTheme.line)
    }
}

extension View {
    func hadaqPanel() -> some View { modifier(HadaqPanel()) }
    func hadaqList() -> some View {
        scrollContentBackground(.hidden)
            .background(HadaqTheme.background.ignoresSafeArea())
    }
}
