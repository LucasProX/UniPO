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
    @State private var commentsLoadID: UUID?
    @State private var isLiking = false
    @State private var isFavoriting = false
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
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
            .task(id: identityKey) { await loadComments() }
            .onChange(of: identityKey) { _, _ in
                if let conversationOwnerID, auth.profile?.id != conversationOwnerID, !auth.isSessionRecoveryPending {
                    startedConversation = nil
                    self.conversationOwnerID = nil
                }
                commentRequestID = nil
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
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(UniColor.ink)
                        Text("Lv.\(post.author.level) \(post.author.levelTitle ?? "")")
                            .font(.uniLabel(11))
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
                    .font(.system(size: 14, weight: .semibold))
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
                .font(.system(size: 24, weight: .heavy))
                .foregroundStyle(UniColor.ink)

            Text(post.content)
                .font(.uniBody(15))
                .foregroundStyle(UniColor.inkSoft)
                .lineSpacing(5)

            if let images = post.images, !images.isEmpty {
                ForEach(images, id: \.self) { raw in
                    if let url = AppConfig.absoluteURL(raw) {
                        AsyncImage(url: url) { phase in
                            if case .success(let image) = phase {
                                image.resizable().scaledToFit()
                            } else {
                                RoundedRectangle(cornerRadius: 16).fill(UniColor.mist)
                                    .frame(height: 200)
                            }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    }
                }
            }

            if !post.tagList.isEmpty {
                HStack(spacing: 6) {
                    ForEach(post.tagList, id: \.self) { UniChip($0) }
                }
            }

            Text(RelativeTime.text(from: post.publishedAt ?? post.createdAt))
                .font(.uniLabel(11))
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
                    .font(.system(size: 13, weight: .semibold))
                Text(title).font(.uniLabel(12))
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
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(UniColor.ink)

            if isLoading {
                UniLoadingState("加载评论…")
            } else if comments.isEmpty {
                Text("还没有评论，来说两句")
                    .font(.uniBody(13))
                    .foregroundStyle(UniColor.secondary)
                    .padding(.vertical, 20)
            } else {
                ForEach(comments) { comment in
                    HStack(alignment: .top, spacing: 10) {
                        UserProfileLink(uid: comment.author.uid, name: comment.author.nickname) {
                            UniAvatar(url: comment.author.avatarUrl, name: comment.author.nickname, size: 34)
                                .frame(width: 44, height: 44, alignment: .topLeading)
                                .contentShape(Rectangle())
                        }
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                UserProfileLink(uid: comment.author.uid, name: comment.author.nickname) {
                                    Text(comment.author.nickname)
                                        .font(.uniLabel(12))
                                        .foregroundStyle(UniColor.ink)
                                }
                                Text(RelativeTime.text(from: comment.createdAt))
                                    .font(.uniLabel(10))
                                    .foregroundStyle(UniColor.tertiary)
                            }
                            Text(comment.content)
                                .font(.uniBody(13))
                                .foregroundStyle(UniColor.inkSoft)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .padding(.top, 4)
    }

    private var commentComposer: some View {
        VStack(alignment: .leading, spacing: 10) {
            if composerExpanded {
                HStack(spacing: 6) {
                    Circle().fill(UniColor.blue).frame(width: 5, height: 5)
                    Text("参与讨论")
                        .font(.uniLabel(11))
                        .foregroundStyle(UniColor.blue)
                    Spacer()
                    Text("\(commentLength)/\(commentLimit)")
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(commentOverLimit ? UniColor.danger : UniColor.tertiary)
                        .accessibilityLabel("评论长度 \(commentLength)，上限 \(commentLimit)")
                }
                .transition(.opacity)
            }

            HStack(alignment: .bottom, spacing: 10) {
                Image(systemName: "square.and.pencil")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(UniColor.blue)
                    .frame(width: 34, height: 44)
                    .background(UniColor.blue.opacity(0.07), in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityHidden(true)

                TextField("写下你的想法…", text: $commentText, axis: .vertical)
                    .font(.uniBody(14))
                    .foregroundStyle(UniColor.ink)
                    .tint(UniColor.blue)
                    .lineLimit(1...5)
                    .focused($commentFocused)
                    .frame(minHeight: 44)
                    .disabled(isSending)
                    .accessibilityLabel("评论内容")
                    .accessibilityHint("最多 \(commentLimit) 个字符")
                    .accessibilityIdentifier("comment.input")
                    .onChange(of: commentText) { _, _ in commentError = nil }

                Button {
                    Task { await sendComment() }
                } label: {
                    HStack(spacing: 5) {
                        if isSending {
                            ProgressView().tint(.white)
                        } else {
                            Text(commentError == nil ? (auth.isAuthenticated ? "发送" : "登录发送") : "重试")
                                .font(.uniLabel(12))
                            Image(systemName: "arrow.up")
                                .font(.system(size: 11, weight: .bold))
                        }
                    }
                    .foregroundStyle(canSendComment || isSending ? .white : UniColor.tertiary)
                    .padding(.horizontal, 13)
                    .frame(minWidth: 58, minHeight: 44)
                    .background(canSendComment || isSending ? UniColor.blue : UniColor.mist.opacity(0.7),
                                in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(!canSendComment)
                .accessibilityLabel(isSending ? "正在发送评论" : (auth.isAuthenticated ? "发送评论" : "登录后发送评论"))
                .accessibilityIdentifier("comment.send")
            }

            if let commentError {
                Text(commentError)
                    .font(.uniLabel(11))
                    .foregroundStyle(UniColor.danger)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("comment.error")
            } else if commentOverLimit {
                Text("评论最多 \(commentLimit) 个字符，请精简后发送")
                    .font(.uniLabel(11))
                    .foregroundStyle(UniColor.danger)
            }
        }
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
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
        isSending = true
        let requestID = UUID()
        commentRequestID = requestID
        commentError = nil
        defer {
            if commentRequestID == requestID { isSending = false; commentRequestID = nil }
        }
        do {
            let created = try await APIClient.shared.addComment(postId: post.id, content: text)
            guard isCurrentIdentity(userID: userID, revision: revision), commentRequestID == requestID else { return }
            if !comments.contains(where: { $0.id == created.id }) { comments.append(created) }
            commentCount += 1
            if trimmedComment == text { commentText = ""; commentFocused = false }
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
