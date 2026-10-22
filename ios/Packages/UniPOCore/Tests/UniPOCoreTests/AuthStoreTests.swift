import Foundation
import XCTest
@testable import UniPOCore

@MainActor
final class AuthStoreTests: XCTestCase {
    private enum FixtureError: Error {
        case storageUnavailable
        case unexpectedRequest
    }

    /// Each request announces its arrival before suspending; tests release responses explicitly.
    @MainActor
    private final class Gate<Input, Output> {
        struct Call {
            let input: Input
            let completion: CheckedContinuation<Output, Error>
        }

        private(set) var calls: [Call] = []
        private var arrivalWaiters: [(count: Int, completion: CheckedContinuation<Void, Never>)] = []

        func invoke(_ input: Input) async throws -> Output {
            try await withCheckedThrowingContinuation { completion in
                calls.append(Call(input: input, completion: completion))
                let ready = arrivalWaiters.filter { calls.count >= $0.count }
                arrivalWaiters.removeAll { calls.count >= $0.count }
                ready.forEach { $0.completion.resume() }
            }
        }

        func waitForCall(_ count: Int = 1) async {
            guard calls.count < count else { return }
            await withCheckedContinuation { completion in
                arrivalWaiters.append((count, completion))
            }
        }
    }

    @MainActor
    private final class Signal {
        private var sent = false
        private var waiters: [CheckedContinuation<Void, Never>] = []

        func send() {
            sent = true
            let ready = waiters
            waiters.removeAll()
            ready.forEach { $0.resume() }
        }

        func wait() async {
            guard !sent else { return }
            await withCheckedContinuation { waiters.append($0) }
        }
    }

    private final class Credentials: SessionCredentialStore {
        var value: SessionCredentials?
        var loadError: Error?
        var saveError: Error?
        var clearError: Error?
        private(set) var loadCount = 0
        private(set) var saveCount = 0
        private(set) var clearCount = 0

        init(_ value: SessionCredentials?) { self.value = value }

        func load() throws -> SessionCredentials? {
            loadCount += 1
            if let loadError { throw loadError }
            return value
        }

        func save(_ credentials: SessionCredentials) throws {
            saveCount += 1
            if let saveError { throw saveError }
            value = credentials
        }

        func clear() throws {
            clearCount += 1
            if let clearError { throw clearError }
            value = nil
        }
    }

    @MainActor
    private final class MessageAPI: MessagesAPI {
        let listGate = Gate<Void, [ConversationView]>()
        func conversations() async throws -> [ConversationView] { try await listGate.invoke(()) }
        func messages(conversationId: Int64) async throws -> [MessageView] { throw FixtureError.unexpectedRequest }
        func sendMessage(conversationId: Int64, content: String) async throws -> MessageView {
            throw FixtureError.unexpectedRequest
        }
    }

    @MainActor
    private final class API: AuthenticationAPI {
        struct LogoutInput {
            let accessToken: String?
            let refreshToken: String?
        }

        struct TokenPublication {
            let token: String?
            let revision: UInt64
        }

        let meGate = Gate<String, UserProfile>()
        var refreshGate: Gate<String, AuthSession>?
        var logoutGate: Gate<LogoutInput, Void>?
        var publicationGate: Gate<TokenPublication, Void>?
        var afterPublicationGate: Gate<TokenPublication, Void>?
        var loginResult: Result<AuthSession, Error> = .failure(FixtureError.unexpectedRequest)
        private(set) var refreshCalls: [String] = []
        private(set) var logoutCalls: [LogoutInput] = []
        nonisolated private let tokenState = AuthTokenState()
        var accessToken: String? { tokenState.currentToken() }
        var authenticationRevision: UInt64 { tokenState.snapshot().revision }

        nonisolated func advanceAuthenticationRevision(_ revision: UInt64, clearToken: Bool) {
            tokenState.advance(to: revision, clearToken: clearToken)
        }

        nonisolated func setUnauthorizedHandler(_ handler: @escaping @Sendable (UInt64) async -> Void) {
            tokenState.setUnauthorizedHandler(handler)
        }

        func rejectRequest(revision: UInt64) async {
            // Inject even an obsolete callback to exercise AuthStore's own fence.
            let handler = tokenState.handler(for: authenticationRevision)
            await handler?(revision)
        }

        func setAccessToken(_ token: String?, revision: UInt64) async {
            if let publicationGate {
                _ = try? await publicationGate.invoke(TokenPublication(token: token, revision: revision))
            }
            tokenState.set(token, revision: revision)
            if let afterPublicationGate {
                _ = try? await afterPublicationGate.invoke(TokenPublication(token: token, revision: revision))
            }
        }

        func me(accessToken: String) async throws -> UserProfile {
            try await meGate.invoke(accessToken)
        }

        func refresh(refreshToken: String) async throws -> AuthSession {
            refreshCalls.append(refreshToken)
            guard let refreshGate else { throw FixtureError.unexpectedRequest }
            return try await refreshGate.invoke(refreshToken)
        }

        func requestSmsCode(phone: String) async throws -> SmsCodeResponse {
            throw FixtureError.unexpectedRequest
        }

        func smsLogin(phone: String, code: String, nickname: String?) async throws -> AuthSession {
            try loginResult.get()
        }

        func login(email: String, password: String) async throws -> AuthSession {
            try loginResult.get()
        }

        func completeOnboarding(_ input: OnboardingInput) async throws -> UserProfile {
            throw FixtureError.unexpectedRequest
        }

        func deleteAccount() async throws -> Bool {
            throw FixtureError.unexpectedRequest
        }

        func logout(accessToken: String?, refreshToken: String?) async {
            let input = LogoutInput(accessToken: accessToken, refreshToken: refreshToken)
            logoutCalls.append(input)
            if let logoutGate { _ = try? await logoutGate.invoke(input) }
        }
    }

    private let stored = SessionCredentials(accessToken: "fixture-access-a", refreshToken: "fixture-refresh-a")

    private func profile(_ id: Int64) -> UserProfile {
        UserProfile(id: id, publicUid: "fixture-user-" + String(id), nickname: "同学" + String(id), avatarUrl: nil,
                    role: "USER", level: 1, levelTitle: nil, xp: nil, bio: nil, college: nil,
                    major: nil, grade: nil, online: nil, onboardingCompleted: true)
    }

    private func session(_ id: Int64) -> AuthSession {
        AuthSession(token: "fixture-access-" + String(id), refreshToken: "fixture-refresh-" + String(id),
                    expiresInSeconds: 7200, user: profile(id))
    }

    private func bundle(_ session: AuthSession) -> SessionCredentials {
        SessionCredentials(accessToken: session.token, refreshToken: session.refreshToken)
    }

    private var recoverableErrors: [Error] {
        [APIError.transport(URLError(.notConnectedToInternet)),
         APIError.server(code: "SERVER_ERROR", message: "服务暂不可用", status: 500),
         APIError.decoding(DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "fixture")))]
    }

    func testProtectedRequestUnauthorizedHidesIdentityAndRefreshesTheCurrentAccountOnce() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let credentials = Credentials(nil)
        let store = AuthStore(api: api, credentials: credentials)
        let initial = session(1)
        api.loginResult = .success(initial)
        await store.login(email: "fixture-a", password: "fixture-password")
        let rejectedRevision = api.authenticationRevision

        let rejection = Task { await api.rejectRequest(revision: rejectedRevision) }
        await refreshGate.waitForCall()
        XCTAssertNil(store.profile)
        XCTAssertNil(api.accessToken)
        XCTAssertTrue(store.isSessionRecoveryPending)
        XCTAssertEqual(credentials.value, bundle(initial))
        XCTAssertTrue(api.meGate.calls.isEmpty)
        XCTAssertEqual(api.refreshCalls, [initial.refreshToken])

        let refreshed = AuthSession(token: "fixture-refreshed-a", refreshToken: initial.refreshToken,
                                    expiresInSeconds: 7200, user: profile(1))
        refreshGate.calls[0].completion.resume(returning: refreshed)
        await rejection.value
        XCTAssertEqual(store.session, refreshed)
        XCTAssertFalse(store.isSessionRecoveryPending)
        XCTAssertEqual(api.accessToken, refreshed.token)
        XCTAssertEqual(credentials.clearCount, 0)
    }

    func testRevokedAccountUnauthorizedThenRefreshUnauthorizedClearsTheLocalSession() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let credentials = Credentials(nil)
        let store = AuthStore(api: api, credentials: credentials)
        api.loginResult = .success(session(1))
        await store.login(email: "fixture-a", password: "fixture-password")
        let rejectedRevision = api.authenticationRevision
        let rejection = Task { await api.rejectRequest(revision: rejectedRevision) }
        await refreshGate.waitForCall()
        refreshGate.calls[0].completion.resume(throwing: APIError.unauthorized(""))
        await rejection.value

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertFalse(store.isSessionRecoveryPending)
        XCTAssertNil(credentials.value)
        XCTAssertNil(api.accessToken)
        XCTAssertEqual(credentials.clearCount, 1)
        XCTAssertEqual(api.refreshCalls.count, 1)
        await api.rejectRequest(revision: rejectedRevision)
        XCTAssertEqual(credentials.clearCount, 1)
    }

    func testRepeatedProtectedRequestRejectionsShareOneRecovery() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let store = AuthStore(api: api, credentials: Credentials(nil))
        api.loginResult = .success(session(1))
        await store.login(email: "fixture-a", password: "fixture-password")
        let rejectedRevision = api.authenticationRevision
        let first = Task { await api.rejectRequest(revision: rejectedRevision) }
        await refreshGate.waitForCall()
        await api.rejectRequest(revision: rejectedRevision)
        await api.rejectRequest(revision: rejectedRevision)
        XCTAssertEqual(api.refreshCalls.count, 1)
        refreshGate.calls[0].completion.resume(returning: session(1))
        await first.value
        await api.rejectRequest(revision: rejectedRevision)
        XCTAssertEqual(api.refreshCalls.count, 1)
        XCTAssertTrue(store.isAuthenticated)
    }

    func testOldRequestRejectionCannotRecoverOrClearTheNewAccount() async {
        let api = API()
        let credentials = Credentials(nil)
        let store = AuthStore(api: api, credentials: credentials)
        api.loginResult = .success(session(1))
        await store.login(email: "fixture-a", password: "fixture-password")
        let oldRevision = api.authenticationRevision
        let newer = session(2)
        api.loginResult = .success(newer)
        await store.login(email: "fixture-b", password: "fixture-password")
        await api.rejectRequest(revision: oldRevision)

        XCTAssertEqual(store.session, newer)
        XCTAssertEqual(credentials.value, bundle(newer))
        XCTAssertTrue(api.refreshCalls.isEmpty)
        XCTAssertEqual(credentials.clearCount, 0)
    }

    func testRuntimeRecoveryFailureKeepsCredentialsAndOwnerForRetry() async {
        for error in recoverableErrors {
            let api = API()
            let refreshGate = Gate<String, AuthSession>()
            api.refreshGate = refreshGate
            let credentials = Credentials(nil)
            let store = AuthStore(api: api, credentials: credentials)
            let initial = session(1)
            api.loginResult = .success(initial)
            await store.login(email: "fixture-a", password: "fixture-password")
            let rejectedRevision = api.authenticationRevision
            let rejection = Task { await api.rejectRequest(revision: rejectedRevision) }
            await refreshGate.waitForCall()
            refreshGate.calls[0].completion.resume(throwing: error)
            await rejection.value

            XCTAssertFalse(store.isAuthenticated)
            XCTAssertTrue(store.isSessionRecoveryPending)
            XCTAssertEqual(credentials.value, bundle(initial))
            XCTAssertEqual(credentials.clearCount, 0)
            XCTAssertNotNil(store.recoveryError)

            let retry = Task { await store.restore() }
            await refreshGate.waitForCall(2)
            refreshGate.calls[1].completion.resume(returning: session(1))
            await retry.value
            XCTAssertEqual(store.profile?.id, 1)
            XCTAssertNil(store.recoveryError)
            XCTAssertFalse(store.isSessionRecoveryPending)
            XCTAssertTrue(api.meGate.calls.isEmpty)
        }
    }

    func testRuntimeRecoveryCannotSwitchToAnUnexpectedRefreshOwner() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let credentials = Credentials(nil)
        let store = AuthStore(api: api, credentials: credentials)
        let initial = session(1)
        api.loginResult = .success(initial)
        await store.login(email: "fixture-a", password: "fixture-password")
        let rejectedRevision = api.authenticationRevision
        let rejection = Task { await api.rejectRequest(revision: rejectedRevision) }
        await refreshGate.waitForCall()
        refreshGate.calls[0].completion.resume(returning: session(2))
        await rejection.value

        XCTAssertNil(store.profile)
        XCTAssertNil(api.accessToken)
        XCTAssertEqual(credentials.value, bundle(initial))
        XCTAssertTrue(store.recoveryError?.contains("重新登录") == true)
    }

    func testMessageVisibilityAndLateResultFollowAuthEvenBeforeTheUIHasSynchronized() async throws {
        let api = API()
        let credentials = Credentials(nil)
        let auth = AuthStore(api: api, credentials: credentials)
        api.loginResult = .success(session(1))
        await auth.login(email: "fixture-a", password: "fixture-password")
        let messageAPI = MessageAPI()
        let messages = MessagesStore(api: messageAPI)
        messages.synchronize(with: auth)
        messages.draft = "A 的私人草稿"
        let old = Task { await messages.loadConversations() }
        await messageAPI.listGate.waitForCall()
        XCTAssertTrue(messages.hasVerifiedIdentity)

        api.loginResult = .success(session(2))
        await auth.login(email: "fixture-b", password: "fixture-password")
        // No MessagesStore synchronization yet: the identity source itself fences
        // both rendering and commits, independent of SwiftUI callback timing.
        XCTAssertFalse(messages.hasVerifiedIdentity)
        let oldConversation = try JSONDecoder().decode(ConversationView.self, from:
            Data(#"{"id":11,"peer":{"id":10,"uid":"fixture-peer","nickname":"同学","role":"USER","level":1},"lastMessage":"A 私信"}"#.utf8))
        messageAPI.listGate.calls[0].completion.resume(returning: [oldConversation])
        await old.value
        XCTAssertTrue(messages.conversations.isEmpty)
        messages.synchronize(with: auth)
        XCTAssertTrue(messages.hasVerifiedIdentity)
        XCTAssertEqual(messages.draft, "")
    }

    func testMissingCredentialsStayGuestWithoutContactingTheAPI() async {
        let api = API()
        let credentials = Credentials(nil)
        let store = AuthStore(api: api, credentials: credentials)

        await store.restore()

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertFalse(store.isRestoring)
        XCTAssertNil(store.recoveryError)
        XCTAssertTrue(api.meGate.calls.isEmpty)
        XCTAssertTrue(api.refreshCalls.isEmpty)
        XCTAssertEqual(credentials.clearCount, 0)
    }

    func testCandidateCredentialsAreNotAuthenticatedOrPublishedBeforeMeSucceeds() async {
        let api = API()
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()

        XCTAssertEqual(api.meGate.calls[0].input, stored.accessToken)
        XCTAssertTrue(store.isRestoring)
        XCTAssertNil(store.session)
        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)

        api.meGate.calls[0].completion.resume(returning: profile(1))
        await restore.value

        XCTAssertEqual(store.profile?.id, 1)
        XCTAssertTrue(store.isAuthenticated)
        XCTAssertEqual(api.accessToken, stored.accessToken)
        XCTAssertEqual(credentials.value, stored)
        XCTAssertEqual(credentials.clearCount, 0)
        XCTAssertFalse(store.isRestoring)
    }

    func testRecoverableMeFailuresKeepCredentialsWithoutAttemptingRefresh() async {
        for error in recoverableErrors {
            let api = API()
            let credentials = Credentials(stored)
            let store = AuthStore(api: api, credentials: credentials)
            let restore = Task { await store.restore() }
            await api.meGate.waitForCall()
            api.meGate.calls[0].completion.resume(throwing: error)
            await restore.value

            XCTAssertFalse(store.isAuthenticated)
            XCTAssertNil(api.accessToken)
            XCTAssertEqual(credentials.value, stored)
            XCTAssertEqual(credentials.clearCount, 0)
            XCTAssertTrue(api.refreshCalls.isEmpty)
            XCTAssertNotNil(store.recoveryError)
            XCTAssertFalse(store.isRestoring)
        }
    }

    func testForbiddenMeDoesNotRefreshOrDeleteCredentials() async {
        let api = API()
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(throwing: APIError.server(code: "FORBIDDEN", message: "无权访问", status: 403))
        await restore.value

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertTrue(api.refreshCalls.isEmpty)
        XCTAssertEqual(credentials.value, stored)
        XCTAssertEqual(credentials.clearCount, 0)
        XCTAssertNotNil(store.recoveryError)
    }

    func testMeUnauthorizedRefreshesOnceAndPublishesTheSavedSession() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(throwing: APIError.unauthorized(""))
        await refreshGate.waitForCall()

        XCTAssertEqual(api.refreshCalls, [stored.refreshToken])
        XCTAssertNil(store.session)
        XCTAssertNil(api.accessToken)

        let refreshed = session(1)
        refreshGate.calls[0].completion.resume(returning: refreshed)
        await restore.value

        XCTAssertEqual(store.session, refreshed)
        XCTAssertEqual(credentials.value, bundle(refreshed))
        XCTAssertEqual(api.accessToken, refreshed.token)
        XCTAssertEqual(credentials.clearCount, 0)
        XCTAssertNil(store.recoveryError)
    }

    func testRefreshUnauthorizedClearsRejectedCredentials() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(throwing: APIError.unauthorized(""))
        await refreshGate.waitForCall()
        refreshGate.calls[0].completion.resume(throwing: APIError.unauthorized(""))
        await restore.value

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertNil(credentials.value)
        XCTAssertEqual(credentials.clearCount, 1)
        XCTAssertEqual(api.refreshCalls.count, 1)
    }

    func testRecoverableRefreshFailuresKeepCredentialsForRetry() async {
        for error in recoverableErrors {
            let api = API()
            let refreshGate = Gate<String, AuthSession>()
            api.refreshGate = refreshGate
            let credentials = Credentials(stored)
            let store = AuthStore(api: api, credentials: credentials)
            let restore = Task { await store.restore() }
            await api.meGate.waitForCall()
            api.meGate.calls[0].completion.resume(throwing: APIError.unauthorized(""))
            await refreshGate.waitForCall()
            refreshGate.calls[0].completion.resume(throwing: error)
            await restore.value

            XCTAssertFalse(store.isAuthenticated)
            XCTAssertNil(api.accessToken)
            XCTAssertEqual(credentials.value, stored)
            XCTAssertEqual(credentials.clearCount, 0)
            XCTAssertNotNil(store.recoveryError)
            XCTAssertFalse(store.isRestoring)
        }
    }

    func testCancelledRecoveryDoesNotRefreshOrDeleteCredentials() async {
        let api = API()
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(throwing: APIError.transport(URLError(.cancelled)))
        await restore.value

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertTrue(api.refreshCalls.isEmpty)
        XCTAssertEqual(credentials.value, stored)
        XCTAssertEqual(credentials.clearCount, 0)
    }

    func testCredentialReadFailureIsRecoverableAndDoesNotCallTheAPI() async {
        let api = API()
        let credentials = Credentials(stored)
        credentials.loadError = FixtureError.storageUnavailable
        let store = AuthStore(api: api, credentials: credentials)

        await store.restore()

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertTrue(api.meGate.calls.isEmpty)
        XCTAssertEqual(credentials.value, stored)
        XCTAssertEqual(credentials.clearCount, 0)
        XCTAssertNotNil(store.recoveryError)
        XCTAssertFalse(store.isRestoring)
    }

    func testLoginCredentialWriteFailureDoesNotPublishTheNewIdentity() async {
        let api = API()
        api.loginResult = .success(session(2))
        let credentials = Credentials(stored)
        credentials.saveError = FixtureError.storageUnavailable
        let store = AuthStore(api: api, credentials: credentials)

        await store.login(email: "fixture-b", password: "fixture-password")

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertEqual(credentials.value, stored)
        XCTAssertEqual(credentials.saveCount, 1)
        XCTAssertEqual(credentials.clearCount, 0)
        XCTAssertNotNil(store.errorMessage)
        XCTAssertFalse(store.isLoggingIn)
    }

    func testRefreshCredentialWriteFailurePreservesThePreviousBundle() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let credentials = Credentials(stored)
        credentials.saveError = FixtureError.storageUnavailable
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(throwing: APIError.unauthorized(""))
        await refreshGate.waitForCall()
        refreshGate.calls[0].completion.resume(returning: session(1))
        await restore.value

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertEqual(credentials.value, stored)
        XCTAssertEqual(credentials.clearCount, 0)
        XCTAssertNotNil(store.recoveryError)
    }

    func testRecoveryCanRetryThePreservedCredentialsAfterAConnectionFailure() async {
        let api = API()
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let first = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(throwing: APIError.transport(URLError(.notConnectedToInternet)))
        await first.value
        XCTAssertNotNil(store.recoveryError)

        let second = Task { await store.restore() }
        await api.meGate.waitForCall(2)
        XCTAssertEqual(api.meGate.calls[1].input, stored.accessToken)
        api.meGate.calls[1].completion.resume(returning: profile(1))
        await second.value

        XCTAssertTrue(store.isAuthenticated)
        XCTAssertNil(store.recoveryError)
        XCTAssertEqual(credentials.clearCount, 0)
    }

    func testConcurrentRestoreCallsShareOneVerificationRequest() async {
        let api = API()
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let first = Task { await store.restore() }
        await api.meGate.waitForCall()
        let secondStarted = Signal()
        let second = Task {
            secondStarted.send()
            await store.restore()
        }
        await secondStarted.wait()

        XCTAssertEqual(api.meGate.calls.count, 1)
        // Release every already-started call, so a duplicate-request regression still finishes.
        api.meGate.calls.forEach { $0.completion.resume(returning: profile(1)) }
        await first.value
        await second.value

        XCTAssertEqual(api.meGate.calls.count, 1)
        XCTAssertEqual(api.refreshCalls.count, 0)
        XCTAssertTrue(store.isAuthenticated)
        XCTAssertFalse(store.isRestoring)
    }

    func testLateMeSuccessCannotReplaceTheAccountThatLoggedInMeanwhile() async {
        let api = API()
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let oldRestore = Task { await store.restore() }
        await api.meGate.waitForCall()
        let newer = session(2)
        api.loginResult = .success(newer)
        await store.login(email: "fixture-b", password: "fixture-password")

        api.meGate.calls[0].completion.resume(returning: profile(1))
        await oldRestore.value

        XCTAssertEqual(store.session, newer)
        XCTAssertEqual(credentials.value, bundle(newer))
        XCTAssertEqual(api.accessToken, newer.token)
        XCTAssertEqual(credentials.clearCount, 0)
        XCTAssertNil(store.recoveryError)
    }

    func testLateMeFailureCannotClearTheNewAccountOrStartAnOldRefresh() async {
        for error in [APIError.unauthorized("")] + recoverableErrors {
            let api = API()
            let credentials = Credentials(stored)
            let store = AuthStore(api: api, credentials: credentials)
            let oldRestore = Task { await store.restore() }
            await api.meGate.waitForCall()
            let newer = session(2)
            api.loginResult = .success(newer)
            await store.login(email: "fixture-b", password: "fixture-password")

            api.meGate.calls[0].completion.resume(throwing: error)
            await oldRestore.value

            XCTAssertEqual(store.session, newer)
            XCTAssertEqual(credentials.value, bundle(newer))
            XCTAssertEqual(api.accessToken, newer.token)
            XCTAssertTrue(api.refreshCalls.isEmpty)
            XCTAssertEqual(credentials.clearCount, 0)
            XCTAssertNil(store.recoveryError)
        }
    }

    func testLateRefreshSuccessOrRejectionCannotAffectTheNewAccount() async {
        let oldResults: [Result<AuthSession, Error>] = [.success(session(1)), .failure(APIError.unauthorized(""))]
        for result in oldResults {
            let api = API()
            let refreshGate = Gate<String, AuthSession>()
            api.refreshGate = refreshGate
            let credentials = Credentials(stored)
            let store = AuthStore(api: api, credentials: credentials)
            let oldRestore = Task { await store.restore() }
            await api.meGate.waitForCall()
            api.meGate.calls[0].completion.resume(throwing: APIError.unauthorized(""))
            await refreshGate.waitForCall()
            let newer = session(2)
            api.loginResult = .success(newer)
            await store.login(email: "fixture-b", password: "fixture-password")

            refreshGate.calls[0].completion.resume(with: result)
            await oldRestore.value

            XCTAssertEqual(store.session, newer)
            XCTAssertEqual(credentials.value, bundle(newer))
            XCTAssertEqual(api.accessToken, newer.token)
            XCTAssertEqual(credentials.clearCount, 0)
            XCTAssertNil(store.recoveryError)
        }
    }

    func testLogoutInvalidatesAnInFlightRestore() async {
        let api = API()
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let oldRestore = Task { await store.restore() }
        await api.meGate.waitForCall()

        await store.logout()
        api.meGate.calls[0].completion.resume(returning: profile(1))
        await oldRestore.value

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertNil(credentials.value)
        XCTAssertTrue(api.refreshCalls.isEmpty)
        XCTAssertFalse(store.isRestoring)
    }

    func testLogoutClearsLocallyBeforeRemoteCompletionAndCannotClearANewerLogin() async {
        let api = API()
        let credentials = Credentials(nil)
        let store = AuthStore(api: api, credentials: credentials)
        let initial = session(1)
        api.loginResult = .success(initial)
        await store.login(email: "fixture-a", password: "fixture-password")
        let logoutGate = Gate<API.LogoutInput, Void>()
        api.logoutGate = logoutGate
        let logout = Task { await store.logout() }
        await logoutGate.waitForCall()

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(credentials.value)
        XCTAssertNil(api.accessToken)
        XCTAssertEqual(logoutGate.calls[0].input.accessToken, initial.token)
        XCTAssertEqual(logoutGate.calls[0].input.refreshToken, initial.refreshToken)

        let newer = session(2)
        api.loginResult = .success(newer)
        await store.login(email: "fixture-b", password: "fixture-password")
        logoutGate.calls[0].completion.resume(returning: ())
        await logout.value

        XCTAssertEqual(store.session, newer)
        XCTAssertEqual(credentials.value, bundle(newer))
        XCTAssertEqual(api.accessToken, newer.token)
        XCTAssertEqual(credentials.clearCount, 1)
    }

    func testOpeningLoginRejectsATokenPublicationAlreadyWaitingInTheAPI() async {
        let api = API()
        let publicationGate = Gate<API.TokenPublication, Void>()
        api.publicationGate = publicationGate
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let oldRestore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(returning: profile(1))
        await publicationGate.waitForCall()
        XCTAssertNil(store.session)

        store.requireLogin()
        publicationGate.calls[0].completion.resume(returning: ())
        await oldRestore.value

        XCTAssertTrue(store.loginRequested)
        XCTAssertNil(store.session)
        XCTAssertNil(api.accessToken)
        XCTAssertFalse(store.isRestoring)
    }

    func testOpeningLoginSynchronouslyClearsAPublishedCandidateBeforeSessionCommit() async {
        let api = API()
        let afterPublicationGate = Gate<API.TokenPublication, Void>()
        api.afterPublicationGate = afterPublicationGate
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let oldRestore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(returning: profile(1))
        await afterPublicationGate.waitForCall()
        XCTAssertEqual(api.accessToken, stored.accessToken)
        XCTAssertNil(store.session)

        store.requireLogin()
        XCTAssertNil(api.accessToken)
        afterPublicationGate.calls[0].completion.resume(returning: ())
        await oldRestore.value

        XCTAssertTrue(store.loginRequested)
        XCTAssertNil(store.session)
        XCTAssertNil(api.accessToken)
    }

    func testFailedNewLoginStillRejectsThePreviousRecoveryTokenPublication() async {
        let api = API()
        let publicationGate = Gate<API.TokenPublication, Void>()
        api.publicationGate = publicationGate
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let oldRestore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(returning: profile(1))
        await publicationGate.waitForCall()

        api.loginResult = .failure(APIError.unauthorized("账号或密码不正确"))
        await store.login(email: "fixture-b", password: "fixture-password")
        publicationGate.calls[0].completion.resume(returning: ())
        await oldRestore.value

        XCTAssertNil(store.session)
        XCTAssertNil(api.accessToken)
        XCTAssertNotNil(store.errorMessage)
        XCTAssertFalse(store.isLoggingIn)
        XCTAssertFalse(store.isRestoring)
        XCTAssertEqual(credentials.value, stored)
        XCTAssertEqual(credentials.clearCount, 0)
    }

    func testLateTokenPublicationCannotReplaceATokenFromANewerSuccessfulLogin() async {
        let api = API()
        let publicationGate = Gate<API.TokenPublication, Void>()
        api.publicationGate = publicationGate
        let credentials = Credentials(stored)
        let store = AuthStore(api: api, credentials: credentials)
        let oldRestore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(returning: profile(1))
        await publicationGate.waitForCall()

        api.publicationGate = nil
        let newer = session(2)
        api.loginResult = .success(newer)
        await store.login(email: "fixture-b", password: "fixture-password")
        publicationGate.calls[0].completion.resume(returning: ())
        await oldRestore.value

        XCTAssertEqual(store.session, newer)
        XCTAssertEqual(api.accessToken, newer.token)
        XCTAssertEqual(credentials.value, bundle(newer))
    }

    func testLegacyPairIsPublishedOnlyAfterBothTokensResolveToTheSameUser() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let legacy = SessionCredentials(accessToken: stored.accessToken, refreshToken: stored.refreshToken,
                                        requiresRefreshValidation: true)
        let credentials = Credentials(legacy)
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(returning: profile(1))
        await refreshGate.waitForCall()

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertEqual(credentials.value, legacy)
        XCTAssertEqual(credentials.saveCount, 0)

        let refreshed = session(1)
        refreshGate.calls[0].completion.resume(returning: refreshed)
        await restore.value

        XCTAssertEqual(store.session, refreshed)
        XCTAssertEqual(credentials.value, bundle(refreshed))
        XCTAssertEqual(credentials.value?.requiresRefreshValidation, false)
        XCTAssertEqual(api.accessToken, refreshed.token)
        XCTAssertEqual(api.refreshCalls.count, 1)
        XCTAssertNil(store.recoveryError)
    }

    func testMismatchedLegacyPairCannotAuthenticateOrOverwriteEitherCredential() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let legacy = SessionCredentials(accessToken: stored.accessToken, refreshToken: stored.refreshToken,
                                        requiresRefreshValidation: true)
        let credentials = Credentials(legacy)
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(returning: profile(1))
        await refreshGate.waitForCall()
        refreshGate.calls[0].completion.resume(returning: session(2))
        await restore.value

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertEqual(credentials.value, legacy)
        XCTAssertEqual(credentials.saveCount, 0)
        XCTAssertEqual(credentials.clearCount, 0)
        XCTAssertEqual(api.refreshCalls.count, 1)
        XCTAssertTrue(store.recoveryError?.contains("重新登录") == true)
    }

    func testExpiredLegacyAccessCannotSilentlyAuthenticateTheUnverifiedRefreshOwner() async {
        let api = API()
        let legacy = SessionCredentials(accessToken: stored.accessToken, refreshToken: stored.refreshToken,
                                        requiresRefreshValidation: true)
        let credentials = Credentials(legacy)
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(throwing: APIError.unauthorized(""))
        await restore.value

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertTrue(api.refreshCalls.isEmpty)
        XCTAssertEqual(credentials.value, legacy)
        XCTAssertEqual(credentials.saveCount, 0)
        XCTAssertEqual(credentials.clearCount, 0)
        XCTAssertTrue(store.recoveryError?.contains("重新登录") == true)
        XCTAssertFalse(store.isRestoring)
    }

    func testRejectedLegacyRefreshClearsCredentialsWithoutASecondRefreshAttempt() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let legacy = SessionCredentials(accessToken: stored.accessToken, refreshToken: stored.refreshToken,
                                        requiresRefreshValidation: true)
        let credentials = Credentials(legacy)
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(returning: profile(1))
        await refreshGate.waitForCall()
        refreshGate.calls[0].completion.resume(throwing: APIError.unauthorized(""))
        await restore.value

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertNil(credentials.value)
        XCTAssertEqual(credentials.clearCount, 1)
        XCTAssertEqual(api.meGate.calls.count, 1)
        XCTAssertEqual(api.refreshCalls.count, 1)
        XCTAssertEqual(credentials.saveCount, 0)
    }

    func testLegacyRefreshConnectionFailureKeepsTheUnmigratedPair() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let legacy = SessionCredentials(accessToken: stored.accessToken, refreshToken: stored.refreshToken,
                                        requiresRefreshValidation: true)
        let credentials = Credentials(legacy)
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(returning: profile(1))
        await refreshGate.waitForCall()
        refreshGate.calls[0].completion.resume(throwing: APIError.transport(URLError(.notConnectedToInternet)))
        await restore.value

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertEqual(credentials.value, legacy)
        XCTAssertEqual(credentials.saveCount, 0)
        XCTAssertEqual(credentials.clearCount, 0)
        XCTAssertNotNil(store.recoveryError)
    }

    func testLegacyRefreshWithoutAnAccessTokenCanRecoverDirectly() async {
        let api = API()
        let refreshGate = Gate<String, AuthSession>()
        api.refreshGate = refreshGate
        let credentials = Credentials(SessionCredentials(accessToken: "", refreshToken: stored.refreshToken,
                                                         requiresRefreshValidation: true))
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await refreshGate.waitForCall()
        XCTAssertTrue(api.meGate.calls.isEmpty)
        XCTAssertEqual(refreshGate.calls[0].input, stored.refreshToken)
        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)

        let refreshed = session(1)
        refreshGate.calls[0].completion.resume(returning: refreshed)
        await restore.value

        XCTAssertEqual(store.session, refreshed)
        XCTAssertEqual(credentials.value, bundle(refreshed))
        XCTAssertEqual(api.accessToken, refreshed.token)
    }

    func testLegacyAccessWithoutARefreshTokenCanMigrateAfterMeValidation() async {
        let api = API()
        let credentials = Credentials(SessionCredentials(accessToken: stored.accessToken, refreshToken: "",
                                                         requiresRefreshValidation: true))
        let store = AuthStore(api: api, credentials: credentials)
        let restore = Task { await store.restore() }
        await api.meGate.waitForCall()
        api.meGate.calls[0].completion.resume(returning: profile(1))
        await restore.value

        XCTAssertEqual(store.profile?.id, 1)
        XCTAssertEqual(api.accessToken, stored.accessToken)
        XCTAssertTrue(api.refreshCalls.isEmpty)
        XCTAssertEqual(credentials.value, SessionCredentials(accessToken: stored.accessToken, refreshToken: ""))
        XCTAssertEqual(credentials.saveCount, 1)
    }

    func testRetryAfterFailedLogoutOnlyRetriesClearingAndCannotRestoreTheOldIdentity() async {
        let api = API()
        let credentials = Credentials(nil)
        let store = AuthStore(api: api, credentials: credentials)
        let initial = session(1)
        api.loginResult = .success(initial)
        await store.login(email: "fixture-a", password: "fixture-password")
        credentials.clearError = FixtureError.storageUnavailable

        await store.logout()

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNil(api.accessToken)
        XCTAssertEqual(credentials.value, bundle(initial))
        XCTAssertEqual(credentials.clearCount, 1)
        XCTAssertNotNil(store.recoveryError)

        await store.restore()
        XCTAssertEqual(credentials.clearCount, 2)
        XCTAssertTrue(api.meGate.calls.isEmpty)
        XCTAssertTrue(api.refreshCalls.isEmpty)
        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNotNil(store.recoveryError)

        credentials.clearError = nil
        await store.restore()

        XCTAssertEqual(credentials.clearCount, 3)
        XCTAssertNil(credentials.value)
        XCTAssertNil(api.accessToken)
        XCTAssertFalse(store.isAuthenticated)
        XCTAssertTrue(api.meGate.calls.isEmpty)
        XCTAssertTrue(api.refreshCalls.isEmpty)
        XCTAssertNil(store.recoveryError)
    }
}
