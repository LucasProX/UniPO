import SwiftUI
import UniPOCore
import UniPOUI

struct PublicProfileView: View {
    let uid: String
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var value: PublicUserProfile?
    @State private var posts: [PostView] = []
    @State private var loading = true
    @State private var loadError: String?
    @State private var actionError: String?
    @State private var followBusy = false
    @State private var messageBusy = false
    @State private var followRequestID: UUID?
    @State private var messageRequestID: UUID?
    @State private var selectedPost: PostView?
    @State private var conversation: ConversationView?
    @State private var generation = 0
    @State private var previousViewer: Int64?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 18) {
                    if let value {
                        header(value)
                        if let loadError { errorNotice(loadError) }
                        if value.blocked || value.blockedBy {
                            Text("暂时无法查看此用户的内容").font(.uniBody(14)).foregroundStyle(UniColor.secondary)
                        } else if loading && posts.isEmpty {
                            UniLoadingState("正在加载作品…")
                        } else if posts.isEmpty && loadError == nil {
                            VStack(spacing: 10) {
                                Image(systemName: "square.grid.2x2").font(.system(size: 26))
                                Text("还没有公开作品").font(.uniBody(14))
                            }
                            .foregroundStyle(UniColor.secondary)
                            .padding(.vertical, 36)
                        } else {
                            HStack { Text("作品").font(.system(size: 18, weight: .bold)); Spacer() }
                                .padding(.horizontal, 20)
                            ForEach(posts) { post in
                                PostCardView(post: post, onOpen: { selectedPost = post })
                                    .padding(.horizontal, 18)
                            }
                        }
                    } else if loading {
                        UniLoadingState("正在打开主页…").padding(.top, 90)
                    } else {
                        errorNotice(loadError ?? "暂时无法打开此主页").padding(.top, 90)
                    }
                }
                .padding(.bottom, 28)
            }
            .background(UniColor.paper.ignoresSafeArea())
            .refreshable { await load() }
            .navigationTitle("个人主页")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("关闭") { dismiss() } } }
            .task(id: auth.profile?.id) {
                if let previousViewer, previousViewer != auth.profile?.id {
                    selectedPost = nil
                    conversation = nil
                }
                previousViewer = auth.profile?.id
                followBusy = false
                messageBusy = false
                followRequestID = nil
                messageRequestID = nil
                actionError = nil
                value = nil
                posts = []
                await load()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await load() } }
            }
            .onReceive(NotificationCenter.default.publisher(for: .unipoRelationshipsChanged)) { _ in
                if !followBusy { Task { await load() } }
            }
            .sheet(item: $selectedPost) { PostDetailView(post: $0).environment(auth) }
            .sheet(item: $conversation) { MessagesView(initialConversation: $0).environment(auth) }
            .fullScreenCover(isPresented: Binding(
                get: { auth.loginRequested }, set: { auth.loginRequested = $0 }
            )) { LoginView().environment(auth) }
            .alert("操作未完成", isPresented: Binding(
                get: { actionError != nil }, set: { if !$0 { actionError = nil } }
            )) { Button("知道了", role: .cancel) {} } message: { Text(actionError ?? "") }
        }
    }

    private func header(_ value: PublicUserProfile) -> some View {
        let user = value.profile
        return VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .center, spacing: 18) {
                UniAvatar(url: user.avatarUrl, name: user.nickname, size: 90, shape: .circle)
                    .overlay(Circle().stroke(.white, lineWidth: 3))
                    .shadow(color: UniColor.blue.opacity(0.16), radius: 14, y: 6)
                VStack(alignment: .leading, spacing: 6) {
                    Text(user.nickname).font(.system(size: 27, weight: .bold)).foregroundStyle(UniColor.ink)
                    Text("UID \(user.publicUid)")
                        .font(.system(size: 11, weight: .medium, design: .monospaced)).foregroundStyle(UniColor.secondary)
                    if user.showGrade != false, let grade = clean(user.grade) {
                        Text(grade).font(.uniLabel(12)).foregroundStyle(UniColor.secondary)
                    }
                    let schoolInfo = [clean(user.college), clean(user.major)].compactMap { $0 }.joined(separator: " · ")
                    if !schoolInfo.isEmpty { Text(schoolInfo).font(.uniLabel(11)).foregroundStyle(UniColor.secondary) }
                }
                Spacer(minLength: 0)
            }
            if let bio = clean(user.bio) { Text(bio).font(.uniBody(13)).foregroundStyle(UniColor.inkSoft).lineSpacing(4) }
            HStack(spacing: 0) {
                statistic(value.stats.following, "关注")
                statistic(value.stats.followers, "粉丝")
                statistic(value.stats.posts, "作品")
            }
            .padding(.vertical, 13)
            .background(Color.white.opacity(0.85), in: RoundedRectangle(cornerRadius: 20))
            if !value.mine {
                HStack(spacing: 12) {
                    Button { Task { await toggleFollow() } } label: {
                        HStack(spacing: 7) {
                            if followBusy { ProgressView().tint(.white) }
                            else { Image(systemName: value.relationship.symbolName) }
                            Text(value.relationship.title)
                        }
                        .font(.uniLabel(14))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(value.relationship == .notFollowing ? UniColor.blue : UniColor.ink, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(followBusy || value.blocked || value.blockedBy || user.statusIsUnavailable)
                    .accessibilityIdentifier("profile.follow")
                    Button { Task { await startMessage() } } label: {
                        HStack(spacing: 7) {
                            if messageBusy { ProgressView() } else { Image(systemName: "bubble.left.and.bubble.right") }
                            Text("私信")
                        }
                        .font(.uniLabel(14)).foregroundStyle(UniColor.ink)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(Color.white.opacity(0.9), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(messageBusy || value.blocked || value.blockedBy || user.statusIsUnavailable)
                }
            }
        }
        .padding(20)
        .background { ProfileBackdrop(url: user.backgroundUrl) }
    }

    private func statistic(_ count: Int, _ title: String) -> some View {
        VStack(spacing: 3) {
            Text("\(count)").font(.system(size: 20, weight: .bold)).monospacedDigit().foregroundStyle(UniColor.ink)
            Text(title).font(.uniLabel(10)).foregroundStyle(UniColor.secondary)
        }.frame(maxWidth: .infinity)
    }

    private func clean(_ value: String?) -> String? {
        guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty, !text.hasPrefix("未设置") else { return nil }
        return text
    }

    private func errorNotice(_ message: String) -> some View {
        VStack(spacing: 12) {
            Text(message).font(.uniBody(14)).foregroundStyle(UniColor.secondary)
            Button("重新加载") { Task { await load() } }.foregroundStyle(UniColor.blue)
        }.frame(maxWidth: .infinity).padding(20)
    }

    private func load() async {
        generation += 1
        let currentGeneration = generation
        let viewer = auth.profile?.id
        loading = true
        loadError = nil
        do {
            let result = try await APIClient.shared.publicProfile(uid: uid)
            guard generation == currentGeneration, auth.profile?.id == viewer else { return }
            value = result
            if result.blocked || result.blockedBy { posts = [] }
            else {
                let loaded = try await APIClient.shared.publicPosts(uid: uid)
                guard generation == currentGeneration, auth.profile?.id == viewer else { return }
                posts = loaded
            }
        } catch {
            guard generation == currentGeneration, auth.profile?.id == viewer else { return }
            loadError = (error as? LocalizedError)?.errorDescription ?? "主页加载失败"
        }
        if generation == currentGeneration { loading = false }
    }

    private func toggleFollow() async {
        guard auth.isAuthenticated else { auth.requireLogin(); return }
        guard let value, !value.mine, !followBusy, !value.blocked, !value.blockedBy else { return }
        let viewer = auth.profile?.id
        let requestID = UUID()
        followRequestID = requestID
        followBusy = true
        defer {
            if followRequestID == requestID {
                followBusy = false
                followRequestID = nil
            }
        }
        do {
            _ = try await APIClient.shared.setFollowing(uid: uid, following: !value.following)
            guard auth.profile?.id == viewer, followRequestID == requestID else { return }
            await load()
            guard auth.profile?.id == viewer, followRequestID == requestID else { return }
            NotificationCenter.default.post(name: .unipoRelationshipsChanged, object: nil)
        } catch {
            guard auth.profile?.id == viewer, followRequestID == requestID else { return }
            actionError = (error as? LocalizedError)?.errorDescription ?? "关注状态更新失败"
        }
    }

    private func startMessage() async {
        guard auth.isAuthenticated else { auth.requireLogin(); return }
        guard !messageBusy else { return }
        let viewer = auth.profile?.id
        let requestID = UUID()
        messageRequestID = requestID
        messageBusy = true
        defer {
            if messageRequestID == requestID {
                messageBusy = false
                messageRequestID = nil
            }
        }
        do {
            let result = try await APIClient.shared.createConversation(peerUid: uid)
            guard auth.profile?.id == viewer, messageRequestID == requestID else { return }
            conversation = result
        } catch {
            guard auth.profile?.id == viewer, messageRequestID == requestID else { return }
            actionError = (error as? LocalizedError)?.errorDescription ?? "暂时无法发起私信"
        }
    }
}

private extension UserProfile {
    var statusIsUnavailable: Bool { status.map { $0 != "active" } ?? false }
}
