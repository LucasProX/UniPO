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
        case .transport(let error):
            let error = error as NSError
            if error.domain == NSURLErrorDomain, error.code == NSURLErrorAppTransportSecurityRequiresSecureConnection {
                return "系统阻止了当前连接，请稍后重试"
            }
            return "网络连接失败，请检查网络后重试"
        case .decoding: return "数据解析失败"
        case .unauthorized(let message): return message.isEmpty ? "登录已过期，请重新登录" : message
        case .server(_, let message, _): return message
        }
    }
}

public actor APIClient {
    public static let shared = APIClient()

    nonisolated private let tokenState = AuthTokenState()
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

    func setAccessToken(_ token: String?) {
        tokenState.setForTesting(token)
    }

    nonisolated func advanceAuthenticationRevision(_ revision: UInt64, clearToken: Bool) {
        tokenState.advance(to: revision, clearToken: clearToken)
    }

    nonisolated func setUnauthorizedHandler(_ handler: @escaping @Sendable (UInt64) async -> Void) {
        tokenState.setUnauthorizedHandler(handler)
    }

    func setAccessToken(_ token: String?, revision: UInt64) {
        tokenState.set(token, revision: revision)
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

    public func requestSmsCode(phone: String) async throws -> SmsCodeResponse {
        try await request(path: "auth/sms-code", method: "POST", body: ["phone": phone])
    }

    public func smsLogin(phone: String, code: String, nickname: String? = nil) async throws -> AuthSession {
        var body: [String: Any] = ["phone": phone, "code": code]
        if let nickname, !nickname.isEmpty { body["nickname"] = nickname }
        return try await request(path: "auth/sms-login", method: "POST", body: body)
    }

    public func completeOnboarding(_ input: OnboardingInput) async throws -> UserProfile {
        var body: [String: Any] = [
            "nickname": input.nickname,
            "showGender": input.showGender,
            "showAge": input.showAge,
            "showGrade": input.showGrade
        ]
        if let gender = input.gender { body["gender"] = gender }
        if let birthYear = input.birthYear { body["birthYear"] = birthYear }
        if let grade = input.grade { body["grade"] = grade }
        if let zodiac = input.zodiac { body["zodiac"] = zodiac }
        if let mbti = input.mbti { body["mbti"] = mbti }
        if let signature = input.signature { body["signature"] = signature }
        if let avatarUrl = input.avatarUrl { body["avatarUrl"] = avatarUrl }
        return try await request(path: "users/me/onboarding", method: "PUT", body: body)
    }

    public func me() async throws -> UserProfile {
        try await request(path: "auth/me")
    }

    func me(accessToken: String) async throws -> UserProfile {
        try await request(path: "auth/me", authorization: .explicit(accessToken))
    }

    public func feed(tab: String = "recommend", cursor: String? = nil, limit: Int = 20) async throws -> PostPage {
        var query = [URLQueryItem(name: "tab", value: tab), URLQueryItem(name: "limit", value: String(limit))]
        if let cursor, !cursor.isEmpty {
            query.append(URLQueryItem(name: "cursor", value: cursor))
        }
        return try await request(path: "posts/page", query: query)
    }

    public func createPost(title: String, content: String, board: String = "school",
                           tags: [String] = [], images: [String] = []) async throws -> PostView {
        try await request(
            path: "posts",
            method: "POST",
            body: [
                "board": board,
                "title": title,
                "content": content,
                "tags": tags,
                "images": images
            ]
        )
    }

    public func comments(postId: Int64) async throws -> [CommentView] {
        try await request(path: "posts/\(postId)/comments")
    }

    public func addComment(postId: Int64, content: String, parentId: Int64? = nil) async throws -> CommentView {
        var body: [String: Any] = ["content": content]
        if let parentId { body["parentId"] = parentId }
        return try await request(path: "posts/\(postId)/comments", method: "POST", body: body)
    }

    @discardableResult
    public func likePost(id: Int64, liked: Bool) async throws -> Bool {
        try await request(path: "posts/\(id)/like", method: liked ? "DELETE" : "POST")
    }

    @discardableResult
    public func favoritePost(id: Int64, favorited: Bool) async throws -> Bool {
        try await request(path: "posts/\(id)/favorite", method: favorited ? "DELETE" : "POST")
    }

    public func conversations() async throws -> [ConversationView] {
        try await request(path: "messages/conversations")
    }

    public func messages(conversationId: Int64) async throws -> [MessageView] {
        try await request(path: "messages/conversations/\(conversationId)/messages")
    }

    public func sendMessage(conversationId: Int64, content: String) async throws -> MessageView {
        try await request(
            path: "messages/conversations/\(conversationId)/messages",
            method: "POST",
            body: ["content": content]
        )
    }

    public func createConversation(peerUid: String) async throws -> ConversationView {
        try await request(path: "messages/conversations", method: "POST", body: ["peerUid": peerUid])
    }

    @discardableResult
    public func registerPushToken(_ token: String) async throws -> Bool {
        try await request(
            path: "push/tokens",
            method: "POST",
            body: ["token": token, "platform": "ios", "environment": "sandbox"]
        )
    }

    public func userStats() async throws -> UserStatsView {
        try await request(path: "users/me/stats")
    }

    public func myPosts() async throws -> [PostView] {
        try await request(path: "users/me/posts")
    }

    public func likedPosts() async throws -> [PostView] {
        try await request(path: "users/me/likes")
    }

    public func favoritePosts() async throws -> [PostView] {
        try await request(path: "users/me/post-favorites")
    }

    public func updateAvatar(url: String) async throws -> UserProfile {
        try await request(path: "users/me", method: "PUT", body: ["avatarUrl": url])
    }

    public func updateBackground(url: String) async throws -> UserProfile {
        try await request(path: "users/me", method: "PUT", body: ["backgroundUrl": url])
    }

    public func publicProfile(uid: String) async throws -> PublicUserProfile {
        try await request(path: userPath(uid))
    }

    public func publicPosts(uid: String) async throws -> [PostView] {
        try await request(path: userPath(uid) + "/posts")
    }

    @discardableResult
    public func setFollowing(uid: String, following: Bool) async throws -> Bool {
        try await request(path: userPath(uid) + "/follow", method: following ? "POST" : "DELETE")
    }

    private func userPath(_ uid: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        return "users/" + (uid.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")
    }

    public func unreadCount() async throws -> Int {
        try await request(path: "messages/unread-count")
    }

    @discardableResult
    public func deleteAccount() async throws -> Bool {
        try await request(path: "users/me", method: "DELETE")
    }

    public func achievements() async throws -> AchievementView {
        try await request(path: "achievements/me")
    }

    public func completeTask(id: Int64) async throws -> AchievementView {
        try await request(path: "achievements/tasks/\(id)/complete", method: "POST")
    }

    public func uploadImage(data: Data, filename: String = "photo.jpg",
                            mimeType: String = "image/jpeg", purpose: String = "post") async throws -> UploadResponse {
        guard var components = URLComponents(string: AppConfig.apiBaseURL.absoluteString + "/media/upload") else {
            throw APIError.invalidURL
        }
        components.queryItems = [URLQueryItem(name: "purpose", value: purpose)]
        guard let url = components.url else { throw APIError.invalidURL }

        let boundary = "UniPO-\(UUID().uuidString)"
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("ios", forHTTPHeaderField: "X-Client")
        let authentication = tokenState.snapshot()
        if let accessToken = authentication.token, !accessToken.isEmpty {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }

        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body

        let (responseData, response): (Data, URLResponse)
        do {
            (responseData, response) = try await session.data(for: request)
        } catch {
            throw APIError.transport(error)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        await reportUnauthorized(status: status, authentication: authentication)
        return try decodeResponse(from: responseData, status: status)
    }

    public func logout(refreshToken: String?) async {
        await logout(accessToken: tokenState.currentToken(), refreshToken: refreshToken)
    }

    public func logout(accessToken: String?, refreshToken: String?) async {
        _ = try? await request(
            path: "auth/logout",
            method: "POST",
            body: refreshToken.map { ["refreshToken": $0] },
            authorization: .explicit(accessToken)
        ) as EmptyResponse
    }

    private enum Authorization {
        case current
        case explicit(String?)
    }

    private func request<T: Decodable>(
        path: String,
        method: String = "GET",
        query: [URLQueryItem] = [],
        body: [String: Any]? = nil,
        authorization: Authorization = .current
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
        let anonymousPaths: Set<String> = ["auth/login", "auth/refresh", "auth/sms-code", "auth/sms-login"]
        let authentication: AuthTokenState.Snapshot?
        let requestToken: String?
        switch authorization {
        case .current:
            let snapshot = tokenState.snapshot()
            authentication = anonymousPaths.contains(path) ? nil : snapshot
            requestToken = snapshot.token
        case .explicit(let token):
            authentication = nil
            requestToken = token
        }
        if let requestToken, !requestToken.isEmpty, !anonymousPaths.contains(path) {
            request.setValue("Bearer \(requestToken)", forHTTPHeaderField: "Authorization")
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
        if let authentication { await reportUnauthorized(status: status, authentication: authentication) }
        return try decodeResponse(from: data, status: status)
    }

    private func reportUnauthorized(status: Int, authentication: AuthTokenState.Snapshot) async {
        guard status == 401, let token = authentication.token, !token.isEmpty,
              let handler = tokenState.handler(for: authentication.revision) else { return }
        // The callback contains only the request's revision. Explicit candidates, login,
        // refresh and logout never enter this path, and writes are never replayed.
        await handler(authentication.revision)
    }

    private func decodeResponse<T: Decodable>(from data: Data, status: Int) throws -> T {
        if status == 401 {
            let message = (try? JSONDecoder().decode(APIEnvelope<EmptyResponse>.self, from: data))?.message ?? ""
            throw APIError.unauthorized(message)
        }
        guard (200..<300).contains(status) else {
            let envelope = try? JSONDecoder().decode(APIEnvelope<EmptyResponse>.self, from: data)
            let code = envelope?.code ?? "HTTP_ERROR"
            let serverMessage = envelope?.message ?? ""
            let message = serverMessage.isEmpty ? "请求失败，请稍后重试" : serverMessage
            throw APIError.server(code: code == "OK" ? "HTTP_ERROR" : code, message: message, status: status)
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
