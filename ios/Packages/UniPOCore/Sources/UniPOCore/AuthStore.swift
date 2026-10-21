import Foundation
import Observation

@Observable
@MainActor
public final class AuthStore {
    public static let shared = AuthStore()

    public private(set) var session: AuthSession?
    public private(set) var isRestoring = true
    public private(set) var isLoggingIn = false
    public private(set) var isSendingCode = false
    public var errorMessage: String?
    /// 任何需要登录的入口都把它置 true，RootView 用 fullScreenCover 弹登录页。
    public var loginRequested = false

    private let keychain = KeychainStore(service: AppConfig.credentialService(for: AppConfig.apiBaseURL))
    private let api = APIClient.shared
    private let accessKey = "unipo.access"
    private let refreshKey = "unipo.refresh"

    public init() {
        Task { await restore() }
    }

    public var isAuthenticated: Bool { session != nil }

    public func requireLogin() {
        if !isAuthenticated {
            errorMessage = nil
            loginRequested = true
        }
    }

    public func requestSmsCode(phone: String) async -> Bool {
        guard !phone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = "请输入手机号"
            return false
        }
        isSendingCode = true
        errorMessage = nil
        defer { isSendingCode = false }
        do {
            _ = try await api.requestSmsCode(phone: phone)
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "验证码发送失败"
            return false
        }
    }

    public func smsLogin(phone: String, code: String, nickname: String? = nil) async {
        isLoggingIn = true
        errorMessage = nil
        defer { isLoggingIn = false }
        do {
            let result = try await api.smsLogin(phone: phone, code: code, nickname: nickname)
            await persist(result)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "登录失败，请稍后再试"
        }
    }
    public var profile: UserProfile? { session?.user }

    public func restore() async {
        defer { isRestoring = false }
        guard let access = keychain.read(account: accessKey),
              let refresh = keychain.read(account: refreshKey) else {
            return
        }
        await api.setAccessToken(access)
        do {
            let user = try await api.me()
            session = AuthSession(token: access, refreshToken: refresh,
                                  expiresInSeconds: 0, user: user)
        } catch {
            // access 过期时先尝试刷新一次，失败再清空。
            do {
                let refreshed = try await api.refresh(refreshToken: refresh)
                await persist(refreshed)
            } catch {
                clear()
            }
        }
    }

    public func login(email: String, password: String) async {
        let account = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !account.isEmpty, !password.isEmpty else {
            errorMessage = "请输入账号和密码"
            return
        }
        isLoggingIn = true
        errorMessage = nil
        defer { isLoggingIn = false }
        do {
            let result = try await api.login(email: account, password: password)
            await persist(result)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "登录失败，请稍后再试"
        }
    }

    public func applyProfile(_ user: UserProfile) {
        guard let current = session, current.user.id == user.id else { return }
        session = AuthSession(token: current.token, refreshToken: current.refreshToken,
                              expiresInSeconds: current.expiresInSeconds, user: user)
    }

    public func completeOnboarding(_ input: OnboardingInput) async throws {
        let user = try await api.completeOnboarding(input)
        guard let current = session else { return }
        session = AuthSession(token: current.token, refreshToken: current.refreshToken,
                              expiresInSeconds: current.expiresInSeconds, user: user)
        loginRequested = false
    }

    public func deleteAccount() async -> Bool {
        isLoggingIn = true
        errorMessage = nil
        defer { isLoggingIn = false }
        do {
            _ = try await api.deleteAccount()
            clear()
            loginRequested = true
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "注销失败，请稍后再试"
            return false
        }
    }

    public func logout() async {
        let refresh = session?.refreshToken
        await api.logout(refreshToken: refresh)
        clear()
    }

    private func persist(_ result: AuthSession) async {
        keychain.save(result.token, account: accessKey)
        keychain.save(result.refreshToken, account: refreshKey)
        await api.setAccessToken(result.token)
        session = result
        errorMessage = nil
        // 新用户 onboarding 未完成时保持登录页容器，由 LoginView 切到引导页；
        // 已有用户直接关闭。
        if result.user.onboardingCompleted != false {
            loginRequested = false
        }
    }

    private func clear() {
        keychain.delete(account: accessKey)
        keychain.delete(account: refreshKey)
        session = nil
        Task { await api.setAccessToken(nil) }
    }
}
