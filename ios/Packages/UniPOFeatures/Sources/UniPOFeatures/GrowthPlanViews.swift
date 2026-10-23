import SwiftUI
import UniPOCore
import UniPOUI

// MARK: - 本学期清单

/// 成长页「本学期」：报名的活动、当期主线任务和自己的计划放在一张清单里。
/// 活动那一项由签到自动完成，完成度统一以服务端汇总为准。
struct TermPlanSection: View {
    @Environment(AuthStore.self) private var auth
    @Bindable var store: TermPlanStore
    /// 主线任务完成/撤回后，成长页顶部的经验与进度一起刷新。
    var onAchievementChange: (AchievementView) -> Void = { _ in }

    @State private var openedActivity: ActivityRef?
    @State private var browsingActivities = false
    @State private var editor: PersonalTaskEditorTarget?
    @State private var deleteCandidate: PersonalTask?
    @State private var undoCandidate: AchievementTaskView?
    /// 勾选立即生效的本地覆盖；服务端重新拉取后移除，失败时移除即回滚。
    @State private var pendingPersonal: [Int64: Bool] = [:]
    @State private var pendingOfficial: [Int64: Bool] = [:]
    @State private var openedTask: TaskRef?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let plan = store.plan {
                termHeader(plan)
                activitiesBlock(plan)
                if let stage = plan.officialStage { officialBlock(stage) }
                personalBlock(plan)
            } else if store.isLoading {
                UniLoadingState("正在整理本学期清单")
                    .frame(maxWidth: .infinity, minHeight: 160)
            } else {
                retryCard
            }
            if store.plan != nil, let message = store.errorMessage {
                Text(message)
                    .uniFont(12, weight: .semibold)
                    .foregroundStyle(UniColor.danger)
            }
        }
        .task { if store.plan == nil { await store.load() } }
        .sheet(item: $openedActivity, onDismiss: { Task { await store.load(termStart: store.termStart) } }) { ref in
            NavigationStack { ActivityDetailView(activityID: ref.id) }
                .environment(auth)
        }
        .sheet(item: $openedTask, onDismiss: { Task { await store.load(termStart: store.termStart) } }) { ref in
            TaskDetailView(taskID: ref.id) { onAchievementChange($0) }
                .environment(auth)
        }
        .sheet(isPresented: $browsingActivities, onDismiss: { Task { await store.load(termStart: store.termStart) } }) {
            ActivityListView().environment(auth)
        }
        .sheet(item: $editor) { target in
            PersonalTaskEditor(target: target) { input in
                await store.perform {
                    if let task = target.task {
                        try await APIClient.shared.updatePersonalTask(id: task.id, input)
                    } else {
                        try await APIClient.shared.createPersonalTask(input)
                    }
                }
            }
            .presentationDetents([.medium, .large])
        }
        .confirmationDialog("删除这条计划？", isPresented: Binding(
            get: { deleteCandidate != nil }, set: { if !$0 { deleteCandidate = nil } }
        ), titleVisibility: .visible, presenting: deleteCandidate) { task in
            Button("删除", role: .destructive) {
                Task { await store.perform { try await APIClient.shared.deletePersonalTask(id: task.id) } }
            }
            Button("取消", role: .cancel) {}
        } message: { task in
            Text("「\(task.title)」删除后不能恢复。")
        }
        .confirmationDialog("撤回这项完成记录？", isPresented: Binding(
            get: { undoCandidate != nil }, set: { if !$0 { undoCandidate = nil } }
        ), titleVisibility: .visible, presenting: undoCandidate) { task in
            Button("撤回完成", role: .destructive) { Task { await setOfficial(task, completed: false) } }
            Button("取消", role: .cancel) {}
        } message: { task in
            Text("撤回后会同时扣回这项任务的 \(task.xp) 经验。")
        }
    }

    // MARK: 学期头

    private func termHeader(_ plan: TermPlan) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                termArrow("chevron.left", label: "上一个学期", enabled: plan.previousTermStart != nil) {
                    await store.showPrevious()
                }
                VStack(spacing: 2) {
                    Text(plan.termLabel)
                        .uniFont(17, weight: .bold)
                        .foregroundStyle(UniColor.ink)
                    Text(termRange(plan))
                        .uniFont(11, weight: .semibold)
                        .foregroundStyle(UniColor.tertiary)
                }
                .frame(maxWidth: .infinity)
                termArrow("chevron.right", label: "下一个学期", enabled: plan.nextTermStart != nil) {
                    await store.showNext()
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text("已完成 \(plan.summary.done)/\(plan.summary.total)")
                        .uniFont(15, weight: .bold)
                        .foregroundStyle(UniColor.ink)
                    Spacer()
                    if !plan.current {
                        Button("回到本学期") { Task { await store.showCurrent() } }
                            .uniFont(12, weight: .semibold)
                            .foregroundStyle(UniColor.blue)
                            .frame(minHeight: 44)
                    }
                }
                ProgressView(value: plan.summary.fraction)
                    .tint(UniColor.blue)
                    .accessibilityLabel("本学期完成度")
                    .accessibilityValue("\(Int(plan.summary.fraction * 100))%")
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 14) { statPills(plan.summary) }
                    VStack(alignment: .leading, spacing: 6) { statPills(plan.summary) }
                }
            }
        }
        .padding(18)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
            .stroke(UniColor.mist.opacity(0.7), lineWidth: 1))
        .opacity(store.isLoading ? 0.6 : 1)
    }

    private func termArrow(_ icon: String, label: String, enabled: Bool,
                           action: @escaping () async -> Void) -> some View {
        Button { Task { await action() } } label: {
            Image(systemName: icon)
                .uniFont(14, weight: .bold)
                .foregroundStyle(enabled ? UniColor.ink : UniColor.mist)
                .frame(width: 44, height: 44)
                .background(UniColor.paper, in: Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled || store.isLoading)
        .accessibilityLabel(label)
    }

    @ViewBuilder
    private func statPills(_ summary: TermSummary) -> some View {
        statPill("figure.walk", "参加 \(summary.attended) 场")
        if summary.activityXp > 0 { statPill("bolt.fill", "+\(summary.activityXp) 经验") }
        if summary.activityCoins > 0 { statPill("circle.hexagongrid.fill", "+\(summary.activityCoins) 金币") }
    }

    private func statPill(_ icon: String, _ text: String) -> some View {
        Label(text, systemImage: icon)
            .uniFont(11, weight: .semibold)
            .foregroundStyle(UniColor.secondary)
            .lineLimit(1)
            .fixedSize()
    }

    private func termRange(_ plan: TermPlan) -> String {
        "\(GrowthDate.monthDay(plan.termStart)) – \(GrowthDate.monthDay(plan.termEnd))"
    }

    // MARK: 活动

    private func activitiesBlock(_ plan: TermPlan) -> some View {
        PlanBlock(title: "校园活动", caption: "签到后自动完成") {
            if plan.activities.isEmpty {
                PlanEmptyRow(text: plan.current ? "这个学期还没报名活动" : "这个学期没有报名记录",
                             action: plan.current ? ("去看看活动", { browsingActivities = true }) : nil)
            } else {
                ForEach(plan.activities) { item in
                    Button { openedActivity = ActivityRef(id: item.activityId) } label: {
                        ActivityPlanRow(item: item)
                    }
                    .buttonStyle(.plain)
                    if item.id != plan.activities.last?.id { Divider().padding(.leading, 44) }
                }
            }
        }
    }

    // MARK: 主线

    private func officialBlock(_ stage: AchievementStageView) -> some View {
        PlanBlock(title: "主线 · \(stage.title)", caption: "\(stage.completedCount)/\(stage.totalCount)") {
            ForEach(stage.tasks) { task in
                let done = pendingOfficial[task.id] ?? task.completed
                HStack(spacing: 10) {
                    PlanCheckButton(done: done, disabled: store.isMutating,
                                    label: done ? "已完成，点按撤回" : "标记完成") {
                        if done { undoCandidate = task } else { Task { await setOfficial(task, completed: true) } }
                    }
                    Button { openedTask = TaskRef(id: task.id) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                PlanTitle(text: task.title, done: done)
                                if let due = task.dueHint, !due.isEmpty, !done {
                                    Label(due, systemImage: "clock")
                                        .uniFont(10, weight: .semibold)
                                        .foregroundStyle(UniColor.secondary)
                                } else if let description = task.description, !description.isEmpty {
                                    Text(description)
                                        .uniFont(11, weight: .semibold)
                                        .foregroundStyle(UniColor.tertiary)
                                        .lineLimit(2)
                                }
                            }
                            Spacer()
                            Text("+\(task.xp)")
                                .uniFont(10, weight: .semibold)
                                .foregroundStyle(UniColor.gold)
                            Image(systemName: "chevron.right")
                                .uniFont(11)
                                .foregroundStyle(UniColor.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("查看任务步骤和来源")
                }
                .padding(.vertical, 4)
                if task.id != stage.tasks.last?.id { Divider().padding(.leading, 44) }
            }
        }
    }

    private func setOfficial(_ task: AchievementTaskView, completed: Bool) async {
        pendingOfficial[task.id] = completed
        defer { pendingOfficial[task.id] = nil }
        await store.perform {
            let view = completed
                ? try await APIClient.shared.completeTask(id: task.id)
                : try await APIClient.shared.undoTask(id: task.id)
            onAchievementChange(view)
        }
    }

    // MARK: 我的计划

    private func personalBlock(_ plan: TermPlan) -> some View {
        PlanBlock(title: "我的计划", caption: "只记录给自己看，不发经验和金币", trailing: {
            if plan.canAddTasks {
                Button {
                    editor = PersonalTaskEditorTarget(task: nil, termStart: plan.termStart)
                } label: {
                    Label("添加", systemImage: "plus")
                        .uniFont(12, weight: .semibold)
                        .foregroundStyle(UniColor.blue)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("添加计划")
            }
        }) {
            if plan.personalTasks.isEmpty {
                PlanEmptyRow(text: plan.canAddTasks ? "给这个学期定几个小目标，比如「四级刷题 20 套」" : "这个学期没有自己的计划",
                             action: plan.canAddTasks
                                ? ("写一个", { editor = PersonalTaskEditorTarget(task: nil, termStart: plan.termStart) })
                                : nil)
            } else {
                ForEach(plan.personalTasks) { task in
                    let done = pendingPersonal[task.id] ?? task.completed
                    HStack(spacing: 10) {
                        PlanCheckButton(done: done, disabled: store.isMutating,
                                        label: done ? "已完成，点按取消" : "标记完成") {
                            Task { await togglePersonal(task, completed: !done) }
                        }
                        Button { editor = PersonalTaskEditorTarget(task: task, termStart: plan.termStart) } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    PlanTitle(text: task.title, done: done)
                                    if let note = task.note, !note.isEmpty {
                                        Text(note)
                                            .uniFont(11, weight: .semibold)
                                            .foregroundStyle(UniColor.tertiary)
                                            .lineLimit(2)
                                    }
                                    if let due = task.dueAt, !done {
                                        Label("截止 \(ActivityTime.short(due))", systemImage: "clock")
                                            .uniFont(10, weight: .semibold)
                                            .foregroundStyle(task.isOverdue ? UniColor.danger : UniColor.secondary)
                                    }
                                }
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("编辑这条计划")
                    }
                    .padding(.vertical, 4)
                    .contextMenu {
                        Button("编辑", systemImage: "pencil") {
                            editor = PersonalTaskEditorTarget(task: task, termStart: plan.termStart)
                        }
                        Button("删除", systemImage: "trash", role: .destructive) { deleteCandidate = task }
                    }
                    if task.id != plan.personalTasks.last?.id { Divider().padding(.leading, 44) }
                }
            }
        }
    }

    private func togglePersonal(_ task: PersonalTask, completed: Bool) async {
        pendingPersonal[task.id] = completed
        defer { pendingPersonal[task.id] = nil }
        await store.perform {
            try await APIClient.shared.setPersonalTaskCompleted(id: task.id, completed: completed)
        }
    }

    private var retryCard: some View {
        UniErrorState(store.errorMessage) { Task { await store.load(termStart: store.termStart) } }
            .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
    }
}

// MARK: - 清单零件

private struct PlanBlock<Trailing: View, Content: View>: View {
    let title: String
    let caption: String
    @ViewBuilder var trailing: () -> Trailing
    @ViewBuilder var content: () -> Content

    init(title: String, caption: String, @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() },
         @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.caption = caption
        self.trailing = trailing
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .uniFont(17, weight: .bold)
                        .foregroundStyle(UniColor.ink)
                    Text(caption)
                        .uniFont(11, weight: .semibold)
                        .foregroundStyle(UniColor.tertiary)
                }
                Spacer()
                trailing()
            }
            VStack(spacing: 0) { content() }
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                    .stroke(UniColor.mist.opacity(0.7), lineWidth: 1))
        }
    }
}

private struct PlanCheckButton: View {
    let done: Bool
    let disabled: Bool
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .uniFont(20, weight: .semibold)
                .foregroundStyle(done ? UniColor.blue : UniColor.tertiary)
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .accessibilityLabel(label)
    }
}

private struct PlanTitle: View {
    let text: String
    let done: Bool

    var body: some View {
        Text(text)
            .uniFont(14, weight: .semibold)
            .foregroundStyle(done ? UniColor.secondary : UniColor.ink)
            .strikethrough(done, color: UniColor.tertiary)
            .multilineTextAlignment(.leading)
    }
}

private struct PlanEmptyRow: View {
    let text: String
    var action: (String, () -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(text)
                .uniFont(13)
                .foregroundStyle(UniColor.secondary)
            if let action {
                Button(action.0, action: action.1)
                    .uniFont(12, weight: .semibold)
                    .foregroundStyle(UniColor.blue)
                    .frame(minHeight: 44)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 10)
    }
}

/// 活动行：状态图标代替勾选框——活动只能靠现场签到完成，不能手动打勾。
private struct ActivityPlanRow: View {
    @Environment(\.dynamicTypeSize) private var typeSize
    let item: GrowthParticipation

    var body: some View {
        HStack(alignment: typeSize.isAccessibilitySize ? .top : .center, spacing: 10) {
            Image(systemName: icon)
                .uniFont(18, weight: .semibold)
                .foregroundStyle(tint)
                .frame(width: 44, height: 44)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                PlanTitle(text: item.title, done: item.checkedIn)
                Text("\(ActivityTime.range(item.startsAt, item.endsAt)) · \(item.location)")
                    .uniFont(11, weight: .semibold)
                    .foregroundStyle(UniColor.tertiary)
                    .lineLimit(typeSize.isAccessibilitySize ? 3 : 1)
                // 辅助功能大字体下状态移到标题下面，给标题留出整行宽度。
                if typeSize.isAccessibilitySize { status }
            }
            Spacer(minLength: 6)
            if !typeSize.isAccessibilitySize {
                VStack(alignment: .trailing, spacing: 3) { status }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("查看活动详情")
    }

    @ViewBuilder
    private var status: some View {
        Text(item.statusLabel)
            .uniFont(10, weight: .semibold)
            .foregroundStyle(tint)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(tint.opacity(0.1), in: Capsule())
        if item.checkedIn, item.xpGranted > 0 {
            Text("+\(item.xpGranted) 经验")
                .uniFont(10, weight: .semibold)
                .foregroundStyle(UniColor.gold)
                .lineLimit(1)
                .fixedSize()
        }
    }

    private var icon: String {
        if item.checkedIn { return "checkmark.seal.fill" }
        if !item.countsTowardPlan { return "minus.circle" }
        return "calendar.badge.clock"
    }

    private var tint: Color {
        if item.checkedIn { return UniColor.blue }
        if !item.countsTowardPlan { return UniColor.tertiary }
        if item.statusLabel == "未签到" { return UniColor.danger }
        return UniColor.secondary
    }
}

// MARK: - 计划编辑

struct PersonalTaskEditorTarget: Identifiable {
    let task: PersonalTask?
    let termStart: String
    var id: String { task.map { "task-\($0.id)" } ?? "new-\(termStart)" }
}

private struct PersonalTaskEditor: View {
    @Environment(\.dismiss) private var dismiss
    let target: PersonalTaskEditorTarget
    /// 返回 true 表示保存成功，可以关闭。
    let onSave: (PersonalTaskInput) async -> Bool

    @State private var input = PersonalTaskInput()
    @State private var hasDue = false
    @State private var saving = false
    @State private var failed = false
    @FocusState private var titleFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("想完成什么", text: $input.title, axis: .vertical)
                        .lineLimit(1...2)
                        .focused($titleFocused)
                    TextField("备注（选填）", text: $input.note, axis: .vertical)
                        .lineLimit(2...5)
                } footer: {
                    Text("\(input.trimmedTitle.count)/\(PersonalTaskInput.maxTitle) · 备注 \(input.note.count)/\(PersonalTaskInput.maxNote)")
                        .foregroundStyle(input.isValid || input.trimmedTitle.isEmpty ? UniColor.tertiary : UniColor.danger)
                }
                Section {
                    Toggle("设置截止时间", isOn: $hasDue.animation())
                    if hasDue {
                        DatePicker("截止", selection: Binding(
                            get: { input.dueAt ?? Self.defaultDue }, set: { input.dueAt = $0 }
                        ), displayedComponents: [.date, .hourAndMinute])
                        .environment(\.timeZone, ActivityTime.zone)
                    }
                } footer: {
                    Text("自己的计划只记录完成情况，不发经验和金币。")
                }
                if failed {
                    Text("保存没有成功，请稍后再试")
                        .uniFont(12, weight: .semibold)
                        .foregroundStyle(UniColor.danger)
                }
            }
            .navigationTitle(target.task == nil ? "新计划" : "编辑计划")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { Task { await save() } }
                        .disabled(!input.isValid || saving)
                }
            }
        }
        .onAppear {
            guard let task = target.task else {
                input = PersonalTaskInput(termStart: target.termStart)
                return
            }
            input = PersonalTaskInput(title: task.title, note: task.note ?? "", dueAt: ActivityTime.parse(task.dueAt))
            hasDue = input.dueAt != nil
        }
        .task {
            // 新建时直接弹出键盘；等弹窗动画结束再聚焦，否则焦点会丢。
            guard target.task == nil else { return }
            try? await Task.sleep(for: .milliseconds(350))
            titleFocused = true
        }
        .interactiveDismissDisabled(saving)
    }

    private static var defaultDue: Date {
        Calendar.current.date(byAdding: .day, value: 7, to: Date()) ?? Date()
    }

    private func save() async {
        saving = true
        failed = false
        var value = input
        if !hasDue { value.dueAt = nil } else if value.dueAt == nil { value.dueAt = Self.defaultDue }
        if await onSave(value) {
            dismiss()
        } else {
            failed = true
        }
        saving = false
    }
}

// MARK: - 成长档案

/// 按学期倒序归档参加过的活动、获得的徽章和完成数。
struct GrowthArchiveSection: View {
    @Environment(AuthStore.self) private var auth
    @State private var archive: GrowthArchive?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var openedActivity: ActivityRef?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let archive {
                HStack(spacing: 10) {
                    totalCard(value: archive.attendedTotal, title: "参加过的活动", icon: "figure.walk")
                    totalCard(value: archive.badgeTotal, title: "获得的徽章", icon: "rosette")
                }
                ForEach(archive.terms) { term in termCard(term) }
            } else if isLoading {
                UniLoadingState("正在翻开成长档案")
                    .frame(maxWidth: .infinity, minHeight: 160)
            } else {
                UniErrorState(errorMessage) { Task { await load() } }
                    .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
            }
        }
        .task { await load() }
        .sheet(item: $openedActivity) { ref in
            NavigationStack { ActivityDetailView(activityID: ref.id) }
                .environment(auth)
        }
    }

    private func totalCard(value: Int, title: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: icon)
                .uniFont(15, weight: .semibold)
                .foregroundStyle(UniColor.blue)
            Text("\(value)")
                .uniFont(26, weight: .heavy)
                .foregroundStyle(UniColor.ink)
                .monospacedDigit()
            Text(title)
                .uniFont(11, weight: .semibold)
                .foregroundStyle(UniColor.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private func termCard(_ term: GrowthArchiveTerm) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Text(term.termLabel)
                    .uniFont(16, weight: .bold)
                    .foregroundStyle(UniColor.ink)
                if term.current {
                    Text("本学期")
                        .uniFont(9, weight: .semibold)
                        .foregroundStyle(UniColor.blue)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(UniColor.blue.opacity(0.1), in: Capsule())
                }
                Spacer()
                if term.activityXp > 0 {
                    Text("+\(term.activityXp) 经验 · +\(term.activityCoins) 金币")
                        .uniFont(10, weight: .semibold)
                        .foregroundStyle(UniColor.gold)
                }
            }

            if term.isEmpty {
                Text(term.current ? "这个学期刚开始，参加活动、完成任务后会记在这里" : "这个学期没有记录")
                    .uniFont(12)
                    .foregroundStyle(UniColor.tertiary)
            } else {
                HStack(spacing: 16) {
                    archiveStat("\(term.activities.count)", "活动")
                    archiveStat("\(term.officialCompleted)", "主线任务")
                    archiveStat("\(term.personalCompleted)", "自己的计划")
                    archiveStat("\(term.badges.count)", "徽章")
                }
                if !term.badges.isEmpty {
                    ScrollView(.horizontal) {
                        HStack(spacing: 8) {
                            ForEach(term.badges) { badge in
                                Label(badge.title, systemImage: badge.icon)
                                    .uniFont(11, weight: .semibold)
                                    .foregroundStyle(UniColor.ink)
                                    .padding(.horizontal, 10)
                                    .frame(minHeight: 30)
                                    .background(UniColor.gold.opacity(0.12), in: Capsule())
                            }
                        }
                    }
                    .scrollIndicators(.hidden)
                }
                if !term.activities.isEmpty {
                    VStack(spacing: 0) {
                        ForEach(term.activities) { item in
                            Button { openedActivity = ActivityRef(id: item.activityId) } label: {
                                HStack(spacing: 10) {
                                    Text(GrowthDate.monthDay(item.startsAt))
                                        .uniFont(11, weight: .semibold)
                                        .foregroundStyle(UniColor.tertiary)
                                        .frame(width: 52, alignment: .leading)
                                    Text(item.title)
                                        .uniFont(14, weight: .semibold)
                                        .foregroundStyle(UniColor.ink)
                                        .lineLimit(1)
                                    Spacer(minLength: 6)
                                    Image(systemName: "chevron.right")
                                        .uniFont(11)
                                        .foregroundStyle(UniColor.tertiary)
                                }
                                .frame(minHeight: 44)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .padding(16)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
            .stroke(term.current ? UniColor.blue.opacity(0.2) : UniColor.mist.opacity(0.7), lineWidth: 1))
    }

    private func archiveStat(_ value: String, _ title: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .uniFont(16, weight: .bold)
                .foregroundStyle(UniColor.ink)
                .monospacedDigit()
            Text(title)
                .uniFont(10, weight: .semibold)
                .foregroundStyle(UniColor.tertiary)
        }
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        do {
            archive = try await APIClient.shared.growthArchive()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
        isLoading = false
    }
}

enum GrowthDate {
    /// 「9月1日」：接受「2026-09-01」与带时区的时间戳。
    static func monthDay(_ raw: String) -> String {
        let parts = raw.prefix(10).split(separator: "-")
        if raw.count == 10, parts.count == 3, let month = Int(parts[1]), let day = Int(parts[2]) {
            return "\(month)月\(day)日"
        }
        guard let date = ActivityTime.parse(raw) else { return "" }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = ActivityTime.zone
        let components = calendar.dateComponents([.month, .day], from: date)
        return "\(components.month ?? 0)月\(components.day ?? 0)日"
    }

    /// 「2026年10月23日」：接受「2026-10-23」。
    static func fullDate(_ raw: String) -> String {
        let parts = raw.prefix(10).split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return raw }
        return "\(parts[0])年\(parts[1])月\(parts[2])日"
    }
}
