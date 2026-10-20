import SwiftUI
import UniPOUI

struct DockBar: View {
    @Binding var tab: AppTab
    let isAuthenticated: Bool
    let onCompose: () -> Void
    let onRequestAuth: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            dockItem(.home, icon: "house.fill", label: "首页")
            dockItem(.tools, icon: "square.grid.2x2.fill", label: "工具")

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

            dockItem(.achievements, icon: "rosette", label: "成就")
            dockItem(.profile, icon: "person.fill", label: "我的")
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 9)
        .glassSurface(radius: 34, interactive: true)
    }

    private func dockItem(_ item: AppTab, icon: String, label: String) -> some View {
        Button {
            // 「我的」是登录后才存在的地方；未登录先跳登录，不展示游客态主页。
            if item == .profile && !isAuthenticated {
                onRequestAuth()
                return
            }
            withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) {
                tab = item
            }
        } label: {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 19, weight: .semibold))
                Text(label)
                    .font(.system(size: 10, weight: .semibold))
            }
            .foregroundStyle(tab == item ? UniColor.ink : UniColor.tertiary)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
