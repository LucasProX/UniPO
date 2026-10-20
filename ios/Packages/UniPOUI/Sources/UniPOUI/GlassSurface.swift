import SwiftUI

/// Liquid Glass（iOS 26+），旧系统退化为 ultraThinMaterial。
public struct GlassSurface: ViewModifier {
    private let radius: CGFloat
    private let interactive: Bool

    public init(radius: CGFloat = UniRadius.panel, interactive: Bool = false) {
        self.radius = radius
        self.interactive = interactive
    }

    public func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(
                interactive ? .regular.interactive() : .regular,
                in: RoundedRectangle(cornerRadius: radius, style: .continuous)
            )
        } else {
            content
                .background(.ultraThinMaterial,
                            in: RoundedRectangle(cornerRadius: radius, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .stroke(Color.white.opacity(0.22), lineWidth: 1)
                )
        }
    }
}

public extension View {
    func glassSurface(radius: CGFloat = UniRadius.panel, interactive: Bool = false) -> some View {
        modifier(GlassSurface(radius: radius, interactive: interactive))
    }
}
