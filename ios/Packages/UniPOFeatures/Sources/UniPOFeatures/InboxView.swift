import SwiftUI
import UniPOCore
import UniPOUI

/// 「消息」Tab：私信与互动通知。打开某个私信会话时隐藏顶部切换，让对话占满页面。
struct InboxView: View {
    @Environment(AuthStore.self) private var auth
    let initialConversationId: Int64?

    @State private var section: Section = .messages
    @State private var threadOpen = false

    enum Section: String, CaseIterable, Identifiable {
        case messages = "私信"
        case notices = "互动"
        case system = "通知"
        var id: String { rawValue }
    }

    var body: some View {
        VStack(spacing: 0) {
            if !threadOpen {
                header
            }
            switch section {
            case .messages:
                MessagesView(initialConversationId: initialConversationId, embedded: true,
                             onThreadChange: { threadOpen = $0 })
            case .notices:
                InteractionNoticesView()
            case .system:
                NotificationsView()
            }
        }
        .background(UniColor.paper)
        .onChange(of: initialConversationId) { _, id in if id != nil { section = .messages } }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("消息")
                .font(.system(size: 30, weight: .heavy))
                .foregroundStyle(UniColor.ink)
            Picker("消息类型", selection: $section) {
                ForEach(Section.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
        }
        .padding(.horizontal, 18)
        .padding(.top, 10)
        .padding(.bottom, 6)
    }
}

/// 别人对我的 PO 和评论的点赞、收藏、评论、回复。
struct InteractionNoticesView: View {
    @Environment(AuthStore.self) private var auth
    @State private var notices: [InteractionNoticeView] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var selectedPost: PostView?
    @State private var loadedFor: Int64?

    var body: some View {
        List {
            if isLoading && notices.isEmpty {
                UniLoadingState("加载互动…").listRowSeparator(.hidden).listRowBackground(Color.clear)
            } else if let errorMessage, notices.isEmpty {
                VStack(spacing: 10) {
                    Text(errorMessage).font(.uniBody(13)).foregroundStyle(UniColor.secondary)
                    Button("重试") { Task { await load() } }.font(.uniLabel(13)).foregroundStyle(UniColor.blue)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 40)
                .listRowSeparator(.hidden).listRowBackground(Color.clear)
            } else if notices.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "heart.text.square").font(.system(size: 30)).foregroundStyle(UniColor.tertiary)
                    Text("还没有互动").font(.system(size: 16, weight: .bold)).foregroundStyle(UniColor.ink)
                    Text("别人点赞、收藏或评论你的 PO 时会出现在这里")
                        .font(.uniBody(12)).foregroundStyle(UniColor.secondary)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 48)
                .listRowSeparator(.hidden).listRowBackground(Color.clear)
            } else {
                ForEach(notices, id: \.listID) { notice in
                    row(notice)
                        .listRowBackground(Color.clear)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { await load() }
        .task(id: auth.profile?.id) {
            guard loadedFor != auth.profile?.id else { return }
            notices = []
            await load()
        }
        .sheet(item: $selectedPost) { post in
            PostDetailView(post: post)
                .environment(auth)
                .presentationDetents([.large])
        }
    }

    private func row(_ notice: InteractionNoticeView) -> some View {
        HStack(alignment: .top, spacing: 12) {
            UserProfileLink(uid: notice.actor.uid, name: notice.actor.nickname) {
                UniAvatar(url: notice.actor.avatarUrl, name: notice.actor.nickname, size: 42)
            }
            .frame(minWidth: 44, minHeight: 44)
            Button {
                selectedPost = notice.post
            } label: {
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 6) {
                        Text(notice.actor.nickname)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(UniColor.ink)
                            .lineLimit(1)
                        Text(action(notice.type))
                            .font(.uniBody(13))
                            .foregroundStyle(UniColor.secondary)
                        Spacer(minLength: 4)
                        Image(systemName: icon(notice.type))
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(tint(notice.type))
                    }
                    if let comment = notice.commentContent, !comment.isEmpty {
                        Text(comment)
                            .font(.uniBody(14))
                            .foregroundStyle(UniColor.ink)
                            .lineLimit(3)
                    }
                    if let post = notice.post {
                        Text(post.title)
                            .font(.uniLabel(12))
                            .foregroundStyle(UniColor.secondary)
                            .lineLimit(1)
                            .padding(.horizontal, 10).padding(.vertical, 7)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(UniColor.mist.opacity(0.6), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                    Text(RelativeTime.text(from: notice.createdAt))
                        .font(.uniLabel(10))
                        .foregroundStyle(UniColor.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(notice.post == nil)
        }
        .padding(.vertical, 6)
    }

    private func load() async {
        guard let userID = auth.profile?.id else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let result = try await APIClient.shared.interactionNotices()
            guard auth.profile?.id == userID else { return }
            notices = result
            loadedFor = userID
        } catch {
            guard auth.profile?.id == userID else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "互动加载失败，请重试"
        }
    }

    private func action(_ type: String) -> String {
        switch type {
        case "like": return "赞了你的 PO"
        case "favorite": return "收藏了你的 PO"
        case "comment": return "评论了你的 PO"
        case "reply": return "回复了你的评论"
        default: return "与你互动"
        }
    }

    private func icon(_ type: String) -> String {
        switch type {
        case "like": return "heart.fill"
        case "favorite": return "star.fill"
        case "reply": return "arrowshape.turn.up.left.fill"
        default: return "text.bubble.fill"
        }
    }

    private func tint(_ type: String) -> Color {
        switch type {
        case "like": return UniColor.danger
        case "favorite": return UniColor.gold
        default: return UniColor.blue
        }
    }
}
