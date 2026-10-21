import SwiftUI

public enum UniAvatarShape {
    case rounded
    case circle
}

public struct UniAvatar: View {
    private let url: String?
    private let name: String
    private let size: CGFloat
    private let shape: UniAvatarShape

    public init(url: String?, name: String, size: CGFloat = 44, shape: UniAvatarShape = .rounded) {
        self.url = url
        self.name = name
        self.size = size
        self.shape = shape
    }

    public var body: some View {
        Group {
            if let url, let parsed = AvatarURL.resolve(url) {
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
        .clipShape(UniAvatarClipShape(shape: shape, size: size))
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

private struct UniAvatarClipShape: Shape {
    let shape: UniAvatarShape
    let size: CGFloat

    func path(in rect: CGRect) -> Path {
        switch shape {
        case .circle:
            return Circle().path(in: rect)
        case .rounded:
            return RoundedRectangle(cornerRadius: size * 0.34, style: .continuous).path(in: rect)
        }
    }
}

private enum AvatarURL {
    static func resolve(_ raw: String?) -> URL? {
        guard let raw, !raw.isEmpty else { return nil }
        if let url = URL(string: raw), url.scheme != nil { return url }
        let base = Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String
            ?? "http://127.0.0.1:8080/api/v1"
        guard var origin = URLComponents(string: base) else { return nil }
        origin.path = ""
        origin.query = nil
        guard let root = origin.url else { return nil }
        return URL(string: raw, relativeTo: root)?.absoluteURL
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
