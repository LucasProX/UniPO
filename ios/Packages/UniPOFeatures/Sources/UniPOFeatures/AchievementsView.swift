import SwiftUI
import UniPOCore
import UniPOUI

struct AchievementsView: View {
    @Environment(AuthStore.self) private var auth

    @State private var achievement: AchievementView?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var appeared = false
    @State private var pulse = false
    @State private var completingId: Int64?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                hero
                stagePath
                currentTasks
                badgeGrid
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 120)
        }
        .scrollIndicators(.hidden)
        .task(id: auth.isAuthenticated) { await load() }
        .onAppear {
            withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(0.1)) {
                appeared = true
            }
            withAnimation(.easeInOut(duration: 2.2).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }

    private var progress: Double {
        guard let achievement, achievement.totalTasks > 0 else { return 0 }
        return Double(achievement.completedTasks) / Double(achievement.totalTasks)
    }

    private var currentStage: AchievementStageView? {
        guard let achievement else { return nil }
        return achievement.stages.first { $0.code == achievement.currentStageCode } ?? achievement.stages.last
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("成就")
                        .font(.system(size: 30, weight: .heavy))
                        .foregroundStyle(.white)
                    Text("大学四年的成长路线图")
                        .font(.uniBody(13))
                        .foregroundStyle(.white.opacity(0.62))
                }
                Spacer()
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
                            .font(.system(size: 20, weight: .heavy))
                            .foregroundStyle(.white)
                        Text(currentStage?.title ?? "—")
                            .font(.uniLabel(10))
                            .foregroundStyle(.white.opacity(0.6))
                    }
                }
                .frame(width: 94, height: 94)

                VStack(alignment: .leading, spacing: 7) {
                    Text(achievement.map { "Lv.\($0.level) · \($0.completedTasks)/\($0.totalTasks) 个任务" } ?? "登录后同步进度")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(.white)
                    Text(currentStage.map { "当前阶段：\($0.title)" } ?? (isLoading ? "加载中…" : "还没有阶段数据"))
                        .font(.uniBody(12))
                        .foregroundStyle(.white.opacity(0.62))
                    HStack(spacing: 6) {
                        Image(systemName: "bolt.fill")
                        Text("\(achievement?.xp ?? 0) XP")
                    }
                    .font(.uniLabel(11))
                    .foregroundStyle(UniColor.gold)
                }
            }

            if !auth.isAuthenticated {
                Button {
                    auth.requireLogin()
                } label: {
                    Text("登录后同步进度")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.black)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 9)
                        .background(.white, in: Capsule())
                }
                .buttonStyle(.plain)
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.uniLabel(11))
                    .foregroundStyle(.white.opacity(0.8))
            }
        }
        .padding(22)
        .background(
            LinearGradient(colors: [UniColor.ink, Color(hex: 0x14264A), UniColor.blue.opacity(0.9)],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 30, style: .continuous)
        )
        .overlay(alignment: .topTrailing) {
            Circle()
                .fill(UniColor.blue.opacity(pulse ? 0.28 : 0.12))
                .frame(width: 180, height: 180)
                .blur(radius: 40)
                .offset(x: 50, y: -60)
                .allowsHitTesting(false)
        }
        .clipped()
    }

    private var stagePath: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("成长路径")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(UniColor.ink)

            VStack(spacing: 0) {
                ForEach(Array((achievement?.stages ?? []).enumerated()), id: \.offset) { index, stage in
                    HStack(alignment: .top, spacing: 14) {
                        VStack(spacing: 0) {
                            ZStack {
                                Circle()
                                    .fill(nodeState(stage) == .locked ? UniColor.mist : UniColor.ink)
                                    .frame(width: 30, height: 30)
                                Image(systemName: nodeState(stage).icon)
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundStyle(nodeState(stage) == .locked ? UniColor.tertiary : .white)
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
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundStyle(nodeState(stage) == .locked ? UniColor.tertiary : UniColor.ink)
                                if stage.code == achievement?.currentStageCode {
                                    Text("进行中")
                                        .font(.uniLabel(9))
                                        .foregroundStyle(UniColor.blue)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(UniColor.blue.opacity(0.1), in: Capsule())
                                }
                                if stage.boss {
                                    Text("BOSS")
                                        .font(.uniLabel(9))
                                        .foregroundStyle(UniColor.danger)
                                }
                            }
                            Text(stage.subtitle ?? "")
                                .font(.uniBody(12))
                                .foregroundStyle(UniColor.secondary)
                            Text("\(stage.completedCount)/\(stage.totalCount)")
                                .font(.uniLabel(10))
                                .foregroundStyle(UniColor.tertiary)
                        }
                        Spacer()
                    }
                    .padding(.bottom, 4)
                }
            }
            .padding(18)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(UniColor.mist.opacity(0.7), lineWidth: 1)
            )
        }
    }

    private var currentTasks: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("当前阶段任务")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(UniColor.ink)
                Spacer()
                if let currentStage {
                    Text("\(currentStage.completedCount)/\(currentStage.totalCount)")
                        .font(.uniLabel(11))
                        .foregroundStyle(UniColor.tertiary)
                }
            }

            if let currentStage {
                VStack(spacing: 0) {
                    ForEach(currentStage.tasks) { task in
                        HStack(spacing: 10) {
                            Button {
                                guard !task.completed else { return }
                                Task { await complete(task) }
                            } label: {
                                Image(systemName: task.completed ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 20, weight: .semibold))
                                    .foregroundStyle(task.completed ? UniColor.blue : UniColor.tertiary)
                            }
                            .buttonStyle(.plain)
                            .disabled(task.completed || completingId == task.id)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(task.title)
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(task.completed ? UniColor.secondary : UniColor.ink)
                                    .strikethrough(task.completed, color: UniColor.tertiary)
                                if let description = task.description, !description.isEmpty {
                                    Text(description)
                                        .font(.uniLabel(11))
                                        .foregroundStyle(UniColor.tertiary)
                                        .lineLimit(2)
                                }
                            }
                            Spacer()
                            Text("+\(task.xp)")
                                .font(.uniLabel(10))
                                .foregroundStyle(UniColor.gold)
                        }
                        .padding(.vertical, 10)
                        if task.id != currentStage.tasks.last?.id {
                            Divider().padding(.leading, 30)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .stroke(UniColor.mist.opacity(0.7), lineWidth: 1)
                )
            } else {
                Text(isLoading ? "加载中…" : "登录后查看你的阶段任务")
                    .font(.uniBody(13))
                    .foregroundStyle(UniColor.secondary)
                    .padding(.vertical, 20)
            }
        }
    }

    private var badgeGrid: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("徽章墙")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(UniColor.ink)
                Spacer()
                if let achievement {
                    Text("\(achievement.badges.filter(\.earned).count) / \(achievement.badges.count)")
                        .font(.uniLabel(11))
                        .foregroundStyle(UniColor.tertiary)
                }
            }

            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                ForEach((achievement?.badges ?? []).prefix(12)) { badge in
                    VStack(spacing: 8) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(badgeTint(badge.rarity).opacity(badge.earned ? 0.12 : 0.05))
                                .frame(width: 58, height: 58)
                            Image(systemName: badge.icon)
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundStyle(badge.earned ? badgeTint(badge.rarity) : UniColor.tertiary.opacity(0.6))
                        }
                        Text(badge.earned ? badge.title : "未解锁")
                            .font(.uniLabel(11))
                            .foregroundStyle(badge.earned ? UniColor.ink : UniColor.tertiary)
                            .lineLimit(1)
                        Text(rarityLabel(badge.rarity))
                            .font(.uniLabel(9))
                            .foregroundStyle(badgeTint(badge.rarity))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(badgeTint(badge.rarity).opacity(badge.earned ? 0.2 : 0.08), lineWidth: 1)
                    )
                    .scaleEffect(appeared ? 1 : 0.92)
                }
            }
        }
    }

    private func nodeState(_ stage: AchievementStageView) -> StageState {
        if stage.code == achievement?.currentStageCode { return .current }
        if stage.totalCount > 0 && stage.completedCount == stage.totalCount { return .done }
        if stage.boss { return .boss }
        return .locked
    }

    private func badgeTint(_ rarity: String) -> Color {
        switch rarity {
        case "RARE": return UniColor.blue
        case "EPIC": return UniColor.gold
        case "LEGENDARY": return UniColor.danger
        case "LIMITED": return Color(hex: 0x8B5CF6)
        default: return UniColor.secondary
        }
    }

    private func rarityLabel(_ rarity: String) -> String {
        switch rarity {
        case "RARE": return "稀有"
        case "EPIC": return "史诗"
        case "LEGENDARY": return "传说"
        case "LIMITED": return "限定"
        default: return "普通"
        }
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

    private func complete(_ task: AchievementTaskView) async {
        completingId = task.id
        do {
            achievement = try await APIClient.shared.completeTask(id: task.id)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
        }
        completingId = nil
    }
}

private enum StageState {
    case done, current, locked, boss

    var icon: String {
        switch self {
        case .done: return "checkmark"
        case .current: return "arrow.right"
        case .locked: return "lock.fill"
        case .boss: return "crown.fill"
        }
    }
}
