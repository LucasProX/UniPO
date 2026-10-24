import SwiftUI
import UniPOCore
import UniPOUI

// 活动、帖子、成长、主页之间的联动组件。

/// 帖子上的关联标签：「活动 · 新生学业规划讲座」「主线 · 四级报名」，点开进入对应详情。
struct PostLinkChips: View {
    @Environment(AuthStore.self) private var auth
    let post: PostView
    @State private var openedActivity: ActivityRef?
    @State private var openedTask: TaskRef?

    var body: some View {
        HStack(spacing: 6) {
            if let activity = post.activity {
                chip("flag.2.crossed", "活动 · \(activity.title)") { openedActivity = ActivityRef(id: activity.id) }
            }
            if let task = post.task {
                chip("map", "主线 · \(task.title)") { openedTask = TaskRef(id: task.id) }
            }
        }
        .sheet(item: $openedActivity) { ref in
            NavigationStack { ActivityDetailView(activityID: ref.id) }.environment(auth)
        }
        .sheet(item: $openedTask) { ref in
            TaskDetailView(taskID: ref.id).environment(auth)
        }
    }

    static func hasLinks(_ post: PostView) -> Bool { post.activity != nil || post.task != nil }

    private func chip(_ icon: String, _ text: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                Text(text).lineLimit(1)
            }
            .uniFont(11, weight: .semibold)
            .foregroundStyle(UniColor.blue)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(UniColor.blue.opacity(0.08), in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityHint("打开关联的详情")
    }
}

/// 活动详情的「活动帖子」、任务详情的「同学经验」：关联到这里的同校帖子 + 发帖入口。
struct LinkedPostsSection: View {
    @Environment(AuthStore.self) private var auth
    let title: String
    let emptyText: String
    let composeTitle: String
    let link: PostLink

    @State private var posts: [PostView] = []
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var openedPost: PostView?
    @State private var composing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title)
                    .uniFont(15, weight: .bold)
                    .foregroundStyle(UniColor.ink)
                Spacer()
                Button {
                    if auth.isAuthenticated { composing = true } else { auth.requireLogin() }
                } label: {
                    Label(composeTitle, systemImage: "square.and.pencil")
                        .uniFont(12, weight: .semibold)
                        .foregroundStyle(UniColor.blue)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
            }
            if isLoading && posts.isEmpty {
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 8)
            } else if let errorMessage, posts.isEmpty {
                Text(errorMessage).uniFont(12).foregroundStyle(UniColor.secondary)
            } else if posts.isEmpty {
                Text(emptyText).uniFont(12).foregroundStyle(UniColor.tertiary)
            } else {
                VStack(spacing: 0) {
                    ForEach(posts) { post in
                        Button { openedPost = post } label: { row(post) }
                            .buttonStyle(.plain)
                        if post.id != posts.last?.id { Divider() }
                    }
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
            .stroke(UniColor.mist.opacity(0.7), lineWidth: 1))
        .task { await load() }
        .onReceive(NotificationCenter.default.publisher(for: .unipoPostPublished)) { _ in
            Task { await load() }
        }
        .sheet(item: $openedPost) { post in
            PostDetailView(post: post).environment(auth)
        }
        .sheet(isPresented: $composing) {
            ComposeSheet(initialLink: link).environment(auth)
        }
    }

    private func row(_ post: PostView) -> some View { CompactPostRow(post: post) }

    private func load() async {
        isLoading = true
        errorMessage = nil
        do {
            switch link {
            case .activity(let ref): posts = try await APIClient.shared.linkedPosts(activityID: ref.id)
            case .task(let ref): posts = try await APIClient.shared.linkedPosts(taskID: ref.id)
            }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "帖子加载失败"
        }
        isLoading = false
    }
}

/// 主页上的成长：等级、学期、参加过的活动数、徽章、最近参加的活动。本人和访客看到的一致。
struct ProfileGrowthSection: View {
    @Environment(AuthStore.self) private var auth
    let uid: String

    @State private var growth: PublicGrowth?
    @State private var failed = false
    @State private var openedActivity: ActivityRef?

    var body: some View {
        Group {
            if let growth {
                content(growth)
            } else if failed {
                EmptyView()
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 44)
            }
        }
        .task(id: uid) { await load() }
        .sheet(item: $openedActivity) { ref in
            NavigationStack { ActivityDetailView(activityID: ref.id) }.environment(auth)
        }
    }

    private func content(_ growth: PublicGrowth) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                stat("Lv.\(growth.level)", growth.levelTitle ?? "等级")
                stat("\(growth.attendedCount)", "参加活动")
                stat("\(growth.badges.count)", "徽章")
                if let term = growth.termLabel { stat(term, "当前学期") }
            }
            if !growth.badges.isEmpty {
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(growth.badges) { badge in
                            Label(badge.title, systemImage: badge.icon)
                                .uniFont(11, weight: .semibold)
                                .foregroundStyle(UniColor.ink)
                                .padding(.horizontal, 10)
                                .frame(minHeight: 30)
                                .background(UniColor.gold.opacity(0.14), in: Capsule())
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            if !growth.recentActivities.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text("最近参加")
                        .uniFont(12, weight: .semibold)
                        .foregroundStyle(UniColor.secondary)
                        .padding(.bottom, 4)
                    ForEach(growth.recentActivities) { item in
                        Button { openedActivity = ActivityRef(id: item.id) } label: {
                            HStack(spacing: 10) {
                                Text(GrowthDate.monthDay(item.startsAt))
                                    .uniFont(11, weight: .semibold)
                                    .foregroundStyle(UniColor.tertiary)
                                    .frame(width: 52, alignment: .leading)
                                Text(item.title)
                                    .uniFont(13, weight: .semibold)
                                    .foregroundStyle(UniColor.ink)
                                    .lineLimit(1)
                                Spacer(minLength: 4)
                                Image(systemName: "chevron.right")
                                    .uniFont(10)
                                    .foregroundStyle(UniColor.tertiary)
                            }
                            .frame(minHeight: 40)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
            .stroke(UniColor.mist.opacity(0.7), lineWidth: 1))
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .uniFont(15, weight: .bold)
                .foregroundStyle(UniColor.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(label)
                .uniFont(10, weight: .semibold)
                .foregroundStyle(UniColor.tertiary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        do {
            growth = try await APIClient.shared.publicGrowth(uid: uid)
            failed = false
        } catch {
            failed = true
        }
    }
}

/// 一行紧凑的帖子：标题、作者、点赞、时间和首图缩略图。用于关联帖子和搜索结果。
struct CompactPostRow: View {
    let post: PostView

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            UniAvatar(url: post.author.avatarUrl, name: post.author.nickname, size: 32, shape: .circle)
            VStack(alignment: .leading, spacing: 3) {
                Text(post.title)
                    .uniFont(14, weight: .semibold)
                    .foregroundStyle(UniColor.ink)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                HStack(spacing: 8) {
                    Text(post.author.nickname)
                    // List 里的 Label 会把图标和文字拉开，用 HStack 排。
                    HStack(spacing: 2) { Image(systemName: "heart"); Text("\(post.likeTotal)") }
                    Text(RelativeTime.text(from: post.publishedAt ?? post.createdAt))
                }
                .uniFont(11)
                .foregroundStyle(UniColor.tertiary)
                .lineLimit(1)
            }
            Spacer(minLength: 4)
            if let first = post.images?.first, let url = AppConfig.absoluteURL(first) {
                AsyncImage(url: url) { phase in
                    if case .success(let image) = phase { image.resizable().scaledToFill() } else { UniColor.mist }
                }
                .frame(width: 52, height: 52)
                .clipShape(RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
            }
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}
