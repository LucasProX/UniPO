import SwiftUI
import UniPOCore
import UniPOUI

/// F09 / U07：标签页。头部是标签名、同校帖子数和最常关联的主线任务，下面是同标签的帖子，最新在前。
/// 从帖子详情、搜索 `#标签` 进入，推入调用方的 NavigationStack。
struct TagPostsView: View {
    @Environment(AuthStore.self) private var auth
    let tag: String

    @State private var summary: TagSummary?
    @State private var posts: [PostView] = []
    @State private var cursor: String?
    @State private var hasMore = false
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var selectedPost: PostView?
    @State private var openedTask: TaskRef?
    @State private var composing = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                header
                content
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 24)
        }
        .background(UniColor.paper.ignoresSafeArea())
        .navigationTitle("标签")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load(reset: true) }
        .task { if posts.isEmpty { await load(reset: true) } }
        .sheet(item: $selectedPost) { post in
            PostDetailView(post: post).environment(auth)
        }
        .sheet(item: $openedTask) { ref in
            TaskDetailView(taskID: ref.id).environment(auth)
        }
        .sheet(isPresented: $composing) {
            ComposeSheet(initialTags: [tag]).environment(auth)
        }
        .onReceive(NotificationCenter.default.publisher(for: .unipoPostPublished)) { _ in
            Task { await load(reset: true) }
        }
        .onReceive(NotificationCenter.default.publisher(for: .unipoPostDeleted)) { note in
            if let id = note.object as? Int64 { posts.removeAll { $0.id == id } }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("#\(tag)")
                    .uniFont(26, weight: .heavy)
                    .foregroundStyle(UniColor.ink)
                    .lineLimit(2)
                Spacer(minLength: 8)
                Button {
                    if auth.isAuthenticated { composing = true } else { auth.requireLogin() }
                } label: {
                    Label("发一条", systemImage: "plus")
                        .uniFont(13, weight: .semibold)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 36)
                        .background(UniColor.ink, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityHint("带上这个标签发帖")
            }
            Text(summary.map { "\($0.postCount) 条 PO" } ?? " ")
                .uniFont(13, weight: .semibold)
                .foregroundStyle(UniColor.secondary)
            if let tasks = summary?.tasks, !tasks.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("相关主线任务")
                        .uniFont(12, weight: .semibold)
                        .foregroundStyle(UniColor.tertiary)
                    ForEach(tasks, id: \.id) { task in
                        Button { openedTask = TaskRef(id: task.id) } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "map").uniFont(12, weight: .semibold)
                                Text(task.title).uniFont(14, weight: .semibold).lineLimit(1)
                                Spacer(minLength: 4)
                                Image(systemName: "chevron.right").uniFont(11, weight: .semibold)
                            }
                            .foregroundStyle(UniColor.blue)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(.top, 8)
    }

    @ViewBuilder
    private var content: some View {
        if isLoading && posts.isEmpty {
            UniLoadingState("正在加载…").frame(maxWidth: .infinity).padding(.top, 40)
        } else if let errorMessage, posts.isEmpty {
            UniErrorState(errorMessage) { Task { await load(reset: true) } }
        } else if posts.isEmpty {
            UniEmptyState(icon: "number", title: "还没有带这个标签的 PO", message: "发一条，做第一个分享的人")
        } else {
            ForEach(posts) { post in
                PostCardView(post: post, onOpen: { selectedPost = post })
                    .onAppear {
                        if post.id == posts.last?.id, hasMore { Task { await load(reset: false) } }
                    }
            }
            if isLoading {
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 12)
            } else if let errorMessage {
                Button("加载失败，点此重试") { Task { await load(reset: false) } }
                    .uniFont(13, weight: .semibold)
                    .foregroundStyle(UniColor.blue)
                    .frame(maxWidth: .infinity)
                    .accessibilityHint(errorMessage)
            }
        }
    }

    private func load(reset: Bool) async {
        guard !isLoading, reset || hasMore else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            if reset {
                async let page = APIClient.shared.tagPosts(name: tag)
                async let head = APIClient.shared.tagSummary(name: tag)
                let (first, info) = try await (page, head)
                posts = first.items
                cursor = first.nextCursor
                hasMore = first.hasMore && first.nextCursor != nil
                summary = info
            } else if let cursor {
                let page = try await APIClient.shared.tagPosts(name: tag, cursor: cursor)
                var seen = Set(posts.map(\.id))
                posts += page.items.filter { seen.insert($0.id).inserted }
                self.cursor = page.nextCursor
                hasMore = page.hasMore && page.nextCursor != nil
            }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "加载失败"
        }
    }
}
