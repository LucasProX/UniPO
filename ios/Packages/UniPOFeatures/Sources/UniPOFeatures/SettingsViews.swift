import SwiftUI
import PhotosUI
import UserNotifications
import UniPOCore
import UniPOUI

/// 「我的 → 设置」。主页背景的上传和注销确认仍由 ProfileView 负责，这里只放入口。
struct SettingsView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    @Binding var backgroundPhoto: PhotosPickerItem?
    let isBusy: Bool
    let isUpdatingBackground: Bool
    let onResetBackground: () -> Void
    let onDeleteAccount: () -> Void
    let onLogout: () -> Void

    @State private var notificationStatus: UNAuthorizationStatus?
    @State private var cacheBytes = 0
    @State private var showGrowthBackground = false

    var body: some View {
        NavigationStack {
            List {
                if let profile = auth.profile {
                    Section {
                        NavigationLink {
                            EditProfileView(profile: profile).environment(auth)
                        } label: {
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
                            .padding(.vertical, 4)
                        }
                        .accessibilityHint("编辑资料")
                    }

                    Section("资料与隐私") {
                        NavigationLink {
                            EditProfileView(profile: profile).environment(auth)
                        } label: {
                            Label("编辑资料", systemImage: "person.text.rectangle")
                        }
                        Button { showGrowthBackground = true } label: {
                            row("成长背景", icon: "graduationcap", value: profile.entryYear.map { "\($0) 级" } ?? "未填写")
                        }
                        .buttonStyle(.plain)
                        NavigationLink {
                            BlockedUsersView().environment(auth)
                        } label: {
                            Label("黑名单", systemImage: "hand.raised")
                        }
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
                    .disabled(isBusy)
                    if let url = auth.profile?.backgroundUrl, !url.isEmpty {
                        Button("恢复默认背景", action: onResetBackground)
                            .disabled(isBusy)
                    }
                }

                Section("通用") {
                    Button(action: openNotificationSettings) {
                        row("通知", icon: "bell", value: notificationLabel)
                    }
                    .buttonStyle(.plain)
                    Button {
                        URLCache.shared.removeAllCachedResponses()
                        refreshCacheSize()
                    } label: {
                        row("清除图片缓存", icon: "trash.slash",
                            value: ByteCountFormatter.string(fromByteCount: Int64(cacheBytes), countStyle: .file))
                    }
                    .buttonStyle(.plain)
                    .disabled(cacheBytes == 0)
                }

                Section("关于") {
                    NavigationLink {
                        FeedbackView().environment(auth)
                    } label: {
                        Label("意见反馈", systemImage: "envelope")
                    }
                    NavigationLink {
                        LegalDocumentView(kind: "terms", fallbackTitle: "用户协议")
                    } label: {
                        Label("用户协议", systemImage: "doc.text")
                    }
                    NavigationLink {
                        LegalDocumentView(kind: "privacy", fallbackTitle: "隐私政策")
                    } label: {
                        Label("隐私政策", systemImage: "lock.shield")
                    }
                    row("版本", icon: "info.circle", value: AppVersion.display)
                }

                Section {
                    Button(action: onDeleteAccount) {
                        Label("注销账号", systemImage: "trash")
                            .foregroundStyle(UniColor.danger)
                    }
                    .disabled(isBusy)
                } footer: {
                    Text("注销后账号会被停用，个人资料会被匿名化，且无法恢复。")
                }

                Section {
                    Button(role: .destructive, action: onLogout) {
                        Text("退出登录")
                    }
                    .disabled(isBusy)
                }
            }
            .uniFont(15)
            .scrollContentBackground(.hidden)
            .background(UniColor.paper)
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
            .task { await refreshNotificationStatus(); refreshCacheSize() }
            .onChange(of: scenePhase) { _, phase in
                // 从系统设置改完通知权限回来时刷新状态。
                if phase == .active { Task { await refreshNotificationStatus() } }
            }
            .sheet(isPresented: $showGrowthBackground) {
                GrowthBackgroundSheet(profile: auth.profile) {}
                    .environment(auth)
            }
        }
    }

    private func row(_ title: String, icon: String, value: String) -> some View {
        HStack {
            // 和 NavigationLink 行一致：图标蓝色、文字墨色。
            Label {
                Text(title).foregroundStyle(UniColor.ink)
            } icon: {
                Image(systemName: icon).foregroundStyle(UniColor.blue)
            }
            Spacer()
            Text(value).foregroundStyle(UniColor.tertiary)
        }
        .contentShape(Rectangle())
    }

    private var notificationLabel: String {
        switch notificationStatus {
        case .authorized, .provisional, .ephemeral: return "已开启"
        case .denied: return "已关闭"
        case .notDetermined: return "未设置"
        default: return ""
        }
    }

    private func refreshNotificationStatus() async {
        notificationStatus = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    private func openNotificationSettings() {
        if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    private func refreshCacheSize() {
        cacheBytes = URLCache.shared.currentDiskUsage + URLCache.shared.currentMemoryUsage
    }
}

/// App 版本，设置页展示，也随意见反馈提交。
enum AppVersion {
    static var display: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "-"
        let build = info?["CFBundleVersion"] as? String ?? "-"
        return "\(version) (\(build))"
    }

    /// 设备型号与系统版本，如「iPhone15,2 · iOS 26.5」。
    static var device: String {
        var system = utsname()
        uname(&system)
        let model = withUnsafeBytes(of: &system.machine) { buffer in
            String(decoding: buffer.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
        return "\(model) · iOS \(UIDevice.current.systemVersion)"
    }
}

/// 我拉黑的人。解除后对方重新能看到我的内容，但之前因拉黑取消的关注不会恢复。
struct BlockedUsersView: View {
    @Environment(AuthStore.self) private var auth
    @State private var users: [BlockedUser] = []
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var busyUID: String?
    @State private var confirming: BlockedUser?

    var body: some View {
        List {
            if isLoading && users.isEmpty {
                UniLoadingState("加载中…").listRowBackground(Color.clear)
            } else if let errorMessage, users.isEmpty {
                UniErrorState(errorMessage) { Task { await load() } }.listRowBackground(Color.clear)
            } else if users.isEmpty {
                UniEmptyState(icon: "hand.raised", title: "黑名单是空的",
                              message: "在对方主页的菜单里可以拉黑；拉黑后双方互相看不到内容，也不能私信")
                    .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(users) { user in
                        HStack(spacing: 12) {
                            UniAvatar(url: user.avatarUrl, name: user.nickname, size: 40, shape: .circle)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(user.nickname).uniFont(15, weight: .semibold).foregroundStyle(UniColor.ink)
                                if let blockedAt = user.blockedAt {
                                    Text("拉黑于 " + RelativeTime.text(from: blockedAt))
                                        .uniFont(11).foregroundStyle(UniColor.tertiary)
                                }
                            }
                            Spacer()
                            Button {
                                confirming = user
                            } label: {
                                Group {
                                    if busyUID == user.publicUid { ProgressView() } else { Text("解除") }
                                }
                                .uniFont(12, weight: .semibold)
                                .foregroundStyle(UniColor.ink)
                                .frame(minWidth: 60, minHeight: 32)
                                .background(UniColor.mist, in: Capsule())
                                .frame(minHeight: 44)
                            }
                            .buttonStyle(.plain)
                            .disabled(busyUID != nil)
                        }
                    }
                } footer: {
                    Text("解除后不会自动恢复之前的关注关系。")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(UniColor.paper)
        .navigationTitle("黑名单")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task { await load() }
        .confirmationDialog("解除对 \(confirming?.nickname ?? "") 的拉黑？",
                            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                            titleVisibility: .visible) {
            Button("解除拉黑") { if let user = confirming { Task { await unblock(user) } } }
            Button("取消", role: .cancel) {}
        }
    }

    private func load() async {
        let owner = auth.profile?.id
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let result = try await APIClient.shared.blockedUsers()
            guard auth.profile?.id == owner else { return }
            users = result
        } catch {
            guard auth.profile?.id == owner else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "加载失败，请重试"
        }
    }

    private func unblock(_ user: BlockedUser) async {
        busyUID = user.publicUid
        defer { busyUID = nil }
        do {
            _ = try await APIClient.shared.setBlocked(uid: user.publicUid, blocked: false)
            users.removeAll { $0.publicUid == user.publicUid }
            NotificationCenter.default.post(name: .unipoRelationshipsChanged, object: nil)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "操作失败，请重试"
        }
    }
}

/// 意见反馈：随附 App 版本和设备型号，不附账号以外的个人信息。
struct FeedbackView: View {
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool
    @State private var category: FeedbackCategory = .bug
    @State private var content = ""
    @State private var isSending = false
    @State private var errorMessage: String?
    @State private var sent = false

    private var trimmed: String { content.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSend: Bool { trimmed.count >= 5 && trimmed.count <= 1000 && !isSending }

    var body: some View {
        List {
            Section("类型") {
                Picker("类型", selection: $category) {
                    ForEach(FeedbackCategory.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
            Section {
                TextField(placeholder, text: $content, axis: .vertical)
                    .lineLimit(6...12)
                    .focused($focused)
                HStack {
                    Spacer()
                    Text("\(trimmed.count)/1000")
                        .uniFont(11)
                        .foregroundStyle(trimmed.count > 1000 ? UniColor.danger : UniColor.tertiary)
                }
            } header: {
                Text("描述")
            } footer: {
                Text("会一起提交 App 版本 \(AppVersion.display) 和设备型号，方便我们定位问题。")
            }
        }
        .uniFont(15)
        .scrollContentBackground(.hidden)
        .background(UniColor.paper)
        .navigationTitle("意见反馈")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if isSending { ProgressView() } else {
                    Button("提交") { Task { await send() } }
                        .fontWeight(.semibold)
                        .disabled(!canSend)
                }
            }
        }
        .onAppear { focused = true }
        .alert("已收到你的反馈", isPresented: $sent) {
            Button("好的") { dismiss() }
        } message: {
            Text("谢谢！我们会认真看每一条。")
        }
        .alert("提交失败", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("知道了", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private var placeholder: String {
        switch category {
        case .bug: return "哪里不好用？在哪个页面、做了什么操作（至少 5 个字）"
        case .suggestion: return "希望 UniPO 增加或改进什么"
        case .content: return "哪条内容有问题，或者你希望看到什么内容"
        case .other: return "想对我们说的话"
        }
    }

    private func send() async {
        guard canSend else { return }
        focused = false
        isSending = true
        defer { isSending = false }
        do {
            _ = try await APIClient.shared.submitFeedback(category: category, content: trimmed,
                                                          appVersion: AppVersion.display, device: AppVersion.device)
            sent = true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "请稍后重试"
        }
    }
}

/// 用户协议 / 隐私政策。正文由服务端提供，登录页和设置页共用。
struct LegalDocumentView: View {
    let kind: String
    let fallbackTitle: String

    @State private var document: LegalDocument?
    @State private var errorMessage: String?

    var body: some View {
        ScrollView {
            if let document {
                VStack(alignment: .leading, spacing: 12) {
                    Text(document.version == document.effectiveDate
                         ? "\(document.effectiveDate) 起生效"
                         : "版本 \(document.version) · \(document.effectiveDate) 起生效")
                        .uniFont(12)
                        .foregroundStyle(UniColor.tertiary)
                    ForEach(Array(blocks(document.content).enumerated()), id: \.offset) { _, block in
                        blockView(block)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            } else if let errorMessage {
                UniErrorState(errorMessage) { Task { await load() } }
                    .padding(.top, 40)
            } else {
                UniLoadingState("加载中…").padding(.top, 40)
            }
        }
        .background(UniColor.paper)
        .navigationTitle(document?.title ?? fallbackTitle)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private enum Block { case heading(String), bullet(String), paragraph(String) }

    /// 只识别「## 标题」「- 列表」和普通段落三种行，行内的 **加粗** 交给 AttributedString。
    private func blocks(_ text: String) -> [Block] {
        text.split(separator: "\n", omittingEmptySubsequences: true).map { raw in
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("## ") { return .heading(String(line.dropFirst(3))) }
            if line.hasPrefix("- ") { return .bullet(String(line.dropFirst(2))) }
            return .paragraph(line)
        }
    }

    @ViewBuilder
    private func blockView(_ block: Block) -> some View {
        switch block {
        case .heading(let text):
            Text(text)
                .uniFont(17, weight: .bold)
                .foregroundStyle(UniColor.ink)
                .padding(.top, 10)
        case .bullet(let text):
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("•").foregroundStyle(UniColor.tertiary)
                Text(inline(text)).foregroundStyle(UniColor.inkSoft)
            }
            .uniFont(14)
            .lineSpacing(4)
        case .paragraph(let text):
            Text(inline(text))
                .uniFont(14)
                .foregroundStyle(UniColor.inkSoft)
                .lineSpacing(4)
        }
    }

    private func inline(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text)) ?? AttributedString(text)
    }

    private func load() async {
        errorMessage = nil
        do {
            document = try await APIClient.shared.legalDocument(kind: kind)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "加载失败，请重试"
        }
    }
}
