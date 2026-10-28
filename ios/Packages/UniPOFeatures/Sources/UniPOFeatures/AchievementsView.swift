import SwiftUI
import UniPOCore
import UniPOUI

struct AchievementsView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var achievement: AchievementView?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var appeared = false
    @State private var pulse = false
    @State private var completingId: Int64?
    @State private var selectedStageCode: String?
    @State private var undoCandidate: AchievementTaskView?
    @State private var editingBackground = false
    /// 0 本学期清单 · 1 成长路线 · 2 成长档案；默认先看本学期该做什么。
    @State private var section = 0
    @State private var termPlan = TermPlanStore()
    @State private var openedTask: TaskRef?
    @State private var showActivities = false
    @State private var openedBadge: AchievementBadgeView?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                hero
                entryGrid
                if let achievement, achievement.needsBackground { backgroundPrompt }
                if auth.isAuthenticated {
                    ProfileSegmentedControl(selection: $section, titles: ["本学期", "成长路线", "成长档案"])
                }
                switch auth.isAuthenticated ? section : 1 {
                case 0:
                    TermPlanSection(store: termPlan) { achievement = $0 }
                case 2:
                    GrowthArchiveSection()
                default:
                    stagePath
                    currentTasks
                    badgeGrid
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 120)
        }
        .scrollIndicators(.hidden)
        .task(id: "\(auth.isAuthenticated):\(auth.profile?.entryYear ?? 0)") { await load() }
        .onChange(of: section) { _, value in
            // 在「成长路线」里勾掉的主线任务要同步回清单。
            if value == 0, termPlan.plan != nil { Task { await termPlan.load(termStart: termPlan.termStart) } }
        }
        .onAppear {
            withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(0.1)) {
                appeared = true
            }
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 2.2).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
        .sheet(isPresented: $showActivities, onDismiss: {
            // 在活动里报名或签到后，本学期清单要跟着更新。
            if termPlan.plan != nil { Task { await termPlan.load(termStart: termPlan.termStart) } }
        }) {
            ActivityListView().environment(auth)
        }
        .sheet(item: $openedTask) { ref in
            TaskDetailView(taskID: ref.id) { achievement = $0 }
                .environment(auth)
        }
        .sheet(isPresented: $editingBackground) {
            GrowthBackgroundSheet(profile: auth.profile) {
                Task {
                    await load()
                    await termPlan.showCurrent()
                }
            }
                .environment(auth)
                .presentationDetents([.large])
        }
        .confirmationDialog("撤回这项完成记录？", isPresented: Binding(
            get: { undoCandidate != nil }, set: { if !$0 { undoCandidate = nil } }
        ), titleVisibility: .visible, presenting: undoCandidate) { task in
            Button("撤回完成", role: .destructive) { Task { await undo(task) } }
            Button("取消", role: .cancel) {}
        } message: { task in
            Text("撤回后会同时扣回这项任务的 \(task.xp) 经验。")
        }
    }

    /// 成长页的子入口。先只有「校园活动」，之后社团、二手等入口按同样的图块往后加。
    private var entryGrid: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 72, maximum: 96), spacing: 12, alignment: .top)],
                  alignment: .leading, spacing: 12) {
            entryTile(title: "校园活动", icon: "flag.2.crossed.fill", tint: UniColor.blue) { showActivities = true }
        }
    }

    private func entryTile(title: String, icon: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .uniFont(22, weight: .semibold)
                    .foregroundStyle(tint)
                    .frame(width: 56, height: 56)
                    .background(tint.opacity(0.1), in: RoundedRectangle(cornerRadius: UniRadius.tile, style: .continuous))
                Text(title)
                    .uniFont(12, weight: .semibold)
                    .foregroundStyle(UniColor.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }

    private var backgroundPrompt: some View {
        Button { editingBackground = true } label: {
            HStack(spacing: 12) {
                Image(systemName: "graduationcap.fill")
                    .uniFont(18, weight: .semibold)
                    .foregroundStyle(UniColor.blue)
                    .frame(width: 40, height: 40)
                    .background(UniColor.blue.opacity(0.1), in: Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text("补充入学年份")
                        .uniFont(15, weight: .bold)
                        .foregroundStyle(UniColor.ink)
                    Text("现在按完成进度显示阶段；补充后按你所在的学期推荐任务")
                        .uniFont(12)
                        .foregroundStyle(UniColor.secondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundStyle(UniColor.tertiary)
            }
            .padding(16)
            .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                .stroke(UniColor.blue.opacity(0.18), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var progress: Double {
        guard let achievement, achievement.totalTasks > 0 else { return 0 }
        return Double(achievement.completedTasks) / Double(achievement.totalTasks)
    }

    private var currentStage: AchievementStageView? {
        guard let achievement else { return nil }
        return achievement.stages.first { $0.code == achievement.currentStageCode } ?? achievement.stages.last
    }

    /// 路径上点选的阶段；默认是服务端给出的当前阶段。
    private var shownStage: AchievementStageView? {
        guard let achievement else { return nil }
        if let selectedStageCode, let stage = achievement.stages.first(where: { $0.code == selectedStageCode }) {
            return stage
        }
        return currentStage
    }

    private var heroSubtitle: String {
        guard let achievement else { return "大学四年的成长路线图" }
        if let term = achievement.termLabel, let year = achievement.entryYear { return "\(year) 级 · \(term)" }
        return "大学四年的成长路线图"
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("成长")
                        .uniFont(30, weight: .heavy)
                        .foregroundStyle(.white)
                    Text(heroSubtitle)
                        .uniFont(13)
                        .foregroundStyle(.white.opacity(0.62))
                }
                Spacer()
                if auth.isAuthenticated, achievement != nil {
                    Button("成长背景") { editingBackground = true }
                        .uniFont(12, weight: .semibold)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 32)
                        .background(.white.opacity(0.14), in: Capsule())
                        .frame(minWidth: 44, minHeight: 44)
                }
            }

            HStack(spacing: 16) {
                ZStack {
                    Circle().stroke(.white.opacity(0.14), lineWidth: 9)
                    Circle()
                        .trim(from: 0, to: appeared ? progress : 0)
                        .stroke(UniColor.blueBright, style: StrokeStyle(lineWidth: 9, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    VStack(spacing: 1) {
                        Text("\(Int(progress * 100))%")
                            .uniFont(20, weight: .heavy)
                            .foregroundStyle(.white)
                        Text(currentStage?.title ?? "—")
                            .uniFont(10, weight: .semibold)
                            .foregroundStyle(.white.opacity(0.6))
                    }
                }
                .frame(width: 94, height: 94)

                VStack(alignment: .leading, spacing: 7) {
                    Text(achievement.map { "Lv.\($0.level) · \($0.completedTasks)/\($0.totalTasks) 个任务" } ?? "登录后同步进度")
                        .uniFont(16, weight: .bold)
                        .foregroundStyle(.white)
                    Text(currentStage.map { "当前阶段：\($0.title)" } ?? (isLoading ? "加载中…" : "还没有阶段数据"))
                        .uniFont(12)
                        .foregroundStyle(.white.opacity(0.62))
                    HStack(spacing: 6) {
                        Image(systemName: "bolt.fill")
                        Text("\(achievement?.xp ?? 0) XP")
                    }
                    .uniFont(11, weight: .semibold)
                    .foregroundStyle(UniColor.gold)
                }
            }

            if !auth.isAuthenticated {
                Button {
                    auth.requireLogin()
                } label: {
                    Text("登录后同步进度")
                        .uniFont(13, weight: .semibold)
                        .foregroundStyle(.black)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 9)
                        .background(.white, in: Capsule())
                }
                .buttonStyle(.plain)
            }

            if let errorMessage {
                Text(errorMessage)
                    .uniFont(11, weight: .semibold)
                    .foregroundStyle(.white.opacity(0.8))
            }
        }
        .padding(22)
        .background(
            LinearGradient(colors: [UniColor.ink, Color(hex: 0x14264A), UniColor.blue.opacity(0.9)],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: UniRadius.panel, style: .continuous)
        )
        .overlay(alignment: .topTrailing) {
            Circle()
                .fill(UniColor.blue.opacity(pulse ? 0.28 : 0.12))
                .frame(width: 180, height: 180)
                .blur(radius: 40)
                .offset(x: 50, y: -60)
                .allowsHitTesting(false)
        }
        .clipShape(RoundedRectangle(cornerRadius: UniRadius.panel, style: .continuous))
    }

    private var stagePath: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("成长路径")
                .uniFont(18, weight: .bold)
                .foregroundStyle(UniColor.ink)

            VStack(spacing: 0) {
                ForEach(Array((achievement?.stages ?? []).enumerated()), id: \.offset) { index, stage in
                    Button {
                        selectedStageCode = stage.code == achievement?.currentStageCode ? nil : stage.code
                    } label: {
                    HStack(alignment: .top, spacing: 14) {
                        VStack(spacing: 0) {
                            ZStack {
                                Circle()
                                    .fill([.locked, .past].contains(nodeState(stage)) ? UniColor.mist : UniColor.ink)
                                    .frame(width: 30, height: 30)
                                Image(systemName: nodeState(stage).icon)
                                    .uniFont(12, weight: .bold)
                                    .foregroundStyle(nodeState(stage) == .locked ? UniColor.tertiary : nodeState(stage) == .past ? UniColor.secondary : .white)
                            }
                            if index < (achievement?.stages.count ?? 0) - 1 {
                                Rectangle()
                                    .fill(nodeState(stage) == .done ? UniColor.blue.opacity(0.5) : UniColor.mist)
                                    .frame(width: 2, height: 40)
                            }
                        }
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(stage.title)
                                    .uniFont(15, weight: .bold)
                                    .foregroundStyle(nodeState(stage) == .locked ? UniColor.tertiary : UniColor.ink)
                                if stage.code == achievement?.currentStageCode {
                                    Text("进行中")
                                        .uniFont(9, weight: .semibold)
                                        .foregroundStyle(UniColor.blue)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(UniColor.blue.opacity(0.1), in: Capsule())
                                }
                                if stage.boss {
                                    Text("关键阶段")
                                        .uniFont(9, weight: .semibold)
                                        .foregroundStyle(UniColor.gold)
                                }
                            }
                            Text(stage.subtitle ?? "")
                                .uniFont(12)
                                .foregroundStyle(UniColor.secondary)
                            Text("\(stage.completedCount)/\(stage.totalCount)")
                                .uniFont(10, weight: .semibold)
                                .foregroundStyle(UniColor.tertiary)
                        }
                        Spacer()
                        if stage.code == shownStage?.code {
                            Image(systemName: "eye")
                                .uniFont(12, weight: .semibold)
                                .foregroundStyle(UniColor.blue)
                                .accessibilityLabel("正在查看")
                        }
                    }
                    .padding(.bottom, 4)
                    .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(18)
            .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                    .stroke(UniColor.mist.opacity(0.7), lineWidth: 1)
            )
        }
    }

    private var currentTasks: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(shownStage?.code == currentStage?.code ? "当前阶段任务" : "\(shownStage?.title ?? "") 的任务")
                    .uniFont(18, weight: .bold)
                    .foregroundStyle(UniColor.ink)
                Spacer()
                if let shownStage {
                    Text("\(shownStage.completedCount)/\(shownStage.totalCount)")
                        .uniFont(11, weight: .semibold)
                        .foregroundStyle(UniColor.tertiary)
                }
            }

            if let shownStage {
                VStack(spacing: 0) {
                    ForEach(shownStage.tasks) { task in
                        HStack(spacing: 10) {
                            Button {
                                if task.completed { undoCandidate = task } else { Task { await complete(task) } }
                            } label: {
                                Image(systemName: task.completed ? "checkmark.circle.fill" : "circle")
                                    .uniFont(20, weight: .semibold)
                                    .foregroundStyle(task.completed ? UniColor.blue : UniColor.tertiary)
                                    .frame(width: 44, height: 44)
                            }
                            .buttonStyle(.plain)
                            .disabled(completingId != nil)
                            .accessibilityLabel(task.completed ? "已完成，点按撤回" : "标记完成")

                            Button { openedTask = TaskRef(id: task.id) } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(task.title)
                                            .uniFont(14, weight: .semibold)
                                            .foregroundStyle(task.completed ? UniColor.secondary : UniColor.ink)
                                            .strikethrough(task.completed, color: UniColor.tertiary)
                                            .multilineTextAlignment(.leading)
                                        if let description = task.description, !description.isEmpty {
                                            Text(description)
                                                .uniFont(11, weight: .semibold)
                                                .foregroundStyle(UniColor.tertiary)
                                                .lineLimit(2)
                                                .multilineTextAlignment(.leading)
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
                        .padding(.vertical, 10)
                        if task.id != shownStage.tasks.last?.id {
                            Divider().padding(.leading, 30)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                        .stroke(UniColor.mist.opacity(0.7), lineWidth: 1)
                )
            } else {
                Text(isLoading ? "加载中…" : "登录后查看你的阶段任务")
                    .uniFont(13)
                    .foregroundStyle(UniColor.secondary)
                    .padding(.vertical, 20)
            }
        }
    }

    private var badgeGrid: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("徽章墙")
                    .uniFont(18, weight: .bold)
                    .foregroundStyle(UniColor.ink)
                Spacer()
                if let achievement {
                    Text("\(achievement.badges.filter(\.earned).count) / \(achievement.badges.count)")
                        .uniFont(11, weight: .semibold)
                        .foregroundStyle(UniColor.tertiary)
                }
            }

            if let title = achievement?.title {
                HStack(spacing: 6) {
                    Text("主页称号").uniFont(11, weight: .semibold).foregroundStyle(UniColor.tertiary)
                    BadgeTitleChip(badge: title)
                }
            }

            // F15：点开看获得条件、稀有度和获得时间，已获得的可以设为主页称号。
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                ForEach(achievement?.badges ?? []) { badge in
                    Button { openedBadge = badge } label: {
                        VStack(spacing: 8) {
                            BadgeMedal(badge: badge)
                            Text(badge.earned ? badge.title : "未解锁")
                                .uniFont(11, weight: .semibold)
                                .foregroundStyle(badge.earned ? UniColor.ink : UniColor.tertiary)
                                .lineLimit(1)
                            Text(badge.rarityLevel.label)
                                .uniFont(9, weight: .semibold)
                                .foregroundStyle(BadgeStyle.tint(badge.rarityLevel))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.tile, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: UniRadius.tile, style: .continuous)
                                .stroke(BadgeStyle.tint(badge.rarityLevel).opacity(badge.earned ? 0.2 : 0.08), lineWidth: 1)
                        )
                        .overlay(alignment: .topTrailing) {
                            if badge.code == achievement?.title?.code {
                                Image(systemName: "person.crop.circle.badge.checkmark")
                                    .uniFont(12, weight: .semibold)
                                    .foregroundStyle(BadgeStyle.tint(badge.rarityLevel))
                                    .padding(8)
                                    .accessibilityLabel("当前称号")
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .scaleEffect(appeared ? 1 : 0.92)
                    .accessibilityLabel("\(badge.earned ? badge.title : "未解锁的徽章")，\(badge.rarityLevel.label)")
                    .accessibilityHint("查看获得条件")
                }
            }
        }
        .sheet(item: $openedBadge) { badge in
            BadgeDetailSheet(badge: badge, isCurrentTitle: badge.code == achievement?.title?.code) { code in
                let updated = try await APIClient.shared.setTitle(badgeCode: code)
                achievement = updated
                NotificationCenter.default.post(name: .unipoTitleChanged, object: updated.title)
            }
        }
    }

    private func nodeState(_ stage: AchievementStageView) -> StageState {
        if stage.code == achievement?.currentStageCode { return .current }
        if stage.totalCount > 0 && stage.completedCount == stage.totalCount { return .done }
        // 已经走过的学期不叫「锁定」：任务仍可补做，只是不再是当前推荐。
        if let current = achievement?.currentStage, stage.order < current.order { return .past }
        if stage.boss { return .boss }
        return .locked
    }

    private func load() async {
        guard auth.isAuthenticated else {
            isLoading = false
            return
        }
        isLoading = true
        errorMessage = nil
        do {
            achievement = try await APIClient.shared.achievements()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
        isLoading = false
    }

    private func undo(_ task: AchievementTaskView) async {
        completingId = task.id
        errorMessage = nil
        do {
            achievement = try await APIClient.shared.undoTask(id: task.id)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "撤回失败，请重试"
        }
        completingId = nil
    }

    private func complete(_ task: AchievementTaskView) async {
        completingId = task.id
        errorMessage = nil
        do {
            achievement = try await APIClient.shared.completeTask(id: task.id)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
        completingId = nil
    }
}

private enum StageState {
    case done, current, past, locked, boss

    var icon: String {
        switch self {
        case .done: return "checkmark"
        case .current: return "arrow.right"
        case .past: return "clock.arrow.circlepath"
        case .locked: return "lock.fill"
        case .boss: return "crown.fill"
        }
    }
}
