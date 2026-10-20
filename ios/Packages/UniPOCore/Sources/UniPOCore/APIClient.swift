import Foundation

public enum APIError: LocalizedError {
    case invalidURL
    case transport(Error)
    case decoding(Error)
    case unauthorized(String)
    case server(code: String, message: String, status: Int)

    public var errorDescription: String? {
        switch self {
        case .invalidURL: return "请求地址不正确"
        case .transport: return "网络连接失败，请检查网络后重试"
        case .decoding: return "数据解析失败"
        case .unauthorized(let message): return message.isEmpty ? "登录已过期，请重新登录" : message
        case .server(_, let message, _): return message
        }
    }
}

public actor APIClient {
    public static let shared = APIClient()

    private var accessToken: String?
    private let session: URLSession

    public init(session: URLSession? = nil) {
        if let session {
            self.session = session
            return
        }
        // App 只走 Bearer：绝对不能自动存/带 Cookie。
        // 否则登录响应里的 unipo_sid 会被 URLSession 存下来，下一次写请求就变成
        // 「Cookie 认证 + 没有 CSRF 头」，后端直接 403（请求校验失败）。
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.httpCookieStorage = nil
        self.session = URLSession(configuration: config)
    }

    public func setAccessToken(_ token: String?) {
        accessToken = token
    }

    public func login(email: String, password: String) async throws -> AuthSession {
        try await request(
            path: "auth/login",
            method: "POST",
            body: ["email": email, "password": password]
        )
    }

    public func refresh(refreshToken: String) async throws -> AuthSession {
        try await request(
            path: "auth/refresh",
            method: "POST",
            body: ["refreshToken": refreshToken]
        )
    }

    public func me() async throws -> UserProfile {
        try await request(path: "auth/me")
    }

    public func feed(tab: String = "recommend", cursor: String? = nil, limit: Int = 20) async throws -> PostPage {
        var query = [URLQueryItem(name: "tab", value: tab), URLQueryItem(name: "limit", value: String(limit))]
        if let cursor, !cursor.isEmpty {
            query.append(URLQueryItem(name: "cursor", value: cursor))
        }
        return try await request(path: "posts/page", query: query)
    }

    public func createPost(title: String, content: String, board: String = "school", tags: [String] = []) async throws -> PostView {
        try await request(
            path: "posts",
            method: "POST",
            body: [
                "board": board,
                "title": title,
                "content": content,
                "tags": tags
            ]
        )
    }

    public func logout(refreshToken: String?) async {
        _ = try? await request(
            path: "auth/logout",
            method: "POST",
            body: refreshToken.map { ["refreshToken": $0] }
        ) as EmptyResponse
    }

    private func request<T: Decodable>(
        path: String,
        method: String = "GET",
        query: [URLQueryItem] = [],
        body: [String: Any]? = nil
    ) async throws -> T {
        guard var components = URLComponents(string: AppConfig.apiBaseURL.absoluteString + "/" + path) else {
            throw APIError.invalidURL
        }
        if !query.isEmpty {
            components.queryItems = query
        }
        guard let url = components.url else { throw APIError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("ios", forHTTPHeaderField: "X-Client")
        // 登录/刷新不能带旧 token：服务端过滤链看到过期 Bearer 会直接 403，
        // 表现就是「刚登录成功，再打开又是未登录」。
        if let accessToken, path != "auth/login", path != "auth/refresh" {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw APIError.transport(error)
        }

        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 {
            let message = (try? JSONDecoder().decode(APIEnvelope<EmptyResponse>.self, from: data))?.message ?? ""
            throw APIError.unauthorized(message)
        }

        do {
            let envelope = try JSONDecoder().decode(APIEnvelope<T>.self, from: data)
            guard envelope.code == "OK", let payload = envelope.data else {
                throw APIError.server(code: envelope.code, message: envelope.message, status: status)
            }
            return payload
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.decoding(error)
        }
    }
}

public struct EmptyResponse: Codable {
    public init() {}
}
