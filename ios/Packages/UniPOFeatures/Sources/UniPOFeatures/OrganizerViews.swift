import SwiftUI
import UniPOCore
import UniPOUI

// MARK: - 报名名单

/// 组织者管理报名：审核、补签、撤销作弊签到。所有动作以服务端返回为准，失败保留原状态并提示。
struct ActivityRosterView: View {
    @Environment(\.dismiss) private var dismiss
    let activity: ActivityView

    @State private var filter = "all"
    @State private var page: ActivityRosterPage?
    @State private var entries: [ActivityRosterEntry] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var busyID: Int64?
    @State private var revokeTarget: ActivityRosterEntry?
    @State private var revokeReason = ""

    private var filters: [(String, String)] {
        var items = [("all", "全部"), ("registered", "已报名"), ("waitlisted", "候补"), ("checked_in", "已签到")]
        if activity.requireApproval { items.insert(("pending", "待审核"), at: 1) }
        return items
    }

    var body: some View {
        NavigationStack {
            List {
                if let page { summary(page).listRowBackground(Color.clear) }
                Picker("名单", selection: $filter) {
                    ForEach(filters, id: \.0) { Text($0.1).tag($0.0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                if let errorMessage {
                    Text(errorMessage).uniFont(12, weight: .semibold).foregroundStyle(UniColor.danger)
                }
                if entries.isEmpty {
                    Text(isLoading ? "加载中…" : "暂无").uniFont(13).foregroundStyle(UniColor.secondary)
                        .frame(maxWidth: .infinity).padding(.vertical, 30)
                }
                ForEach(entries) { entry in row(entry) }
                if page?.hasMore == true {
                    Button("加载更多") { Task { await load(more: true) } }.frame(maxWidth: .infinity)
                }
            }
            .navigationTitle("报名名单")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("关闭") { dismiss() } } }
            .refreshable { await load(more: false) }
            .task(id: filter) { await load(more: false) }
            .alert("撤销签到", isPresented: Binding(get: { revokeTarget != nil }, set: { if !$0 { revokeTarget = nil } })) {
                TextField("原因（会告知对方）", text: $revokeReason)
                Button("撤销并扣回奖励", role: .destructive) {
                    guard let target = revokeTarget else { return }
                    Task { await perform(target, "revoke-checkin", reason: revokeReason) }
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("签到后 24 小时内可撤销，经验和金币按实际发放的数额扣回，对方不能再次签到。")
            }
        }
    }

    private func summary(_ page: ActivityRosterPage) -> some View {
        HStack(spacing: 0) {
            stat("已报名", page.registeredCount)
            stat("已签到", page.checkedInCount)
            stat("候补", page.waitlistCount)
            if activity.requireApproval { stat("待审核", page.pendingCount) }
        }
        .padding(.vertical, 10)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
    }

    private func stat(_ title: String, _ value: Int) -> some View {
        VStack(spacing: 2) {
            Text("\(value)").uniFont(20, weight: .bold).monospacedDigit().foregroundStyle(UniColor.ink)
            Text(title).uniFont(10, weight: .semibold).foregroundStyle(UniColor.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func row(_ entry: ActivityRosterEntry) -> some View {
        HStack(spacing: 12) {
            UniAvatar(url: entry.avatarUrl, name: entry.nickname, size: 40, shape: .circle)
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.nickname).uniFont(15, weight: .semibold).foregroundStyle(UniColor.ink)
                Text(detail(entry)).uniFont(11, weight: .semibold).foregroundStyle(UniColor.secondary).lineLimit(2)
            }
            Spacer(minLength: 6)
            if busyID == entry.id {
                ProgressView()
            } else {
                actions(entry)
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private func actions(_ entry: ActivityRosterEntry) -> some View {
        if entry.status == "PENDING" {
            HStack(spacing: 8) {
                Button("拒绝") { Task { await perform(entry, "reject") } }
                    .buttonStyle(.bordered).tint(UniColor.secondary)
                Button("通过") { Task { await perform(entry, "approve") } }
                    .buttonStyle(.borderedProminent).tint(UniColor.blue)
            }
            .uniFont(12, weight: .semibold)
        } else if entry.checkedInAt != nil {
            Menu {
                if entry.revocable {
                    Button("撤销签到", systemImage: "arrow.uturn.backward", role: .destructive) {
                        revokeReason = ""
                        revokeTarget = entry
                    }
                }
            } label: {
                Label("已签到", systemImage: "checkmark.circle.fill").uniFont(12, weight: .semibold).foregroundStyle(UniColor.blue)
                    .frame(minHeight: 44)
            }
            .disabled(!entry.revocable)
        } else if entry.status == "REGISTERED", entry.checkinRevokedAt == nil,
                  activity.checkinOpen || activity.phase == "ENDED" {
            Button("补签") { Task { await perform(entry, "checkin") } }
                .buttonStyle(.bordered).tint(UniColor.blue).uniFont(12, weight: .semibold)
        } else {
            Text(statusText(entry)).uniFont(11, weight: .semibold).foregroundStyle(UniColor.tertiary)
        }
    }

    private func detail(_ entry: ActivityRosterEntry) -> String {
        var parts = [entry.grade, entry.major ?? entry.college].compactMap { $0 }.filter { !$0.isEmpty }
        if let at = entry.checkedInAt {
            let method = entry.checkinMethod == "MANUAL" ? "补签" : entry.checkinMethod == "CODE" ? "签到码" : "扫码"
            parts.append("\(ActivityTime.short(at)) \(method) · +\(entry.rewardXpGranted) 经验 +\(entry.rewardCoinsGranted) 金币")
        } else if let reason = entry.revokeReason {
            parts.append("签到已撤销：\(reason)")
        } else {
            parts.append(statusText(entry))
        }
        return parts.joined(separator: " · ")
    }

    private func statusText(_ entry: ActivityRosterEntry) -> String {
        switch entry.status {
        case "REGISTERED": return entry.checkinRevokedAt == nil ? "未签到" : "签到已撤销"
        case "WAITLISTED": return "候补中"
        case "PENDING": return "待审核"
        case "REJECTED": return "未通过"
        default: return "已取消"
        }
    }

    private func load(more: Bool) async {
        isLoading = true
        defer { isLoading = false }
        do {
            let result = try await APIClient.shared.activityRoster(id: activity.id, filter: filter,
                                                                    cursor: more ? page?.nextCursor : nil)
            page = result
            entries = more ? entries + result.items.filter { item in !entries.contains { $0.id == item.id } } : result.items
            errorMessage = nil
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "名单加载失败"
        }
    }

    private func perform(_ entry: ActivityRosterEntry, _ action: String, reason: String? = nil) async {
        if action == "revoke-checkin", reason?.trimmingCharacters(in: .whitespaces).isEmpty != false {
            errorMessage = "撤销签到需要填写原因"
            return
        }
        busyID = entry.id
        defer { busyID = nil }
        do {
            let updated = try await APIClient.shared.rosterAction(activityID: activity.id, registrationID: entry.id,
                                                                  action: action, reason: reason?.trimmingCharacters(in: .whitespaces))
            if let index = entries.firstIndex(where: { $0.id == entry.id }) { entries[index] = updated }
            errorMessage = nil
            await load(more: false)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "操作失败，请重试"
        }
    }
}

// MARK: - 编辑活动

struct EditActivityView: View {
    @Environment(\.dismiss) private var dismiss
    let activity: ActivityView
    let onSaved: (ActivityView) -> Void

    @State private var input: UpdateActivityInput
    @State private var limitCapacity: Bool
    @State private var capacityText: String
    @State private var isSaving = false
    @State private var errorMessage: String?

    init(activity: ActivityView, onSaved: @escaping (ActivityView) -> Void) {
        self.activity = activity
        self.onSaved = onSaved
        _input = State(initialValue: UpdateActivityInput(activity: activity))
        _limitCapacity = State(initialValue: activity.capacity != nil)
        _capacityText = State(initialValue: activity.capacity.map(String.init) ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("基本信息") {
                    TextField("活动标题", text: $input.title)
                    TextField("地点", text: $input.location)
                }
                Section {
                    DatePicker("开始", selection: $input.startsAt, in: Date()...)
                    DatePicker("结束", selection: $input.endsAt, in: input.startsAt...)
                    DatePicker("报名截止", selection: $input.signupDeadline, in: ...input.startsAt)
                } header: {
                    Text("时间")
                } footer: {
                    Text("修改时间或地点会通知所有已报名的同学。")
                }
                Section {
                    Toggle("限制名额", isOn: $limitCapacity)
                    if limitCapacity { TextField("名额", text: $capacityText).keyboardType(.numberPad) }
                } header: {
                    Text("名额")
                } footer: {
                    Text("已报名 \(activity.registeredCount) 人，名额不能少于这个数；扩容后候补按顺序转正。")
                }
                Section("介绍与须知") {
                    TextField("活动介绍", text: $input.description, axis: .vertical).lineLimit(4...10)
                    TextField("报名须知", text: $input.notice, axis: .vertical).lineLimit(2...6)
                }
                if let errorMessage { Section { Text(errorMessage).foregroundStyle(UniColor.danger) } }
            }
            .navigationTitle("编辑活动")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    if isSaving { ProgressView() } else { Button("保存") { Task { await save() } } }
                }
            }
        }
    }

    private func save() async {
        var request = input
        if limitCapacity {
            guard let value = Int(capacityText), value > 0 else { errorMessage = "请填写有效的名额"; return }
            request.capacity = value
        } else {
            request.capacity = nil
        }
        isSaving = true
        defer { isSaving = false }
        do {
            let updated = try await APIClient.shared.updateActivity(id: activity.id, request)
            onSaved(updated)
            dismiss()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "保存失败，请重试"
        }
    }
}

// MARK: - 通知

struct NotificationsView: View {
    @Environment(AuthStore.self) private var auth
    @State private var items: [AppNotification] = []
    @State private var page: AppNotificationPage?
    @State private var errorMessage: String?
    @State private var selected: Int64?
    @State private var openedPost: PostView?
    @State private var postError: String?

    var body: some View {
        List {
            if items.isEmpty {
                Group {
                    if let errorMessage {
                        UniErrorState(errorMessage) { Task { await load() } }
                    } else if page == nil {
                        UniLoadingState("加载通知…")
                    } else {
                        UniEmptyState(icon: "bell", title: "还没有通知",
                                      message: "活动提醒、签到结果、关注的人发的新帖，以及举报和审核结果会出现在这里")
                    }
                }
                .listRowSeparator(.hidden).listRowBackground(Color.clear)
            }
            ForEach(items) { item in
                Button { open(item) } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: icon(item.type))
                            .uniFont(15, weight: .semibold)
                            .foregroundStyle(tint(item.type))
                            .frame(width: 34, height: 34)
                            .background(tint(item.type).opacity(0.1), in: Circle())
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(item.title).uniFont(15, weight: .bold).foregroundStyle(UniColor.ink)
                                if !item.read { Circle().fill(UniColor.danger).frame(width: 7, height: 7) }
                                Spacer()
                                Text(RelativeTime.text(from: item.createdAt)).uniFont(10, weight: .semibold).foregroundStyle(UniColor.tertiary)
                            }
                            Text(item.body).uniFont(13).foregroundStyle(UniColor.inkSoft).multilineTextAlignment(.leading)
                        }
                    }
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(item.activityId == nil && item.postId == nil)
                .listRowBackground(Color.clear)
                .onAppear { if item.id == items.last?.id { Task { await loadMore() } } }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { await load() }
        .task(id: auth.profile?.id) { await load() }
        .sheet(item: Binding(get: { selected.map(ActivityRef.init) }, set: { selected = $0?.id })) { ref in
            NavigationStack { ActivityDetailView(activityID: ref.id) }.environment(auth)
        }
        .sheet(item: $openedPost) { post in
            PostDetailView(post: post).environment(auth)
        }
        .alert("打不开这条帖子", isPresented: Binding(get: { postError != nil }, set: { if !$0 { postError = nil } })) {
            Button("知道了", role: .cancel) {}
        } message: {
            Text(postError ?? "")
        }
    }

    private func open(_ item: AppNotification) {
        if let postId = item.postId {
            Task {
                do { openedPost = try await APIClient.shared.post(id: postId) }
                catch { postError = (error as? LocalizedError)?.errorDescription ?? "帖子可能已删除" }
            }
        } else {
            selected = item.activityId
        }
    }

    private func load() async {
        do {
            let result = try await APIClient.shared.notifications()
            page = result
            items = result.items
            errorMessage = nil
            if result.unreadCount > 0 {
                _ = try? await APIClient.shared.readAllNotifications()
                NotificationCenter.default.post(name: .unipoNotificationsRead, object: nil)
            }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "通知加载失败"
        }
    }

    private func loadMore() async {
        guard page?.hasMore == true, let cursor = page?.nextCursor else { return }
        if let result = try? await APIClient.shared.notifications(cursor: cursor) {
            page = result
            let ids = Set(items.map(\.id))
            items += result.items.filter { !ids.contains($0.id) }
        }
    }

    private func icon(_ type: String) -> String {
        switch type {
        case "REGISTRATION_APPROVED", "WAITLIST_PROMOTED": return "checkmark.circle.fill"
        case "REGISTRATION_REJECTED", "CHECKIN_REVOKED": return "exclamationmark.circle.fill"
        case "ACTIVITY_CANCELLED": return "xmark.circle.fill"
        case "ACTIVITY_CHANGED": return "calendar.badge.exclamationmark"
        case "FOLLOWING_POST": return "square.text.square.fill"
        case "REPORT_RESOLVED": return "checkmark.shield.fill"
        case "CONTENT_REMOVED": return "exclamationmark.triangle.fill"
        case "POST_APPROVED": return "checkmark.seal.fill"
        default: return "bell.fill"
        }
    }

    private func tint(_ type: String) -> Color {
        switch type {
        case "REGISTRATION_REJECTED", "CHECKIN_REVOKED", "ACTIVITY_CANCELLED", "CONTENT_REMOVED": return UniColor.danger
        case "ACTIVITY_CHANGED": return UniColor.gold
        default: return UniColor.blue
        }
    }
}

struct ActivityRef: Identifiable {
    let id: Int64
}

extension Notification.Name {
    /// 通知页打开并标记已读后，底栏角标立即刷新。
    static let unipoNotificationsRead = Notification.Name("unipo.notifications.read")
}
