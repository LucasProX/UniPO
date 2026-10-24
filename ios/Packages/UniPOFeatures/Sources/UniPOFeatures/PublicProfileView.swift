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
    @State private var blockBusy = false
    @State private var followRequestID: UUID?
    @State private var messageRequestID: UUID?
    @State private var blockRequestID: UUID?
    @State private var blockConfirmation: BlockConfirmation?
    @State private var reporting = false
    @State private var followList: FollowListTarget?
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
                        if !value.isBlockedEitherWay {
                            ProfileGrowthSection(uid: value.profile.publicUid)
                                .padding(.horizontal, 20)
                        }
                        if value.isBlockedEitherWay {
                            Text("暂时无法查看此用户的内容").uniFont(14).foregroundStyle(UniColor.secondary)
                        } else if loading && posts.isEmpty {
                            UniLoadingState("正在加载作品…")
                        } else if posts.isEmpty && loadError == nil {
                            VStack(spacing: 10) {
                                Image(systemName: "square.grid.2x2").uniFont(26)
                                Text("还没有公开作品").uniFont(14)
                            }
                            .foregroundStyle(UniColor.secondary)
                            .padding(.vertical, 36)
                        } else {
                            HStack { Text("作品").uniFont(18, weight: .bold); Spacer() }
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
            .toolbar {
                if let value, !value.mine {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button("举报", systemImage: "exclamationmark.bubble") {
                                if auth.isAuthenticated { reporting = true } else { auth.requireLogin() }
                            }
                            if value.blocked {
                                Button("取消拉黑", systemImage: "person.crop.circle.badge.checkmark") {
                                    requestBlockChange(blocked: false)
                                }
                            } else if !value.blockedBy {
                                Button("拉黑", systemImage: "hand.raised", role: .destructive) {
                                    requestBlockChange(blocked: true)
                                }
                            }
                        } label: {
                            Image(systemName: "ellipsis")
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .accessibilityLabel("更多用户操作")
                        .disabled(blockBusy)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) { Button("关闭") { dismiss() } }
            }
            .sheet(item: $followList) { target in
                FollowListView(target: target).environment(auth)
            }
            .sheet(isPresented: $reporting) {
                if let value { ReportSheet(uid: value.profile.publicUid, name: value.profile.nickname) }
            }
            .task(id: auth.profile?.id) {
                if let previousViewer, previousViewer != auth.profile?.id {
                    selectedPost = nil
                    conversation = nil
                }
                previousViewer = auth.profile?.id
                followBusy = false
                messageBusy = false
                blockBusy = false
                followRequestID = nil
                messageRequestID = nil
                blockRequestID = nil
                blockConfirmation = nil
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
            .confirmationDialog(
                blockConfirmation?.title ?? "",
                isPresented: Binding(
                    get: { blockConfirmation != nil },
                    set: { if !$0 { blockConfirmation = nil } }
                ),
                titleVisibility: .visible
            ) {
                if let action = blockConfirmation {
                    Button(action.confirmationTitle, role: action.blocked ? .destructive : nil) {
                        blockConfirmation = nil
                        Task { await changeBlock(blocked: action.blocked) }
                    }
                    Button("取消", role: .cancel) { blockConfirmation = nil }
                }
            } message: {
                Text(blockConfirmation?.message ?? "")
            }
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
                    Text(user.nickname).uniFont(27, weight: .bold).foregroundStyle(UniColor.ink)
                    Text("UID \(user.publicUid)")
                        .uniFont(11, weight: .medium, design: .monospaced).foregroundStyle(UniColor.secondary)
                    if user.showGrade != false, let grade = clean(user.grade) {
                        Text(grade).uniFont(12, weight: .semibold).foregroundStyle(UniColor.secondary)
                    }
                    let schoolInfo = [clean(user.college), clean(user.major)].compactMap { $0 }.joined(separator: " · ")
                    if !schoolInfo.isEmpty { Text(schoolInfo).uniFont(11, weight: .semibold).foregroundStyle(UniColor.secondary) }
                }
                Spacer(minLength: 0)
            }
            if let bio = clean(user.bio) { Text(bio).uniFont(13).foregroundStyle(UniColor.inkSoft).lineSpacing(4) }
            HStack(spacing: 0) {
                Button { followList = FollowListTarget(uid: value.profile.publicUid, kind: .following) } label: {
                    statistic(value.stats.following, "关注").contentShape(Rectangle())
                }.buttonStyle(.plain)
                Button { followList = FollowListTarget(uid: value.profile.publicUid, kind: .followers) } label: {
                    statistic(value.stats.followers, "粉丝").contentShape(Rectangle())
                }.buttonStyle(.plain)
                statistic(value.stats.posts, "作品")
            }
            .padding(.vertical, 13)
            .background(Color.white.opacity(0.85), in: RoundedRectangle(cornerRadius: UniRadius.card))
            if !value.mine {
                HStack(spacing: 12) {
                    Button { Task { await toggleFollow() } } label: {
                        HStack(spacing: 7) {
                            if followBusy { ProgressView().tint(.white) }
                            else { Image(systemName: value.relationship.symbolName) }
                            Text(value.relationship.title)
                        }
                        .uniFont(14, weight: .semibold)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(value.relationship == .notFollowing ? UniColor.blue : UniColor.ink, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(followBusy || value.isBlockedEitherWay || user.statusIsUnavailable)
                    .accessibilityIdentifier("profile.follow")
                    Button { Task { await startMessage() } } label: {
                        HStack(spacing: 7) {
                            if messageBusy { ProgressView() } else { Image(systemName: "bubble.left.and.bubble.right") }
                            Text("私信")
                        }
                        .uniFont(14, weight: .semibold).foregroundStyle(UniColor.ink)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(Color.white.opacity(0.9), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(messageBusy || value.isBlockedEitherWay || user.statusIsUnavailable)
                }
            }
        }
        .padding(20)
        .background { ProfileBackdrop(url: user.backgroundUrl) }
    }

    private func statistic(_ count: Int, _ title: String) -> some View {
        VStack(spacing: 3) {
            Text("\(count)").uniFont(20, weight: .bold).monospacedDigit().foregroundStyle(UniColor.ink)
            Text(title).uniFont(10, weight: .semibold).foregroundStyle(UniColor.secondary)
        }.frame(maxWidth: .infinity)
    }

    private func clean(_ value: String?) -> String? {
        guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty, !text.hasPrefix("未设置") else { return nil }
        return text
    }

    private func errorNotice(_ message: String) -> some View {
        VStack(spacing: 12) {
            Text(message).uniFont(14).foregroundStyle(UniColor.secondary)
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
            if result.isBlockedEitherWay { posts = [] }
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
        guard let value, !value.mine, !followBusy, !value.isBlockedEitherWay else { return }
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

    private func requestBlockChange(blocked: Bool) {
        guard auth.isAuthenticated else { auth.requireLogin(); return }
        guard let value, !value.mine, value.canManageBlock, !blockBusy else { return }
        guard blocked ? (!value.blocked && !value.blockedBy) : value.blocked else { return }
        blockConfirmation = BlockConfirmation(blocked: blocked)
    }

    private func changeBlock(blocked: Bool) async {
        guard auth.isAuthenticated, let current = value, !current.mine, current.canManageBlock, !blockBusy else { return }
        guard blocked ? (!current.blocked && !current.blockedBy) : current.blocked else { return }
        let viewer = auth.profile?.id
        let requestID = UUID()
        blockRequestID = requestID
        blockBusy = true
        defer {
            if blockRequestID == requestID {
                blockBusy = false
                blockRequestID = nil
            }
        }
        do {
            let blockStatus = try await APIClient.shared.setBlocked(uid: uid, blocked: blocked)
            guard auth.profile?.id == viewer, blockRequestID == requestID else { return }
            generation += 1
            value = current.applying(blockStatus: blockStatus)
            loadError = nil
            if blockStatus.isBlockedEitherWay {
                loading = false
                posts = []
                selectedPost = nil
                conversation = nil
            } else {
                posts = []
                await load()
            }
            guard auth.profile?.id == viewer, blockRequestID == requestID else { return }
        } catch {
            guard auth.profile?.id == viewer, blockRequestID == requestID else { return }
            actionError = (error as? LocalizedError)?.errorDescription ?? "拉黑状态更新失败"
        }
    }
}

private struct BlockConfirmation {
    let blocked: Bool

    var title: String { blocked ? "拉黑此用户？" : "取消拉黑？" }
    var confirmationTitle: String { blocked ? "拉黑" : "取消拉黑" }
    var message: String {
        blocked ? "拉黑后，双方将无法查看彼此的内容或发送私信。" : "取消后，双方可重新查看彼此的内容并发送私信。"
    }
}

private extension UserProfile {
    var statusIsUnavailable: Bool { status.map { $0 != "active" } ?? false }
}
