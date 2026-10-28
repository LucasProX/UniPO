import SwiftUI
import UniPOCore
import UniPOUI

/// 帖子图片宫格。行列只由张数决定，整体尺寸由调用方给定，所以卡片高度不会因为图片多少而变化：
/// 1 张整幅；2、3 张一行；4 张 2×2；5、6 张 3×2；7 至 9 张 3×3。超过 9 张时最后一格标出剩余张数。
struct PostMediaGrid: View {
    let urls: [String]
    var spacing: CGFloat = 4
    var cornerRadius: CGFloat = UniRadius.button
    var onTap: (Int) -> Void = { _ in }

    static let maxCells = 9

    /// (列数, 行数)
    static func layout(for count: Int) -> (columns: Int, rows: Int) {
        switch count {
        case ...1: return (1, 1)
        case 2: return (2, 1)
        case 3: return (3, 1)
        case 4: return (2, 2)
        case 5, 6: return (3, 2)
        default: return (3, 3)
        }
    }

    var body: some View {
        let shown = Array(urls.prefix(Self.maxCells))
        let (columns, rows) = Self.layout(for: shown.count)
        VStack(spacing: spacing) {
            ForEach(0..<rows, id: \.self) { row in
                HStack(spacing: spacing) {
                    ForEach(0..<columns, id: \.self) { column in
                        let index = row * columns + column
                        if index < shown.count {
                            cell(shown[index], index: index, extra: index == shown.count - 1 ? urls.count - shown.count : 0)
                        } else {
                            Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }

    private func cell(_ raw: String, index: Int, extra: Int) -> some View {
        Button { onTap(index) } label: {
            UniColor.mist
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlay {
                    if let url = AppConfig.absoluteURL(raw) {
                        RemoteImage(url: url) { phase in
                            if case .success(let image) = phase {
                                image.resizable().scaledToFill()
                            }
                        }
                    }
                }
                .overlay {
                    if extra > 0 {
                        ZStack {
                            Color.black.opacity(0.4)
                            Text("+\(extra)").uniFont(18, weight: .bold).foregroundStyle(.white)
                        }
                    }
                }
                .clipped()
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("第 \(index + 1) 张图片，共 \(urls.count) 张")
    }
}

/// 全屏看图：左右滑切换，双指缩放、双击放大，点「关闭」退出。
struct PostImageViewer: View {
    @Environment(\.dismiss) private var dismiss
    let urls: [String]
    @State var index: Int

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(Array(urls.enumerated()), id: \.offset) { offset, raw in
                    ZoomableImage(raw: raw)
                        .tag(offset)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: urls.count > 1 ? .automatic : .never))
            .ignoresSafeArea()

            HStack {
                if urls.count > 1 {
                    Text("\(index + 1) / \(urls.count)")
                        .uniFont(14, weight: .semibold)
                        .foregroundStyle(.white)
                        .monospacedDigit()
                }
                Spacer()
                Button { dismiss() } label: {
                    Image(systemName: "xmark")
                        .uniFont(15, weight: .bold)
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .background(.white.opacity(0.16), in: Circle())
                }
                .accessibilityLabel("关闭")
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
        }
        .statusBarHidden()
    }
}

private struct ZoomableImage: View {
    let raw: String

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        Group {
            if let url = AppConfig.absoluteURL(raw) {
                RemoteImage(url: url, sizing: .original) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFit()
                    case .failure:
                        Image(systemName: "photo").uniFont(32).foregroundStyle(.white.opacity(0.5))
                    default:
                        ProgressView().tint(.white)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .scaleEffect(scale)
        .offset(offset)
        .gesture(
            MagnifyGesture()
                .onChanged { value in scale = min(max(lastScale * value.magnification, 1), 4) }
                .onEnded { _ in
                    lastScale = scale
                    if scale <= 1 { reset() }
                }
        )
        // 只在放大后挂拖动手势做平移。原始大小时不挂任何拖动：
        // 哪怕是 simultaneousGesture，也会把左右滑从 TabView 手里抢走，翻页就失灵了。
        .gesture(
            DragGesture()
                .onChanged { value in
                    offset = CGSize(width: lastOffset.width + value.translation.width,
                                    height: lastOffset.height + value.translation.height)
                }
                .onEnded { _ in lastOffset = offset },
            including: scale > 1 ? .all : .subviews
        )
        .onTapGesture(count: 2) {
            withAnimation(.spring(response: 0.3)) {
                if scale > 1 { reset() } else { scale = 2.5; lastScale = 2.5 }
            }
        }
    }

    private func reset() {
        scale = 1
        lastScale = 1
        offset = .zero
        lastOffset = .zero
    }
}

struct ImageViewerTarget: Identifiable {
    let urls: [String]
    let index: Int
    var id: Int { index }
}
