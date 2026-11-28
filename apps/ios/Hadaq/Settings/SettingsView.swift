import SwiftData
import SwiftUI

struct SettingsView: View {
    @AppStorage(AppSettings.serverURLKey) private var serverURL = AppSettings.defaultServerURL
    @AppStorage(AppSettings.userKey) private var user = AppSettings.defaultUser
    @Query private var items: [QueuedCapture]
    @State private var health: String?
    @State private var checking = false
    @State private var confirmReset = false

    var body: some View {
        Form {
            Section {
                TextField("Server URL", text: $serverURL)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .environment(\.layoutDirection, .leftToRight)
                Button(checking ? "جاري الفحص..." : "فحص الاتصال (/health)") { Task { await check() } }
                    .disabled(checking)
                if let health { Text(health).font(.caption).foregroundStyle(.secondary) }
            } header: {
                Text("الخادم")
            } footer: {
                Text("عنوان اللابتوب على نفس نقطة الاتصال الشخصية، مثل http://192.168.43.10:8080. افتح http://<ip>:8080/health في سفاري أولاً.")
            }

            Section("المستخدم") {
                Picker("المستخدم", selection: $user) {
                    ForEach(AppSettings.roles, id: \.id) { role in Text(role.nameAr).tag(role.id) }
                }
            }

            Section {
                Button("إعادة ضبط الصور (\(items.count))", role: .destructive) { confirmReset = true }
            } footer: {
                Text("يحذف كل الصور من قائمة الهاتف وملفاتها، للعرض التجريبي. لا يحذف شيئاً من الخادم.")
            }
        }
        .navigationTitle("الإعدادات")
        .onChange(of: serverURL) { _, _ in health = nil }
        .confirmationDialog("حذف كل الصور من الهاتف؟", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("حذف \(items.count) صورة", role: .destructive) { CaptureStore.shared.reset() }
        }
    }

    private func check() async {
        checking = true
        defer { checking = false }
        do {
            let (data, response) = try await URLSession.shared.data(for: try AppSettings.api.health())
            try HadaqAPI.check(data, response)
            health = "متصل: " + (String(data: data, encoding: .utf8) ?? "ok").prefix(120)
            await TaskStore.shared.refresh()
            await Uploader.shared.drain(force: true)
        } catch {
            health = "غير متصل: \(error.localizedDescription)"
        }
    }
}
