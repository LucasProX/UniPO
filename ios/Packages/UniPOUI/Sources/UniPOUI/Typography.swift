import SwiftUI

/// 语义字体：设计稿里写的是默认字号下的点数，这里把它挂到最接近的系统文字样式上，
/// 随「设置 › 显示与亮度 › 文字大小」与辅助功能大字体等比缩放。
///
/// 用 `.uniFont(13, weight: .semibold)` 代替 `.font(.system(size: 13, weight: .semibold))`。
/// 后者是固定点数，Dynamic Type 调多大都不变。
public extension View {
    func uniFont(_ size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> some View {
        modifier(UniScaledFont(size: size, weight: weight, design: design))
    }
}

public enum UniType {
    /// 默认字号下的点数对应的系统文字样式，缩放曲线跟随该样式。
    public static func textStyle(for size: CGFloat) -> Font.TextStyle {
        switch size {
        case ..<11.5: return .caption2
        case ..<12.5: return .caption
        case ..<14: return .footnote
        case ..<15.5: return .subheadline
        case ..<16.5: return .callout
        case ..<19: return .body
        case ..<21: return .title3
        case ..<25: return .title2
        case ..<31: return .title
        default: return .largeTitle
        }
    }

    /// 放大上限。正文最多放大到 2 倍，大标题与数字最多 1.35 倍：
    /// 再往上，固定尺寸的头图、圆环和卡片会被撑破，而它们已经足够大、可读。
    public static func maxScale(for size: CGFloat) -> CGFloat {
        if size >= 28 { return 1.35 }
        if size >= 20 { return 1.6 }
        return 2
    }

    /// 实际字号：系统给的缩放结果夹在 [默认值的 0.85 倍, 上限] 之间。
    public static func clamped(_ scaled: CGFloat, base: CGFloat) -> CGFloat {
        min(max(scaled, base * 0.85), base * maxScale(for: base))
    }
}

struct UniScaledFont: ViewModifier {
    @ScaledMetric private var scaled: CGFloat
    private let size: CGFloat
    private let weight: Font.Weight
    private let design: Font.Design

    init(size: CGFloat, weight: Font.Weight, design: Font.Design) {
        self.size = size
        self.weight = weight
        self.design = design
        _scaled = ScaledMetric(wrappedValue: size, relativeTo: UniType.textStyle(for: size))
    }

    func body(content: Content) -> some View {
        content.font(.system(size: UniType.clamped(scaled, base: size), weight: weight, design: design))
    }
}
