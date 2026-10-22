import Foundation
import Observation

protocol MessagesAPI {
    func conversationPage(cursor: String?, limit: Int) async throws -> ConversationPage
    func conversation(id: Int64) async throws -> ConversationView
    func messagePage(conversationId: Int64, cursor: String?, afterId: Int64?, limit: Int) async throws -> MessagePage
    func sendMessage(conversationId: Int64, content: String, clientMessageId: String) async throws -> MessageView
}

extension APIClient: MessagesAPI {}

public struct PendingMessage: Identifiable, Equatable {
    public let id: String
    public let conversationID: Int64
    public let content: String
    public fileprivate(set) var isSending: Bool
    public fileprivate(set) var errorMessage: String?
}

@Observable
@MainActor
public final class MessagesStore {
    public private(set) var conversations: [ConversationView] = []
    public private(set) var selected: ConversationView?
    public private(set) var messages: [MessageView] = []
    public var draft = ""
    public private(set) var isLoading = false
    public private(set) var isLoadingMoreConversations = false
    public private(set) var isLoadingMessages = false
    public private(set) var isLoadingHistory = false
    public private(set) var isSyncing = false
    public private(set) var hasMoreConversations = false
    public private(set) var hasOlderMessages = false
    public private(set) var conversationError: String?
    public private(set) var conversationPageError: String?
    public private(set) var messageError: String?
    public private(set) var historyError: String?
    public private(set) var syncError: String?
    public private(set) var scrollToBottomRevision = 0

    public var pendingMessages: [PendingMessage] { outgoing.filter { $0.conversationID == selected?.id } }
    public var isSending: Bool { pendingMessages.contains { $0.isSending } }
    public var errorMessage: String? {
        conversationError ?? conversationPageError ?? messageError ?? historyError ?? syncError
            ?? pendingMessages.last?.errorMessage
    }
    public var hasVerifiedIdentity: Bool {
        guard let identity else { return false }
        return isCurrent(identity)
    }

    private struct Identity: Equatable {
        let userID: Int64
        let revision: UInt64
    }
    private struct SuspendedDraft {
        let userID: Int64
        let conversationID: Int64?
        let text: String
        let outgoing: [PendingMessage]
    }

    private let api: any MessagesAPI
    private weak var auth: AuthStore?
    private var identity: Identity?
    private var suspendedDraft: SuspendedDraft?
    private var pendingConversationID: Int64?
    private var outgoing: [PendingMessage] = []
    private var listRequest: UUID?
    private var threadGeneration = UUID()
    private var threadRequest: UUID?
    private var historyRequest: UUID?
    private var syncRequest: UUID?
    private var conversationCursor: String?
    private var historyCursor: String?
    private var threadLoaded = false
    // Only contiguous HTTP synchronization advances this position; a send may jump over unseen messages.
    private var synchronizedMessageID: Int64 = 0
    private var syncAgain = false

    public convenience init() { self.init(api: APIClient.shared) }
    init(api: any MessagesAPI) { self.api = api }

    public func synchronize(with auth: AuthStore) {
        self.auth = auth
        synchronize(userID: auth.profile?.id, revision: auth.authenticationRevision,
                    recoveryPending: auth.isSessionRecoveryPending)
    }

    func synchronize(userID: Int64?, revision: UInt64, recoveryPending: Bool) {
        let next = userID.map { Identity(userID: $0, revision: revision) }
        guard next != identity || (next == nil && !recoveryPending) else { return }
        if let previous = identity, (recoveryPending && next == nil) || next?.userID == previous.userID {
            let recoverable = outgoing.map { item in
                var item = item
                item.isSending = false
                item.errorMessage = "未确认发送，请重试"
                return item
            }
            suspendedDraft = SuspendedDraft(userID: previous.userID, conversationID: selected?.id,
                                            text: draft, outgoing: recoverable)
        }
        identity = next
        clearVisibleState()
        if let next, let saved = suspendedDraft, next.userID == saved.userID {
            draft = saved.text
            outgoing = saved.outgoing
            pendingConversationID = saved.conversationID
        }
        if next != nil || !recoveryPending { suspendedDraft = nil }
    }

    public func select(_ conversation: ConversationView?) {
        guard identity != nil else { return }
        if selected?.id != conversation?.id {
            if pendingConversationID != conversation?.id { draft = "" }
            clearThread()
        }
        selected = conversation
        pendingConversationID = nil
    }

    public func loadConversations(initialConversationID: Int64? = nil) async {
        guard let identity, isCurrent(identity) else { return }
        let request = UUID()
        listRequest = request
        isLoading = true
        isLoadingMoreConversations = false
        conversationError = nil
        conversationPageError = nil
        defer { if listRequest == request { isLoading = false; listRequest = nil } }
        do {
            let page = try await api.conversationPage(cursor: nil, limit: 20)
            guard isCurrent(identity), listRequest == request else { return }
            try validateCursor(page.nextCursor, hasMore: page.hasMore, previous: nil)
            conversations = uniqueConversations(page.items)
            conversationCursor = page.nextCursor
            hasMoreConversations = page.hasMore
            if selected == nil, let id = pendingConversationID ?? initialConversationID {
                if let match = conversations.first(where: { $0.id == id }) {
                    select(match)
                } else {
                    let match = try await api.conversation(id: id)
                    guard isCurrent(identity), listRequest == request, selected == nil else { return }
                    select(match)
                }
            }
        } catch {
            guard isCurrent(identity), listRequest == request else { return }
            conversationError = description(error, fallback: "会话加载失败，请重试")
        }
    }

    public func loadMoreConversations() async {
        guard let identity, isCurrent(identity), listRequest == nil,
              hasMoreConversations, let cursor = conversationCursor else { return }
        let request = UUID()
        listRequest = request
        isLoadingMoreConversations = true
        conversationPageError = nil
        defer { if listRequest == request { isLoadingMoreConversations = false; listRequest = nil } }
        do {
            let page = try await api.conversationPage(cursor: cursor, limit: 20)
            guard isCurrent(identity), listRequest == request else { return }
            try validateCursor(page.nextCursor, hasMore: page.hasMore, previous: cursor)
            conversations = uniqueConversations(conversations + page.items)
            conversationCursor = page.nextCursor
            hasMoreConversations = page.hasMore
        } catch {
            guard isCurrent(identity), listRequest == request else { return }
            conversationPageError = description(error, fallback: "更多会话加载失败，请重试")
        }
    }

    public func loadMessages(_ conversationID: Int64) async {
        guard let identity, isCurrent(identity), selected?.id == conversationID else { return }
        if threadLoaded { await synchronizeMessages(); return }
        guard threadRequest == nil else { syncAgain = true; return }
        let request = UUID(), generation = threadGeneration
        threadRequest = request
        isLoadingMessages = true
        messageError = nil
        defer { if threadRequest == request { isLoadingMessages = false; threadRequest = nil } }
        do {
            let page = try await api.messagePage(conversationId: conversationID, cursor: nil, afterId: nil, limit: 40)
            guard isCurrent(identity, generation: generation), threadRequest == request else { return }
            try validateCursor(page.nextCursor, hasMore: page.hasMore, previous: nil)
            mergeMessages(page.items)
            synchronizedMessageID = page.items.map(\.id).max() ?? 0
            historyCursor = page.nextCursor
            hasOlderMessages = page.hasMore
            threadLoaded = true
            scrollToBottomRevision += 1
            if syncAgain { await synchronizeMessages() }
        } catch {
            guard isCurrent(identity, generation: generation), threadRequest == request else { return }
            messageError = description(error, fallback: "消息加载失败，请重试")
        }
    }

    public func loadOlderMessages() async {
        guard let identity, isCurrent(identity), let conversationID = selected?.id,
              threadLoaded, historyRequest == nil, hasOlderMessages, let cursor = historyCursor else { return }
        let request = UUID(), generation = threadGeneration
        historyRequest = request
        isLoadingHistory = true
        historyError = nil
        defer { if historyRequest == request { isLoadingHistory = false; historyRequest = nil } }
        do {
            let page = try await api.messagePage(conversationId: conversationID, cursor: cursor, afterId: nil, limit: 40)
            guard isCurrent(identity, generation: generation), historyRequest == request else { return }
            try validateCursor(page.nextCursor, hasMore: page.hasMore, previous: cursor)
            mergeMessages(page.items)
            historyCursor = page.nextCursor
            hasOlderMessages = page.hasMore
        } catch {
            guard isCurrent(identity, generation: generation), historyRequest == request else { return }
            historyError = description(error, fallback: "历史消息加载失败，请重试")
        }
    }

    public func synchronizeMessages() async {
        guard let identity, isCurrent(identity), let conversationID = selected?.id else { return }
        guard threadLoaded else { await loadMessages(conversationID); return }
        guard syncRequest == nil else { syncAgain = true; return }
        let request = UUID(), generation = threadGeneration
        syncRequest = request
        isSyncing = true
        syncError = nil
        defer { if syncRequest == request { isSyncing = false; syncRequest = nil } }
        do {
            repeat {
                syncAgain = false
                let afterID = synchronizedMessageID
                var cursor: String?
                repeat {
                    let page = try await api.messagePage(conversationId: conversationID, cursor: cursor,
                                                          afterId: cursor == nil ? afterID : nil, limit: 40)
                    guard isCurrent(identity, generation: generation), syncRequest == request, !Task.isCancelled else { return }
                    try validateCursor(page.nextCursor, hasMore: page.hasMore, previous: cursor)
                    mergeMessages(page.items)
                    synchronizedMessageID = max(synchronizedMessageID, page.items.map(\.id).max() ?? 0)
                    cursor = page.hasMore ? page.nextCursor : nil
                } while cursor != nil
            } while syncAgain
        } catch {
            guard isCurrent(identity, generation: generation), syncRequest == request else { return }
            syncError = description(error, fallback: "新消息同步失败，请重试")
        }
    }

    public func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let identity, isCurrent(identity), let conversation = selected, !text.isEmpty, !isSending else { return }
        if let existing = pendingMessages.last(where: { $0.content == text && !$0.isSending }) {
            await retryMessage(existing.id)
            return
        }
        let item = PendingMessage(id: UUID().uuidString, conversationID: conversation.id, content: text,
                                  isSending: true, errorMessage: nil)
        outgoing.append(item)
        scrollToBottomRevision += 1
        await performSend(item, identity: identity)
    }

    public func retryMessage(_ id: String) async {
        guard let identity, isCurrent(identity), !isSending,
              let index = outgoing.firstIndex(where: { $0.id == id && $0.conversationID == selected?.id && !$0.isSending }) else { return }
        outgoing[index].isSending = true
        outgoing[index].errorMessage = nil
        await performSend(outgoing[index], identity: identity)
    }

    public func discardRetry(_ id: String) {
        outgoing.removeAll { $0.id == id && !$0.isSending }
    }

    private func performSend(_ item: PendingMessage, identity: Identity) async {
        do {
            let message = try await api.sendMessage(conversationId: item.conversationID, content: item.content, clientMessageId: item.id)
            guard isCurrent(identity) else { return }
            outgoing.removeAll { $0.id == item.id }
            if selected?.id == item.conversationID {
                mergeMessages([message])
                if draft.trimmingCharacters(in: .whitespacesAndNewlines) == item.content { draft = "" }
                scrollToBottomRevision += 1
            }
            await loadConversations()
        } catch {
            guard isCurrent(identity), let index = outgoing.firstIndex(where: { $0.id == item.id }) else { return }
            outgoing[index].isSending = false
            outgoing[index].errorMessage = description(error, fallback: "未确认发送，请重试")
        }
    }

    private func mergeMessages(_ incoming: [MessageView]) {
        var byID = Dictionary(messages.map { ($0.id, $0) }, uniquingKeysWith: { _, new in new })
        for message in incoming where message.conversationId == selected?.id {
            byID[message.id] = message
            if message.senderId == identity?.userID, let key = message.clientMessageId,
               let pending = outgoing.first(where: { $0.id == key && $0.conversationID == message.conversationId }) {
                outgoing.removeAll { $0.id == key }
                if draft.trimmingCharacters(in: .whitespacesAndNewlines) == pending.content { draft = "" }
            }
        }
        messages = byID.values.sorted { $0.id < $1.id }
    }

    private func uniqueConversations(_ input: [ConversationView]) -> [ConversationView] {
        var seen = Set<Int64>()
        return input.filter { seen.insert($0.id).inserted }
    }

    private func validateCursor(_ next: String?, hasMore: Bool, previous: String?) throws {
        if hasMore && (next == nil || next?.isEmpty == true || next == previous) {
            throw URLError(.cannotParseResponse)
        }
    }

    private func description(_ error: Error, fallback: String) -> String {
        (error as? LocalizedError)?.errorDescription ?? fallback
    }

    private func isCurrent(_ requestIdentity: Identity, generation: UUID? = nil) -> Bool {
        guard identity == requestIdentity, generation == nil || generation == threadGeneration else { return false }
        if let auth {
            return auth.profile?.id == requestIdentity.userID && auth.authenticationRevision == requestIdentity.revision
        }
        return true
    }

    private func clearThread() {
        threadGeneration = UUID()
        threadRequest = nil
        historyRequest = nil
        syncRequest = nil
        messages = []
        isLoadingMessages = false
        isLoadingHistory = false
        isSyncing = false
        threadLoaded = false
        historyCursor = nil
        hasOlderMessages = false
        synchronizedMessageID = 0
        syncAgain = false
        messageError = nil
        historyError = nil
        syncError = nil
    }

    private func clearVisibleState() {
        clearThread()
        conversations = []
        selected = nil
        outgoing = []
        draft = ""
        pendingConversationID = nil
        listRequest = nil
        conversationCursor = nil
        hasMoreConversations = false
        isLoading = false
        isLoadingMoreConversations = false
        conversationError = nil
        conversationPageError = nil
    }
}
