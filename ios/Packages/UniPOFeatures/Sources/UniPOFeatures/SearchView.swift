import SwiftUI
import UniPOCore
import UniPOUI

/// 搜索：帖子（标题、正文、标签，访客可用）和同学（昵称或 UID，需要登录）。
/// 点「搜索」或回车才发请求，不边打边搜：全文检索比列表重，服务端也有限流。
struct SearchView: View {
    enum Scope: String, CaseIterable, Identifiable {
        case posts, users
        var id: String { rawValue }
        var title: String { self == .posts ? "帖子" : "同学" }
    }

    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    @FocusState private var fieldFocused: Bool

    @State private var text = ""
    @State private var scope: Scope = .posts
    /// 已提交的搜索词；结果都对应它，而不是输入框里正在改的内容。
    @State private var submitted = ""
    @State private var posts: [PostView] = []
    @State private var cursor: String?
    @State private var hasMore = false
    @State private var users: [FollowUserView] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var generation = 0
    @State private var openedPost: PostView?
    @State private var busyUID: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                searchField
                Picker("范围", selection: $scope) {
                    ForEach(Scope.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 18)
                results
            }
            .padding(.top, 8)
            .background(UniColor.paper.ignoresSafeArea())
            .navigationTitle("搜索")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("关闭") { dismiss() } }
            }
            .onAppear { fieldFocused = true }
            .onChange(of: scope) { _, _ in
                if !submitted.isEmpty { Task { await search(submitted) } }
            }
            .sheet(item: $openedPost) { post in
                PostDetailView(post: post).environment(auth)
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(UniColor.tertiary)
            TextField(scope == .posts ? "搜帖子，# 开头搜标签" : "搜昵称或 UID", text: $text)
                .uniFont(15)
                .focused($fieldFocused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { Task { await search(text) } }
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(UniColor.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清空")
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 44)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous)
            .stroke(UniColor.mist, lineWidth: 1))
        .padding(.horizontal, 18)
    }

    @ViewBuilder
    private var results: some View {
        if scope == .users && !auth.isAuthenticated {
            VStack(spacing: 12) {
                UniEmptyState(icon: "person.crop.circle.badge.questionmark", title: "登录后可以搜索同学")
                Button("去登录") {
                    dismiss()
                    // 等搜索页收起再弹登录，避免两个全屏层同时出现。
                    Task {
                        try? await Task.sleep(nanoseconds: 450_000_000)
                        auth.requireLogin()
                    }
                }
                .buttonStyle(UniPrimaryButtonStyle())
                .padding(.horizontal, 60)
            }
            .frame(maxHeight: .infinity, alignment: .top)
        } else if submitted.isEmpty {
            UniEmptyState(icon: "magnifyingglass", title: scope == .posts ? "搜同校的帖子" : "找同学",
                          message: scope == .posts ? "至少输入两个字，标题、正文和标签都会搜" : "输入昵称，或对方主页上的 UID")
                .frame(maxHeight: .infinity, alignment: .top)
        } else if isLoading && posts.isEmpty && users.isEmpty {
            UniLoadingState("正在搜索…").frame(maxHeight: .infinity, alignment: .top)
        } else if let errorMessage, posts.isEmpty && users.isEmpty {
            UniErrorState(errorMessage) { Task { await search(submitted) } }
                .frame(maxHeight: .infinity, alignment: .top)
        } else if scope == .posts {
            VStack(spacing: 8) {
                if let tag = submittedTag { tagShortcut(tag) }
                postResults
            }
        } else {
            userResults
        }
    }

    /// 以 # 开头时服务端按标签精确查（F09），这里再给一个进标签页的入口。
    private var submittedTag: String? {
        guard submitted.hasPrefix("#") || submitted.hasPrefix("＃") else { return nil }
        let tag = submitted.drop(while: { $0 == "#" || $0 == "＃" }).trimmingCharacters(in: .whitespaces)
        return tag.isEmpty ? nil : tag
    }

    private func tagShortcut(_ tag: String) -> some View {
        NavigationLink {
            TagPostsView(tag: tag).environment(auth)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "number").uniFont(15, weight: .bold)
                Text("进入 #\(tag) 标签页").uniFont(15, weight: .semibold)
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").uniFont(12, weight: .semibold)
            }
            .foregroundStyle(UniColor.blue)
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 18)
    }

    @ViewBuilder
    private var postResults: some View {
        if posts.isEmpty {
            UniEmptyState(icon: "text.magnifyingglass", title: "没有找到相关帖子", message: "换个说法试试")
                .frame(maxHeight: .infinity, alignment: .top)
        } else {
            List {
                ForEach(posts) { post in
                    Button { openedPost = post } label: { CompactPostRow(post: post) }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.clear)
                        .onAppear {
                            if post.id == posts.last?.id, hasMore { Task { await loadMore() } }
                        }
                }
                if let errorMessage {
                    Button("加载失败，点此重试") { Task { await loadMore() } }
                        .uniFont(13, weight: .semibold)
                        .foregroundStyle(UniColor.blue)
                        .listRowBackground(Color.clear)
                        .accessibilityHint(errorMessage)
                } else if isLoading {
                    ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollDismissesKeyboard(.immediately)
        }
    }

    @ViewBuilder
    private var userResults: some View {
        if users.isEmpty {
            UniEmptyState(icon: "person.2.slash", title: "没有找到这位同学", message: "昵称至少两个字，或者输入完整 UID")
                .frame(maxHeight: .infinity, alignment: .top)
        } else {
            List {
                ForEach(users) { user in
                    FollowUserRow(user: user, isBusy: busyUID == user.publicUid, isDisabled: busyUID != nil) {
                        Task { await toggle(user) }
                    }
                    .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollDismissesKeyboard(.immediately)
        }
    }

    private func search(_ raw: String) async {
        let query = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        fieldFocused = false
        submitted = query
        generation += 1
        let requestGeneration = generation
        let requestScope = scope
        posts = []
        users = []
        cursor = nil
        hasMore = false
        errorMessage = nil
        if requestScope == .users && !auth.isAuthenticated { return }
        isLoading = true
        defer { if generation == requestGeneration { isLoading = false } }
        do {
            if requestScope == .posts {
                let page = try await APIClient.shared.searchPosts(query: query)
                guard generation == requestGeneration else { return }
                posts = page.items
                cursor = page.nextCursor
                hasMore = page.hasMore && page.nextCursor != nil
            } else {
                let result = try await APIClient.shared.searchUsers(query: query)
                guard generation == requestGeneration else { return }
                users = result
            }
        } catch {
            guard generation == requestGeneration else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "搜索失败，请重试"
        }
    }

    private func loadMore() async {
        guard !isLoading, hasMore, let cursor else { return }
        let requestGeneration = generation
        isLoading = true
        errorMessage = nil
        defer { if generation == requestGeneration { isLoading = false } }
        do {
            let page = try await APIClient.shared.searchPosts(query: submitted, cursor: cursor)
            guard generation == requestGeneration else { return }
            var seen = Set(posts.map(\.id))
            posts += page.items.filter { seen.insert($0.id).inserted }
            self.cursor = page.nextCursor
            hasMore = page.hasMore && page.nextCursor != nil
        } catch {
            guard generation == requestGeneration else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "加载失败"
        }
    }

    private func toggle(_ user: FollowUserView) async {
        busyUID = user.publicUid
        defer { busyUID = nil }
        do {
            try await APIClient.shared.setFollowing(uid: user.publicUid, following: !user.following)
            NotificationCenter.default.post(name: .unipoRelationshipsChanged, object: nil)
            let refreshed = try await APIClient.shared.searchUsers(query: submitted)
            if scope == .users { users = refreshed }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "操作失败，请重试"
        }
    }
}
