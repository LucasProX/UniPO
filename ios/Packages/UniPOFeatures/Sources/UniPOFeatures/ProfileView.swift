import SwiftUI
import UniPOCore
import UniPOUI

struct ProfileView: View {
    @Environment(AuthStore.self) private var auth

    @State private var stats: UserStatsView?
    @State private var myPosts: [PostView] = []
    @State private var likedPosts: [PostView] = []
    @State private var favoritePosts: [PostView] = []
    @State private var tab = 0
    @State private var isLoading = true
    @State private var selectedPost: PostView?
    @State private var showSettings = false
    @State private var showDeleteConfirm = false
    @State private var isUploadingAvatar = false
    @State private var avatarError: String?

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 3)
    private let textPalettes: [[Color]] = [
        [Color(hex: 0xE9F0FF), Color(hex: 0xD8E6FF)],
        [Color(hex: 0xFFF0E7), Color(hex: 0xFFDFD0)],
        [Color(hex: 0xE8F8F1), Color(hex: 0xD5F0E4)],
        [Color(hex: 0xF1ECFF), Color(hex: 0xE3D8FF)]
    ]

    var body: some View {
        ScrollView {
            if let profile = auth.profile {
                LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                    hero(profile)

                    Section {
                        content
                    } header: {
                        tabBar
                    }
                }
                .padding(.bottom, 110)
            } else {
                // 未登录不渲染任何「我的」内容；入口在 RootView 已被拦到登录页。
                EmptyView()
            }
        }
        .scrollIndicators(.hidden)
        .background(UniColor.paper.ignoresSafeArea())
        .task(id: auth.isAuthenticated) { await load() }
        .sheet(item: $selectedPost) { post in
            PostDetailView(post: post)
                .environment(auth)
        }
        .sheet(isPresented: $showSettings) {
            settingsSheet
                .presentationDetents([.medium])
                .presentationCornerRadius(28)
        }
        .alert("注销账号", isPresented: $showDeleteConfirm) {
            Button("取消", role: .cancel) {}
            Button("确认注销", role: .destructive) {
                Task { _ = await auth.deleteAccount() }
            }
        } message: {
            Text("注销后账号会被停用、个人资料会被匿名化，且无法恢复。确定继续吗？")
        }
        .alert("更换头像失败", isPresented: Binding(
            get: { avatarError != nil },
            set: { if !$0 { avatarError = nil } }
        )) {
            Button("知道了", role: .cancel) {}
        } message: {
            Text(avatarError ?? "")
        }
    }

    // MARK: - Hero

    private func hero(_ profile: UserProfile) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            topBar

            HStack(alignment: .top, spacing: 18) {
                avatar(profile)
                    .padding(.top, 2)
                identity(profile)
            }

            if let bio = profile.bio, !bio.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(bio)
                    .font(.system(size: 13, weight: .regular))
                    .foregroundStyle(UniColor.inkSoft.opacity(0.68))
                    .lineSpacing(3)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            statsCard
        }
        .padding(.horizontal, 20)
        .padding(.top, 6)
        .padding(.bottom, 22)
        .background {
            ZStack {
                LinearGradient(
                    stops: [
                        .init(color: UniColor.paper, location: 0),
                        .init(color: Color(hex: 0xEEF4FF), location: 0.34),
                        .init(color: UniColor.paper, location: 1)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                RadialGradient(
                    colors: [UniColor.blue.opacity(0.12), .clear],
                    center: .topTrailing,
                    startRadius: 0,
                    endRadius: 300
                )
                RadialGradient(
                    colors: [Color(hex: 0xFFD9E4).opacity(0.24), .clear],
                    center: .bottomLeading,
                    startRadius: 0,
                    endRadius: 260
                )
            }
            .ignoresSafeArea(edges: .top)
        }
    }

    private var topBar: some View {
        HStack {
            HStack(spacing: 7) {
                Circle()
                    .fill(UniColor.blue)
                    .frame(width: 6, height: 6)
                Text("我的主页")
                    .font(.system(size: 12, weight: .semibold))
                    .tracking(1.2)
                    .foregroundStyle(UniColor.secondary)
            }

            Spacer()

            Button {
                showSettings = true
            } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(UniColor.inkSoft)
                    .frame(width: 38, height: 38)
                    .background(Color.white.opacity(0.78), in: Circle())
                    .overlay(Circle().stroke(.white.opacity(0.95), lineWidth: 1))
                    .shadow(color: UniColor.ink.opacity(0.08), radius: 10, y: 4)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("设置")
        }
    }

    private func avatar(_ profile: UserProfile) -> some View {
        AvatarPickerButton(onPicked: uploadAvatar) {
            ZStack {
                Circle()
                    .fill(AngularGradient(
                        colors: [UniColor.blue, Color(hex: 0x8EC5FF), Color(hex: 0xB9C8FF), UniColor.blue],
                        center: .center
                    ))
                    .frame(width: 100, height: 100)

                UniAvatar(url: profile.avatarUrl, name: profile.nickname, size: 88, shape: .circle)
                    .overlay(Circle().stroke(.white, lineWidth: 3))
                    .overlay {
                        if isUploadingAvatar {
                            Circle()
                                .fill(.black.opacity(0.35))
                                .frame(width: 88, height: 88)
                            ProgressView().tint(.white)
                        }
                    }
            }
            .frame(width: 100, height: 100)
            .shadow(color: UniColor.ink.opacity(0.16), radius: 16, y: 8)
        }
        .accessibilityLabel("更换头像")
    }

    private func identity(_ profile: UserProfile) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(profile.nickname)
                .font(.system(size: 28, weight: .bold))
                .tracking(-0.6)
                .foregroundStyle(UniColor.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.72)

            HStack(spacing: 5) {
                Text("UID \(profile.publicUid)")
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(UniColor.tertiary)

                if profile.showGrade != false, let grade = profile.grade, !grade.isEmpty {
                    Text("·")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(UniColor.tertiary.opacity(0.7))
                    Text(grade)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(UniColor.secondary)
                }
            }

            if let detail = educationLine(profile) {
                Text(detail)
                    .font(.system(size: 11, weight: .regular))
                    .foregroundStyle(UniColor.secondary.opacity(0.82))
                    .lineLimit(1)
            }

            metaChips(profile)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func educationLine(_ profile: UserProfile) -> String? {
        let parts = [profile.college, profile.major]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && !$0.contains("未设置") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func metaChips(_ profile: UserProfile) -> some View {
        HStack(spacing: 6) {
            if profile.showGender != false, let gender = profile.gender {
                genderChip(gender)
            }
            if let zodiac = profile.zodiac, !zodiac.isEmpty {
                metaChip(zodiac)
            }
            if let mbti = profile.mbti, !mbti.isEmpty {
                metaChip(mbti)
            }
            if profile.showAge != false, let age = age(profile) {
                metaChip("\(age)岁")
            }
        }
        .lineLimit(1)
    }

    private func genderChip(_ gender: String) -> some View {
        let isFemale = gender == "female"
        let isMale = gender == "male"
        let symbol = isFemale ? "♀" : (isMale ? "♂" : "○")
        let tint = isFemale ? Color(hex: 0xFF6B9A) : (isMale ? Color(hex: 0x2F6BFF) : UniColor.secondary)

        return Text(symbol)
            .font(.system(size: 15, weight: .black))
            .foregroundStyle(tint)
            .frame(width: 27, height: 24)
            .background(tint.opacity(0.12), in: Capsule())
            .overlay(Capsule().stroke(tint.opacity(0.12), lineWidth: 1))
    }

    private func metaChip(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(UniColor.inkSoft.opacity(0.72))
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(Color.white.opacity(0.72), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.95), lineWidth: 1))
    }

    private var statsCard: some View {
        HStack(spacing: 0) {
            statItem(stats?.following ?? 0, "关注")
            statDivider
            statItem(stats?.followers ?? 0, "粉丝")
            statDivider
            statItem(stats?.posts ?? myPosts.count, "作品")
        }
        .padding(.vertical, 13)
        .background(Color.white.opacity(0.78), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(.white.opacity(0.95), lineWidth: 1)
        )
        .shadow(color: UniColor.ink.opacity(0.05), radius: 18, y: 8)
    }

    private func statItem(_ value: Int, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text("\(value)")
                .font(.system(size: 20, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(UniColor.ink)
            Text(label)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(UniColor.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private var statDivider: some View {
        Rectangle()
            .fill(UniColor.mist)
            .frame(width: 1, height: 26)
    }

    // MARK: - Tabs

    private var tabBar: some View {
        HStack(spacing: 0) {
            tabButton("作品", index: 0, count: myPosts.count)
            tabButton("喜欢", index: 1, count: likedPosts.count)
            tabButton("收藏", index: 2, count: favoritePosts.count)
        }
        .padding(4)
        .background(Color.white.opacity(0.86), in: Capsule())
        .overlay(Capsule().stroke(.white.opacity(0.95), lineWidth: 1))
        .shadow(color: UniColor.ink.opacity(0.05), radius: 12, y: 4)
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(.ultraThinMaterial)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(UniColor.mist.opacity(0.55))
                .frame(height: 0.5)
        }
    }

    private func tabButton(_ title: String, index: Int, count: Int) -> some View {
        Button {
            withAnimation(.snappy(duration: 0.26)) { tab = index }
        } label: {
            HStack(spacing: 5) {
                Text(title)
                    .font(.system(size: 13, weight: tab == index ? .semibold : .medium))
                if count > 0 {
                    Text("\(count)")
                        .font(.system(size: 9, weight: .bold))
                        .monospacedDigit()
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(
                            tab == index ? Color.white.opacity(0.18) : UniColor.mist.opacity(0.9),
                            in: Capsule()
                        )
                }
            }
            .foregroundStyle(tab == index ? .white : UniColor.secondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(tab == index ? UniColor.ink : .clear, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Content

    private var content: some View {
        VStack(spacing: 0) {
            if isLoading {
                skeleton
            } else if currentPosts.isEmpty {
                emptyState
            } else {
                sectionHeader
                grid
                footer
            }
        }
    }

    private var sectionHeader: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(sectionTitle)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(UniColor.ink)
            Spacer()
            Text("\(currentPosts.count) 篇")
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(UniColor.tertiary)
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
        .padding(.bottom, 8)
    }

    private var grid: some View {
        LazyVGrid(columns: columns, spacing: 6) {
            ForEach(currentPosts) { post in
                Button {
                    selectedPost = post
                } label: {
                    gridCell(post)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
    }

    private var skeleton: some View {
        LazyVGrid(columns: columns, spacing: 6) {
            ForEach(0..<9, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [UniColor.mist.opacity(0.8), UniColor.mist.opacity(0.4)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .aspectRatio(1, contentMode: .fit)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [UniColor.blue.opacity(0.14), Color(hex: 0xFFDDE8).opacity(0.55)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 76, height: 76)
                Image(systemName: emptyIcon)
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(UniColor.blue)
            }

            Text(emptyTitle)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(UniColor.ink)

            Text(emptySubtitle)
                .font(.system(size: 12))
                .foregroundStyle(UniColor.secondary)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .frame(maxWidth: 260)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 46)
        .padding(.bottom, 52)
    }

    private var footer: some View {
        Text("UNIPO · CAMPUS")
            .font(.system(size: 9, weight: .semibold))
            .tracking(2.2)
            .foregroundStyle(UniColor.tertiary.opacity(0.7))
            .frame(maxWidth: .infinity)
            .padding(.top, 26)
            .padding(.bottom, 10)
    }

    private func gridCell(_ post: PostView) -> some View {
        ZStack(alignment: .bottomLeading) {
            if let url = imageURL(post) {
                Color.clear
                    .overlay {
                        AsyncImage(url: url) { phase in
                            if case .success(let image) = phase {
                                image.resizable().scaledToFill()
                            } else {
                                imagePlaceholder
                            }
                        }
                    }
                LinearGradient(
                    colors: [.clear, .black.opacity(0.48)],
                    startPoint: .center,
                    endPoint: .bottom
                )
                .allowsHitTesting(false)
            } else {
                textCover(post)
            }

            likeBadge(post)
                .padding(8)
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .stroke(.white.opacity(0.35), lineWidth: 0.5)
        )
        .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }

    private func textCover(_ post: PostView) -> some View {
        let palette = textPalettes[Int(post.id % Int64(textPalettes.count))]
        return ZStack(alignment: .topLeading) {
            LinearGradient(colors: palette, startPoint: .topLeading, endPoint: .bottomTrailing)

            Text("“")
                .font(.system(size: 54, weight: .black, design: .serif))
                .foregroundStyle(.white.opacity(0.55))
                .offset(x: 7, y: -7)

            VStack(alignment: .leading, spacing: 0) {
                Spacer(minLength: 0)
                Text(post.title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(UniColor.ink.opacity(0.8))
                    .lineLimit(4)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .padding(10)
            .padding(.bottom, 20)
        }
    }

    private var imagePlaceholder: some View {
        ZStack {
            LinearGradient(
                colors: [UniColor.mist, UniColor.mist.opacity(0.5)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Image(systemName: "photo")
                .font(.system(size: 19, weight: .medium))
                .foregroundStyle(UniColor.tertiary)
        }
    }

    private func likeBadge(_ post: PostView) -> some View {
        HStack(spacing: 3) {
            Image(systemName: "heart.fill")
                .font(.system(size: 8, weight: .semibold))
            Text("\(post.likeTotal)")
                .font(.system(size: 9, weight: .semibold))
                .monospacedDigit()
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .background(.black.opacity(0.38), in: Capsule())
    }

    private func imageURL(_ post: PostView) -> URL? {
        guard let raw = post.coverUrl ?? post.images?.first else { return nil }
        return AppConfig.absoluteURL(raw)
    }

    private var currentPosts: [PostView] {
        switch tab {
        case 1: return likedPosts
        case 2: return favoritePosts
        default: return myPosts
        }
    }

    private var sectionTitle: String {
        switch tab {
        case 1: return "最近喜欢"
        case 2: return "我的收藏"
        default: return "最近发布"
        }
    }

    private var emptyIcon: String {
        switch tab {
        case 1: return "heart"
        case 2: return "bookmark"
        default: return "square.and.pencil"
        }
    }

    private var emptyTitle: String {
        switch tab {
        case 1: return "还没有喜欢的内容"
        case 2: return "还没有收藏"
        default: return "这里还很安静"
        }
    }

    private var emptySubtitle: String {
        switch tab {
        case 1: return "刷到心动的帖子，点个赞就会出现在这里。"
        case 2: return "把想反复看的内容收藏起来吧。"
        default: return "你的第一篇校园记录，会从这里开始。"
        }
    }

    // MARK: - Settings

    private var settingsSheet: some View {
        NavigationStack {
            List {
                if let profile = auth.profile {
                    Section {
                        HStack(spacing: 12) {
                            UniAvatar(url: profile.avatarUrl, name: profile.nickname, size: 46, shape: .circle)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(profile.nickname)
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(UniColor.ink)
                                Text("UID \(profile.publicUid)")
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundStyle(UniColor.tertiary)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }

                Section {
                    Button {
                        showSettings = false
                        showDeleteConfirm = true
                    } label: {
                        Label("注销账号", systemImage: "trash")
                            .foregroundStyle(UniColor.danger)
                    }
                } footer: {
                    Text("注销后账号会被停用，个人资料会被匿名化，且无法恢复。")
                }

                Section {
                    Button(role: .destructive) {
                        showSettings = false
                        Task { await auth.logout() }
                    } label: {
                        Text("退出登录")
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(UniColor.paper)
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { showSettings = false }
                }
            }
        }
    }

    private func load() async {
        guard auth.isAuthenticated else {
            stats = nil
            myPosts = []; likedPosts = []; favoritePosts = []
            isLoading = false
            return
        }
        isLoading = true
        async let statsTask = try? APIClient.shared.userStats()
        async let myTask = try? APIClient.shared.myPosts()
        async let likedTask = try? APIClient.shared.likedPosts()
        async let favoriteTask = try? APIClient.shared.favoritePosts()
        stats = await statsTask
        myPosts = await myTask ?? []
        likedPosts = await likedTask ?? []
        favoritePosts = await favoriteTask ?? []
        isLoading = false
    }

    private func age(_ profile: UserProfile) -> Int? {
        guard let year = profile.birthYear else { return nil }
        return max(0, Calendar.current.component(.year, from: Date()) - year)
    }

    private func uploadAvatar(_ image: UIImage) {
        let data = image.jpegData(compressionQuality: 0.9) ?? image.pngData()
        guard let data else {
            avatarError = "图片处理失败，请换一张试试"
            return
        }
        isUploadingAvatar = true
        Task {
            defer { isUploadingAvatar = false }
            do {
                let uploaded = try await APIClient.shared.uploadImage(data: data, filename: "avatar.jpg", mimeType: "image/jpeg")
                let updated = try await APIClient.shared.updateAvatar(url: uploaded.url)
                auth.applyProfile(updated)
            } catch {
                avatarError = (error as? LocalizedError)?.errorDescription ?? "上传失败，请稍后再试"
            }
        }
    }
}
