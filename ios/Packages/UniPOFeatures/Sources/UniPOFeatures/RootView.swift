import SwiftUI
import UniPOCore
import UniPOUI

public enum AppTab: Int, CaseIterable {
    case home, tools, achievements, profile
}

public struct RootView: View {
    @Environment(AuthStore.self) private var auth
    @State private var tab: AppTab = .home
    @State private var showCompose = false
    @State private var showAuth = false
    @State private var showMessages = false

    public init() {
        // 调试用：xcrun simctl launch booted com.unipo.app -tab achievements
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "-tab"),
           ProcessInfo.processInfo.arguments.indices.contains(index + 1) {
            switch ProcessInfo.processInfo.arguments[index + 1] {
            case "tools": _tab = State(initialValue: .tools)
            case "achievements": _tab = State(initialValue: .achievements)
            case "profile": _tab = State(initialValue: .profile)
            default: _tab = State(initialValue: .home)
            }
        }
        if ProcessInfo.processInfo.arguments.contains("-compose") {
            _showCompose = State(initialValue: true)
        }
        if ProcessInfo.processInfo.arguments.contains("-messages") {
            _showMessages = State(initialValue: true)
        }
    }

    public var body: some View {
        ZStack(alignment: .bottom) {
            Color(UniColor.paper).ignoresSafeArea()

            Group {
                switch tab {
                case .home:
                    HomeView(onRequestAuth: { showAuth = true },
                             onOpenAchievements: { tab = .achievements },
                             onOpenMessages: { showMessages = true })
                case .tools:
                    ToolsView()
                case .achievements:
                    AchievementsView()
                case .profile:
                    ProfileView(onRequestAuth: { showAuth = true })
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

            DockBar(tab: $tab, onCompose: { showCompose = true })
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
        }
        .sheet(isPresented: $showCompose) {
            ComposeSheet()
                .environment(auth)
                .presentationDetents([.large])
                .presentationCornerRadius(32)
        }
        .sheet(isPresented: $showAuth) {
            AuthSheet()
                .environment(auth)
                .presentationDetents([.medium, .large])
                .presentationCornerRadius(32)
        }
        .sheet(isPresented: $showMessages) {
            MessagesView()
                .environment(auth)
                .presentationDetents([.large])
                .presentationCornerRadius(30)
        }
        .tint(UniColor.blue)
        .task {
            #if DEBUG
            // 调试登录：xcrun simctl launch booted com.unipo.app -email a@b.com -password xxx
            let args = ProcessInfo.processInfo.arguments
            if let ei = args.firstIndex(of: "-email"), args.indices.contains(ei + 1),
               let pi = args.firstIndex(of: "-password"), args.indices.contains(pi + 1) {
                await auth.login(email: args[ei + 1], password: args[pi + 1])
            }
            #endif
        }
    }
}
