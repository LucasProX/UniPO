import SwiftUI
import UniPOCore

struct UserProfileLink<Label: View>: View {
    let uid: String
    let name: String?
    private let label: Label
    @State private var showProfile = false

    init(uid: String, name: String? = nil, @ViewBuilder label: () -> Label) {
        self.uid = uid
        self.name = name
        self.label = label()
    }

    var body: some View {
        Button { showProfile = true } label: { label }
            .buttonStyle(.plain)
            .disabled(uid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .accessibilityLabel(name.map { "查看\($0)的主页" } ?? "查看用户主页")
            .sheet(isPresented: $showProfile) {
                UserProfileDestination(uid: uid)
            }
    }
}

struct UserProfileDestination: View {
    let uid: String
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if auth.profile?.publicUid == uid {
                NavigationStack {
                    ProfileView()
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) { Button("关闭") { dismiss() } }
                        }
                }
            } else {
                PublicProfileView(uid: uid)
            }
        }
        .environment(auth)
        .presentationDetents([.large])
        .presentationCornerRadius(28)
    }
}

extension Notification.Name {
    static let unipoRelationshipsChanged = Notification.Name("unipo.relationships.changed")
}
