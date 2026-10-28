import SwiftUI
import UniPOCore
import UniPOUI

/// F12：发帖时自己选关联。活动按「参加过 → 报名了 → 本校近期」排，任务按当前学期优先。
/// 服务端只接受本校、未取消的活动，这里也不列已取消的。
struct PostLinkPicker: View {
    enum Kind: String, CaseIterable, Identifiable {
        case activity, task
        var id: String { rawValue }
        var title: String { self == .activity ? "校园活动" : "主线任务" }
    }

    @Environment(\.dismiss) private var dismiss
    let onPick: (PostLink) -> Void

    @State private var kind: Kind = .activity
    @State private var activitySections: [(title: String, items: [ActivityView])] = []
    @State private var stages: [AchievementStageView] = []
    @State private var currentStageCode: String?
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Picker("类型", selection: $kind) {
                    ForEach(Kind.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 18)
                list
            }
            .padding(.top, 8)
            .background(UniColor.paper.ignoresSafeArea())
            .navigationTitle("关联到")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("取消") { dismiss() } }
            }
            .task(id: kind) { await load() }
        }
        .presentationDetents([.medium, .large])
    }

    @ViewBuilder
    private var list: some View {
        if isLoading && activitySections.isEmpty && stages.isEmpty {
            UniLoadingState("正在加载…").frame(maxHeight: .infinity, alignment: .top)
        } else if let errorMessage {
            UniErrorState(errorMessage) { Task { await load() } }.frame(maxHeight: .infinity, alignment: .top)
        } else if kind == .activity {
            if activitySections.isEmpty {
                UniEmptyState(icon: "flag.2.crossed", title: "本校暂时没有可关联的活动")
                    .frame(maxHeight: .infinity, alignment: .top)
            } else {
                List {
                    ForEach(activitySections, id: \.title) { section in
                        Section(section.title) {
                            ForEach(section.items) { activity in
                                row(activity.title, subtitle: "\(activity.categoryLabel) · \(ActivityTime.short(activity.startsAt))") {
                                    onPick(.activity(PostLinkRef(id: activity.id, title: activity.title)))
                                }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
        } else {
            List {
                ForEach(orderedStages) { stage in
                    Section(stage.code == currentStageCode ? "\(stage.title) · 当前" : stage.title) {
                        ForEach(stage.tasks) { task in
                            row(task.title, subtitle: task.completed ? "已完成" : nil) {
                                onPick(.task(PostLinkRef(id: task.id, title: task.title)))
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
    }

    private var orderedStages: [AchievementStageView] {
        stages.filter { !$0.tasks.isEmpty }
            .sorted { ($0.code == currentStageCode ? 0 : 1, $0.order) < ($1.code == currentStageCode ? 0 : 1, $1.order) }
    }

    private func row(_ title: String, subtitle: String?, action: @escaping () -> Void) -> some View {
        Button {
            action()
            dismiss()
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).uniFont(15, weight: .semibold).foregroundStyle(UniColor.ink).lineLimit(2)
                if let subtitle {
                    Text(subtitle).uniFont(12).foregroundStyle(UniColor.tertiary).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.white)
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            if kind == .activity {
                async let attended = APIClient.shared.myActivities(state: "history", cursor: nil)
                async let registered = APIClient.shared.myActivities(state: "upcoming", cursor: nil)
                async let school = APIClient.shared.activities(tab: "recommend", cursor: nil)
                let pages = try await [("参加过", attended), ("我报名的", registered), ("本校活动", school)]
                var seen = Set<Int64>()
                activitySections = pages.compactMap { title, page in
                    let items = page.items.filter { $0.phase != "CANCELLED" && seen.insert($0.id).inserted }
                    return items.isEmpty ? nil : (title, items)
                }
            } else {
                let achievement = try await APIClient.shared.achievements()
                stages = achievement.stages
                currentStageCode = achievement.currentStageCode
            }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "加载失败"
        }
    }
}
