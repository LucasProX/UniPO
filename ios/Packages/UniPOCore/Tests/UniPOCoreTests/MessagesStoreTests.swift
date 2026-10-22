import Foundation
import XCTest
@testable import UniPOCore

@MainActor
final class MessagesStoreTests: XCTestCase {
    @MainActor
    private final class Gate<Input, Output> {
        struct Call {
            let input: Input
            let completion: CheckedContinuation<Output, Error>
        }
        var calls: [Call] = []
        private var waiters: [(Int, CheckedContinuation<Void, Never>)] = []

        func invoke(_ input: Input) async throws -> Output {
            try await withCheckedThrowingContinuation { completion in
                calls.append(Call(input: input, completion: completion))
                let ready = waiters.filter { calls.count >= $0.0 }
                waiters.removeAll { calls.count >= $0.0 }
                ready.forEach { $0.1.resume() }
            }
        }
        func wait(_ count: Int = 1) async {
            guard calls.count < count else { return }
            await withCheckedContinuation { waiters.append((count, $0)) }
        }
    }

    @MainActor
    private final class API: MessagesAPI {
        struct Query {
            let id: Int64
            let cursor: String?
            let afterID: Int64?
        }
        let list = Gate<String?, ConversationPage>()
        let exact = Gate<Int64, ConversationView>()
        let thread = Gate<Query, MessagePage>()
        let send = Gate<(Int64, String, String), MessageView>()

        func conversationPage(cursor: String?, limit: Int) async throws -> ConversationPage {
            try await list.invoke(cursor)
        }
        func conversation(id: Int64) async throws -> ConversationView { try await exact.invoke(id) }
        func messagePage(conversationId: Int64, cursor: String?, afterId: Int64?, limit: Int) async throws -> MessagePage {
            try await thread.invoke(Query(id: conversationId, cursor: cursor, afterID: afterId))
        }
        func sendMessage(conversationId: Int64, content: String, clientMessageId: String) async throws -> MessageView {
            try await send.invoke((conversationId, content, clientMessageId))
        }
    }

    private func conversationPage(_ items: [ConversationView], cursor: String? = nil) -> ConversationPage {
        ConversationPage(items: items, nextCursor: cursor, hasMore: cursor != nil, limit: 20)
    }

    private func messagePage(_ items: [MessageView], cursor: String? = nil) -> MessagePage {
        MessagePage(items: items, nextCursor: cursor, hasMore: cursor != nil, limit: 40)
    }

    private func conversation(_ id: Int64) -> ConversationView {
        let peer = AuthorView(id: 10, uid: "fixture-peer", nickname: "同学", avatarUrl: nil, role: "USER",
                              level: 1, levelTitle: nil, school: nil, college: nil, major: nil,
                              grade: nil, online: nil)
        return ConversationView(id: id, peer: peer, lastMessage: "私人消息", unreadCount: 2,
                                lastMessageAt: nil, updatedAt: nil)
    }

    private func message(_ id: Int64, conversationID: Int64 = 11) -> MessageView {
        MessageView(id: id, conversationId: conversationID, senderId: 1, receiverId: 10,
                    content: "私人正文", read: false, createdAt: nil)
    }

    private func load(_ store: MessagesStore, api: API) async {
        let list = Task { await store.loadConversations(initialConversationID: 11) }
        await api.list.wait()
        api.list.calls[0].completion.resume(returning: conversationPage([conversation(11)]))
        await list.value
        let thread = Task { await store.loadMessages(11) }
        await api.thread.wait()
        api.thread.calls[0].completion.resume(returning: messagePage([message(1)]))
        await thread.value
    }

    func testUnknownIdentityHidesPrivateDataAndSameOwnerRecoversOnlyTheUnsentDraft() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        await load(store, api: api)
        store.draft = "只属于 A 的草稿"

        store.synchronize(userID: nil, revision: 2, recoveryPending: true)
        XCTAssertTrue(store.conversations.isEmpty)
        XCTAssertTrue(store.messages.isEmpty)
        XCTAssertNil(store.selected)
        XCTAssertEqual(store.draft, "")
        await store.send()
        await store.loadConversations()
        XCTAssertTrue(api.send.calls.isEmpty)
        XCTAssertEqual(api.list.calls.count, 1)

        // A network failure keeps recovery pending without exposing the suspended draft.
        store.synchronize(userID: nil, revision: 2, recoveryPending: true)
        XCTAssertEqual(store.draft, "")
        store.synchronize(userID: 1, revision: 3, recoveryPending: false)
        XCTAssertEqual(store.draft, "只属于 A 的草稿")
        XCTAssertTrue(store.messages.isEmpty)
        let retry = Task { await store.loadConversations() }
        await api.list.wait(2)
        api.list.calls[1].completion.resume(returning: conversationPage([conversation(11)]))
        await retry.value
        XCTAssertEqual(store.selected?.id, 11)
        XCTAssertEqual(store.draft, "只属于 A 的草稿")
    }

    func testSameAccountRevisionChangePreservesDraftEvenIfTheUISkippedTheIntermediateRecoveryFrame() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        store.select(conversation(11))
        store.draft = "待发送"
        store.synchronize(userID: 1, revision: 2, recoveryPending: false)
        XCTAssertEqual(store.draft, "待发送")
        XCTAssertNil(store.selected)
        XCTAssertTrue(store.messages.isEmpty)
    }

    func testDifferentAccountNeverReceivesTheSuspendedDraftOrConversation() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        await load(store, api: api)
        store.draft = "A 草稿"
        store.synchronize(userID: nil, revision: 2, recoveryPending: true)
        store.synchronize(userID: 2, revision: 3, recoveryPending: false)
        XCTAssertEqual(store.draft, "")
        XCTAssertNil(store.selected)
        XCTAssertTrue(store.conversations.isEmpty)
        XCTAssertTrue(store.messages.isEmpty)
        store.synchronize(userID: 1, revision: 4, recoveryPending: false)
        XCTAssertEqual(store.draft, "")
    }

    func testFinalUnauthorizedOrExplicitLogoutDiscardsTheSuspendedDraftPermanently() async {
        let store = MessagesStore(api: API())
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        store.select(conversation(11))
        store.draft = "A 草稿"
        store.synchronize(userID: nil, revision: 2, recoveryPending: true)
        store.synchronize(userID: nil, revision: 2, recoveryPending: false)
        store.synchronize(userID: 1, revision: 3, recoveryPending: false)
        XCTAssertEqual(store.draft, "")
        XCTAssertNil(store.selected)

        store.select(conversation(11))
        store.draft = "新的 A 草稿"
        store.synchronize(userID: nil, revision: 4, recoveryPending: false)
        store.synchronize(userID: 1, revision: 5, recoveryPending: false)
        XCTAssertEqual(store.draft, "")
    }

    func testLateListThreadAndSendCannotWriteAfterAnAccountChange() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        store.select(conversation(11))
        store.draft = "A 草稿"
        let list = Task { await store.loadConversations() }
        let thread = Task { await store.loadMessages(11) }
        let send = Task { await store.send() }
        await api.list.wait()
        await api.thread.wait()
        await api.send.wait()

        store.synchronize(userID: 2, revision: 2, recoveryPending: false)
        store.select(conversation(22))
        store.draft = "B 草稿"
        api.list.calls[0].completion.resume(returning: conversationPage([conversation(11)]))
        api.thread.calls[0].completion.resume(returning: messagePage([message(1)]))
        api.send.calls[0].completion.resume(returning: message(2))
        await list.value
        await thread.value
        await send.value
        XCTAssertTrue(store.conversations.isEmpty)
        XCTAssertTrue(store.messages.isEmpty)
        XCTAssertEqual(store.selected?.id, 22)
        XCTAssertEqual(store.draft, "B 草稿")
        XCTAssertFalse(store.isSending)
        XCTAssertFalse(store.isLoading)
        XCTAssertNil(store.errorMessage)
    }

    func testLateFailuresFromPreviousAccountCannotSetCurrentErrors() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        store.select(conversation(11))
        store.draft = "A 草稿"
        let list = Task { await store.loadConversations() }
        let thread = Task { await store.loadMessages(11) }
        let send = Task { await store.send() }
        await api.list.wait()
        await api.thread.wait()
        await api.send.wait()
        store.synchronize(userID: 2, revision: 2, recoveryPending: false)
        api.list.calls[0].completion.resume(throwing: APIError.unauthorized(""))
        api.thread.calls[0].completion.resume(throwing: URLError(.notConnectedToInternet))
        api.send.calls[0].completion.resume(throwing: APIError.unauthorized(""))
        await list.value
        await thread.value
        await send.value
        XCTAssertNil(store.errorMessage)
        XCTAssertTrue(store.messages.isEmpty)
        XCTAssertEqual(store.draft, "")
    }

    func testSendIsSingleFlightAndFailurePreservesDraftForAnExplicitRetry() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        store.select(conversation(11))
        store.draft = "待重试"
        let first = Task { await store.send() }
        await api.send.wait()
        await store.send()
        XCTAssertEqual(api.send.calls.count, 1)
        api.send.calls[0].completion.resume(throwing: APIError.transport(URLError(.notConnectedToInternet)))
        await first.value
        XCTAssertEqual(store.draft, "待重试")
        XCTAssertNotNil(store.errorMessage)
        XCTAssertFalse(store.isSending)

        let retry = Task { await store.send() }
        await api.send.wait(2)
        XCTAssertEqual(api.send.calls[1].input.1, "待重试")
        XCTAssertEqual(api.send.calls[0].input.2, api.send.calls[1].input.2, "An explicit retry keeps one intent")
        api.send.calls[1].completion.resume(returning: message(1))
        await api.list.wait()
        api.list.calls[0].completion.resume(returning: conversationPage([conversation(11)]))
        await retry.value
        XCTAssertEqual(store.messages.map { $0.id }, [1])
        XCTAssertEqual(store.draft, "")
        XCTAssertNil(store.errorMessage)
    }

    func testLateThreadResultCannotReplaceTheSelectedConversation() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        store.select(conversation(11))
        let old = Task { await store.loadMessages(11) }
        await api.thread.wait()
        store.select(conversation(22))
        let current = Task { await store.loadMessages(22) }
        await api.thread.wait(2)
        api.thread.calls[1].completion.resume(returning: messagePage([message(2, conversationID: 22)]))
        await current.value
        api.thread.calls[0].completion.resume(returning: messagePage([message(1)]))
        await old.value
        XCTAssertEqual(store.messages.map { $0.id }, [2])
        XCTAssertEqual(store.selected?.id, 22)
    }

    func testConversationContinuationIsSingleFlightAndFailedCursorCanBeRetried() async {
        let api = API()
        let paged = MessagesStore(api: api)
        paged.synchronize(userID: 1, revision: 1, recoveryPending: false)
        let initial = Task { await paged.loadConversations() }
        await api.list.wait()
        api.list.calls[0].completion.resume(returning: conversationPage([conversation(11)], cursor: "next"))
        await initial.value
        let more = Task { await paged.loadMoreConversations() }
        await api.list.wait(2)
        await paged.loadMoreConversations()
        XCTAssertEqual(api.list.calls.count, 2)
        api.list.calls[1].completion.resume(throwing: URLError(.networkConnectionLost))
        await more.value
        XCTAssertEqual(paged.conversations.map(\.id), [11])
        XCTAssertNotNil(paged.conversationPageError)
        let retry = Task { await paged.loadMoreConversations() }
        await api.list.wait(3)
        XCTAssertEqual(api.list.calls[2].input, "next")
        api.list.calls[2].completion.resume(returning: conversationPage([conversation(11), conversation(12)]))
        await retry.value
        XCTAssertEqual(paged.conversations.map(\.id), [11, 12])
        XCTAssertFalse(paged.hasMoreConversations)
    }

    func testInitialConversationOutsideTheFirstPageUsesTheExactLookup() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        let load = Task { await store.loadConversations(initialConversationID: 99) }
        await api.list.wait()
        api.list.calls[0].completion.resume(returning: conversationPage([conversation(11)], cursor: "more"))
        await api.exact.wait()
        XCTAssertEqual(api.exact.calls[0].input, 99)
        api.exact.calls[0].completion.resume(returning: conversation(99))
        await load.value
        XCTAssertEqual(store.selected?.id, 99)
        XCTAssertEqual(api.list.calls.count, 1)
    }

    func testHistoryPrependsWithoutMovingTheTailAndRejectsLateResultsAfterSwitchingBack() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        store.select(conversation(11))
        let initial = Task { await store.loadMessages(11) }
        await api.thread.wait()
        api.thread.calls[0].completion.resume(returning: messagePage([message(3), message(2)], cursor: "older"))
        await initial.value
        let scrollRevision = store.scrollToBottomRevision
        let older = Task { await store.loadOlderMessages() }
        await api.thread.wait(2)
        await store.loadOlderMessages()
        XCTAssertEqual(api.thread.calls.count, 2)
        api.thread.calls[1].completion.resume(returning: messagePage([message(2), message(1)], cursor: "oldest"))
        await older.value
        XCTAssertEqual(store.messages.map(\.id), [1, 2, 3])
        XCTAssertEqual(store.scrollToBottomRevision, scrollRevision)
        let stale = Task { await store.loadOlderMessages() }
        await api.thread.wait(3)
        store.select(conversation(22))
        store.select(conversation(11))
        api.thread.calls[2].completion.resume(returning: messagePage([message(0)]))
        await stale.value
        XCTAssertTrue(store.messages.isEmpty)
    }

    func testSuccessfulSendDoesNotSkipMessagesMissingBetweenTwoSyncPages() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        await load(store, api: api)
        store.draft = "新消息"
        let send = Task { await store.send() }
        await api.send.wait()
        api.send.calls[0].completion.resume(returning: message(100))
        await api.list.wait(2)
        api.list.calls[1].completion.resume(returning: conversationPage([conversation(11)]))
        await send.value
        let sync = Task { await store.synchronizeMessages() }
        await api.thread.wait(2)
        XCTAssertEqual(api.thread.calls[1].input.afterID, 1, "The saved outgoing ID is not the sync position")
        api.thread.calls[1].completion.resume(returning: messagePage((2...41).map { message(Int64($0)) }, cursor: "forward"))
        await api.thread.wait(3)
        XCTAssertEqual(api.thread.calls[2].input.cursor, "forward")
        XCTAssertNil(api.thread.calls[2].input.afterID)
        api.thread.calls[2].completion.resume(returning: messagePage((42...81).map { message(Int64($0)) }, cursor: "forward-2"))
        await api.thread.wait(4)
        api.thread.calls[3].completion.resume(returning: messagePage((82...100).map { message(Int64($0)) }))
        await sync.value
        XCTAssertEqual(store.messages.map(\.id), (1...100).map(Int64.init))
        XCTAssertNil(store.syncError)
    }

    func testLostSendResponseIsReconciledByClientIntentWithoutResending() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        await load(store, api: api)
        store.draft = "响应丢失"
        let send = Task { await store.send() }
        await api.send.wait()
        let key = api.send.calls[0].input.2
        api.send.calls[0].completion.resume(throwing: URLError(.networkConnectionLost))
        await send.value
        XCTAssertEqual(store.pendingMessages.count, 1)
        let sync = Task { await store.synchronizeMessages() }
        await api.thread.wait(2)
        let saved = MessageView(id: 2, conversationId: 11, senderId: 1, receiverId: 10,
                                content: "响应丢失", read: false, createdAt: nil, clientMessageId: key)
        api.thread.calls[1].completion.resume(returning: messagePage([saved]))
        await sync.value
        XCTAssertTrue(store.pendingMessages.isEmpty)
        XCTAssertEqual(store.draft, "")
        XCTAssertEqual(api.send.calls.count, 1)
        XCTAssertEqual(store.messages.map(\.id), [1, 2])
    }

    func testRecoveringTheSameOwnerPreservesTheFailedIntentWithoutAutomaticallySendingIt() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        store.select(conversation(11))
        store.draft = "身份恢复后仍可重试"
        let send = Task { await store.send() }
        await api.send.wait()
        let key = api.send.calls[0].input.2
        store.synchronize(userID: nil, revision: 2, recoveryPending: true)
        XCTAssertTrue(store.pendingMessages.isEmpty)
        api.send.calls[0].completion.resume(throwing: URLError(.networkConnectionLost))
        await send.value
        store.synchronize(userID: 1, revision: 3, recoveryPending: false)
        store.select(conversation(11))
        XCTAssertEqual(store.pendingMessages.first?.id, key)
        XCTAssertFalse(store.isSending)
        XCTAssertEqual(api.send.calls.count, 1)
        store.synchronize(userID: 2, revision: 4, recoveryPending: false)
        store.select(conversation(11))
        XCTAssertTrue(store.pendingMessages.isEmpty)
    }

    func testAnEventDuringInitialLoadTriggersOneFollowupSync() async {
        let api = API()
        let store = MessagesStore(api: api)
        store.synchronize(userID: 1, revision: 1, recoveryPending: false)
        store.select(conversation(11))
        let initial = Task { await store.loadMessages(11) }
        await api.thread.wait()
        await store.loadMessages(11)
        XCTAssertEqual(api.thread.calls.count, 1)
        api.thread.calls[0].completion.resume(returning: messagePage([message(1)]))
        await api.thread.wait(2)
        XCTAssertEqual(api.thread.calls[1].input.afterID, 1)
        api.thread.calls[1].completion.resume(returning: messagePage([message(2)]))
        await initial.value
        XCTAssertEqual(store.messages.map(\.id), [1, 2])
        XCTAssertFalse(store.isSyncing)
    }
}
