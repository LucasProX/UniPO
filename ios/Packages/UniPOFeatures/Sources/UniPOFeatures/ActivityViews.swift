import SwiftUI
import UniPOCore
import UniPOUI

// MARK: - 卡片

/// 活动卡片：首页横向条用紧凑版，列表用完整版。
struct ActivityCard: View {
    let activity: ActivityView
    var compact = false
    /// 首页横条的卡宽随字号放大，最宽 320，避免大字体下奖励和状态被挤成竖排。
    @ScaledMetric(relativeTo: .body) private var compactWidth: CGFloat = 240

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            cover
            VStack(alignment: .leading, spacing: 6) {
                Text(activity.title)
                    .uniFont(compact ? 15 : 17, weight: .bold)
                    .foregroundStyle(UniColor.ink)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Label(ActivityTime.range(activity.startsAt, activity.endsAt), systemImage: "calendar")
                    .uniFont(11, weight: .semibold)
                    .foregroundStyle(UniColor.secondary)
                    .lineLimit(1)
                Label(activity.location, systemImage: "mappin.and.ellipse")
                    .uniFont(11, weight: .semibold)
                    .foregroundStyle(UniColor.secondary)
                    .lineLimit(1)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 6) {
                        if activity.hasReward { RewardChips(activity: activity) }
                        Spacer(minLength: 0)
                        statusTag
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        if activity.hasReward { RewardChips(activity: activity) }
                        statusTag
                    }
                }
            }
            .padding(.horizontal, compact ? 12 : 14)
            .padding(.bottom, compact ? 12 : 14)
        }
        .frame(width: compact ? min(compactWidth, 320) : nil, alignment: .leading)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous).stroke(UniColor.mist, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    private var cover: some View {
        ZStack(alignment: .topLeading) {
            if let url = activity.coverUrl.flatMap(AppConfig.absoluteURL) {
                RemoteImage(url: url) { phase in
                    if case .success(let image) = phase { image.resizable().scaledToFill() } else { placeholder }
                }
            } else {
                placeholder
            }
            HStack(spacing: 6) {
                Text(activity.categoryLabel)
                Text("·")
                Text(activity.organizerName).lineLimit(1)
            }
            .uniFont(10, weight: .semibold)
            .foregroundStyle(.white)
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(.black.opacity(0.35), in: Capsule())
            .padding(10)
        }
        .frame(height: compact ? 96 : 132)
        .frame(maxWidth: .infinity)
        .clipShape(UnevenRoundedRectangle(topLeadingRadius: UniRadius.card, topTrailingRadius: UniRadius.card,
                                          style: .continuous))
    }

    private var placeholder: some View {
        LinearGradient(colors: [UniColor.ink, UniColor.blue], startPoint: .topLeading, endPoint: .bottomTrailing)
            .overlay(alignment: .bottomTrailing) {
                Image(systemName: ActivityIcon.symbol(activity.category))
                    .uniFont(40, weight: .semibold)
                    .foregroundStyle(.white.opacity(0.22))
                    .padding(12)
            }
    }

    @ViewBuilder
    private var statusTag: some View {
        let text: String = {
            if activity.phase == "CANCELLED" { return "已取消" }
            if activity.checkedIn { return "已签到" }
            if activity.isPending { return "待审核" }
            if activity.isRejected { return "未通过" }
            if activity.isRegistered { return activity.checkinOpen ? "可签到" : "已报名" }
            if activity.isWaitlisted { return "候补中" }
            if activity.phase == "OPEN", activity.isFull { return activity.waitlistEnabled ? "可候补" : "已满" }
            return activity.phaseLabel
        }()
        Text(text)
            .uniFont(10, weight: .semibold)
            .lineLimit(1)
            .fixedSize()
            .foregroundStyle(activity.isRegistered ? UniColor.blue : UniColor.secondary)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background((activity.isRegistered ? UniColor.blue : UniColor.mist).opacity(activity.isRegistered ? 0.1 : 0.8),
                        in: Capsule())
    }
}

struct RewardChips: View {
    let activity: ActivityView

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 4) { chips }
            VStack(alignment: .leading, spacing: 4) { chips }
        }
    }

    @ViewBuilder
    private var chips: some View {
        if activity.rewardXp > 0 { chip("+\(activity.rewardXp) 经验", "bolt.fill") }
        if activity.rewardCoins > 0 { chip("+\(activity.rewardCoins) 金币", "circle.hexagongrid.fill") }
    }

    private func chip(_ text: String, _ icon: String) -> some View {
        // 不用 Label：在 List 行里自动样式可能只显示图标，奖励数字就丢了。
        HStack(spacing: 3) {
            Image(systemName: icon)
            Text(text)
        }
            .uniFont(10, weight: .semibold)
            .foregroundStyle(UniColor.gold)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(UniColor.gold.opacity(0.12), in: Capsule())
    }
}

enum ActivityIcon {
    static func symbol(_ category: String) -> String {
        switch category {
        case "LECTURE": return "mic.fill"
        case "COMPETITION": return "trophy.fill"
        case "VOLUNTEER": return "hands.sparkles.fill"
        case "SPORTS": return "figure.run"
        case "CULTURE": return "music.note"
        case "CLASS": return "person.3.fill"
        default: return "sparkles"
        }
    }
}

// MARK: - 列表

struct ActivityListView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    var initialTab = "recommend"

    @State private var store = ActivityListStore()
    @State private var tab = "recommend"

    private var tabs: [(String, String)] {
        if initialTab == "organized" { return [("organized", "我发布的")] }
        return auth.isAuthenticated
            ? [("recommend", "全部"), ("week", "本周"), ("upcoming", "我报名的"), ("history", "参加过")]
            : [("recommend", "全部"), ("week", "本周")]
    }

    var body: some View {
        NavigationStack {
            List {
                if tabs.count > 1 {
                    Picker("活动列表", selection: $tab) {
                        ForEach(tabs, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                }

                if store.isLoading && store.items.isEmpty {
                    UniLoadingState("加载活动…").listRowSeparator(.hidden).listRowBackground(Color.clear)
                } else if let error = store.errorMessage, store.items.isEmpty {
                    UniErrorState(error) { Task { await store.refresh() } }
                        .listRowSeparator(.hidden).listRowBackground(Color.clear)
                } else if store.items.isEmpty {
                    UniEmptyState(icon: "calendar", title: emptyText)
                        .listRowSeparator(.hidden).listRowBackground(Color.clear)
                } else {
                    ForEach(store.items) { activity in
                        NavigationLink(value: activity) { ActivityCard(activity: activity) }
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                            .onAppear {
                                if activity.id == store.items.last?.id { Task { await store.loadMore() } }
                            }
                    }
                    if store.isLoadingMore {
                        ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden).listRowBackground(Color.clear)
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(UniColor.paper)
            .refreshable { await store.refresh() }
            .navigationTitle(initialTab == "organized" ? "我发布的活动" : "校园活动")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: ActivityView.self) { activity in
                ActivityDetailView(activityID: activity.id, initial: activity, showsClose: false) { store.replace($0) }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("关闭") { dismiss() } }
            }
            .task { tab = initialTab; await store.refresh(tab: initialTab) }
            .onChange(of: tab) { _, value in Task { await store.refresh(tab: value) } }
        }
    }

    private var emptyText: String {
        switch tab {
        case "upcoming": return "还没有报名的活动"
        case "history": return "还没有参加过的活动"
        case "organized": return "还没有发布过活动"
        case "week": return "本周没有活动"
        default: return "暂时没有活动"
        }
    }
}

// MARK: - 详情

struct ActivityDetailView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    let activityID: Int64
    /// 作为弹窗根页面时显示「关闭」；在列表里推入时由列表负责关闭。
    var showsClose = true
    var onChange: (ActivityView) -> Void = { _ in }

    @State private var activity: ActivityView?
    @State private var loadError: String?
    @State private var actionError: String?
    @State private var isWorking = false
    @State private var confirmCancel = false
    @State private var checkin: CheckinTarget?
    @State private var showOrganizerCode = false
    @State private var showRoster = false
    @State private var showEdit = false
    @State private var showCancelActivity = false
    @State private var cancelReason = ""

    init(activityID: Int64, initial: ActivityView? = nil, showsClose: Bool = true,
         onChange: @escaping (ActivityView) -> Void = { _ in }) {
        self.activityID = activityID
        self.showsClose = showsClose
        self.onChange = onChange
        _activity = State(initialValue: initial)
    }

    var body: some View {
        ScrollView {
            if let activity {
                content(activity)
            } else if let loadError {
                VStack(spacing: 12) {
                    Text(loadError).uniFont(14).foregroundStyle(UniColor.secondary)
                    Button("重新加载") { Task { await load() } }.foregroundStyle(UniColor.blue)
                }
                .padding(.top, 120)
            } else {
                UniLoadingState("加载活动…").padding(.top, 120)
            }
        }
        .background(UniColor.paper)
        .safeAreaInset(edge: .bottom) { if let activity { actionBar(activity) } }
        .navigationTitle("活动详情")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsClose {
                ToolbarItem(placement: .topBarTrailing) { Button("关闭") { dismiss() } }
            }
            if let activity, activity.canManage {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button("签到码", systemImage: "qrcode") { showOrganizerCode = true }
                        Button("报名名单", systemImage: "person.2") { showRoster = true }
                        if activity.phase == "OPEN" || activity.phase == "CLOSED" {
                            Button("编辑活动", systemImage: "pencil") { showEdit = true }
                        }
                        if activity.phase != "ENDED" && activity.phase != "CANCELLED" {
                            Button("取消活动", systemImage: "xmark.circle", role: .destructive) {
                                cancelReason = ""
                                showCancelActivity = true
                            }
                        }
                    } label: {
                        Label("管理", systemImage: "slider.horizontal.3").frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel("管理活动")
                }
            }
        }
        .task(id: "\(auth.authenticationRevision):\(auth.profile?.id ?? 0)") { await load() }
        .confirmationDialog("取消报名？", isPresented: $confirmCancel, titleVisibility: .visible) {
            Button("取消报名", role: .destructive) { Task { await perform(cancel: true) } }
            Button("再想想", role: .cancel) {}
        } message: {
            Text(activity?.isRegistered == true ? "名额会让给候补的同学，之后再报名需要重新排队。" : "将退出候补队列。")
        }
        .sheet(item: $checkin) { target in
            CheckinSheet(target: target) { updated in
                activity = updated
                onChange(updated)
            }
            .environment(auth)
        }
        .sheet(isPresented: $showOrganizerCode, onDismiss: { Task { await load() } }) {
            if let activity { OrganizerCheckinView(activity: activity) }
        }
        .sheet(isPresented: $showRoster, onDismiss: { Task { await load() } }) {
            if let activity { ActivityRosterView(activity: activity) }
        }
        .sheet(isPresented: $showEdit) {
            if let activity {
                EditActivityView(activity: activity) { updated in
                    self.activity = updated
                    onChange(updated)
                }
            }
        }
        .alert("取消活动", isPresented: $showCancelActivity) {
            TextField("取消原因（会通知所有报名者）", text: $cancelReason)
            Button("确认取消活动", role: .destructive) { Task { await cancelWholeActivity() } }
            Button("再想想", role: .cancel) {}
        } message: {
            Text("取消后不能恢复；已签到的同学奖励保留。")
        }
        .fullScreenCover(isPresented: Binding(get: { auth.loginRequested }, set: { auth.loginRequested = $0 })) {
            LoginView().environment(auth)
        }
    }

    private func content(_ activity: ActivityView) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            if activity.phase == "CANCELLED" {
                Label("活动已取消" + (activity.cancelReason.map { "：\($0)" } ?? ""), systemImage: "xmark.octagon.fill")
                    .uniFont(13, weight: .semibold).foregroundStyle(UniColor.danger)
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(UniColor.danger.opacity(0.08), in: RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
            }
            header(activity)
            infoCard(activity)
            if activity.capacity != nil { capacityCard(activity) }
            section("活动介绍", activity.description)
            if let notice = activity.notice, !notice.isEmpty { section("报名须知", notice) }
            if !activity.recentAttendees.isEmpty { attendees(activity) }
            if activity.phase != "CANCELLED" {
                LinkedPostsSection(
                    title: "活动帖子",
                    emptyText: "还没有人发这场活动的帖子，来记录第一篇吧",
                    composeTitle: "发帖",
                    link: .activity(PostLinkRef(id: activity.id, title: activity.title))
                )
            }
        }
        .padding(18)
        .padding(.bottom, 20)
    }

    private func header(_ activity: ActivityView) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Text(activity.categoryLabel)
                    .uniFont(11, weight: .semibold).foregroundStyle(UniColor.blue)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(UniColor.blue.opacity(0.1), in: Capsule())
                Text(activity.phaseLabel).uniFont(11, weight: .semibold).foregroundStyle(UniColor.secondary)
            }
            Text(activity.title)
                .uniFont(24, weight: .heavy)
                .foregroundStyle(UniColor.ink)
            HStack(spacing: 8) {
                if let organizer = activity.organizer {
                    UserProfileLink(uid: organizer.uid, name: organizer.nickname) {
                        UniAvatar(url: organizer.avatarUrl, name: activity.organizerName, size: 28, shape: .circle)
                    }
                }
                Text(activity.organizerName).uniFont(13, weight: .semibold).foregroundStyle(UniColor.ink)
                Image(systemName: "checkmark.seal.fill").uniFont(12).foregroundStyle(UniColor.blue)
                    .accessibilityLabel("认证组织者")
            }
            if activity.hasReward {
                HStack(spacing: 6) {
                    RewardChips(activity: activity)
                    Text("签到后发放").uniFont(10, weight: .semibold).foregroundStyle(UniColor.tertiary)
                }
            }
        }
    }

    private func infoCard(_ activity: ActivityView) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            infoRow("calendar", "时间", ActivityTime.range(activity.startsAt, activity.endsAt))
            infoRow("mappin.and.ellipse", "地点", activity.location)
            infoRow("clock.badge.exclamationmark", "报名截止", ActivityTime.short(activity.signupDeadline))
            infoRow("person.2", "面向", activity.audienceLabel)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
    }

    private func infoRow(_ icon: String, _ title: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon).uniFont(14).foregroundStyle(UniColor.blue).frame(width: 20)
            Text(title).uniFont(12, weight: .semibold).foregroundStyle(UniColor.secondary).frame(width: 56, alignment: .leading)
            Text(value).uniFont(14).foregroundStyle(UniColor.ink)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }

    private func capacityCard(_ activity: ActivityView) -> some View {
        let capacity = max(1, activity.capacity ?? 1)
        let ratio = min(1, Double(activity.registeredCount) / Double(capacity))
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("名额").uniFont(12, weight: .semibold).foregroundStyle(UniColor.secondary)
                Spacer()
                Text("\(activity.registeredCount)/\(capacity)").uniFont(12, weight: .semibold).foregroundStyle(UniColor.ink)
                if activity.waitlistCount > 0 {
                    Text("候补 \(activity.waitlistCount)").uniFont(11, weight: .semibold).foregroundStyle(UniColor.tertiary)
                }
            }
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(UniColor.mist)
                    Capsule().fill(ratio >= 1 ? UniColor.gold : UniColor.blue).frame(width: proxy.size.width * ratio)
                }
            }
            .frame(height: 6)
        }
        .padding(16)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private func section(_ title: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).uniFont(16, weight: .bold).foregroundStyle(UniColor.ink)
            Text(text).uniFont(14).foregroundStyle(UniColor.inkSoft).lineSpacing(4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func attendees(_ activity: ActivityView) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("已报名 \(activity.registeredCount) 人").uniFont(16, weight: .bold).foregroundStyle(UniColor.ink)
            HStack(spacing: -8) {
                ForEach(activity.recentAttendees, id: \.uid) { person in
                    UniAvatar(url: person.avatarUrl, name: person.nickname, size: 32, shape: .circle)
                        .overlay(Circle().stroke(Color.white, lineWidth: 2))
                }
            }
        }
    }

    @ViewBuilder
    private func actionBar(_ activity: ActivityView) -> some View {
        VStack(spacing: 6) {
            if let actionError {
                Text(actionError).uniFont(12, weight: .semibold).foregroundStyle(UniColor.danger)
            } else if let hint = hint(activity) {
                Text(hint).uniFont(12, weight: .semibold).foregroundStyle(UniColor.secondary).multilineTextAlignment(.center)
            }
            Button {
                Task { await primaryAction(activity) }
            } label: {
                Group {
                    if isWorking { ProgressView().tint(.white) } else { Text(primaryTitle(activity)) }
                }
                .uniFont(16, weight: .semibold)
                .foregroundStyle(primaryEnabled(activity) ? .white : UniColor.tertiary)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(primaryEnabled(activity) ? primaryColor(activity) : UniColor.mist,
                            in: RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(!primaryEnabled(activity) || isWorking)
            if activity.isRegistered, activity.checkinOpen, !activity.checkedIn,
               activity.phase == "OPEN" || activity.phase == "CLOSED" {
                Button("取消报名") { confirmCancel = true }
                    .uniFont(12, weight: .semibold).foregroundStyle(UniColor.secondary)
                    .frame(minHeight: 36)
            }
        }
        .padding(.horizontal, 18).padding(.top, 10).padding(.bottom, 8)
        .background(UniColor.paper.opacity(0.96).ignoresSafeArea(edges: .bottom))
    }

    private func primaryTitle(_ activity: ActivityView) -> String {
        if !auth.isAuthenticated { return "登录后报名" }
        if activity.checkedIn {
            return "已签到 · " + (activity.checkedInAt.map { ActivityTime.short($0) } ?? "")
        }
        if activity.checkinRevoked { return "签到已被撤销" }
        if activity.isRejected { return "报名未通过" }
        if activity.isPending { return "待审核 · 取消报名" }
        if activity.isRegistered, activity.checkinOpen { return "去签到" }
        if activity.isRegistered { return activity.phase == "OPEN" || activity.phase == "CLOSED" ? "已报名 · 取消报名" : "已报名" }
        if activity.isWaitlisted { return "候补第 \(activity.myWaitlistPosition ?? 1) 位 · 退出候补" }
        switch activity.phase {
        case "OPEN":
            if !activity.eligible { return "不在报名范围" }
            if activity.requireApproval { return "申请报名" }
            if activity.isFull { return activity.waitlistEnabled ? "加入候补" : "名额已满" }
            return "立即报名"
        case "CLOSED": return "报名已截止"
        case "ONGOING": return "活动进行中"
        case "CANCELLED": return "活动已取消"
        default: return "活动已结束"
        }
    }

    private func primaryEnabled(_ activity: ActivityView) -> Bool {
        if !auth.isAuthenticated { return true }
        if activity.checkedIn || activity.checkinRevoked || activity.isRejected { return false }
        if activity.isPending { return activity.phase == "OPEN" || activity.phase == "CLOSED" }
        if activity.isRegistered, activity.checkinOpen { return true }
        if activity.isRegistered || activity.isWaitlisted {
            return activity.phase == "OPEN" || activity.phase == "CLOSED"
        }
        return activity.phase == "OPEN" && activity.eligible && (!activity.isFull || activity.waitlistEnabled)
    }

    private func primaryColor(_ activity: ActivityView) -> Color {
        if activity.isRegistered, activity.checkinOpen { return UniColor.blue }
        return activity.isRegistered || activity.isWaitlisted ? UniColor.inkSoft : UniColor.blue
    }

    private func hint(_ activity: ActivityView) -> String? {
        if auth.isAuthenticated, !activity.eligible, !activity.isRegistered, !activity.isWaitlisted,
           activity.phase == "OPEN" {
            return activity.ineligibleReason
        }
        if activity.checkinRevoked { return "组织者撤销了你的签到，奖励已扣回" }
        if activity.isPending { return "组织者审核后会通知你" }
        if activity.requireApproval, activity.myStatus == "NONE", activity.phase == "OPEN" { return "这个活动需要组织者审核报名" }
        if activity.checkedIn, activity.hasReward { return "奖励已在签到时发放" }
        if activity.isRegistered, activity.checkinOpen { return "到现场输入签到码或扫二维码" + (activity.hasReward ? "，签到即得奖励" : "") }
        if activity.isRegistered, activity.phase != "ENDED" { return "开始前 30 分钟开放签到" + (activity.hasReward ? "，奖励随签到发放" : "") }
        return nil
    }

    private func primaryAction(_ activity: ActivityView) async {
        guard auth.isAuthenticated else { auth.requireLogin(); return }
        if activity.isRegistered, activity.checkinOpen, !activity.checkedIn {
            checkin = CheckinTarget(activityID: activity.id)
            return
        }
        if activity.isRegistered || activity.isWaitlisted || activity.isPending { confirmCancel = true; return }
        await perform(cancel: false)
    }

    private func cancelWholeActivity() async {
        let reason = cancelReason.trimmingCharacters(in: .whitespaces)
        guard !reason.isEmpty else { actionError = "取消活动需要填写原因"; return }
        isWorking = true
        defer { isWorking = false }
        do {
            let result = try await APIClient.shared.cancelActivity(id: activityID, reason: reason)
            activity = result
            onChange(result)
        } catch {
            actionError = (error as? LocalizedError)?.errorDescription ?? "取消失败，请重试"
        }
    }

    private func perform(cancel: Bool) async {
        let userID = auth.profile?.id
        isWorking = true
        actionError = nil
        defer { isWorking = false }
        do {
            let result = cancel
                ? try await APIClient.shared.cancelActivityRegistration(id: activityID)
                : try await APIClient.shared.registerActivity(id: activityID)
            guard auth.profile?.id == userID else { return }
            activity = result
            onChange(result)
        } catch {
            guard auth.profile?.id == userID else { return }
            actionError = (error as? LocalizedError)?.errorDescription ?? (cancel ? "取消失败，请重试" : "报名失败，请重试")
            await load()
        }
    }

    private func load() async {
        let userID = auth.profile?.id
        do {
            let result = try await APIClient.shared.activity(id: activityID)
            guard auth.profile?.id == userID else { return }
            activity = result
            loadError = nil
            onChange(result)
        } catch {
            guard auth.profile?.id == userID else { return }
            if activity == nil { loadError = (error as? LocalizedError)?.errorDescription ?? "活动加载失败" }
        }
    }
}
