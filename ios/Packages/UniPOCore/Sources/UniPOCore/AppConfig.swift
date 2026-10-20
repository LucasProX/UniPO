import Foundation

public enum AppConfig {
    /// 调试时优先用 Info.plist 的 API_BASE_URL，方便切本机/测试环境。
    public static var apiBaseURL: URL {
        if let raw = Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String,
           let url = URL(string: raw) {
            return url
        }
        return URL(string: "http://127.0.0.1:8080/api/v1")!
    }

    /// 后端媒体地址可能是相对路径（/api/media/...），App 里统一补成绝对 URL。
    public static func absoluteURL(_ raw: String?) -> URL? {
        guard let raw, !raw.isEmpty else { return nil }
        if raw.hasPrefix("http://") || raw.hasPrefix("https://") {
            return URL(string: raw)
        }
        var components = URLComponents()
        components.scheme = apiBaseURL.scheme
        components.host = apiBaseURL.host
        components.port = apiBaseURL.port
        guard let origin = components.url else { return nil }
        return URL(string: raw, relativeTo: origin)?.absoluteURL
    }

    public static var realtimeURL: URL {
        var components = URLComponents(url: apiBaseURL.appendingPathComponent("realtime/ws"),
                                       resolvingAgainstBaseURL: false)
        components?.scheme = apiBaseURL.scheme == "https" ? "wss" : "ws"
        return components?.url ?? URL(string: "ws://127.0.0.1:8080/api/v1/realtime/ws")!
    }

    public static let appGroup = "group.com.unipo.app"
}
