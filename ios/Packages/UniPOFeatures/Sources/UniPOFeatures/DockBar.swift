import SwiftUI
import UniPOUI

struct DockBar: View {
    @Binding var tab: AppTab
    let isAuthenticated: Bool
    var unreadCount = 0
    let onCompose: () -> Void
    let onRequestAuth: (AppTab) -> Void

    var body: some View {
        HStack(spacing: 0) {
            dockItem(.home, icon: "house.fill", label: "首页")
            dockItem(.achievements, icon: "map.fill", label: "成长")

            Button(action: onCompose) {
                ZStack {
                    Circle()
                        .fill(UniColor.ink)
                        .frame(width: 54, height: 54)
                        .shadow(color: UniColor.ink.opacity(0.24), radius: 16, y: 8)
                    Image(systemName: "plus")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("发 PO")

            dockItem(.messages, icon: "bubble.left.and.bubble.right.fill", label: "消息", badge: unreadCount)
            dockItem(.profile, icon: "person.fill", label: "我的")
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 9)
        .glassSurface(radius: 34, interactive: true)
    }

    private func dockItem(_ item: AppTab, icon: String, label: String, badge: Int = 0) -> some View {
        Button {
            // 「消息」与「我的」是登录后才存在的地方；未登录先跳登录，不展示游客态。
            if (item == .profile || item == .messages) && !isAuthenticated {
                onRequestAuth(item)
                return
            }
            withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) {
                tab = item
            }
        } label: {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 19, weight: .semibold))
                    .overlay(alignment: .topTrailing) {
                        if badge > 0 {
                            Text(badge > 99 ? "99+" : "\(badge)")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 4)
                                .padding(.vertical, 1)
                                .background(UniColor.danger, in: Capsule())
                                .offset(x: 10, y: -6)
                        }
                    }
                Text(label)
                    .font(.system(size: 10, weight: .semibold))
            }
            .foregroundStyle(tab == item ? UniColor.ink : UniColor.tertiary)
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(badge > 0 ? "\(label)，\(badge) 条未读" : label)
        // 底栏和系统 Tab 栏一样不随字号放大（会挤掉内容区），大字体下长按显示放大预览。
        .accessibilityShowsLargeContentViewer {
            Label(label, systemImage: icon)
        }
    }
}
