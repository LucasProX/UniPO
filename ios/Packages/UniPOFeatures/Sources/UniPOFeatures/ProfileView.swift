import SwiftUI
import PhotosUI
import UniPOCore
import UniPOUI

struct ProfileView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    @State private var stats: UserStatsView?
    @State private var myPosts: [PostView] = []
    @State private var likedPosts: [PostView] = []
    @State private var favoritePosts: [PostView] = []
    @State private var tab = 0
    @State private var followList: FollowListTarget?
    @State private var activityList: String?
    @State private var showPublish = false
    @State private var showWallet = false
    @State private var organizer: ActivityOrganizerProfile?
    @State private var isLoading = true
    @State private var loadGeneration = 0
    @State private var dataUserID: Int64?
    @State private var selectedPost: PostView?
    @State private var showSettings = false
    @State private var showDeleteConfirm = false
    @State private var isUploadingAvatar = false
    @State private var avatarError: String?
    @State private var backgroundPhoto: PhotosPickerItem?
    @State private var isUpdatingBackground = false
    @State private var backgroundError: String?
    @State private var tabMovesForward = true

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
        .refreshable { await load() }
        .task(id: auth.profile?.id) { await load() }
        .onReceive(NotificationCenter.default.publisher(for: .unipoRelationshipsChanged)) { _ in
            Task { await load() }
        }
        .onChange(of: backgroundPhoto) { _, item in
            guard let item else { return }
            Task { await uploadBackground(item) }
        }
        .sheet(item: Binding(get: { activityList.map(ActivityListTab.init) }, set: { activityList = $0?.id })) { item in
            ActivityListView(initialTab: item.id).environment(auth)
        }
        .sheet(isPresented: $showWallet) { WalletScreen().environment(auth) }
        .sheet(isPresented: $showPublish) {
            if let organizer { CreateActivityView(profile: organizer).environment(auth) }
        }
        .task(id: auth.profile?.id) {
            organizer = try? await APIClient.shared.activityOrganizerProfile()
        }
        .sheet(item: $followList) { target in
            FollowListView(target: target).environment(auth)
        }
        .onReceive(NotificationCenter.default.publisher(for: .unipoPostDeleted)) { note in
            guard let id = note.object as? Int64 else { return }
            myPosts.removeAll { $0.id == id }
            likedPosts.removeAll { $0.id == id }
            favoritePosts.removeAll { $0.id == id }
        }
        .sheet(item: $selectedPost) { post in
            PostDetailView(post: post)
                .environment(auth)
        }
        .sheet(isPresented: $showSettings) {
            settingsSheet
                .presentationDetents([.medium, .large])
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
        .alert("背景图更新失败", isPresented: Binding(
            get: { backgroundError != nil },
            set: { if !$0 { backgroundError = nil } }
        )) {
            Button("知道了", role: .cancel) {}
        } message: {
            Text(backgroundError ?? "")
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
                    .uniFont(13, weight: .regular)
                    .foregroundStyle(UniColor.inkSoft.opacity(0.68))
                    .lineSpacing(3)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            statsCard
            activityShortcuts
        }
        .padding(.horizontal, 20)
        .padding(.top, 6)
        .padding(.bottom, 22)
        .background {
            ProfileBackdrop(url: profile.backgroundUrl)
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
                    .uniFont(12, weight: .semibold)
                    .tracking(1.2)
                    .foregroundStyle(UniColor.secondary)
            }

            Spacer()

            PhotosPicker(selection: $backgroundPhoto, matching: .images) {
                HStack(spacing: 5) {
                    if isUpdatingBackground {
                        ProgressView().tint(UniColor.blue)
                    } else {
                        Image(systemName: "photo")
                            .uniFont(13, weight: .semibold)
                        Text("背景")
                            .uniFont(11, weight: .semibold)
                    }
                }
                .foregroundStyle(UniColor.inkSoft)
                .padding(.horizontal, 12)
                .frame(height: 38)
                .background(Color.white.opacity(0.78), in: Capsule())
                .overlay(Capsule().stroke(.white.opacity(0.95), lineWidth: 1))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .disabled(isUpdatingBackground || isUploadingAvatar)
            .accessibilityLabel(isUpdatingBackground ? "正在保存背景图" : "更换主页背景图")
            .accessibilityIdentifier("profile.background-picker")

            Button {
                showSettings = true
            } label: {
                Image(systemName: "gearshape")
                    .uniFont(15, weight: .semibold)
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
        .disabled(isUploadingAvatar || isUpdatingBackground)
        .accessibilityLabel("更换头像")
    }

    private func identity(_ profile: UserProfile) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(profile.nickname)
                .uniFont(28, weight: .bold)
                .tracking(-0.6)
                .foregroundStyle(UniColor.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.72)

            HStack(spacing: 5) {
                Text("UID \(profile.publicUid)")
                    .uniFont(11, weight: .medium, design: .monospaced)
                    .foregroundStyle(UniColor.tertiary)

                if profile.showGrade != false, let grade = profile.grade, !grade.isEmpty {
                    Text("·")
                        .uniFont(11, weight: .medium)
                        .foregroundStyle(UniColor.tertiary.opacity(0.7))
                    Text(grade)
                        .uniFont(11, weight: .medium)
                        .foregroundStyle(UniColor.secondary)
                }
            }

            if let detail = educationLine(profile) {
                Text(detail)
                    .uniFont(11, weight: .regular)
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
            .uniFont(15, weight: .black)
            .foregroundStyle(tint)
            .frame(width: 27, height: 24)
            .background(tint.opacity(0.12), in: Capsule())
            .overlay(Capsule().stroke(tint.opacity(0.12), lineWidth: 1))
    }

    private func metaChip(_ text: String) -> some View {
        Text(text)
            .uniFont(11, weight: .semibold)
            .foregroundStyle(UniColor.inkSoft.opacity(0.72))
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(Color.white.opacity(0.72), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.95), lineWidth: 1))
    }

    private var statsCard: some View {
        HStack(spacing: 0) {
            followStat(stats?.following ?? 0, .following)
            statDivider
            followStat(stats?.followers ?? 0, .followers)
            statDivider
            statItem(stats?.posts ?? myPosts.count, "作品")
        }
        .padding(.vertical, 13)
        .background(Color.white.opacity(0.78), in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                .stroke(.white.opacity(0.95), lineWidth: 1)
        )
        .shadow(color: UniColor.ink.opacity(0.05), radius: 18, y: 8)
    }

    private var activityShortcuts: some View {
        // 默认一行放下全部入口；辅助功能大字体下每行至少 104pt，自动折成多行。
        LazyVGrid(columns: [GridItem(.adaptive(minimum: dynamicTypeSize.isAccessibilitySize ? 104 : 60), spacing: 8)],
                  spacing: 8) {
            shortcut("我的活动", "ticket", tint: UniColor.blue) { activityList = "upcoming" }
            shortcut("参加过", "clock.arrow.circlepath", tint: UniColor.inkSoft) { activityList = "history" }
            shortcut("金币", "circle.hexagongrid.fill", tint: UniColor.gold) { showWallet = true }
            if organizer?.canPublish == true {
                shortcut("发布", "plus.circle.fill", tint: UniColor.gold) { showPublish = true }
                shortcut("我发布的", "list.bullet.rectangle", tint: UniColor.inkSoft) { activityList = "organized" }
            }
        }
    }

    private func shortcut(_ title: String, _ icon: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon).uniFont(17, weight: .semibold).foregroundStyle(tint)
                Text(title).uniFont(11, weight: .semibold).foregroundStyle(UniColor.ink).lineLimit(1).minimumScaleFactor(0.8)
            }
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 58)
            .background(Color.white.opacity(0.78), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func followStat(_ value: Int, _ kind: FollowListKind) -> some View {
        Button {
            if let uid = auth.profile?.publicUid { followList = FollowListTarget(uid: uid, kind: kind) }
        } label: {
            statItem(value, kind.title).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("查看\(kind.title)列表")
    }

    private func statItem(_ value: Int, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text("\(value)")
                .uniFont(20, weight: .bold)
                .monospacedDigit()
                .foregroundStyle(UniColor.ink)
            Text(label)
                .uniFont(10, weight: .medium)
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
        ProfileSegmentedControl(
            selection: Binding(get: { tab }, set: { selectTab($0) }),
            titles: ["作品", "喜欢", "收藏"],
            counts: [myPosts.count, likedPosts.count, favoritePosts.count]
        )
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

    private func selectTab(_ index: Int) {
        guard (0...2).contains(index), tab != index else { return }
        tabMovesForward = index > tab
        withAnimation(reduceMotion ? nil : .spring(response: 0.38, dampingFraction: 0.88)) {
            tab = index
        }
    }

    // MARK: - Content

    private var content: some View {
        ZStack(alignment: .top) {
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
            .id(tab)
            .transition(reduceMotion ? .identity : .asymmetric(
                insertion: .opacity.combined(with: .offset(x: tabMovesForward ? 18 : -18)),
                removal: .opacity.combined(with: .offset(x: tabMovesForward ? -12 : 12))
            ))
        }
        .clipped()
        .animation(reduceMotion ? nil : .spring(response: 0.38, dampingFraction: 0.88), value: tab)
        .simultaneousGesture(
            DragGesture(minimumDistance: 24).onEnded { value in
                guard abs(value.translation.width) > 55,
                      abs(value.translation.width) > abs(value.translation.height) * 1.4 else { return }
                selectTab(tab + (value.translation.width < 0 ? 1 : -1))
            }
        )
    }

    private var sectionHeader: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(sectionTitle)
                .uniFont(13, weight: .semibold)
                .foregroundStyle(UniColor.ink)
            Spacer()
            Text("\(currentPosts.count) 篇")
                .uniFont(10, weight: .medium)
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
                    .uniFont(26, weight: .semibold)
                    .foregroundStyle(UniColor.blue)
            }

            Text(emptyTitle)
                .uniFont(16, weight: .semibold)
                .foregroundStyle(UniColor.ink)

            Text(emptySubtitle)
                .uniFont(12)
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
            .uniFont(9, weight: .semibold)
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
                .uniFont(54, weight: .black, design: .serif)
                .foregroundStyle(.white.opacity(0.55))
                .offset(x: 7, y: -7)

            VStack(alignment: .leading, spacing: 0) {
                Spacer(minLength: 0)
                Text(post.title)
                    .uniFont(12, weight: .semibold)
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
                .uniFont(19, weight: .medium)
                .foregroundStyle(UniColor.tertiary)
        }
    }

    private func likeBadge(_ post: PostView) -> some View {
        HStack(spacing: 3) {
            Image(systemName: "heart.fill")
                .uniFont(8, weight: .semibold)
            Text("\(post.likeTotal)")
                .uniFont(9, weight: .semibold)
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
                        Button { showSettings = false } label: {
                            HStack(spacing: 12) {
                                UniAvatar(url: profile.avatarUrl, name: profile.nickname, size: 46, shape: .circle)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(profile.nickname)
                                        .uniFont(15, weight: .semibold)
                                        .foregroundStyle(UniColor.ink)
                                    Text("UID \(profile.publicUid)")
                                        .uniFont(11, design: .monospaced)
                                        .foregroundStyle(UniColor.tertiary)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 4)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("返回我的主页")
                    }
                }

                Section("主页背景") {
                    PhotosPicker(selection: $backgroundPhoto, matching: .images) {
                        HStack {
                            Label("更换背景图", systemImage: "photo")
                            Spacer()
                            if isUpdatingBackground { ProgressView() }
                        }
                    }
                    .disabled(isUpdatingBackground || isUploadingAvatar)
                    if let url = auth.profile?.backgroundUrl, !url.isEmpty {
                        Button("恢复默认背景") { Task { await resetBackground() } }
                            .disabled(isUpdatingBackground || isUploadingAvatar)
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
                    .disabled(isUpdatingBackground || isUploadingAvatar)
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
                    .disabled(isUpdatingBackground || isUploadingAvatar)
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
        loadGeneration += 1
        let generation = loadGeneration
        guard let initialProfile = auth.profile else {
            stats = nil
            myPosts = []; likedPosts = []; favoritePosts = []
            dataUserID = nil
            isLoading = false
            return
        }
        if dataUserID != initialProfile.id {
            stats = nil
            myPosts = []; likedPosts = []; favoritePosts = []
            dataUserID = initialProfile.id
        }
        isLoading = true
        defer {
            if loadGeneration == generation { isLoading = false }
        }
        async let profileTask = try? APIClient.shared.me()
        async let statsTask = try? APIClient.shared.userStats()
        async let myTask = try? APIClient.shared.myPosts()
        async let likedTask = try? APIClient.shared.likedPosts()
        async let favoriteTask = try? APIClient.shared.favoritePosts()
        let (latestProfile, latestStats, latestPosts, latestLikes, latestFavorites) =
            await (profileTask, statsTask, myTask, likedTask, favoriteTask)
        guard loadGeneration == generation, auth.profile?.id == initialProfile.id else { return }
        // A late refresh must not overwrite a cover/avatar saved while these requests were in flight.
        if auth.profile == initialProfile, let latestProfile { auth.applyProfile(latestProfile) }
        stats = latestStats
        myPosts = latestPosts ?? []
        likedPosts = latestLikes ?? []
        favoritePosts = latestFavorites ?? []
    }

    private func age(_ profile: UserProfile) -> Int? {
        guard let year = profile.birthYear else { return nil }
        return max(0, Calendar.current.component(.year, from: Date()) - year)
    }

    private func uploadAvatar(_ image: UIImage) {
        guard let userID = auth.profile?.id, !isUploadingAvatar, !isUpdatingBackground else { return }
        let data = image.jpegData(compressionQuality: 0.9) ?? image.pngData()
        guard let data else {
            avatarError = "图片处理失败，请换一张试试"
            return
        }
        isUploadingAvatar = true
        Task {
            defer { isUploadingAvatar = false }
            do {
                guard auth.profile?.id == userID else { return }
                let uploaded = try await APIClient.shared.uploadImage(data: data, filename: "avatar.jpg", mimeType: "image/jpeg")
                guard auth.profile?.id == userID else { return }
                let updated = try await APIClient.shared.updateAvatar(url: uploaded.url)
                guard auth.profile?.id == userID else { return }
                auth.applyProfile(updated)
            } catch {
                guard auth.profile?.id == userID else { return }
                avatarError = (error as? LocalizedError)?.errorDescription ?? "上传失败，请稍后再试"
            }
        }
    }

    private func uploadBackground(_ item: PhotosPickerItem) async {
        guard let userID = auth.profile?.id, !isUpdatingBackground, !isUploadingAvatar else { return }
        isUpdatingBackground = true
        backgroundError = nil
        defer {
            isUpdatingBackground = false
            backgroundPhoto = nil
        }
        do {
            guard let original = try await item.loadTransferable(type: Data.self) else {
                throw ProfileBackgroundImage.Failure.unsupported
            }
            let data = try await Task.detached(priority: .userInitiated) {
                try ProfileBackgroundImage.jpegData(from: original)
            }.value
            guard auth.profile?.id == userID else { return }
            let uploaded = try await APIClient.shared.uploadImage(
                data: data, filename: "profile-background.jpg", mimeType: "image/jpeg", purpose: "profile-background"
            )
            guard auth.profile?.id == userID else { return }
            let updated = try await APIClient.shared.updateBackground(url: uploaded.url)
            guard auth.profile?.id == userID else { return }
            auth.applyProfile(updated)
        } catch {
            guard auth.profile?.id == userID else { return }
            backgroundError = (error as? LocalizedError)?.errorDescription ?? "背景图保存失败，请稍后重试"
        }
    }

    private func resetBackground() async {
        guard let userID = auth.profile?.id, !isUpdatingBackground, !isUploadingAvatar else { return }
        isUpdatingBackground = true
        backgroundError = nil
        defer { isUpdatingBackground = false }
        do {
            let updated = try await APIClient.shared.updateBackground(url: "")
            guard auth.profile?.id == userID else { return }
            auth.applyProfile(updated)
        } catch {
            guard auth.profile?.id == userID else { return }
            backgroundError = (error as? LocalizedError)?.errorDescription ?? "背景图保存失败，请稍后重试"
        }
    }
}
