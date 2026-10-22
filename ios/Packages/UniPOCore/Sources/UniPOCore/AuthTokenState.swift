import Foundation

/// The synchronous revision fence and actor token publication share this lock.
/// An established session can retain its token while its next operation starts.
final class AuthTokenState: @unchecked Sendable {
    struct Snapshot {
        let token: String?
        let revision: UInt64
    }

    private let lock = NSLock()
    private var token: String?
    private var revision: UInt64 = 0
    private var unauthorizedHandler: (@Sendable (UInt64) async -> Void)?

    func advance(to nextRevision: UInt64, clearToken: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard nextRevision >= revision else { return }
        revision = nextRevision
        if clearToken { token = nil }
    }

    func set(_ nextToken: String?, revision nextRevision: UInt64) {
        lock.lock()
        defer { lock.unlock() }
        guard nextRevision >= revision else { return }
        revision = nextRevision
        token = nextToken
    }

    func currentToken() -> String? {
        snapshot().token
    }

    func snapshot() -> Snapshot {
        lock.lock()
        defer { lock.unlock() }
        return Snapshot(token: token, revision: revision)
    }

    func setUnauthorizedHandler(_ handler: @escaping @Sendable (UInt64) async -> Void) {
        lock.lock()
        defer { lock.unlock() }
        unauthorizedHandler = handler
    }

    func handler(for rejectedRevision: UInt64) -> (@Sendable (UInt64) async -> Void)? {
        lock.lock()
        defer { lock.unlock() }
        guard rejectedRevision == revision else { return nil }
        return unauthorizedHandler
    }

    /// Kept internal for request tests that do not instantiate an authentication store.
    func setForTesting(_ nextToken: String?) {
        lock.lock()
        defer { lock.unlock() }
        token = nextToken
    }
}
