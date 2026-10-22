import SwiftUI
import UniPOCore
import UniPOUI

enum FollowListKind: String, Identifiable {
    case following, followers
    var id: String { rawValue }
    var title: String { self == .following ? "关注" : "粉丝" }
}

struct FollowListTarget: Identifiable, Hashable {
    let uid: String
    let kind: FollowListKind
    var id: String { "\(uid)-\(kind.rawValue)" }
}

/// 关注 / 粉丝列表。关系状态以服务端为准：列表里的「已关注」来自 following 字段，
/// 操作后重新读取，而不是本地推断朋友关系。
struct FollowListView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    let target: FollowListTarget

    @State private var users: [FollowUserView] = []
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var busyUID: String?

    var body: some View {
        NavigationStack {
            List {
                if isLoading && users.isEmpty {
                    UniLoadingState("加载中…").listRowSeparator(.hidden)
                } else if let errorMessage, users.isEmpty {
                    VStack(spacing: 10) {
                        Text(errorMessage).font(.uniBody(13)).foregroundStyle(UniColor.secondary)
                        Button("重试") { Task { await load() } }.foregroundStyle(UniColor.blue)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 40).listRowSeparator(.hidden)
                } else if users.isEmpty {
                    Text(target.kind == .following ? "还没有关注任何人" : "还没有粉丝")
                        .font(.uniBody(13)).foregroundStyle(UniColor.secondary)
                        .frame(maxWidth: .infinity).padding(.vertical, 40).listRowSeparator(.hidden)
                } else {
                    ForEach(users) { user in row(user) }
                }
            }
            .listStyle(.plain)
            .refreshable { await load() }
            .navigationTitle(target.kind.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("关闭") { dismiss() } }
            }
            .task { await load() }
        }
    }

    private func row(_ user: FollowUserView) -> some View {
        HStack(spacing: 12) {
            UserProfileLink(uid: user.publicUid, name: user.nickname) {
                HStack(spacing: 12) {
                    UniAvatar(url: user.avatarUrl, name: user.nickname, size: 44)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(user.nickname)
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(UniColor.ink)
                            .lineLimit(1)
                        let detail = [user.grade, user.major ?? user.college].compactMap { $0 }
                            .filter { !$0.isEmpty }.joined(separator: " · ")
                        Text(detail.isEmpty ? "Lv.\(user.level ?? 1) \(user.levelTitle ?? "")" : detail)
                            .font(.uniLabel(11))
                            .foregroundStyle(UniColor.secondary)
                            .lineLimit(1)
                    }
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            Spacer(minLength: 8)
            if user.publicUid != auth.profile?.publicUid {
                Button {
                    Task { await toggle(user) }
                } label: {
                    Group {
                        if busyUID == user.publicUid { ProgressView() }
                        else { Text(user.following ? "已关注" : (target.kind == .followers ? "回关" : "关注")) }
                    }
                    .font(.uniLabel(12))
                    .foregroundStyle(user.following ? UniColor.ink : .white)
                    .frame(minWidth: 68, minHeight: 34)
                    .background(user.following ? UniColor.mist : UniColor.blue, in: Capsule())
                    .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
                .disabled(busyUID != nil)
            }
        }
        .padding(.vertical, 2)
    }

    private func load() async {
        let viewer = auth.profile?.id
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let result = target.kind == .following
                ? try await APIClient.shared.following(uid: target.uid)
                : try await APIClient.shared.followers(uid: target.uid)
            guard auth.profile?.id == viewer else { return }
            users = result
        } catch {
            guard auth.profile?.id == viewer else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "列表加载失败，请重试"
        }
    }

    private func toggle(_ user: FollowUserView) async {
        guard auth.isAuthenticated else { auth.requireLogin(); return }
        busyUID = user.publicUid
        defer { busyUID = nil }
        do {
            try await APIClient.shared.setFollowing(uid: user.publicUid, following: !user.following)
            NotificationCenter.default.post(name: .unipoRelationshipsChanged, object: nil)
            await load()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "操作失败，请重试"
        }
    }
}
