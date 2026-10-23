import SwiftUI

public enum UniColor {
    public static let ink = Color(hex: 0x0B0F17)
    public static let inkSoft = Color(hex: 0x1A2230)
    public static let blue = Color(hex: 0x2F6BFF)
    public static let blueBright = Color(hex: 0x0A84FF)
    public static let paper = Color(hex: 0xF7F8FB)
    public static let mist = Color(hex: 0xE9EDF4)
    public static let secondary = Color(hex: 0x6B7688)
    public static let tertiary = Color(hex: 0x9AA4B2)
    public static let danger = Color(hex: 0xE5484D)
    public static let gold = Color(hex: 0xC9A227)
}

public enum UniSpace {
    public static let xs: CGFloat = 4
    public static let sm: CGFloat = 8
    public static let md: CGFloat = 12
    public static let lg: CGFloat = 16
    public static let xl: CGFloat = 24
    public static let xxl: CGFloat = 32
}

/// 圆角只用这几档：12 输入框与小按钮、16 按钮与小卡、18 图块、22 卡片、30 头图面板。
public enum UniRadius {
    public static let control: CGFloat = 12
    public static let button: CGFloat = 16
    public static let tile: CGFloat = 18
    public static let card: CGFloat = 22
    public static let panel: CGFloat = 30
}

public extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

/// 固定点数字体，只给必须返回 `Text` 的场合（例如 `Text + Text` 拼接）用；
/// 其他地方一律用会随 Dynamic Type 缩放的 `.uniFont(_:weight:)`。
public extension Font {
    static func uniTitle(_ size: CGFloat = 28) -> Font {
        .system(size: size, weight: .bold, design: .default)
    }

    static func uniBody(_ size: CGFloat = 15) -> Font {
        .system(size: size, weight: .regular, design: .default)
    }

    static func uniLabel(_ size: CGFloat = 12) -> Font {
        .system(size: size, weight: .semibold, design: .default)
    }
}

public struct UniCard<Content: View>: View {
    private let padding: CGFloat
    private let content: Content

    public init(padding: CGFloat = UniSpace.lg, @ViewBuilder content: () -> Content) {
        self.padding = padding
        self.content = content()
    }

    public var body: some View {
        content
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                    .fill(Color.white)
                    .shadow(color: UniColor.ink.opacity(0.05), radius: 18, y: 8)
            )
            .overlay(
                RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                    .stroke(UniColor.mist.opacity(0.7), lineWidth: 1)
            )
    }
}
