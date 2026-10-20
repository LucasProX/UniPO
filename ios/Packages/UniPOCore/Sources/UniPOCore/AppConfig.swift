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

    public static let appGroup = "group.com.unipo.app"
}
