import SwiftUI

public struct UniAvatar: View {
    private let url: String?
    private let name: String
    private let size: CGFloat

    public init(url: String?, name: String, size: CGFloat = 44) {
        self.url = url
        self.name = name
        self.size = size
    }

    public var body: some View {
        Group {
            if let url, let parsed = URL(string: url) {
                AsyncImage(url: parsed) { phase in
                    if case .success(let image) = phase {
                        image.resizable().scaledToFill()
                    } else {
                        fallback
                    }
                }
            } else {
                fallback
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.34, style: .continuous))
    }

    private var fallback: some View {
        ZStack {
            LinearGradient(colors: [UniColor.ink, UniColor.blue],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            Text(String(name.prefix(1)).uppercased())
                .font(.system(size: size * 0.4, weight: .bold))
                .foregroundStyle(.white)
        }
    }
}

public struct UniChip: View {
    private let text: String
    private let tint: Color

    public init(_ text: String, tint: Color = UniColor.secondary) {
        self.text = text
        self.tint = tint
    }

    public var body: some View {
        Text(text)
            .font(.uniLabel(11))
            .foregroundStyle(tint)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(tint.opacity(0.09), in: Capsule())
    }
}

public struct UniPrimaryButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(UniColor.ink, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .opacity(configuration.isPressed ? 0.82 : 1)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.8), value: configuration.isPressed)
    }
}

public struct UniGhostButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(UniColor.ink)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(UniColor.mist.opacity(0.7), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}

public struct UniLoadingState: View {
    private let text: String

    public init(_ text: String = "加载中…") {
        self.text = text
    }

    public var body: some View {
        VStack(spacing: 12) {
            ProgressView()
                .tint(UniColor.ink)
            Text(text)
                .font(.uniBody(13))
                .foregroundStyle(UniColor.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }
}
