import SwiftUI
import UniPOCore
import UniPOUI

extension Notification.Name {
    /// object 为新的称号（AchievementBadgeView?）；「我的」页头部据此更新（F15）。
    static let unipoTitleChanged = Notification.Name("unipo.achievement.title")
}

/// 徽章的稀有度配色。U03 的正式视觉（边框、角标、获得动效）还没定，先沿用徽章墙原来的颜色，
/// 只在这里维护一份，徽章墙、详情和称号共用。
enum BadgeStyle {
    static func tint(_ rarity: BadgeRarity) -> Color {
        switch rarity {
        case .rare: return UniColor.blue
        case .epic: return UniColor.gold
        case .legendary: return UniColor.danger
        case .limited: return Color(hex: 0x8B5CF6)
        case .common: return UniColor.secondary
        }
    }
}

/// 徽章图标：圆角方块 + SF Symbol，未获得时变灰。
struct BadgeMedal: View {
    let badge: AchievementBadgeView
    var size: CGFloat = 58

    var body: some View {
        let tint = BadgeStyle.tint(badge.rarityLevel)
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.3, style: .continuous)
                .fill(tint.opacity(badge.earned ? 0.12 : 0.05))
            RoundedRectangle(cornerRadius: size * 0.3, style: .continuous)
                .stroke(tint.opacity(badge.earned ? 0.35 : 0.1), lineWidth: badge.rarityLevel.rank >= BadgeRarity.epic.rank ? 2 : 1)
            // 图标跟着方块大小走，不随 Dynamic Type 缩放（和头像首字母一样）。
            Image(systemName: badge.icon)
                .resizable()
                .scaledToFit()
                .fontWeight(.semibold)
                .frame(width: size * 0.4, height: size * 0.4)
                .foregroundStyle(badge.earned ? tint : UniColor.tertiary.opacity(0.6))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// 主页称号：名字下面的一枚胶囊，颜色跟着稀有度。
struct BadgeTitleChip: View {
    let badge: AchievementBadgeView

    var body: some View {
        let tint = BadgeStyle.tint(badge.rarityLevel)
        HStack(spacing: 4) {
            Image(systemName: badge.icon)
            Text(badge.title).lineLimit(1)
        }
        .uniFont(11, weight: .semibold)
        .foregroundStyle(tint)
        .padding(.horizontal, 9)
        .frame(minHeight: 24)
        .background(tint.opacity(0.1), in: Capsule())
        .overlay(Capsule().stroke(tint.opacity(0.25), lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("称号：\(badge.title)，\(badge.rarityLevel.label)")
    }
}

/// F15：徽章详情。获得条件、稀有度、获得时间；自己已获得的可以设为主页称号。
struct BadgeDetailSheet: View {
    @Environment(\.dismiss) private var dismiss
    let badge: AchievementBadgeView
    var isCurrentTitle = false
    /// 为空时只读（别人的主页）；传 nil 取消称号。
    var onTitleChange: ((String?) async throws -> Void)?

    @State private var isSaving = false
    @State private var errorMessage: String?

    var body: some View {
        let rarity = badge.rarityLevel
        VStack(spacing: 16) {
            BadgeMedal(badge: badge, size: 96)
                .padding(.top, 28)
            VStack(spacing: 6) {
                Text(badge.title)
                    .uniFont(22, weight: .bold)
                    .foregroundStyle(UniColor.ink)
                Text(rarity.label)
                    .uniFont(12, weight: .semibold)
                    .foregroundStyle(BadgeStyle.tint(rarity))
                    .padding(.horizontal, 10)
                    .frame(minHeight: 24)
                    .background(BadgeStyle.tint(rarity).opacity(0.1), in: Capsule())
            }
            VStack(spacing: 4) {
                Text("获得条件")
                    .uniFont(12, weight: .semibold)
                    .foregroundStyle(UniColor.tertiary)
                Text(badge.description ?? "暂无说明")
                    .uniFont(15)
                    .foregroundStyle(UniColor.inkSoft)
                    .multilineTextAlignment(.center)
            }
            Text(badge.earned
                 ? badge.earnedAt.map { "\(GrowthDate.fullDate($0)) 获得" } ?? "已获得"
                 : "尚未获得")
                .uniFont(13, weight: .semibold)
                .foregroundStyle(badge.earned ? UniColor.secondary : UniColor.tertiary)

            if let errorMessage {
                Text(errorMessage).uniFont(12, weight: .semibold).foregroundStyle(UniColor.danger)
            }
            Spacer(minLength: 0)
            if badge.earned, let onTitleChange {
                Button {
                    Task {
                        isSaving = true
                        errorMessage = nil
                        defer { isSaving = false }
                        do {
                            try await onTitleChange(isCurrentTitle ? nil : badge.code)
                            dismiss()
                        } catch {
                            errorMessage = (error as? LocalizedError)?.errorDescription ?? "设置失败，请重试"
                        }
                    }
                } label: {
                    if isSaving { ProgressView().tint(.white) }
                    else { Text(isCurrentTitle ? "取消主页称号" : "设为主页称号") }
                }
                .buttonStyle(UniPrimaryButtonStyle())
                .disabled(isSaving)
                Text(isCurrentTitle ? "现在展示在你的主页上" : "选中后会展示在你的主页名字下面")
                    .uniFont(11)
                    .foregroundStyle(UniColor.tertiary)
            }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 20)
        .frame(maxWidth: .infinity)
        .background(UniColor.paper.ignoresSafeArea())
        .presentationDetents([.medium])
        .presentationCornerRadius(UniRadius.panel)
    }
}
