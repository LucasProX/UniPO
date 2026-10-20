import SwiftUI
import UniPOCore
import UniPOUI

struct HomeView: View {
    @Environment(AuthStore.self) private var auth
    let onRequestAuth: () -> Void
    let onOpenAchievements: () -> Void
    let onOpenMessages: () -> Void

    @State private var board = "recommend"
    @State private var posts: [PostView] = []
    @State private var cursor: String?
    @State private var hasMore = false
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var selectedPost: PostView?
    @State private var unreadCount = 0

    private let boards = [
        ("recommend", "推荐"),
        ("school", "校 PO"),
        ("college", "院 PO"),
        ("major", "专业 PO")
    ]

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 16) {
                header
                boardPicker
                stageCard
                content
            }
            .padding(.horizontal, 18)
            .padding(.top, 6)
            .padding(.bottom, 120)
        }
        .scrollIndicators(.hidden)
        .refreshable { await load(reset: true) }
        .task { await load(reset: true) }
        .task { await pollUnread() }
        .task {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-post-detail"), selectedPost == nil {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                selectedPost = posts.first
            }
            #endif
        }
        .sheet(item: $selectedPost) { post in
            PostDetailView(post: post)
                .environment(auth)
                .presentationDetents([.large])
                .presentationCornerRadius(30)
        }
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(UniColor.ink)
                    .frame(width: 44, height: 44)
                Text("PO")
                    .font(.system(size: 15, weight: .heavy))
                    .foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("UniPO")
                    .font(.system(size: 22, weight: .heavy))
                    .foregroundStyle(UniColor.ink)
                Text(Self.todayText)
                    .font(.uniLabel(11))
                    .foregroundStyle(UniColor.secondary)
            }
            Spacer()
            Button(action: onOpenMessages) {
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(UniColor.ink)
                    .frame(width: 40, height: 40)
                    .background(UniColor.mist.opacity(0.75), in: Circle())
                    .overlay(alignment: .topTrailing) {
                        if unreadCount > 0 {
                            Text(unreadCount > 99 ? "99+" : "\(unreadCount)")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(UniColor.danger, in: Capsule())
                                .offset(x: 4, y: -4)
                        }
                    }
            }
            .buttonStyle(.plain)
            Button {
                if auth.isAuthenticated { onOpenAchievements() } else { onRequestAuth() }
            } label: {
                if let profile = auth.profile {
                    UniAvatar(url: profile.avatarUrl, name: profile.nickname, size: 40)
                } else {
                    Text("登录")
                        .font(.uniLabel(13))
                        .foregroundStyle(UniColor.ink)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 9)
                        .background(UniColor.mist.opacity(0.8), in: Capsule())
                }
            }
            .buttonStyle(.plain)
        }
    }

    private var boardPicker: some View {
        HStack(spacing: 8) {
            ForEach(boards, id: \.0) { item in
                Button {
                    guard board != item.0 else { return }
                    board = item.0
                    Task { await load(reset: true) }
                } label: {
                    Text(item.1)
                        .font(.uniLabel(13))
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .foregroundStyle(board == item.0 ? .white : UniColor.secondary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(
                            Capsule().fill(board == item.0 ? UniColor.ink : Color.white)
                        )
                        .overlay(
                            Capsule().stroke(UniColor.mist.opacity(board == item.0 ? 0 : 1), lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
    }

    private var stageCard: some View {
        Button(action: onOpenAchievements) {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("你当前")
                            .font(.uniLabel(11))
                            .foregroundStyle(UniColor.blue)
                        Text("大一上 · 第 3 周")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(UniColor.ink)
                    }
                    Spacer()
                    ZStack {
                        Circle()
                            .stroke(UniColor.mist, lineWidth: 6)
                        Circle()
                            .trim(from: 0, to: 0.42)
                            .stroke(UniColor.blue, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                        Text("42%")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(UniColor.ink)
                    }
                    .frame(width: 54, height: 54)
                }

                HStack(spacing: 8) {
                    stageTask("四级报名", done: false)
                    stageTask("选课确认", done: true)
                    Text("+1 待办")
                        .font(.uniLabel(10))
                        .foregroundStyle(UniColor.tertiary)
                }

                HStack(spacing: 6) {
                    Text("查看完整成就")
                        .font(.uniLabel(12))
                    Image(systemName: "arrow.right")
                        .font(.system(size: 11, weight: .bold))
                }
                .foregroundStyle(UniColor.blue)
            }
            .padding(18)
            .background(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .fill(Color.white)
                    .shadow(color: UniColor.ink.opacity(0.07), radius: 22, y: 10)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .stroke(UniColor.blue.opacity(0.16), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func stageTask(_ title: String, done: Bool) -> some View {
        HStack(spacing: 5) {
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(done ? UniColor.blue : UniColor.tertiary)
            Text(title)
                .font(.uniLabel(11))
                .foregroundStyle(done ? UniColor.secondary : UniColor.ink)
                .lineLimit(1)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 7)
        .background(UniColor.paper, in: Capsule())
    }

    @ViewBuilder
    private var content: some View {
        if isLoading && posts.isEmpty {
            UniLoadingState("正在加载校园动态…")
        } else if let errorMessage, posts.isEmpty {
            VStack(spacing: 12) {
                Text(errorMessage)
                    .font(.uniBody(14))
                    .foregroundStyle(UniColor.secondary)
                Button("重试") { Task { await load(reset: true) } }
                    .font(.uniLabel(13))
                    .foregroundStyle(UniColor.blue)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 40)
        } else if posts.isEmpty {
            Text("这个板块还没有内容")
                .font(.uniBody(14))
                .foregroundStyle(UniColor.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 40)
        } else {
            ForEach(posts) { post in
                PostCardView(post: post)
                    .onTapGesture { selectedPost = post }
                    .onAppear {
                        if post.id == posts.last?.id, hasMore {
                            Task { await load(reset: false) }
                        }
                    }
            }
            if isLoading {
                ProgressView().tint(UniColor.ink).padding(.vertical, 16)
            }
        }
    }

    private func load(reset: Bool) async {
        if reset {
            isLoading = true
            errorMessage = nil
            cursor = nil
        }
        do {
            let page = try await APIClient.shared.feed(
                tab: board,
                cursor: reset ? nil : cursor,
                limit: 20
            )
            if reset {
                posts = page.items
            } else {
                posts.append(contentsOf: page.items)
            }
            cursor = page.nextCursor
            hasMore = page.hasMore
            errorMessage = nil
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "加载失败"
        }
        isLoading = false
    }

    private func pollUnread() async {
        while !Task.isCancelled {
            if auth.isAuthenticated, let count = try? await APIClient.shared.unreadCount() {
                unreadCount = count
            }
            try? await Task.sleep(nanoseconds: 15_000_000_000)
        }
    }

    private static var todayText: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M 月 d 日 · EEEE"
        return formatter.string(from: Date())
    }
}
