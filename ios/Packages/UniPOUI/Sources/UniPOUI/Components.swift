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
            .uniFont(11, weight: .semibold)
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
            .uniFont(16, weight: .semibold)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(UniColor.ink, in: RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous))
            .opacity(configuration.isPressed ? 0.82 : 1)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.8), value: configuration.isPressed)
    }
}

public struct UniGhostButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .uniFont(15, weight: .semibold)
            .foregroundStyle(UniColor.ink)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(UniColor.mist.opacity(0.7), in: RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous))
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
                .uniFont(13)
                .foregroundStyle(UniColor.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }
}

/// 统一空态：图标 + 标题 + 说明 + 可选操作。用在列表为空、尚无记录的地方。
public struct UniEmptyState: View {
    private let icon: String
    private let title: String
    private let message: String?
    private let actionTitle: String?
    private let action: (() -> Void)?

    public init(icon: String, title: String, message: String? = nil,
                actionTitle: String? = nil, action: (() -> Void)? = nil) {
        self.icon = icon
        self.title = title
        self.message = message
        self.actionTitle = actionTitle
        self.action = action
    }

    public var body: some View {
        VStack(spacing: UniSpace.md) {
            Image(systemName: icon)
                .uniFont(26, weight: .semibold)
                .foregroundStyle(UniColor.tertiary)
                .accessibilityHidden(true)
            Text(title)
                .uniFont(15, weight: .semibold)
                .foregroundStyle(UniColor.ink)
                .multilineTextAlignment(.center)
            if let message {
                Text(message)
                    .uniFont(13)
                    .foregroundStyle(UniColor.secondary)
                    .multilineTextAlignment(.center)
            }
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .uniFont(14, weight: .semibold)
                    .foregroundStyle(UniColor.blue)
                    .frame(minHeight: 44)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, UniSpace.xl)
        .padding(.horizontal, UniSpace.lg)
        .accessibilityElement(children: .contain)
    }
}

/// 统一错误态：说明发生了什么 + 重试。接口失败必须显式展示，不能假装有数据。
public struct UniErrorState: View {
    private let message: String
    private let retryTitle: String
    private let retry: () -> Void

    public init(_ message: String?, retryTitle: String = "重试", retry: @escaping () -> Void) {
        self.message = (message?.isEmpty == false ? message : nil) ?? "加载失败，请检查网络后重试"
        self.retryTitle = retryTitle
        self.retry = retry
    }

    public var body: some View {
        VStack(spacing: UniSpace.md) {
            Image(systemName: "wifi.exclamationmark")
                .uniFont(24, weight: .semibold)
                .foregroundStyle(UniColor.tertiary)
                .accessibilityHidden(true)
            Text(message)
                .uniFont(13)
                .foregroundStyle(UniColor.secondary)
                .multilineTextAlignment(.center)
            Button(retryTitle, action: retry)
                .uniFont(14, weight: .semibold)
                .foregroundStyle(UniColor.ink)
                .padding(.horizontal, UniSpace.xl)
                .frame(minHeight: 44)
                .background(UniColor.mist.opacity(0.7), in: Capsule())
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, UniSpace.xl)
        .padding(.horizontal, UniSpace.lg)
    }
}
