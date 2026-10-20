import SwiftUI
import UniPOCore
import UniPOUI

struct MessagesView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss

    @State private var conversations: [ConversationView] = []
    @State private var selected: ConversationView?
    @State private var messages: [MessageView] = []
    @State private var draft = ""
    @State private var isLoading = true
    @State private var isSending = false
    @State private var errorMessage: String?
    @State private var realtimeTick = 0

    init(initialConversation: ConversationView? = nil) {
        _selected = State(initialValue: initialConversation)
    }

    var body: some View {
        NavigationStack {
            Group {
                if !auth.isAuthenticated {
                    signedOut
                } else if let selected {
                    thread(selected)
                } else {
                    list
                }
            }
            .navigationTitle(selected == nil ? "消息" : "私信")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if selected != nil {
                        Button {
                            selected = nil
                        } label: {
                            Image(systemName: "chevron.left")
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
            .task(id: auth.isAuthenticated) { await bootstrap() }
            .onChange(of: realtimeTick) { _, _ in
                Task {
                    await loadConversations()
                    if let selected { await loadMessages(selected.id) }
                }
            }
            .onDisappear { RealtimeClient.shared.disconnect() }
            .fullScreenCover(isPresented: Binding(
                get: { auth.loginRequested },
                set: { auth.loginRequested = $0 }
            )) {
                LoginView()
                    .environment(auth)
            }
        }
    }

    private var signedOut: some View {
        VStack(spacing: 14) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 34))
                .foregroundStyle(UniColor.tertiary)
            Text("登录后查看私信")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(UniColor.ink)
            Button("去登录") { auth.requireLogin() }
                .buttonStyle(UniPrimaryButtonStyle())
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var list: some View {
        List {
            if isLoading {
                UniLoadingState("加载会话…")
                    .listRowSeparator(.hidden)
            } else if conversations.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "bubble.left.and.bubble.right")
                        .font(.system(size: 30))
                        .foregroundStyle(UniColor.tertiary)
                    Text("还没有会话")
                        .font(.uniBody(14))
                        .foregroundStyle(UniColor.secondary)
                    Text("从帖子详情或作者主页发起私信")
                        .font(.uniLabel(11))
                        .foregroundStyle(UniColor.tertiary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 40)
                .listRowSeparator(.hidden)
            } else {
                ForEach(conversations) { conversation in
                    Button {
                        selected = conversation
                        Task { await loadMessages(conversation.id) }
                    } label: {
                        HStack(spacing: 12) {
                            UniAvatar(url: conversation.peer.avatarUrl, name: conversation.peer.nickname, size: 46)
                            VStack(alignment: .leading, spacing: 3) {
                                HStack {
                                    Text(conversation.peer.nickname)
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundStyle(UniColor.ink)
                                    Spacer()
                                    Text(RelativeTime.text(from: conversation.lastMessageAt))
                                        .font(.uniLabel(10))
                                        .foregroundStyle(UniColor.tertiary)
                                }
                                Text(conversation.lastMessage ?? "开始聊天")
                                    .font(.uniBody(12))
                                    .foregroundStyle(UniColor.secondary)
                                    .lineLimit(1)
                            }
                            if (conversation.unreadCount ?? 0) > 0 {
                                Text("\(conversation.unreadCount ?? 0)")
                                    .font(.uniLabel(10))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 3)
                                    .background(UniColor.blue, in: Capsule())
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .listStyle(.plain)
        .refreshable { await loadConversations() }
    }

    private func thread(_ conversation: ConversationView) -> some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(messages) { message in
                            bubble(message, conversation: conversation)
                                .id(message.id)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                }
                .scrollIndicators(.hidden)
                .onChange(of: messages.count) { _, _ in
                    if let last = messages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }

            HStack(spacing: 8) {
                TextField("发消息…", text: $draft, axis: .vertical)
                    .lineLimit(1...3)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(UniColor.paper, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                Button {
                    Task { await send(conversation) }
                } label: {
                    if isSending { ProgressView() } else { Image(systemName: "arrow.up.circle.fill") }
                }
                .font(.system(size: 28))
                .foregroundStyle(draft.trimmingCharacters(in: .whitespaces).isEmpty ? UniColor.tertiary : UniColor.ink)
                .disabled(isSending || draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial)
        }
    }

    private func bubble(_ message: MessageView, conversation: ConversationView) -> some View {
        let mine = message.senderId == auth.profile?.id
        return HStack {
            if mine { Spacer(minLength: 40) }
            Text(message.content)
                .font(.uniBody(14))
                .foregroundStyle(mine ? .white : UniColor.ink)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(
                    mine ? AnyShapeStyle(UniColor.ink) : AnyShapeStyle(UniColor.paper),
                    in: RoundedRectangle(cornerRadius: 18, style: .continuous)
                )
            if !mine { Spacer(minLength: 40) }
        }
    }

    private func bootstrap() async {
        isLoading = true
        await loadConversations()
        #if DEBUG
        if selected == nil,
           ProcessInfo.processInfo.arguments.contains("-open-thread"),
           let first = conversations.first {
            selected = first
        }
        #endif
        if let selected {
            await loadMessages(selected.id)
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-send-test") {
                draft = "iOS 自动测试消息"
                await send(selected)
            }
            #endif
        }
        isLoading = false
        if let token = auth.session?.token {
            RealtimeClient.shared.onEvent = { _, _ in
                realtimeTick += 1
            }
            RealtimeClient.shared.connect(token: token)
        }
    }

    private func loadConversations() async {
        do {
            conversations = try await APIClient.shared.conversations()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
    }

    private func loadMessages(_ id: Int64) async {
        do {
            messages = try await APIClient.shared.messages(conversationId: id)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
    }

    private func send(_ conversation: ConversationView) async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        isSending = true
        do {
            let message = try await APIClient.shared.sendMessage(conversationId: conversation.id, content: text)
            messages.append(message)
            draft = ""
            await loadConversations()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
        isSending = false
    }
}
