import SwiftUI
import UniPOCore
import UniPOUI

struct TaskRef: Identifiable {
    let id: Int64
}

/// 主线任务详情（G05）：步骤、期限、来源与复核日期；完成与撤回都在这里，撤回需要确认。
struct TaskDetailView: View {
    @Environment(\.dismiss) private var dismiss
    let taskID: Int64
    /// 完成或撤回后把最新的成长数据交给调用方，顶部经验和清单一起刷新。
    var onChange: (AchievementView) -> Void = { _ in }

    @State private var detail: AchievementTaskDetail?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var actionError: String?
    @State private var working = false
    @State private var confirmingUndo = false

    var body: some View {
        NavigationStack {
            ScrollView {
                if let detail {
                    content(detail)
                } else if isLoading {
                    UniLoadingState("加载任务…")
                } else {
                    UniErrorState(errorMessage) { Task { await load() } }
                }
            }
            .background(UniColor.paper)
            .navigationTitle("任务详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if let detail { actionBar(detail) }
            }
        }
        .task { await load() }
        .confirmationDialog("撤回这项完成记录？", isPresented: $confirmingUndo, titleVisibility: .visible) {
            Button("撤回完成", role: .destructive) { Task { await setCompleted(false) } }
            Button("取消", role: .cancel) {}
        } message: {
            Text("撤回后会同时扣回这项任务的 \(detail?.xp ?? 0) 经验。")
        }
    }

    private func content(_ detail: AchievementTaskDetail) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                if let stage = detail.stageTitle {
                    Text("主线 · \(stage)")
                        .uniFont(12, weight: .semibold)
                        .foregroundStyle(UniColor.blue)
                }
                Text(detail.title)
                    .uniFont(24, weight: .bold)
                    .foregroundStyle(UniColor.ink)
                if let description = detail.description, !description.isEmpty {
                    Text(description)
                        .uniFont(14)
                        .foregroundStyle(UniColor.secondary)
                }
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { chips(detail) }
                    VStack(alignment: .leading, spacing: 6) { chips(detail) }
                }
            }

            if detail.updatedSinceCompletion {
                Label("你完成之后，这项任务的内容更新过。完成记录照常保留，可以再看一遍新的步骤。",
                      systemImage: "arrow.triangle.2.circlepath")
                    .uniFont(12, weight: .semibold)
                    .foregroundStyle(UniColor.inkSoft)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(UniColor.gold.opacity(0.12), in: RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
            }

            if !detail.steps.isEmpty {
                card(title: "怎么做") {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(Array(detail.steps.enumerated()), id: \.offset) { index, step in
                            HStack(alignment: .firstTextBaseline, spacing: 10) {
                                Text("\(index + 1)")
                                    .uniFont(12, weight: .bold)
                                    .foregroundStyle(.white)
                                    .frame(minWidth: 22, minHeight: 22)
                                    .background(UniColor.ink, in: Circle())
                                    .accessibilityHidden(true)
                                Text(step)
                                    .uniFont(14)
                                    .foregroundStyle(UniColor.ink)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel("第 \(index + 1) 步，\(step)")
                        }
                    }
                }
            }

            if let badge = detail.badge {
                card(title: "完成可得徽章") {
                    HStack(spacing: 12) {
                        Image(systemName: badge.icon)
                            .uniFont(20, weight: .semibold)
                            .foregroundStyle(badge.earned ? UniColor.gold : UniColor.tertiary)
                            .frame(width: 44, height: 44)
                            .background(UniColor.gold.opacity(0.12), in: RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(badge.title).uniFont(14, weight: .semibold).foregroundStyle(UniColor.ink)
                            Text(badge.earned ? "已获得" : (badge.description ?? "完成这项任务后获得"))
                                .uniFont(12).foregroundStyle(UniColor.secondary)
                        }
                    }
                }
            }

            card(title: "来源") {
                VStack(alignment: .leading, spacing: 6) {
                    Text("\(detail.sourceLabel) · \(detail.sourceName)")
                        .uniFont(13, weight: .semibold)
                        .foregroundStyle(UniColor.ink)
                    if let reviewed = detail.reviewedAt {
                        Text("最近复核：\(GrowthDate.fullDate(reviewed))")
                            .uniFont(12)
                            .foregroundStyle(UniColor.secondary)
                    }
                    if detail.sourceType == "PLATFORM" {
                        Text("这是通用建议，具体时间和要求以你所在学校的通知为准。")
                            .uniFont(12)
                            .foregroundStyle(UniColor.tertiary)
                    }
                    if let raw = detail.sourceUrl, let url = URL(string: raw), url.scheme == "https" {
                        Link(destination: url) {
                            Label("查看原文", systemImage: "arrow.up.right.square")
                                .uniFont(13, weight: .semibold)
                                .frame(minHeight: 44)
                        }
                    }
                }
            }

            if let actionError {
                Text(actionError)
                    .uniFont(12, weight: .semibold)
                    .foregroundStyle(UniColor.danger)
            }
        }
        .padding(18)
    }

    @ViewBuilder
    private func chips(_ detail: AchievementTaskDetail) -> some View {
        if let due = detail.dueHint, !due.isEmpty {
            UniChip("期限：\(due)", tint: UniColor.inkSoft)
        }
        UniChip("+\(detail.xp) 经验", tint: UniColor.gold)
        if detail.completed, let at = detail.completedAt {
            UniChip("\(ActivityTime.short(at)) 完成", tint: UniColor.blue)
        }
    }

    private func card<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .uniFont(15, weight: .bold)
                .foregroundStyle(UniColor.ink)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color.white, in: RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
            .stroke(UniColor.mist.opacity(0.7), lineWidth: 1))
    }

    private func actionBar(_ detail: AchievementTaskDetail) -> some View {
        Button {
            if detail.completed { confirmingUndo = true } else { Task { await setCompleted(true) } }
        } label: {
            HStack(spacing: 8) {
                if working { ProgressView().tint(detail.completed ? UniColor.ink : .white) }
                Text(detail.completed ? "已完成 · 撤回" : "我完成了")
            }
        }
        .buttonStyle(ActionStyle(completed: detail.completed))
        .disabled(working)
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }

    private struct ActionStyle: ButtonStyle {
        let completed: Bool

        func makeBody(configuration: Configuration) -> some View {
            configuration.label
                .uniFont(16, weight: .semibold)
                .foregroundStyle(completed ? UniColor.ink : .white)
                .frame(maxWidth: .infinity, minHeight: 50)
                .background(completed ? UniColor.mist : UniColor.ink,
                            in: RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous))
                .opacity(configuration.isPressed ? 0.82 : 1)
        }
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        do {
            detail = try await APIClient.shared.taskDetail(id: taskID)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
        isLoading = false
    }

    private func setCompleted(_ completed: Bool) async {
        working = true
        actionError = nil
        defer { working = false }
        do {
            let view = completed
                ? try await APIClient.shared.completeTask(id: taskID)
                : try await APIClient.shared.undoTask(id: taskID)
            onChange(view)
            // 完成状态以服务端为准，重新拉一次详情。
            detail = try await APIClient.shared.taskDetail(id: taskID)
        } catch {
            actionError = (error as? LocalizedError)?.errorDescription ?? "操作失败，请重试"
        }
    }
}
