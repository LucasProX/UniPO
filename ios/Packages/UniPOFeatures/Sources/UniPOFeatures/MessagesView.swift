import SwiftUI
import UniPOCore
import UniPOUI

struct MessagesView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var store = MessagesStore()
    @State private var realtimeTick = 0
    @State private var nearBottom = true

    private let initialConversationId: Int64?
    /// 作为「消息」Tab 嵌入时不显示关闭按钮，也不自己弹登录页（RootView 统一处理）。
    private let embedded: Bool
    private let onThreadChange: ((Bool) -> Void)?

    private var conversations: [ConversationView] { store.conversations }
    private var selected: ConversationView? { store.selected }
    private var messages: [MessageView] { store.messages }
    private var draft: String { store.draft }
    private var isLoading: Bool { store.isLoading }
    private var isSending: Bool { store.isSending }
    private var identityKey: String {
        "\(auth.authenticationRevision):\(auth.profile?.id ?? 0):\(auth.isSessionRecoveryPending)"
    }

    init(initialConversation: ConversationView? = nil, initialConversationId: Int64? = nil,
         embedded: Bool = false, onThreadChange: ((Bool) -> Void)? = nil) {
        self.initialConversationId = initialConversation?.id ?? initialConversationId
        self.embedded = embedded
        self.onThreadChange = onThreadChange
    }

    var body: some View {
        NavigationStack {
            Group {
                if !auth.isAuthenticated || !store.hasVerifiedIdentity {
                    signedOut
                } else if let selected {
                    thread(selected)
                } else {
                    list
                }
            }
            .navigationTitle(selected == nil ? "消息" : "私信")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(embedded && selected == nil ? .hidden : .automatic, for: .navigationBar)
            .onChange(of: selected?.id) { _, id in onThreadChange?(id != nil) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if selected != nil {
                        Button {
                            store.select(nil)
                            // 进入对话时服务端已把看到的消息标为已读；回到列表重新拉一次，未读数才会清掉。
                            Task { await store.loadConversations() }
                        } label: {
                            Image(systemName: "chevron.left")
                        }
                    }
                }
                if !embedded {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("关闭") { dismiss() }
                    }
                }
            }
            .task(id: identityKey) { await bootstrap() }
            .onChange(of: identityKey) { _, _ in
                store.synchronize(with: auth)
                if !auth.isAuthenticated {
                    RealtimeClient.shared.onEvent = nil
                    RealtimeClient.shared.disconnect()
                }
            }
            .onChange(of: realtimeTick) { _, _ in
                Task {
                    await store.loadConversations()
                    if let selected { await store.loadMessages(selected.id) }
                }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await bootstrap() } }
            }
            .onDisappear {
                RealtimeClient.shared.onEvent = nil
                RealtimeClient.shared.disconnect()
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                if store.hasVerifiedIdentity, let error = selected == nil ? store.conversationError : store.syncError {
                    HStack {
                        Text(error).uniFont(12).foregroundStyle(UniColor.secondary)
                        Spacer()
                        Button("重试") {
                            Task {
                                if selected == nil { await store.loadConversations() }
                                else { await store.synchronizeMessages() }
                            }
                        }
                            .uniFont(12, weight: .semibold).foregroundStyle(UniColor.blue)
                    }
                    .padding(14).background(UniColor.paper)
                }
            }
            .fullScreenCover(isPresented: Binding(
                get: { !embedded && auth.loginRequested },
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
                .uniFont(34)
                .foregroundStyle(UniColor.tertiary)
            Text(auth.isSessionRecoveryPending ? "正在确认登录状态" : "登录后查看私信")
                .uniFont(18, weight: .bold)
                .foregroundStyle(UniColor.ink)
            if auth.isRestoring {
                ProgressView().tint(UniColor.blue)
            } else if auth.isSessionRecoveryPending {
                Text(auth.recoveryError ?? "请稍后重试")
                    .uniFont(12).foregroundStyle(UniColor.secondary)
                Button("重试") { Task { await auth.restore() } }
                    .buttonStyle(UniPrimaryButtonStyle()).padding(.horizontal, 40)
            } else {
                Button("去登录") { auth.requireLogin() }
                    .buttonStyle(UniPrimaryButtonStyle()).padding(.horizontal, 40)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var list: some View {
        List {
            if isLoading && conversations.isEmpty {
                UniLoadingState("加载会话…")
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            } else if conversations.isEmpty && store.conversationError == nil && !store.hasMoreConversations {
                UniEmptyState(icon: "bubble.left.and.bubble.right", title: "还没有会话",
                              message: "从帖子详情或作者主页发起私信")
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            } else {
                ForEach(conversations) { conversation in
                    HStack(spacing: 12) {
                        UserProfileLink(uid: conversation.peer.uid, name: conversation.peer.nickname) {
                            UniAvatar(url: conversation.peer.avatarUrl, name: conversation.peer.nickname, size: 46)
                        }
                        Button {
                            store.select(conversation)
                            Task { await store.loadMessages(conversation.id) }
                        } label: {
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 3) {
                                    HStack {
                                        Text(conversation.peer.nickname)
                                            .uniFont(15, weight: .bold)
                                            .foregroundStyle(UniColor.ink)
                                        Spacer()
                                        Text(RelativeTime.text(from: conversation.lastMessageAt))
                                            .uniFont(10, weight: .semibold)
                                            .foregroundStyle(UniColor.tertiary)
                                    }
                                    Text(conversation.lastMessage ?? "开始聊天")
                                        .uniFont(12)
                                        .foregroundStyle(UniColor.secondary)
                                        .lineLimit(1)
                                }
                                if (conversation.unreadCount ?? 0) > 0 {
                                    Text("\(conversation.unreadCount ?? 0)")
                                        .uniFont(10, weight: .semibold)
                                        .foregroundStyle(.white)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 3)
                                        .background(UniColor.blue, in: Capsule())
                                }
                            }
                            .padding(.vertical, 4)
                            // .plain 按钮只有不透明的像素能点；昵称和时间之间的 Spacer 是空白，不加这一行会点不中。
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    .listRowBackground(Color.clear)
                }
            }
            if store.hasMoreConversations {
                VStack(spacing: 8) {
                    if let error = store.conversationPageError {
                        Text(error).uniFont(12).foregroundStyle(UniColor.secondary)
                    }
                    if store.isLoadingMoreConversations { ProgressView() }
                    else {
                        Button(store.conversationPageError == nil ? "加载更多会话" : "重新加载") {
                            Task { await store.loadMoreConversations() }
                        }
                        .uniFont(13, weight: .semibold).foregroundStyle(UniColor.blue)
                    }
                }
                .frame(maxWidth: .infinity).padding(.vertical, 14)
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(embedded ? .hidden : .automatic)
        .background(embedded ? UniColor.paper : Color.clear)
        .refreshable { await store.loadConversations() }
    }

    private func thread(_ conversation: ConversationView) -> some View {
        VStack(spacing: 0) {
            UserProfileLink(uid: conversation.peer.uid, name: conversation.peer.nickname) {
                HStack(spacing: 10) {
                    UniAvatar(url: conversation.peer.avatarUrl, name: conversation.peer.nickname, size: 36, shape: .circle)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(conversation.peer.nickname)
                            .uniFont(13, weight: .semibold)
                            .foregroundStyle(UniColor.ink)
                        Text("UID \(conversation.peer.uid)")
                            .uniFont(10, weight: .semibold)
                            .foregroundStyle(UniColor.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .uniFont(11, weight: .semibold)
                        .foregroundStyle(UniColor.tertiary)
                }
                .frame(minHeight: 44)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.white)
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        if store.isLoadingMessages {
                            UniLoadingState("加载消息…")
                        } else if let error = store.messageError {
                            Text(error).uniFont(12).foregroundStyle(UniColor.secondary)
                            Button("重新加载消息") { Task { await store.loadMessages(conversation.id) } }
                                .uniFont(13, weight: .semibold).foregroundStyle(UniColor.blue)
                        }
                        if store.hasOlderMessages {
                            if let error = store.historyError {
                                Text(error).uniFont(12).foregroundStyle(UniColor.secondary)
                            }
                            if store.isLoadingHistory { ProgressView().padding(10) }
                            else {
                                Button(store.historyError == nil ? "查看更早消息" : "重新加载历史") {
                                    let anchor = messages.first?.id
                                    Task {
                                        await store.loadOlderMessages()
                                        guard selected?.id == conversation.id else { return }
                                        if let anchor { proxy.scrollTo(anchor, anchor: .top) }
                                    }
                                }
                                .uniFont(12, weight: .semibold).foregroundStyle(UniColor.blue)
                                .padding(.vertical, 10)
                            }
                        }
                        ForEach(messages) { message in
                            bubble(message, conversation: conversation)
                                .id(message.id)
                        }
                        ForEach(store.pendingMessages) { pending in
                            pendingBubble(pending).id(pending.id)
                        }
                        Color.clear.frame(height: 1).id("message-bottom")
                            .onAppear { nearBottom = true }
                            .onDisappear { nearBottom = false }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                }
                .scrollIndicators(.hidden)
                .refreshable { await store.synchronizeMessages() }
                .onChange(of: messages.last?.id) { _, _ in
                    if nearBottom { scrollToBottom(proxy) }
                }
                .onChange(of: store.scrollToBottomRevision) { _, _ in scrollToBottom(proxy) }
            }

            HStack(spacing: 8) {
                TextField("发消息…", text: Binding(get: { store.draft }, set: { store.draft = $0 }), axis: .vertical)
                    .lineLimit(1...3)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(UniColor.paper, in: RoundedRectangle(cornerRadius: UniRadius.tile, style: .continuous))
                Button {
                    Task { await store.send() }
                } label: {
                    if isSending { ProgressView() } else { Image(systemName: "arrow.up.circle.fill") }
                }
                .uniFont(28)
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
        return HStack(alignment: .top, spacing: 8) {
            if mine {
                Spacer(minLength: 40)
            } else {
                // 对方的每条消息左侧带头像，点头像进对方主页。
                UserProfileLink(uid: conversation.peer.uid, name: conversation.peer.nickname) {
                    UniAvatar(url: conversation.peer.avatarUrl, name: conversation.peer.nickname, size: 34, shape: .circle)
                }
                .accessibilityLabel("\(conversation.peer.nickname)的主页")
            }
            VStack(alignment: mine ? .trailing : .leading, spacing: 4) {
                Text(message.content)
                .uniFont(14)
                .foregroundStyle(mine ? .white : UniColor.ink)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(
                    mine ? AnyShapeStyle(UniColor.ink) : AnyShapeStyle(UniColor.paper),
                    in: RoundedRectangle(cornerRadius: UniRadius.tile, style: .continuous)
                )
                if mine && message.id == messages.last?.id {
                    Text("已发送").uniFont(10, weight: .semibold).foregroundStyle(UniColor.tertiary)
                }
            }
            if !mine { Spacer(minLength: 40) }
        }
    }

    private func pendingBubble(_ pending: PendingMessage) -> some View {
        HStack {
            Spacer(minLength: 40)
            VStack(alignment: .trailing, spacing: 7) {
                Text(pending.content)
                    .uniFont(14).foregroundStyle(UniColor.ink)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(UniColor.blue.opacity(0.06), in: RoundedRectangle(cornerRadius: UniRadius.tile))
                    .overlay(RoundedRectangle(cornerRadius: UniRadius.tile).stroke(UniColor.blue.opacity(0.24), lineWidth: 1))
                if pending.isSending {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.mini)
                        Text("正在发送").uniFont(11, weight: .semibold)
                    }.foregroundStyle(UniColor.secondary)
                } else {
                    Text(pending.errorMessage ?? "未确认发送，请重试")
                        .uniFont(11).foregroundStyle(UniColor.secondary)
                    HStack(spacing: 18) {
                        Button("不再重试") { store.discardRetry(pending.id) }
                            .foregroundStyle(UniColor.secondary)
                        Button("重试") { Task { await store.retryMessage(pending.id) } }
                            .foregroundStyle(UniColor.blue).disabled(isSending)
                    }
                    .uniFont(12, weight: .semibold).frame(minHeight: 44)
                }
            }
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
            proxy.scrollTo("message-bottom", anchor: .bottom)
        }
    }

    private func bootstrap() async {
        RealtimeClient.shared.onEvent = nil
        RealtimeClient.shared.disconnect()
        store.synchronize(with: auth)
        let userID = auth.profile?.id
        let revision = auth.authenticationRevision
        guard userID != nil else { return }
        await store.loadConversations(initialConversationID: initialConversationId)
        guard auth.profile?.id == userID, auth.authenticationRevision == revision, !Task.isCancelled else { return }
        #if DEBUG
        if selected == nil,
           ProcessInfo.processInfo.arguments.contains("-open-thread"),
           let first = conversations.first {
            store.select(first)
        }
        #endif
        if let selected {
            await store.loadMessages(selected.id)
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-send-test") {
                store.draft = "iOS 自动测试消息"
                await store.send()
            }
            #endif
        }
        guard auth.profile?.id == userID, auth.authenticationRevision == revision, !Task.isCancelled else { return }
        if let token = auth.session?.token {
            RealtimeClient.shared.onEvent = { _, _ in
                guard auth.profile?.id == userID, auth.authenticationRevision == revision else { return }
                realtimeTick += 1
            }
            RealtimeClient.shared.connect(token: token)
        }
    }

}
