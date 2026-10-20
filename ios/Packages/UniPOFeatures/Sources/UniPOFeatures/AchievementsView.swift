import SwiftUI
import UniPOUI

struct AchievementsView: View {
    @State private var appeared = false
    @State private var pulse = false

    private let stages: [StageNode] = [
        StageNode(title: "大一上", caption: "适应与探索", state: .current),
        StageNode(title: "大一下", caption: "四级 · 社团 · 转专业", state: .locked),
        StageNode(title: "大二", caption: "竞赛 · 实验室", state: .locked),
        StageNode(title: "大三", caption: "实习 · 交换", state: .locked),
        StageNode(title: "大四", caption: "求职 / 考研 / 考公", state: .boss)
    ]

    private let badges: [BadgeItem] = [
        BadgeItem(title: "初来乍到", rarity: "普通", icon: "sparkles", tint: UniColor.secondary),
        BadgeItem(title: "四级报名", rarity: "稀有", icon: "checkmark.seal.fill", tint: UniColor.blue),
        BadgeItem(title: "社团面试", rarity: "稀有", icon: "person.3.fill", tint: UniColor.blueBright),
        BadgeItem(title: "图书馆常客", rarity: "史诗", icon: "books.vertical.fill", tint: UniColor.gold),
        BadgeItem(title: "迎新志愿者", rarity: "限定", icon: "heart.fill", tint: UniColor.danger),
        BadgeItem(title: "???", rarity: "传说", icon: "lock.fill", tint: UniColor.tertiary)
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                hero
                stagePath
                badgeGrid
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 120)
        }
        .scrollIndicators(.hidden)
        .onAppear {
            withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(0.1)) {
                appeared = true
            }
            withAnimation(.easeInOut(duration: 2.2).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
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
                Text("预览")
                    .font(.uniLabel(10))
                    .foregroundStyle(.white.opacity(0.7))
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(.white.opacity(0.12), in: Capsule())
            }

            HStack(spacing: 16) {
                ZStack {
                    Circle()
                        .stroke(.white.opacity(0.14), lineWidth: 9)
                    Circle()
                        .trim(from: 0, to: appeared ? 0.42 : 0)
                        .stroke(UniColor.blueBright,
                                style: StrokeStyle(lineWidth: 9, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    VStack(spacing: 1) {
                        Text("42%")
                            .font(.system(size: 20, weight: .heavy))
                            .foregroundStyle(.white)
                        Text("大一上")
                            .font(.uniLabel(10))
                            .foregroundStyle(.white.opacity(0.6))
                    }
                }
                .frame(width: 94, height: 94)

                VStack(alignment: .leading, spacing: 7) {
                    Text("当前阶段：大一上 · 第 3 周")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(.white)
                    Text("还有 2 个任务没完成")
                        .font(.uniBody(12))
                        .foregroundStyle(.white.opacity(0.62))
                    HStack(spacing: 6) {
                        Image(systemName: "flame.fill")
                        Text("连签 6 天")
                    }
                    .font(.uniLabel(11))
                    .foregroundStyle(UniColor.gold)
                }
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
                ForEach(Array(stages.enumerated()), id: \.offset) { index, stage in
                    HStack(alignment: .top, spacing: 14) {
                        VStack(spacing: 0) {
                            ZStack {
                                Circle()
                                    .fill(stage.state == .locked ? UniColor.mist : UniColor.ink)
                                    .frame(width: 30, height: 30)
                                Image(systemName: stage.state.icon)
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundStyle(stage.state == .locked ? UniColor.tertiary : .white)
                            }
                            if index < stages.count - 1 {
                                Rectangle()
                                    .fill(stage.state == .done ? UniColor.blue.opacity(0.5) : UniColor.mist)
                                    .frame(width: 2, height: 40)
                            }
                        }
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(stage.title)
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundStyle(stage.state == .locked ? UniColor.tertiary : UniColor.ink)
                                if stage.state == .current {
                                    Text("进行中")
                                        .font(.uniLabel(9))
                                        .foregroundStyle(UniColor.blue)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(UniColor.blue.opacity(0.1), in: Capsule())
                                }
                                if stage.state == .boss {
                                    Text("BOSS")
                                        .font(.uniLabel(9))
                                        .foregroundStyle(UniColor.danger)
                                }
                            }
                            Text(stage.caption)
                                .font(.uniBody(12))
                                .foregroundStyle(UniColor.secondary)
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

    private var badgeGrid: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("徽章墙")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(UniColor.ink)
                Spacer()
                Text("6 / 24")
                    .font(.uniLabel(11))
                    .foregroundStyle(UniColor.tertiary)
            }

            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                ForEach(badges) { badge in
                    VStack(spacing: 8) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(badge.tint.opacity(0.10))
                                .frame(width: 58, height: 58)
                            Image(systemName: badge.icon)
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundStyle(badge.tint)
                        }
                        Text(badge.title)
                            .font(.uniLabel(11))
                            .foregroundStyle(UniColor.ink)
                            .lineLimit(1)
                        Text(badge.rarity)
                            .font(.uniLabel(9))
                            .foregroundStyle(badge.tint)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(badge.tint.opacity(0.18), lineWidth: 1)
                    )
                    .scaleEffect(appeared ? 1 : 0.92)
                }
            }
        }
    }
}

private struct StageNode {
    let title: String
    let caption: String
    let state: StageState
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

private struct BadgeItem: Identifiable {
    let id = UUID()
    let title: String
    let rarity: String
    let icon: String
    let tint: Color
}
