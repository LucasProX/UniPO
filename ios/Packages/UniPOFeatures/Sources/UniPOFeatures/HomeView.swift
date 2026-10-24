import SwiftUI
import UniPOCore
import UniPOUI

struct HomeView: View {
    @Environment(AuthStore.self) private var auth

    @State private var feed = FeedStore()
    @State private var selectedPost: PostView?
    @State private var previousViewerID: Int64?
    @State private var searching = false

    private var board: String { feed.board }
    private var posts: [PostView] { feed.posts }
    private var isLoading: Bool { feed.isLoading }
    private var errorMessage: String? { feed.errorMessage }

    private let boards = [
        ("recommend", "推荐"),
        (FeedStore.followingBoard, "关注"),
        ("school", "校 PO"),
        ("college", "院 PO"),
        ("major", "专业 PO")
    ]

    var body: some View {
        ScrollView {
            // 首页只放帖子流（2026-10-23）：品牌头、头像、学期卡和本周活动都移走，
            // 成长与活动入口统一在「成长」页。
            LazyVStack(spacing: 16) {
                boardPicker
                content
            }
            .padding(.horizontal, 18)
            .padding(.top, 4)
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
            feed.clear()
            guard !auth.isRestoring else { return }
            // 「关注」只对登录用户有意义；退出登录后回到推荐。
            if board == FeedStore.followingBoard && !auth.isAuthenticated {
                await feed.refresh(board: "recommend")
            } else {
                await load(reset: true)
            }
        }
        .task {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-post-detail"), selectedPost == nil {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                selectedPost = posts.first
            }
            #endif
        }
        .onReceive(NotificationCenter.default.publisher(for: .unipoPostPublished)) { _ in
            Task { await feed.refresh() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .unipoPostDeleted)) { note in
            if let id = note.object as? Int64 { feed.remove(postID: id) }
        }
        .sheet(isPresented: $searching) {
            SearchView().environment(auth)
        }
        .sheet(item: $selectedPost) { post in
            PostDetailView(post: post)
                .environment(auth)
                .presentationDetents([.large])
                .presentationCornerRadius(30)
        }
    }

    private var boardPicker: some View {
        HStack(spacing: 8) {
            ScrollView(.horizontal) {
                boardChips
            }
            .scrollIndicators(.hidden)
            Button { searching = true } label: {
                Image(systemName: "magnifyingglass")
                    .uniFont(15, weight: .semibold)
                    .foregroundStyle(UniColor.ink)
                    .frame(width: 44, height: 44)
                    .background(Color.white, in: Circle())
                    .overlay(Circle().stroke(UniColor.mist, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("搜索")
        }
    }

    private var boardChips: some View {
        HStack(spacing: 8) {
            ForEach(boards, id: \.0) { item in
                Button {
                    guard board != item.0 else { return }
                    if item.0 == FeedStore.followingBoard && !auth.isAuthenticated {
                        auth.requireLogin()
                        return
                    }
                    Task { await feed.refresh(board: item.0) }
                } label: {
                    Text(item.1)
                        .uniFont(13, weight: .semibold)
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
        }
    }

    @ViewBuilder
    private var content: some View {
        if isLoading && posts.isEmpty {
            UniLoadingState("正在加载校园动态…")
        } else if let errorMessage, posts.isEmpty {
            UniErrorState(errorMessage) { Task { await load(reset: true) } }
        } else if posts.isEmpty && board == FeedStore.followingBoard {
            VStack(spacing: 12) {
                UniEmptyState(icon: "person.2", title: "关注的人还没有发帖", message: "去搜搜同学，关注后他们的新帖会出现在这里")
                Button("搜索同学") { searching = true }
                    .uniFont(14, weight: .semibold)
                    .foregroundStyle(UniColor.blue)
            }
        } else if posts.isEmpty {
            UniEmptyState(icon: "text.bubble", title: "这个板块还没有内容", message: "同校同学发的经验贴会出现在这里")
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
                    Text(errorMessage).uniFont(12).foregroundStyle(UniColor.secondary)
                    Button("重新加载") {
                        Task {
                            if feed.hasMore { await feed.loadMore() } else { await feed.refresh() }
                        }
                    }
                    .uniFont(13, weight: .semibold)
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
        if reset { await feed.refresh() } else { await feed.loadMore() }
    }
}
