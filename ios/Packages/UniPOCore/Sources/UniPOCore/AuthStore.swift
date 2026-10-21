import Foundation
import Observation

protocol AuthenticationAPI {
    func advanceAuthenticationRevision(_ revision: UInt64, clearToken: Bool)
    func setAccessToken(_ token: String?, revision: UInt64) async
    func me(accessToken: String) async throws -> UserProfile
    func refresh(refreshToken: String) async throws -> AuthSession
    func requestSmsCode(phone: String) async throws -> SmsCodeResponse
    func smsLogin(phone: String, code: String, nickname: String?) async throws -> AuthSession
    func login(email: String, password: String) async throws -> AuthSession
    func completeOnboarding(_ input: OnboardingInput) async throws -> UserProfile
    func deleteAccount() async throws -> Bool
    func logout(accessToken: String?, refreshToken: String?) async
}

extension APIClient: AuthenticationAPI {}

@Observable
@MainActor
public final class AuthStore {
    public static let shared = AuthStore()
    public private(set) var session: AuthSession?
    public private(set) var isRestoring = false
    public private(set) var recoveryError: String?
    public private(set) var isLoggingIn = false
    public private(set) var isSendingCode = false
    public var errorMessage: String?
    public var loginRequested = false

    private let credentials: any SessionCredentialStore
    private let api: any AuthenticationAPI
    private var revision: UInt64 = 0
    private var recoveryTask: Task<Void, Never>?
    private var codeRequestID: UUID?
    private var restorationSuppressed = false

    public convenience init() {
        self.init(api: APIClient.shared, credentials: KeychainSessionCredentialStore(), restoreAutomatically: true)
    }

    init(api: any AuthenticationAPI, credentials: any SessionCredentialStore, restoreAutomatically: Bool = false) {
        self.api = api
        self.credentials = credentials
        if restoreAutomatically {
            isRestoring = true
            Task { await restore() }
        }
    }

    public var isAuthenticated: Bool { session != nil }
    public var profile: UserProfile? { session?.user }

    public func requireLogin() {
        guard !isAuthenticated else { return }
        _ = beginOperation()
        errorMessage = nil
        loginRequested = true
    }

    public func requestSmsCode(phone: String) async -> Bool {
        guard !isSendingCode else { return false }
        guard !phone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = "请输入手机号"; return false
        }
        let requestID = UUID()
        let current = revision
        codeRequestID = requestID
        isSendingCode = true
        errorMessage = nil
        defer {
            if codeRequestID == requestID { isSendingCode = false; codeRequestID = nil }
        }
        do {
            _ = try await api.requestSmsCode(phone: phone)
            return current == revision && codeRequestID == requestID
        } catch {
            guard current == revision, codeRequestID == requestID else { return false }
            errorMessage = message(error, fallback: "验证码发送失败"); return false
        }
    }

    public func smsLogin(phone: String, code: String, nickname: String? = nil) async {
        await authenticate { try await api.smsLogin(phone: phone, code: code, nickname: nickname) }
    }

    public func login(email: String, password: String) async {
        let account = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !account.isEmpty, !password.isEmpty else { errorMessage = "请输入账号和密码"; return }
        await authenticate { try await api.login(email: account, password: password) }
    }

    private func authenticate(using operation: () async throws -> AuthSession) async {
        guard !isLoggingIn else { return }
        let current = beginOperation()
        isLoggingIn = true
        errorMessage = nil
        defer { if current == revision { isLoggingIn = false } }
        do {
            let result = try await operation()
            try await persist(result, revision: current)
        } catch {
            guard current == revision else { return }
            errorMessage = message(error, fallback: "登录失败，请稍后再试")
        }
    }

    public func restore() async {
        if let recoveryTask { await recoveryTask.value; return }
        guard !isAuthenticated, !isLoggingIn, !loginRequested else { return }
        if restorationSuppressed {
            let current = beginOperation()
            await clearSession(revision: current)
            return
        }
        let current = beginOperation()
        isRestoring = true
        let task = Task { await performRestore(revision: current) }
        recoveryTask = task
        await task.value
    }

    private func performRestore(revision current: UInt64) async {
        defer {
            if current == revision { isRestoring = false; recoveryTask = nil }
        }
        do {
            guard let saved = try credentials.load() else { return }
            var verifiedUser: UserProfile?
            if !saved.accessToken.isEmpty {
                do {
                    // A candidate is private to this request until the server validates it.
                    verifiedUser = try await api.me(accessToken: saved.accessToken)
                } catch {
                    guard current == revision else { return }
                    if !isUnauthorized(error) { throw error }
                    // Two legacy entries may already belong to different accounts. Without a
                    // verified owner for access, do not silently switch to the refresh owner.
                    if saved.requiresRefreshValidation, !saved.refreshToken.isEmpty {
                        throw SessionRecoveryFailure.identityMismatch
                    }
                }
            }
            guard current == revision else { return }
            if let user = verifiedUser, !saved.requiresRefreshValidation || saved.refreshToken.isEmpty {
                try await persist(AuthSession(token: saved.accessToken, refreshToken: saved.refreshToken,
                                              expiresInSeconds: 0, user: user), revision: current)
            } else {
                try await refreshSession(saved, expectedUserID: verifiedUser?.id, revision: current)
            }
        } catch {
            guard current == revision, !Task.isCancelled else { return }
            recoveryError = message(error, fallback: "暂时无法恢复登录，请重试")
        }
    }

    private func refreshSession(_ saved: SessionCredentials, expectedUserID: Int64?, revision current: UInt64) async throws {
        guard !saved.refreshToken.isEmpty else { await clearSession(revision: current); return }
        do {
            let refreshed = try await api.refresh(refreshToken: saved.refreshToken)
            guard current == revision else { return }
            if let expectedUserID, refreshed.user.id != expectedUserID { throw SessionRecoveryFailure.identityMismatch }
            try await persist(refreshed, revision: current)
        } catch {
            guard current == revision else { return }
            if isUnauthorized(error) { await clearSession(revision: current) } else { throw error }
        }
    }

    public func applyProfile(_ user: UserProfile) {
        guard let current = session, current.user.id == user.id else { return }
        session = AuthSession(token: current.token, refreshToken: current.refreshToken,
                              expiresInSeconds: current.expiresInSeconds, user: user)
    }

    public func completeOnboarding(_ input: OnboardingInput) async throws {
        guard let initial = session else { throw APIError.unauthorized("") }
        let current = revision
        let user = try await api.completeOnboarding(input)
        guard current == revision, session?.user.id == initial.user.id else { throw CancellationError() }
        applyProfile(user)
        loginRequested = false
    }

    public func deleteAccount() async -> Bool {
        guard isAuthenticated, !isLoggingIn else { return false }
        let current = beginOperation()
        isLoggingIn = true
        errorMessage = nil
        defer { if current == revision { isLoggingIn = false } }
        do {
            _ = try await api.deleteAccount()
            guard current == revision else { return false }
            await clearSession(revision: current)
            guard current == revision else { return false }
            loginRequested = true
            return true
        } catch {
            guard current == revision else { return false }
            errorMessage = message(error, fallback: "注销失败，请稍后再试"); return false
        }
    }

    public func logout() async {
        let saved = session.map { SessionCredentials(accessToken: $0.token, refreshToken: $0.refreshToken) }
            ?? (try? credentials.load())
        let current = beginOperation()
        await clearSession(revision: current)
        // Remote revocation owns the old snapshot and never clears a later login.
        if let saved { await api.logout(accessToken: saved.accessToken, refreshToken: saved.refreshToken) }
    }

    @discardableResult
    private func beginOperation() -> UInt64 {
        revision &+= 1
        api.advanceAuthenticationRevision(revision, clearToken: session == nil)
        recoveryTask?.cancel()
        recoveryTask = nil
        isRestoring = false
        isLoggingIn = false
        recoveryError = nil
        codeRequestID = nil
        isSendingCode = false
        return revision
    }

    private func persist(_ result: AuthSession, revision current: UInt64) async throws {
        guard current == revision else { return }
        try credentials.save(SessionCredentials(accessToken: result.token, refreshToken: result.refreshToken))
        await api.setAccessToken(result.token, revision: current)
        guard current == revision else { return }
        session = result
        restorationSuppressed = false
        errorMessage = nil
        recoveryError = nil
        if result.user.onboardingCompleted != false { loginRequested = false }
    }

    private func clearSession(revision current: UInt64) async {
        guard current == revision else { return }
        restorationSuppressed = true
        session = nil
        api.advanceAuthenticationRevision(current, clearToken: true)
        do { try credentials.clear() }
        catch { recoveryError = message(error, fallback: "登录信息清理失败，请重试") }
    }

    private func isUnauthorized(_ error: Error) -> Bool {
        guard let error = error as? APIError else { return false }
        if case .unauthorized = error { return true }
        return false
    }

    private func message(_ error: Error, fallback: String) -> String {
        (error as? LocalizedError)?.errorDescription ?? fallback
    }
}

private enum SessionRecoveryFailure: LocalizedError {
    case identityMismatch
    var errorDescription: String? { "登录信息需要重新确认，请重新登录" }
}
