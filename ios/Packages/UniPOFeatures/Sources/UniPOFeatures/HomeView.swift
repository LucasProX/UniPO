import SwiftUI
import UniPOCore
import UniPOUI

struct HomeView: View {
    @Environment(AuthStore.self) private var auth
    let onRequestAuth: () -> Void
    let onOpenAchievements: () -> Void
    let onOpenMessages: () -> Void

    @State private var feed = FeedStore()
    @State private var growth: AchievementView?
    @State private var growthLoading = false
    @State private var growthError: String?
    @State private var growthGeneration = 0
    @State private var selectedPost: PostView?
    @State private var unreadCount = 0

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
        .task(id: "\(auth.isRestoring):\(auth.profile?.id ?? 0)") {
            guard !auth.isRestoring else { return }
            selectedPost = nil
            unreadCount = 0
            growth = nil
            feed.clear()
            await load(reset: true)
        }
        .task { await pollUnread() }
        .task {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-post-detail"), selectedPost == nil {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                selectedPost = posts.first
            }
            #endif
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
            Button(action: onOpenMessages) {
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(UniColor.ink)
                    .frame(width: 40, height: 40)
                    .background(UniColor.mist.opacity(0.75), in: Circle())
                    .overlay(alignment: .topTrailing) {
                        if unreadCount > 0 {
                            Text(unreadCount > 99 ? "99+" : "\(unreadCount)")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(UniColor.danger, in: Capsule())
                                .offset(x: 4, y: -4)
                        }
                    }
            }
            .buttonStyle(.plain)
            Button {
                if auth.isAuthenticated { onOpenAchievements() } else { onRequestAuth() }
            } label: {
                if let profile = auth.profile {
                    UniAvatar(url: profile.avatarUrl, name: profile.nickname, size: 40)
                } else {
                    Text("登录")
                        .font(.uniLabel(13))
                        .foregroundStyle(UniColor.ink)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 9)
                        .background(UniColor.mist.opacity(0.8), in: Capsule())
                }
            }
            .buttonStyle(.plain)
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
        Button {
            if !auth.isAuthenticated { onRequestAuth() }
            else if growthError != nil { Task { await loadGrowth() } }
            else { onOpenAchievements() }
        } label: {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(auth.isAuthenticated ? "成长主线进度" : "成长任务")
                            .font(.uniLabel(11))
                            .foregroundStyle(UniColor.blue)
                        Text(growthTitle)
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(UniColor.ink)
                    }
                    Spacer()
                    if let growth, growth.totalTasks > 0 {
                        ZStack {
                            Circle()
                                .stroke(UniColor.mist, lineWidth: 6)
                            Circle()
                                .trim(from: 0, to: growthProgress)
                                .stroke(UniColor.blue, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                            Text("\(Int(growthProgress * 100))%")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(UniColor.ink)
                        }
                        .frame(width: 54, height: 54)
                    } else if growthLoading {
                        ProgressView().tint(UniColor.blue)
                    }
                }

                if let growth {
                    Text("累计完成 \(growth.completedTasks) / \(growth.totalTasks) 项任务")
                        .font(.uniLabel(12))
                        .foregroundStyle(UniColor.secondary)
                    Text("通用成长主线，尚未按学校与专业定制")
                        .font(.uniLabel(10))
                        .foregroundStyle(UniColor.tertiary)
                } else {
                    Text(growthError ?? (auth.isAuthenticated ? "正在读取你的任务记录" : "登录后查看真实任务和完成记录"))
                        .font(.uniLabel(12))
                        .foregroundStyle(UniColor.secondary)
                }

                HStack(spacing: 6) {
                    Text(growthError != nil ? "点此重试" : (auth.isAuthenticated ? "查看完整成就" : "登录查看成长任务"))
                        .font(.uniLabel(12))
                    Image(systemName: "arrow.right")
                        .font(.system(size: 11, weight: .bold))
                }
                .foregroundStyle(UniColor.blue)
            }
            .padding(18)
            .background(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .fill(Color.white)
                    .shadow(color: UniColor.ink.opacity(0.07), radius: 22, y: 10)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .stroke(UniColor.blue.opacity(0.16), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("home.growth")
    }

    private var growthTitle: String {
        guard auth.isAuthenticated else { return "你的下一步，从这里开始" }
        if growthLoading { return "正在加载成长进度" }
        if growthError != nil { return "成长进度暂不可用" }
        guard let growth else { return "暂时没有成长任务" }
        if let stage = growth.stages.first(where: { $0.code == growth.currentStageCode }) { return stage.title }
        return growth.totalTasks > 0 ? "当前主线已完成" : "暂时没有成长任务"
    }

    private var growthProgress: CGFloat {
        guard let growth, growth.totalTasks > 0 else { return 0 }
        return min(1, max(0, CGFloat(growth.completedTasks) / CGFloat(growth.totalTasks)))
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
                PostCardView(post: post)
                    .onTapGesture { selectedPost = post }
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
            guard auth.profile?.id == userID, growthGeneration == requestGeneration else { return }
            growth = result
        } catch {
            guard auth.profile?.id == userID, growthGeneration == requestGeneration else { return }
            growthError = (error as? LocalizedError)?.errorDescription ?? "成长进度加载失败"
        }
        growthLoading = false
    }

    private func pollUnread() async {
        while !Task.isCancelled {
            let userID = auth.profile?.id
            if userID != nil, let count = try? await APIClient.shared.unreadCount(), auth.profile?.id == userID {
                unreadCount = count
            } else if !auth.isAuthenticated {
                unreadCount = 0
            }
            try? await Task.sleep(nanoseconds: 15_000_000_000)
        }
    }

    private static var todayText: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M 月 d 日 · EEEE"
        return formatter.string(from: Date())
    }
}
