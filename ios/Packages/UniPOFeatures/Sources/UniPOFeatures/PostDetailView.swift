import SwiftUI
import UniPOCore
import UniPOUI

struct PostDetailView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss

    let post: PostView

    @State private var comments: [CommentView] = []
    @State private var commentText = ""
    @State private var liked: Bool
    @State private var favorited: Bool
    @State private var likeCount: Int
    @State private var favoriteCount: Int
    @State private var isLoading = true
    @State private var isSending = false
    @State private var errorMessage: String?
    @State private var startedConversation: ConversationView?

    init(post: PostView) {
        self.post = post
        _liked = State(initialValue: post.liked ?? false)
        _favorited = State(initialValue: post.favorited ?? false)
        _likeCount = State(initialValue: post.likeTotal)
        _favoriteCount = State(initialValue: post.favoriteTotal)
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
            .task { await loadComments() }
            .sheet(item: $startedConversation) { conversation in
                MessagesView(initialConversation: conversation)
                    .environment(auth)
                    .presentationDetents([.large])
                    .presentationCornerRadius(30)
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
            UniAvatar(url: post.author.avatarUrl, name: post.author.nickname, size: 42)
            VStack(alignment: .leading, spacing: 2) {
                Text(post.author.nickname)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(UniColor.ink)
                Text("Lv.\(post.author.level) \(post.author.levelTitle ?? "")")
                    .font(.uniLabel(11))
                    .foregroundStyle(UniColor.secondary)
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
            actionButton(title: "\(post.commentTotal)", icon: "bubble.right", tint: UniColor.secondary) {}
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
                        UniAvatar(url: comment.author.avatarUrl, name: comment.author.nickname, size: 34)
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(comment.author.nickname)
                                    .font(.uniLabel(12))
                                    .foregroundStyle(UniColor.ink)
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
        HStack(spacing: 8) {
            TextField("写评论…", text: $commentText, axis: .vertical)
                .lineLimit(1...3)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(UniColor.paper, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            Button {
                Task { await sendComment() }
            } label: {
                if isSending { ProgressView() } else { Image(systemName: "arrow.up.circle.fill") }
            }
            .font(.system(size: 28))
            .foregroundStyle(commentText.trimmingCharacters(in: .whitespaces).isEmpty ? UniColor.tertiary : UniColor.ink)
            .disabled(isSending || commentText.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }

    private func startChat() async {
        guard auth.isAuthenticated else {
            auth.requireLogin()
            return
        }
        if post.author.uid == auth.profile?.publicUid {
            errorMessage = "这是你自己发的帖子"
            return
        }
        do {
            startedConversation = try await APIClient.shared.createConversation(peerUid: post.author.uid)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
    }

    private func loadComments() async {
        isLoading = true
        do {
            comments = try await APIClient.shared.comments(postId: post.id)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
        isLoading = false
    }

    private func sendComment() async {
        guard auth.isAuthenticated else {
            auth.requireLogin()
            return
        }
        let text = commentText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        isSending = true
        do {
            let created = try await APIClient.shared.addComment(postId: post.id, content: text)
            comments.append(created)
            commentText = ""
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
        isSending = false
    }

    private func toggleLike() {
        guard auth.isAuthenticated else { auth.requireLogin(); return }
        Task {
            do {
                try await APIClient.shared.likePost(id: post.id, liked: liked)
                liked.toggle()
                likeCount = max(0, likeCount + (liked ? 1 : -1))
            } catch {
                errorMessage = (error as? LocalizedError)?.errorDescription
            }
        }
    }

    private func toggleFavorite() {
        guard auth.isAuthenticated else { auth.requireLogin(); return }
        Task {
            do {
                try await APIClient.shared.favoritePost(id: post.id, favorited: favorited)
                favorited.toggle()
                favoriteCount = max(0, favoriteCount + (favorited ? 1 : -1))
            } catch {
                errorMessage = (error as? LocalizedError)?.errorDescription
            }
        }
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
