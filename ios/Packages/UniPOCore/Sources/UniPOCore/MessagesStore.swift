import Foundation
import Observation

protocol MessagesAPI {
    func conversations() async throws -> [ConversationView]
    func messages(conversationId: Int64) async throws -> [MessageView]
    func sendMessage(conversationId: Int64, content: String) async throws -> MessageView
}

extension APIClient: MessagesAPI {}

@Observable
@MainActor
public final class MessagesStore {
    public private(set) var conversations: [ConversationView] = []
    public private(set) var selected: ConversationView?
    public private(set) var messages: [MessageView] = []
    public var draft = ""
    public private(set) var isLoading = false
    public private(set) var isSending = false
    public private(set) var errorMessage: String?
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
    }

    private let api: any MessagesAPI
    private weak var auth: AuthStore?
    private var identity: Identity?
    private var suspendedDraft: SuspendedDraft?
    private var pendingConversationID: Int64?
    private var listRequest: UUID?
    private var threadRequest: UUID?
    private var sendRequest: UUID?

    public convenience init() { self.init(api: APIClient.shared) }
    init(api: any MessagesAPI) { self.api = api }

    public func synchronize(with auth: AuthStore) {
        self.auth = auth
        synchronize(userID: auth.profile?.id, revision: auth.authenticationRevision,
                    recoveryPending: auth.isSessionRecoveryPending)
    }

    /// Private content is discarded while identity is unknown. Only an unsent draft
    /// is kept privately, and it can be restored solely to its verified owner.
    func synchronize(userID: Int64?, revision: UInt64, recoveryPending: Bool) {
        let next = userID.map { Identity(userID: $0, revision: revision) }
        guard next != identity || (next == nil && !recoveryPending) else { return }
        if let previous = identity, (recoveryPending && next == nil) || next?.userID == previous.userID {
            suspendedDraft = SuspendedDraft(userID: previous.userID, conversationID: selected?.id, text: draft)
        }
        identity = next
        clearVisibleState()
        if let next, let saved = suspendedDraft, next.userID == saved.userID {
            draft = saved.text
            pendingConversationID = saved.conversationID
        }
        if next != nil || !recoveryPending { suspendedDraft = nil }
    }

    public func select(_ conversation: ConversationView?) {
        guard identity != nil else { return }
        if selected?.id != conversation?.id {
            if pendingConversationID != conversation?.id { draft = "" }
            messages = []
            threadRequest = nil
            sendRequest = nil
            isSending = false
        }
        selected = conversation
        pendingConversationID = nil
        errorMessage = nil
    }

    public func loadConversations(initialConversationID: Int64? = nil) async {
        guard let identity, isCurrent(identity) else { return }
        let request = UUID()
        listRequest = request
        isLoading = true
        errorMessage = nil
        defer { if listRequest == request { isLoading = false; listRequest = nil } }
        do {
            let result = try await api.conversations()
            guard isCurrent(identity), listRequest == request else { return }
            conversations = result
            if selected == nil, let id = pendingConversationID ?? initialConversationID,
               let match = result.first(where: { $0.id == id }) {
                select(match)
            }
        } catch {
            guard isCurrent(identity), listRequest == request else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "会话加载失败，请重试"
        }
    }

    public func loadMessages(_ conversationID: Int64) async {
        guard let identity, isCurrent(identity), selected?.id == conversationID else { return }
        let request = UUID()
        threadRequest = request
        errorMessage = nil
        do {
            let result = try await api.messages(conversationId: conversationID)
            guard isCurrent(identity), threadRequest == request, selected?.id == conversationID else { return }
            messages = result
        } catch {
            guard isCurrent(identity), threadRequest == request, selected?.id == conversationID else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "消息加载失败，请重试"
        }
    }

    public func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let identity, isCurrent(identity), let conversation = selected, !text.isEmpty, !isSending else { return }
        let request = UUID()
        sendRequest = request
        isSending = true
        errorMessage = nil
        defer { if sendRequest == request { isSending = false; sendRequest = nil } }
        do {
            let message = try await api.sendMessage(conversationId: conversation.id, content: text)
            guard isCurrent(identity), sendRequest == request, selected?.id == conversation.id else { return }
            if !messages.contains(where: { $0.id == message.id }) { messages.append(message) }
            if draft.trimmingCharacters(in: .whitespacesAndNewlines) == text { draft = "" }
            await loadConversations()
        } catch {
            guard isCurrent(identity), sendRequest == request else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "发送失败，请重试"
        }
    }

    private func isCurrent(_ requestIdentity: Identity) -> Bool {
        guard identity == requestIdentity else { return false }
        if let auth {
            return auth.profile?.id == requestIdentity.userID && auth.authenticationRevision == requestIdentity.revision
        }
        return true
    }

    private func clearVisibleState() {
        conversations = []
        selected = nil
        messages = []
        draft = ""
        pendingConversationID = nil
        listRequest = nil
        threadRequest = nil
        sendRequest = nil
        isLoading = false
        isSending = false
        errorMessage = nil
    }
}
