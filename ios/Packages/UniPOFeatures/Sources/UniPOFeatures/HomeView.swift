import SwiftUI
import UniPOCore
import UniPOUI

struct HomeView: View {
    @Environment(AuthStore.self) private var auth
    let onRequestAuth: () -> Void
    let onOpenAchievements: () -> Void

    @State private var feed = FeedStore()
    @State private var growth: AchievementView?
    @State private var growthLoading = false
    @State private var growthError: String?
    @State private var growthGeneration = 0
    @State private var editingBackground = false
    @State private var completingTask = false
    @State private var selectedPost: PostView?
    @State private var previousViewerID: Int64?

    private var board: String { feed.board }
    private var posts: [PostView] { feed.posts }
    private var isLoading: Bool { feed.isLoading }
    private var errorMessage: String? { feed.errorMessage }

    private let boards = [
        ("recommend", "推荐"),
        ("school", "校 PO"),
        ("college", "院 PO"),
        ("major", "专业 PO")
    ]

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 16) {
                header
                boardPicker
                stageCard
                content
            }
            .padding(.horizontal, 18)
            .padding(.top, 6)
            .padding(.bottom, 120)
        }
        .scrollIndicators(.hidden)
        .refreshable { await load(reset: true) }
        .task(id: "\(auth.authenticationRevision):\(auth.isRestoring):\(auth.profile?.id ?? 0)") {
            // Public post details and their comment draft survive guest/login and
            // same-owner recovery. A different verified account starts afresh.
            if let userID = auth.profile?.id {
                if let previousViewerID, previousViewerID != userID { selectedPost = nil }
                previousViewerID = userID
            }
            growth = nil
            growthError = nil
            growthLoading = false
            growthGeneration += 1
            feed.clear()
            guard !auth.isRestoring else { return }
            await load(reset: true)
        }
        .task {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-post-detail"), selectedPost == nil {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                selectedPost = posts.first
            }
            #endif
        }
        .onChange(of: auth.profile?.entryYear) { _, _ in
            Task { await loadGrowth() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .unipoPostPublished)) { _ in
            Task { await feed.refresh() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .unipoPostDeleted)) { note in
            if let id = note.object as? Int64 { feed.remove(postID: id) }
        }
        .sheet(item: $selectedPost) { post in
            PostDetailView(post: post)
                .environment(auth)
                .presentationDetents([.large])
                .presentationCornerRadius(30)
        }
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(UniColor.ink)
                    .frame(width: 44, height: 44)
                Text("PO")
                    .font(.system(size: 15, weight: .heavy))
                    .foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("UniPO")
                    .font(.system(size: 22, weight: .heavy))
                    .foregroundStyle(UniColor.ink)
                Text(Self.todayText)
                    .font(.uniLabel(11))
                    .foregroundStyle(UniColor.secondary)
            }
            Spacer()
            if let profile = auth.profile {
                UserProfileLink(uid: profile.publicUid, name: profile.nickname) {
                    UniAvatar(url: profile.avatarUrl, name: profile.nickname, size: 40)
                }
                .frame(minWidth: 44, minHeight: 44)
            } else {
                Button(action: onRequestAuth) {
                    Text("登录")
                        .font(.uniLabel(13))
                        .foregroundStyle(UniColor.ink)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 9)
                        .background(UniColor.mist.opacity(0.8), in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var boardPicker: some View {
        HStack(spacing: 8) {
            ForEach(boards, id: \.0) { item in
                Button {
                    guard board != item.0 else { return }
                    Task { await feed.refresh(board: item.0) }
                } label: {
                    Text(item.1)
                        .font(.uniLabel(13))
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .foregroundStyle(board == item.0 ? .white : UniColor.secondary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(
                            Capsule().fill(board == item.0 ? UniColor.ink : Color.white)
                        )
                        .overlay(
                            Capsule().stroke(UniColor.mist.opacity(board == item.0 ? 0 : 1), lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
    }

    private var stageCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Button {
                if !auth.isAuthenticated { onRequestAuth() }
                else if growthError != nil { Task { await loadGrowth() } }
                else { onOpenAchievements() }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(stageEyebrow)
                            .font(.uniLabel(11))
                            .foregroundStyle(UniColor.blue)
                        Text(growthTitle)
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(UniColor.ink)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer()
                    if let stage = growth?.currentStage, stage.totalCount > 0 {
                        ZStack {
                            Circle().stroke(UniColor.mist, lineWidth: 5)
                            Circle()
                                .trim(from: 0, to: CGFloat(stage.completedCount) / CGFloat(stage.totalCount))
                                .stroke(UniColor.blue, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                            Text("\(stage.completedCount)/\(stage.totalCount)")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(UniColor.ink)
                        }
                        .frame(width: 50, height: 50)
                        .accessibilityLabel("本阶段完成 \(stage.completedCount) 项，共 \(stage.totalCount) 项")
                    } else if growthLoading {
                        ProgressView().tint(UniColor.blue)
                    } else {
                        Image(systemName: "arrow.right")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(UniColor.blue)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if let growth {
                if let next = growth.nextTask {
                    nextTaskRow(next)
                } else {
                    Text(growth.totalTasks > 0 ? "这个阶段的任务都完成了，去「成长」看看下一阶段" : "暂时没有适用的成长任务")
                        .font(.uniBody(13))
                        .foregroundStyle(UniColor.secondary)
                }
                if growth.needsBackground {
                    Button { editingBackground = true } label: {
                        Label("补充入学年份，按你所在的学期推荐任务", systemImage: "graduationcap")
                            .font(.uniLabel(12))
                            .foregroundStyle(UniColor.blue)
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                }
            } else {
                Text(growthError ?? (auth.isAuthenticated ? "正在读取你的任务记录" : "登录后按你的入学年份推荐这个阶段该做的事"))
                    .font(.uniBody(13))
                    .foregroundStyle(UniColor.secondary)
            }
        }
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                .fill(Color.white)
                .shadow(color: UniColor.ink.opacity(0.06), radius: 18, y: 8)
        )
        .overlay(
            RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                .stroke(UniColor.blue.opacity(0.16), lineWidth: 1)
        )
        .accessibilityIdentifier("home.growth")
        .sheet(isPresented: $editingBackground) {
            GrowthBackgroundSheet(profile: auth.profile) { Task { await loadGrowth() } }
                .environment(auth)
        }
    }

    private func nextTaskRow(_ task: AchievementTaskView) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("下一步")
                    .font(.uniLabel(10))
                    .foregroundStyle(UniColor.tertiary)
                Text(task.title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(UniColor.ink)
                if let detail = task.description, !detail.isEmpty {
                    Text(detail)
                        .font(.uniBody(12))
                        .foregroundStyle(UniColor.secondary)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            Button {
                Task { await completeNext(task) }
            } label: {
                Group {
                    if completingTask { ProgressView().tint(.white) }
                    else { Text("完成").font(.uniLabel(13)) }
                }
                .foregroundStyle(.white)
                .frame(minWidth: 64, minHeight: 44)
                .background(UniColor.ink, in: Capsule())
            }
            .buttonStyle(.plain)
            .disabled(completingTask)
            .accessibilityLabel("完成任务：\(task.title)")
        }
        .padding(14)
        .background(UniColor.paper, in: RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
    }

    private var stageEyebrow: String {
        guard auth.isAuthenticated, let growth else { return "成长主线" }
        if let term = growth.termLabel { return "当前 · \(term)" }
        return "成长主线 · 按完成进度"
    }

    private var growthTitle: String {
        guard auth.isAuthenticated else { return "你的下一步，从这里开始" }
        if growthLoading && growth == nil { return "正在加载成长进度" }
        if growthError != nil { return "成长进度暂不可用，点此重试" }
        guard let growth else { return "暂时没有成长任务" }
        if let stage = growth.currentStage { return stage.title + (stage.subtitle.map { " · \($0)" } ?? "") }
        return growth.totalTasks > 0 ? "当前主线已完成" : "暂时没有成长任务"
    }


    @ViewBuilder
    private var content: some View {
        if isLoading && posts.isEmpty {
            UniLoadingState("正在加载校园动态…")
        } else if let errorMessage, posts.isEmpty {
            VStack(spacing: 12) {
                Text(errorMessage)
                    .font(.uniBody(14))
                    .foregroundStyle(UniColor.secondary)
                Button("重试") { Task { await load(reset: true) } }
                    .font(.uniLabel(13))
                    .foregroundStyle(UniColor.blue)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 40)
        } else if posts.isEmpty {
            Text("这个板块还没有内容")
                .font(.uniBody(14))
                .foregroundStyle(UniColor.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 40)
        } else {
            ForEach(posts) { post in
                PostCardView(post: post, onOpen: { selectedPost = post })
                    .onAppear {
                        if post.id == posts.last?.id, feed.hasMore {
                            Task { await feed.loadMore() }
                        }
                    }
            }
            if let errorMessage {
                VStack(spacing: 8) {
                    Text(errorMessage).font(.uniBody(12)).foregroundStyle(UniColor.secondary)
                    Button("重新加载") {
                        Task {
                            if feed.hasMore { await feed.loadMore() } else { await feed.refresh() }
                        }
                    }
                    .font(.uniLabel(13))
                    .foregroundStyle(UniColor.blue)
                }
                .padding(.vertical, 12)
            }
            if isLoading || feed.isLoadingMore {
                ProgressView().tint(UniColor.ink).padding(.vertical, 16)
            }
        }
    }

    private func load(reset: Bool) async {
        if reset {
            await feed.refresh()
            await loadGrowth()
        } else { await feed.loadMore() }
    }

    private func loadGrowth() async {
        growthGeneration += 1
        let requestGeneration = growthGeneration
        let userID = auth.profile?.id
        let revision = auth.authenticationRevision
        guard userID != nil else {
            growth = nil
            growthError = nil
            growthLoading = false
            return
        }
        growthLoading = true
        growthError = nil
        do {
            let result = try await APIClient.shared.achievements()
            guard auth.profile?.id == userID, auth.authenticationRevision == revision,
                  growthGeneration == requestGeneration else { return }
            growth = result
        } catch {
            guard auth.profile?.id == userID, auth.authenticationRevision == revision,
                  growthGeneration == requestGeneration else { return }
            growthError = (error as? LocalizedError)?.errorDescription ?? "成长进度加载失败"
        }
        growthLoading = false
    }

    private func completeNext(_ task: AchievementTaskView) async {
        let userID = auth.profile?.id
        completingTask = true
        defer { completingTask = false }
        do {
            let result = try await APIClient.shared.completeTask(id: task.id)
            guard auth.profile?.id == userID else { return }
            growth = result
            growthError = nil
        } catch {
            growthError = (error as? LocalizedError)?.errorDescription ?? "任务记录失败，请重试"
        }
    }

    private static var todayText: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M 月 d 日 · EEEE"
        return formatter.string(from: Date())
    }
}
