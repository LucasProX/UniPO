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

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 3)

    var body: some View {
        ScrollView {
            if let profile = auth.profile {
                signedIn(profile)
            } else {
                // 未登录不渲染任何「我的」内容；入口在 RootView 已被拦到登录页。
                EmptyView()
            }
        }
        .scrollIndicators(.hidden)
        .preferredColorScheme(.dark)
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

    private func signedIn(_ profile: UserProfile) -> some View {
        VStack(spacing: 0) {
            header(profile)
            tabBar
            grid
        }
        .padding(.bottom, 120)
    }

    private func header(_ profile: UserProfile) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                AvatarPickerButton(onPicked: uploadAvatar) {
                    UniAvatar(url: profile.avatarUrl, name: profile.nickname, size: 86)
                        .overlay {
                            if isUploadingAvatar {
                                ProgressView().tint(.white)
                            }
                        }
                }

                Spacer(minLength: 18)

                HStack(spacing: 26) {
                    stat(stats?.following ?? 0, "关注")
                    stat(stats?.followers ?? 0, "粉丝")
                    stat(stats?.posts ?? myPosts.count, "作品")
                }

                Spacer(minLength: 0)

                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "gearshape")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.8))
                        .frame(width: 36, height: 36)
                        .background(.white.opacity(0.1), in: Circle())
                }
                .buttonStyle(.plain)
            }

            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    Text(profile.nickname)
                        .font(.system(size: 24, weight: .heavy))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                }

                Text("UID \(profile.publicUid)")
                    .font(.uniLabel(11))
                    .foregroundStyle(.white.opacity(0.45))

                infoLine(profile)

                if let bio = profile.bio, !bio.isEmpty {
                    Text(bio)
                        .font(.uniBody(13))
                        .foregroundStyle(.white.opacity(0.7))
                        .lineLimit(3)
                        .padding(.top, 2)
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 22)
        .background(
            LinearGradient(colors: [Color(hex: 0x0B0F17), Color(hex: 0x152340)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
                .ignoresSafeArea(edges: .top)
        )
    }

    private func infoLine(_ profile: UserProfile) -> some View {
        HStack(spacing: 7) {
            if profile.showGender != false, let gender = profile.gender {
                Text(gender == "female" ? "♀" : (gender == "male" ? "♂" : "○"))
                    .font(.system(size: 17, weight: .black))
                    .foregroundStyle(gender == "female" ? Color(hex: 0xFF6B9A) : (gender == "male" ? Color(hex: 0x5AA7FF) : .white.opacity(0.5)))
            }
            if let zodiac = profile.zodiac, !zodiac.isEmpty {
                Text(zodiac)
            }
            if let mbti = profile.mbti, !mbti.isEmpty {
                Text(mbti)
            }
            if profile.showAge != false, let age = age(profile) {
                Text("\(age)岁")
            }
        }
        .font(.system(size: 12, weight: .semibold))
        .foregroundStyle(.white.opacity(0.62))
        .lineLimit(1)
    }

    private var tabBar: some View {
        HStack(spacing: 0) {
            tabButton("作品", index: 0, count: myPosts.count)
            tabButton("喜欢", index: 1, count: likedPosts.count)
            tabButton("收藏", index: 2, count: favoritePosts.count)
        }
        .padding(.top, 14)
        .background(Color.white)
        .overlay(alignment: .bottom) {
            Rectangle().fill(UniColor.mist.opacity(0.8)).frame(height: 1)
        }
    }

    private func tabButton(_ title: String, index: Int, count: Int) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.2)) { tab = index }
        } label: {
            VStack(spacing: 7) {
                HStack(spacing: 4) {
                    Text(title)
                        .font(.system(size: 15, weight: tab == index ? .bold : .medium))
                    if count > 0 {
                        Text("\(count)")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(UniColor.tertiary)
                    }
                }
                .foregroundStyle(tab == index ? UniColor.ink : UniColor.secondary)
                Rectangle()
                    .fill(tab == index ? UniColor.ink : .clear)
                    .frame(width: 24, height: 2)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var grid: some View {
        if isLoading {
            ProgressView().tint(UniColor.ink).padding(.top, 60)
        } else if currentPosts.isEmpty {
            VStack(spacing: 10) {
                Image(systemName: "square.grid.2x2")
                    .font(.system(size: 26))
                    .foregroundStyle(UniColor.tertiary)
                Text(tab == 0 ? "还没有作品" : (tab == 1 ? "还没有喜欢的内容" : "还没有收藏"))
                    .font(.uniBody(13))
                    .foregroundStyle(UniColor.tertiary)
            }
            .padding(.top, 60)
            .frame(maxWidth: .infinity)
        } else {
            LazyVGrid(columns: columns, spacing: 2) {
                ForEach(currentPosts) { post in
                    Button {
                        selectedPost = post
                    } label: {
                        gridCell(post)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func gridCell(_ post: PostView) -> some View {
        ZStack(alignment: .bottomLeading) {
            if let raw = post.coverUrl ?? post.images?.first, let url = AppConfig.absoluteURL(raw) {
                AsyncImage(url: url) { phase in
                    if case .success(let image) = phase {
                        image.resizable().scaledToFill()
                    } else {
                        Color(hex: 0xE8EDF5)
                    }
                }
                .frame(minWidth: 0, maxWidth: .infinity)
                .aspectRatio(1, contentMode: .fill)
                .clipped()
            } else {
                LinearGradient(colors: [Color(hex: 0x1D3B6E), Color(hex: 0x0B0F17)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                    .aspectRatio(1, contentMode: .fit)
                    .overlay(alignment: .center) {
                        Text(post.title)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.9))
                            .multilineTextAlignment(.leading)
                            .lineLimit(4)
                            .padding(10)
                    }
            }
            HStack(spacing: 3) {
                Image(systemName: "heart.fill").font(.system(size: 9))
                Text("\(post.likeTotal)").font(.system(size: 10, weight: .semibold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 5)
            .padding(.vertical, 3)
            .background(.black.opacity(0.35), in: Capsule())
            .padding(6)
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(1, contentMode: .fit)
        .clipped()
    }

    private var currentPosts: [PostView] {
        switch tab {
        case 1: return likedPosts
        case 2: return favoritePosts
        default: return myPosts
        }
    }

    private func stat(_ value: Int, _ label: String) -> some View {
        VStack(spacing: 3) {
            Text("\(value)")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(.white)
            Text(label)
                .font(.uniLabel(10))
                .foregroundStyle(.white.opacity(0.5))
        }
    }

    private var settingsSheet: some View {
        NavigationStack {
            List {
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
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { showSettings = false }
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
        guard let data = image.pngData() else { return }
        isUploadingAvatar = true
        Task {
            defer { isUploadingAvatar = false }
            do {
                let uploaded = try await APIClient.shared.uploadImage(data: data, filename: "avatar.png", mimeType: "image/png")
                let updated = try await APIClient.shared.updateAvatar(url: uploaded.url)
                auth.applyProfile(updated)
            } catch {
                avatarError = (error as? LocalizedError)?.errorDescription ?? "上传失败，请稍后再试"
            }
        }
    }
}
