import SwiftUI
import UniPOCore
import UniPOUI

public enum AppTab: Int, CaseIterable {
    case home, achievements, messages, profile
}

public struct RootView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab: AppTab = .home
    @State private var showCompose = false
    @State private var unreadCount = 0
    @State private var pendingCheckin: CheckinTarget?
    @State private var pendingConversationId: Int64?
    @State private var pendingProfileTab = false
    @State private var lastVerifiedUserID: Int64?

    private var identityKey: String {
        "\(auth.authenticationRevision):\(auth.profile?.id ?? 0):\(auth.isSessionRecoveryPending)"
    }

    public init() {
        // 调试用：xcrun simctl launch booted com.unipo.app -tab achievements
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "-tab"),
           ProcessInfo.processInfo.arguments.indices.contains(index + 1) {
            switch ProcessInfo.processInfo.arguments[index + 1] {
            case "messages": _tab = State(initialValue: .messages)
            case "achievements": _tab = State(initialValue: .achievements)
            case "profile": _tab = State(initialValue: .profile)
            default: _tab = State(initialValue: .home)
            }
        }
        if ProcessInfo.processInfo.arguments.contains("-compose") {
            _showCompose = State(initialValue: true)
        }
        if ProcessInfo.processInfo.arguments.contains("-messages") {
            _tab = State(initialValue: .messages)
        }
    }

    public var body: some View {
        ZStack(alignment: .bottom) {
            Color(UniColor.paper).ignoresSafeArea()

            Group {
                switch tab {
                case .home:
                    HomeView(onRequestAuth: { auth.requireLogin() },
                             onOpenAchievements: { tab = .achievements })
                case .achievements:
                    AchievementsView()
                        .id(identityKey)
                case .messages:
                    InboxView(initialConversationId: pendingConversationId)
                        .id("\(identityKey):\(pendingConversationId ?? 0)")
                case .profile:
                    ProfileView()
                        .id(identityKey)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .safeAreaPadding(.bottom, 92)

            LinearGradient(
                colors: [Color(UniColor.paper).opacity(0), Color(UniColor.paper).opacity(0.92)],
                startPoint: .top, endPoint: .bottom
            )
            .frame(height: 150)
            .allowsHitTesting(false)

            DockBar(tab: $tab, isAuthenticated: auth.isAuthenticated, unreadCount: unreadCount, onCompose: {
                if auth.isAuthenticated { showCompose = true } else { auth.requireLogin() }
            }, onRequestAuth: { target in
                pendingProfileTab = target == .profile
                auth.requireLogin()
            })
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if auth.isRestoring || auth.recoveryError != nil { sessionRecoveryNotice }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active, auth.recoveryError != nil, !auth.loginRequested {
                Task { await auth.restore() }
            }
        }
        .sheet(isPresented: $showCompose) {
            ComposeSheet()
                .environment(auth)
                .presentationDetents([.large])
                .presentationCornerRadius(32)
        }
        .fullScreenCover(isPresented: Binding(
            get: { auth.loginRequested },
            set: { auth.loginRequested = $0 }
        ), onDismiss: {
            if !auth.isAuthenticated { pendingProfileTab = false }
        }) {
            LoginView()
                .environment(auth)
        }
        .tint(UniColor.blue)
        .onChange(of: auth.profile?.onboardingCompleted) { _, completed in
            if auth.isAuthenticated, pendingProfileTab, completed == true {
                pendingProfileTab = false
                tab = .profile
            }
        }
        .task(id: identityKey) {
            let userID = auth.profile?.id
            if let userID {
                if let previous = lastVerifiedUserID, previous != userID { clearPrivatePresentation() }
                lastVerifiedUserID = userID
            } else if !auth.isSessionRecoveryPending {
                clearPrivatePresentation()
                lastVerifiedUserID = nil
            }
            if auth.isAuthenticated, pendingProfileTab, auth.profile?.onboardingCompleted == true {
                pendingProfileTab = false
                tab = .profile
            }
            if !auth.isAuthenticated, !auth.isSessionRecoveryPending, tab == .profile || tab == .messages {
                tab = .home
            }
            if auth.isAuthenticated, auth.profile?.onboardingCompleted == false {
                auth.loginRequested = true
            }
            if auth.isAuthenticated, let token = PushTokenStore.token {
                _ = try? await APIClient.shared.registerPushToken(token)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .unipoPushToken)) { note in
            guard auth.isAuthenticated, let token = note.object as? String else { return }
            Task { _ = try? await APIClient.shared.registerPushToken(token) }
        }
        .onOpenURL { url in
            // 系统相机扫现场二维码会打开 unipo://checkin?...，直接进入签到。
            guard let link = CheckinLink(url: url) else { return }
            pendingCheckin = CheckinTarget(activityID: link.activityID, token: link.token)
            if !auth.isAuthenticated { auth.requireLogin() }
        }
        .sheet(item: Binding(get: { auth.isAuthenticated ? pendingCheckin : nil }, set: { pendingCheckin = $0 })) { target in
            CheckinSheet(target: target).environment(auth)
        }
        .onReceive(NotificationCenter.default.publisher(for: .unipoOpenConversation)) { note in
            guard auth.isAuthenticated, let id = note.object as? Int64 else { return }
            pendingConversationId = id
            tab = .messages
        }
        .task(id: identityKey) { await pollUnread() }
        .onChange(of: tab) { _, current in
            if current != .messages { pendingConversationId = nil }
            Task { await refreshUnread() }
        }
        .task {
            #if DEBUG
            // 调试登录：xcrun simctl launch booted com.unipo.app -email a@b.com -password xxx
            let args = ProcessInfo.processInfo.arguments
            if args.contains("-logout") {
                await auth.logout()
            }
            if args.contains("-login") {
                auth.loginRequested = true
            }
            if let ei = args.firstIndex(of: "-email"), args.indices.contains(ei + 1),
               let pi = args.firstIndex(of: "-password"), args.indices.contains(pi + 1) {
                await auth.login(email: args[ei + 1], password: args[pi + 1])
            }
            #endif
        }
    }

    /// 底栏「消息」角标；私信页本身走 WebSocket，这里只做低频兜底。
    private func pollUnread() async {
        while !Task.isCancelled {
            await refreshUnread()
            try? await Task.sleep(nanoseconds: 20_000_000_000)
        }
    }

    private func refreshUnread() async {
        let userID = auth.profile?.id
        let revision = auth.authenticationRevision
        guard userID != nil else { unreadCount = 0; return }
        if let count = try? await APIClient.shared.unreadCount(),
           auth.profile?.id == userID, auth.authenticationRevision == revision {
            unreadCount = count
        }
    }

    private func clearPrivatePresentation() {
        if tab == .messages { tab = .home }
        unreadCount = 0
        showCompose = false
        pendingConversationId = nil
        RealtimeClient.shared.onEvent = nil
        RealtimeClient.shared.disconnect()
    }

    private var sessionRecoveryNotice: some View {
        HStack(spacing: 12) {
            if auth.isRestoring {
                ProgressView().tint(UniColor.blue)
                Text("正在确认登录状态…").font(.uniLabel(12))
            } else {
                Image(systemName: "arrow.triangle.2.circlepath").foregroundStyle(UniColor.blue)
                VStack(alignment: .leading, spacing: 3) {
                    Text("登录状态需要确认").font(.uniLabel(12))
                    Text(auth.recoveryError ?? "请稍后重试")
                        .font(.uniBody(11)).foregroundStyle(UniColor.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 4)
                Button("重试") { Task { await auth.restore() } }
                    .font(.uniLabel(12)).foregroundStyle(UniColor.blue)
                    .frame(minWidth: 44, minHeight: 44)
                    .accessibilityIdentifier("session.recovery.retry")
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(UniColor.ink)
        .padding(.horizontal, 18).padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(UniColor.paper)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("session.recovery")
    }
}
