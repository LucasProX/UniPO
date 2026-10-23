import SwiftUI
import UniPOCore
import UniPOUI

struct PostDetailView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let post: PostView

    @State private var comments: [CommentView] = []
    @State private var commentText = ""
    @State private var liked: Bool
    @State private var favorited: Bool
    @State private var likeCount: Int
    @State private var favoriteCount: Int
    @State private var commentCount: Int
    @State private var isLoading = true
    @State private var isSending = false
    @State private var errorMessage: String?
    @State private var startedConversation: ConversationView?
    @State private var conversationOwnerID: Int64?
    @State private var commentError: String?
    @State private var commentRequestID: UUID?
    @State private var commentIntentKey = UUID().uuidString
    @State private var commentsLoadID: UUID?
    @State private var isLiking = false
    @State private var isFavoriting = false
    @State private var replyTarget: CommentView?
    @State private var commentLikes: [Int64: (liked: Bool, count: Int)] = [:]
    @State private var likingComment: Int64?
    @State private var shareItem: ShareItem?
    @State private var reporting = false
    @State private var confirmingDelete = false
    @State private var isDeleting = false
    @FocusState private var commentFocused: Bool

    private let commentLimit = 1_000
    private var identityKey: String {
        "\(auth.authenticationRevision):\(auth.profile?.id ?? 0):\(auth.isSessionRecoveryPending)"
    }

    init(post: PostView) {
        self.post = post
        _liked = State(initialValue: post.liked ?? false)
        _favorited = State(initialValue: post.favorited ?? false)
        _likeCount = State(initialValue: post.likeTotal)
        _favoriteCount = State(initialValue: post.favoriteTotal)
        _commentCount = State(initialValue: post.commentTotal)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    bodyContent
                    actions
                    commentsSection
                }
                .padding(.horizontal, 18)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom) {
                commentComposer
            }
            .navigationTitle("帖子")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("分享", systemImage: "square.and.arrow.up") {
                            shareItem = ShareItem(text: shareText)
                        }
                        if isMine {
                            Button("删除这条 PO", systemImage: "trash", role: .destructive) { confirmingDelete = true }
                        } else {
                            Button("举报作者", systemImage: "exclamationmark.bubble") {
                                if auth.isAuthenticated { reporting = true } else { auth.requireLogin() }
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("更多操作")
                    .disabled(isDeleting)
                }
            }
            .sheet(item: $shareItem) { item in
                ShareSheet(items: [item.text]) { Task { await recordShare() } }
                    .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $reporting) {
                ReportSheet(uid: post.author.uid, name: post.author.nickname)
            }
            .confirmationDialog("删除后无法恢复，评论与点赞也会一起隐藏。", isPresented: $confirmingDelete,
                                titleVisibility: .visible) {
                Button("删除", role: .destructive) { Task { await deletePost() } }
                Button("取消", role: .cancel) {}
            }
            .task(id: identityKey) { await loadComments() }
            .onChange(of: identityKey) { _, _ in
                if let conversationOwnerID, auth.profile?.id != conversationOwnerID, !auth.isSessionRecoveryPending {
                    startedConversation = nil
                    self.conversationOwnerID = nil
                }
                commentRequestID = nil
                replyTarget = nil
                commentLikes = [:]
                likingComment = nil
                isSending = false
                isLiking = false
                isFavoriting = false
                errorMessage = nil
                commentError = nil
            }
            .task {
                #if DEBUG
                if ProcessInfo.processInfo.arguments.contains("-like-test") {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    toggleLike()
                }
                #endif
            }
            .sheet(item: $startedConversation) { conversation in
                MessagesView(initialConversation: conversation)
                    .environment(auth)
                    .presentationDetents([.large])
                    .presentationCornerRadius(30)
            }
            .fullScreenCover(isPresented: Binding(
                get: { auth.loginRequested },
                set: { auth.loginRequested = $0 }
            )) {
                LoginView()
                    .environment(auth)
            }
            .alert("提示", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("知道了") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            UserProfileLink(uid: post.author.uid, name: post.author.nickname) {
                HStack(spacing: 10) {
                    UniAvatar(url: post.author.avatarUrl, name: post.author.nickname, size: 42)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(post.author.nickname)
                            .uniFont(15, weight: .bold)
                            .foregroundStyle(UniColor.ink)
                        Text("Lv.\(post.author.level) \(post.author.levelTitle ?? "")")
                            .uniFont(11, weight: .semibold)
                            .foregroundStyle(UniColor.secondary)
                    }
                }
                .frame(minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
            }
            Spacer()
            Button {
                Task { await startChat() }
            } label: {
                Image(systemName: "bubble.left")
                    .uniFont(14, weight: .semibold)
                    .foregroundStyle(UniColor.ink)
                    .frame(width: 34, height: 34)
                    .background(UniColor.paper, in: Circle())
            }
            .buttonStyle(.plain)
            UniChip(boardLabel(post.board))
        }
    }

    private var bodyContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(post.title)
                .uniFont(24, weight: .heavy)
                .foregroundStyle(UniColor.ink)

            Text(post.content)
                .uniFont(15)
                .foregroundStyle(UniColor.inkSoft)
                .lineSpacing(5)

            if let images = post.images, !images.isEmpty {
                ForEach(images, id: \.self) { raw in
                    if let url = AppConfig.absoluteURL(raw) {
                        AsyncImage(url: url) { phase in
                            if case .success(let image) = phase {
                                image.resizable().scaledToFit()
                            } else {
                                RoundedRectangle(cornerRadius: UniRadius.button).fill(UniColor.mist)
                                    .frame(height: 200)
                            }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: UniRadius.tile, style: .continuous))
                    }
                }
            }

            if !post.tagList.isEmpty {
                HStack(spacing: 6) {
                    ForEach(post.tagList, id: \.self) { UniChip($0) }
                }
            }

            Text(RelativeTime.text(from: post.publishedAt ?? post.createdAt))
                .uniFont(11, weight: .semibold)
                .foregroundStyle(UniColor.tertiary)
        }
    }

    private var actions: some View {
        HStack(spacing: 10) {
            actionButton(
                title: "\(likeCount)",
                icon: liked ? "heart.fill" : "heart",
                tint: liked ? UniColor.danger : UniColor.secondary
            ) {
                toggleLike()
            }
            actionButton(
                title: "\(favoriteCount)",
                icon: favorited ? "bookmark.fill" : "bookmark",
                tint: favorited ? UniColor.gold : UniColor.secondary
            ) {
                toggleFavorite()
            }
            actionButton(title: "\(commentCount)", icon: "bubble.right", tint: UniColor.secondary) {
                commentFocused = true
            }
            Spacer()
        }
    }

    private func actionButton(title: String, icon: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .uniFont(13, weight: .semibold)
                Text(title).uniFont(12, weight: .semibold)
            }
            .foregroundStyle(tint)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(UniColor.paper, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private var commentsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("评论 \(comments.count)")
                .uniFont(17, weight: .bold)
                .foregroundStyle(UniColor.ink)

            if isLoading {
                UniLoadingState("加载评论…")
            } else if comments.isEmpty {
                Text("还没有评论，来说两句")
                    .uniFont(13)
                    .foregroundStyle(UniColor.secondary)
                    .padding(.vertical, 20)
            } else {
                ForEach(threads, id: \.root.id) { thread in
                    commentRow(thread.root, isReply: false)
                    ForEach(thread.replies) { reply in
                        commentRow(reply, isReply: true)
                            .padding(.leading, 44)
                    }
                }
            }
        }
        .padding(.top, 4)
    }

    /// 后端返回扁平列表；按 parentId 归到最上层评论下，楼中楼只缩进一级。
    private var threads: [(root: CommentView, replies: [CommentView])] {
        let byID = Dictionary(comments.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        func rootID(_ comment: CommentView) -> Int64 {
            var current = comment
            var guardCount = 0
            while let parent = current.parentId, let next = byID[parent], guardCount < 20 {
                current = next
                guardCount += 1
            }
            return current.id
        }
        var replies: [Int64: [CommentView]] = [:]
        var roots: [CommentView] = []
        for comment in comments {
            let root = rootID(comment)
            if root == comment.id { roots.append(comment) } else { replies[root, default: []].append(comment) }
        }
        return roots.map { ($0, replies[$0.id] ?? []) }
    }

    private func commentRow(_ comment: CommentView, isReply: Bool) -> some View {
        let likeState = commentLikes[comment.id] ?? (comment.liked ?? false, comment.likeCount ?? 0)
        let parentName = comment.parentId.flatMap { id in comments.first { $0.id == id } }
            .flatMap { parent in parent.parentId == nil ? nil : parent.author.nickname }
        return HStack(alignment: .top, spacing: 10) {
            UserProfileLink(uid: comment.author.uid, name: comment.author.nickname) {
                UniAvatar(url: comment.author.avatarUrl, name: comment.author.nickname, size: isReply ? 28 : 34)
                    .frame(width: 44, height: 44, alignment: .topLeading)
                    .contentShape(Rectangle())
            }
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    UserProfileLink(uid: comment.author.uid, name: comment.author.nickname) {
                        Text(comment.author.nickname)
                            .uniFont(12, weight: .semibold)
                            .foregroundStyle(UniColor.ink)
                    }
                    if comment.author.uid == post.author.uid {
                        Text("作者").uniFont(9, weight: .semibold).foregroundStyle(UniColor.blue)
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(UniColor.blue.opacity(0.1), in: Capsule())
                    }
                    Text(RelativeTime.text(from: comment.createdAt))
                        .uniFont(10, weight: .semibold)
                        .foregroundStyle(UniColor.tertiary)
                }
                (parentName.map { Text("回复 @\($0)：").foregroundColor(UniColor.secondary) } ?? Text(""))
                    + Text(comment.content)
                Button("回复") {
                    guard auth.isAuthenticated else { auth.requireLogin(); return }
                    replyTarget = comment
                    commentIntentKey = UUID().uuidString
                    commentFocused = true
                }
                .uniFont(11, weight: .semibold)
                .foregroundStyle(UniColor.secondary)
                .frame(minHeight: 28)
            }
            .uniFont(13)
            .foregroundStyle(UniColor.inkSoft)
            Spacer(minLength: 4)
            Button {
                Task { await toggleCommentLike(comment, current: likeState) }
            } label: {
                VStack(spacing: 2) {
                    Image(systemName: likeState.liked ? "heart.fill" : "heart")
                        .uniFont(13, weight: .semibold)
                    if likeState.count > 0 { Text("\(likeState.count)").uniFont(10, weight: .semibold) }
                }
                .foregroundStyle(likeState.liked ? UniColor.danger : UniColor.tertiary)
                .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .disabled(likingComment == comment.id)
            .accessibilityLabel(likeState.liked ? "取消点赞评论" : "点赞评论")
        }
        .padding(.vertical, 2)
    }

    private var commentComposer: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let replyTarget {
                HStack(spacing: 6) {
                    Text("回复 @\(replyTarget.author.nickname)")
                        .uniFont(11, weight: .semibold)
                        .foregroundStyle(UniColor.blue)
                        .lineLimit(1)
                    Spacer()
                    Button {
                        self.replyTarget = nil
                        commentIntentKey = UUID().uuidString
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(UniColor.tertiary)
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("取消回复")
                }
            } else if composerExpanded {
                HStack(spacing: 6) {
                    Circle().fill(UniColor.blue).frame(width: 5, height: 5)
                    Text("参与讨论")
                        .uniFont(11, weight: .semibold)
                        .foregroundStyle(UniColor.blue)
                    Spacer()
                    Text("\(commentLength)/\(commentLimit)")
                        .uniFont(10, weight: .medium, design: .monospaced)
                        .foregroundStyle(commentOverLimit ? UniColor.danger : UniColor.tertiary)
                        .accessibilityLabel("评论长度 \(commentLength)，上限 \(commentLimit)")
                }
                .transition(.opacity)
            }

            HStack(alignment: .bottom, spacing: 10) {
                Image(systemName: "square.and.pencil")
                    .uniFont(15, weight: .semibold)
                    .foregroundStyle(UniColor.blue)
                    .frame(width: 34, height: 44)
                    .background(UniColor.blue.opacity(0.07), in: RoundedRectangle(cornerRadius: UniRadius.control))
                    .accessibilityHidden(true)

                TextField("写下你的想法…", text: $commentText, axis: .vertical)
                    .uniFont(14)
                    .foregroundStyle(UniColor.ink)
                    .tint(UniColor.blue)
                    .lineLimit(1...5)
                    .focused($commentFocused)
                    .frame(minHeight: 44)
                    .disabled(isSending)
                    .accessibilityLabel("评论内容")
                    .accessibilityHint("最多 \(commentLimit) 个字符")
                    .accessibilityIdentifier("comment.input")
                    .onChange(of: commentText) { _, _ in
                        commentError = nil
                        commentIntentKey = UUID().uuidString
                    }

                Button {
                    Task { await sendComment() }
                } label: {
                    HStack(spacing: 5) {
                        if isSending {
                            ProgressView().tint(.white)
                        } else {
                            Text(commentError == nil ? (auth.isAuthenticated ? "发送" : "登录发送") : "重试")
                                .uniFont(12, weight: .semibold)
                            Image(systemName: "arrow.up")
                                .uniFont(11, weight: .bold)
                        }
                    }
                    .foregroundStyle(canSendComment || isSending ? .white : UniColor.tertiary)
                    .padding(.horizontal, 13)
                    .frame(minWidth: 58, minHeight: 44)
                    .background(canSendComment || isSending ? UniColor.blue : UniColor.mist.opacity(0.7),
                                in: RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(!canSendComment)
                .accessibilityLabel(isSending ? "正在发送评论" : (auth.isAuthenticated ? "发送评论" : "登录后发送评论"))
                .accessibilityIdentifier("comment.send")
            }

            if let commentError {
                Text(commentError)
                    .uniFont(11, weight: .semibold)
                    .foregroundStyle(UniColor.danger)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("comment.error")
            } else if commentOverLimit {
                Text("评论最多 \(commentLimit) 个字符，请精简后发送")
                    .uniFont(11, weight: .semibold)
                    .foregroundStyle(UniColor.danger)
            }
        }
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                .stroke(commentFocused ? UniColor.blue.opacity(0.6) : UniColor.mist, lineWidth: commentFocused ? 1.2 : 1)
        }
        .shadow(color: UniColor.ink.opacity(0.04), radius: 14, y: 5)
        .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.88), value: composerExpanded)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: commentFocused)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(UniColor.paper.ignoresSafeArea(edges: .bottom))
        .accessibilityIdentifier("comment.composer")
    }

    private var trimmedComment: String {
        commentText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // The API's Java @Size constraint counts UTF-16 code units.
    private var commentLength: Int { trimmedComment.utf16.count }
    private var commentOverLimit: Bool { commentLength > commentLimit }
    private var composerExpanded: Bool { commentFocused || !commentText.isEmpty || commentError != nil }
    private var canSendComment: Bool { !isSending && !trimmedComment.isEmpty && !commentOverLimit }

    private func startChat() async {
        guard let userID = auth.profile?.id else {
            auth.requireLogin()
            return
        }
        let revision = auth.authenticationRevision
        if post.author.uid == auth.profile?.publicUid {
            errorMessage = "这是你自己发的帖子"
            return
        }
        do {
            let conversation = try await APIClient.shared.createConversation(peerUid: post.author.uid)
            guard isCurrentIdentity(userID: userID, revision: revision) else { return }
            conversationOwnerID = userID
            startedConversation = conversation
        } catch {
            guard isCurrentIdentity(userID: userID, revision: revision) else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
    }

    private func loadComments() async {
        let userID = auth.profile?.id
        let revision = auth.authenticationRevision
        let requestID = UUID()
        commentsLoadID = requestID
        isLoading = true
        defer { if commentsLoadID == requestID { isLoading = false; commentsLoadID = nil } }
        let existingIds = Set(comments.map(\.id))
        do {
            let loaded = try await APIClient.shared.comments(postId: post.id)
            guard isCurrentIdentity(userID: userID, revision: revision), commentsLoadID == requestID else { return }
            let loadedIds = Set(loaded.map(\.id))
            let submittedWhileLoading = comments.filter { !existingIds.contains($0.id) && !loadedIds.contains($0.id) }
            comments = loaded + submittedWhileLoading
        } catch {
            guard isCurrentIdentity(userID: userID, revision: revision), commentsLoadID == requestID else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
    }

    private func sendComment() async {
        guard !isSending else { return }
        guard let userID = auth.profile?.id else {
            auth.requireLogin()
            return
        }
        let revision = auth.authenticationRevision
        let text = trimmedComment
        guard !text.isEmpty, !commentOverLimit else { return }
        // A retry of this draft owns the same intent across transport failures and
        // login recovery. Authentication changes never rotate its request key.
        let intentKey = commentIntentKey
        isSending = true
        let requestID = UUID()
        commentRequestID = requestID
        commentError = nil
        defer {
            if commentRequestID == requestID { isSending = false; commentRequestID = nil }
        }
        do {
            let created = try await APIClient.shared.addComment(postId: post.id, content: text,
                                                                parentId: replyTarget?.id, requestKey: intentKey)
            guard isCurrentIdentity(userID: userID, revision: revision), commentRequestID == requestID else { return }
            if !comments.contains(where: { $0.id == created.id }) {
                comments.append(created)
                commentCount += 1
            }
            if commentIntentKey == intentKey {
                commentIntentKey = UUID().uuidString
                if trimmedComment == text { commentText = ""; commentFocused = false; replyTarget = nil }
            }
        } catch {
            guard isCurrentIdentity(userID: userID, revision: revision), commentRequestID == requestID else { return }
            commentError = (error as? LocalizedError)?.errorDescription ?? "评论未发送，请重试"
        }
    }

    private func toggleLike() {
        guard let userID = auth.profile?.id else { auth.requireLogin(); return }
        guard !isLiking else { return }
        let revision = auth.authenticationRevision
        let wasLiked = liked
        isLiking = true
        Task {
            defer { if isCurrentIdentity(userID: userID, revision: revision) { isLiking = false } }
            do {
                try await APIClient.shared.likePost(id: post.id, liked: wasLiked)
                guard isCurrentIdentity(userID: userID, revision: revision) else { return }
                liked = !wasLiked
                likeCount = max(0, likeCount + (liked ? 1 : -1))
            } catch {
                guard isCurrentIdentity(userID: userID, revision: revision) else { return }
                errorMessage = (error as? LocalizedError)?.errorDescription
            }
        }
    }

    private func toggleFavorite() {
        guard let userID = auth.profile?.id else { auth.requireLogin(); return }
        guard !isFavoriting else { return }
        let revision = auth.authenticationRevision
        let wasFavorited = favorited
        isFavoriting = true
        Task {
            defer { if isCurrentIdentity(userID: userID, revision: revision) { isFavoriting = false } }
            do {
                try await APIClient.shared.favoritePost(id: post.id, favorited: wasFavorited)
                guard isCurrentIdentity(userID: userID, revision: revision) else { return }
                favorited = !wasFavorited
                favoriteCount = max(0, favoriteCount + (favorited ? 1 : -1))
            } catch {
                guard isCurrentIdentity(userID: userID, revision: revision) else { return }
                errorMessage = (error as? LocalizedError)?.errorDescription
            }
        }
    }

    private var isMine: Bool { post.author.uid == auth.profile?.publicUid }

    private var shareText: String {
        let summary = post.excerpt ?? String(post.content.prefix(80))
        return "\(post.title)\n\(summary)\n—— 来自 UniPO"
    }

    private func recordShare() async {
        _ = try? await APIClient.shared.sharePost(id: post.id)
    }

    private func deletePost() async {
        guard let userID = auth.profile?.id else { return }
        let revision = auth.authenticationRevision
        isDeleting = true
        defer { isDeleting = false }
        do {
            try await APIClient.shared.deletePost(id: post.id)
            guard isCurrentIdentity(userID: userID, revision: revision) else { return }
            NotificationCenter.default.post(name: .unipoPostDeleted, object: post.id)
            dismiss()
        } catch {
            guard isCurrentIdentity(userID: userID, revision: revision) else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "删除失败，请重试"
        }
    }

    private func toggleCommentLike(_ comment: CommentView, current: (liked: Bool, count: Int)) async {
        guard let userID = auth.profile?.id else { auth.requireLogin(); return }
        guard likingComment == nil else { return }
        let revision = auth.authenticationRevision
        likingComment = comment.id
        defer { if isCurrentIdentity(userID: userID, revision: revision) { likingComment = nil } }
        do {
            try await APIClient.shared.likeComment(id: comment.id, liked: current.liked)
            guard isCurrentIdentity(userID: userID, revision: revision) else { return }
            commentLikes[comment.id] = (!current.liked, max(0, current.count + (current.liked ? -1 : 1)))
        } catch {
            guard isCurrentIdentity(userID: userID, revision: revision) else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
    }

    private func isCurrentIdentity(userID: Int64?, revision: UInt64) -> Bool {
        auth.profile?.id == userID && auth.authenticationRevision == revision
    }

    private func boardLabel(_ board: String) -> String {
        switch board {
        case "school": return "校 PO"
        case "college": return "院 PO"
        case "major": return "专业 PO"
        default: return "推荐"
        }
    }
}
