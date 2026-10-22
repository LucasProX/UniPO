import Foundation

@MainActor
protocol RealtimeSocketTransport: AnyObject {
    func resume()
    func cancel()
    func send(_ text: String)
    func receive() async throws -> URLSessionWebSocketTask.Message
}

@MainActor
private final class URLSessionRealtimeSocket: RealtimeSocketTransport {
    private let task: URLSessionWebSocketTask

    init(task: URLSessionWebSocketTask) { self.task = task }

    func resume() { task.resume() }
    func cancel() { task.cancel(with: .goingAway, reason: nil) }
    func send(_ text: String) { task.send(.string(text)) { _ in } }
    func receive() async throws -> URLSessionWebSocketTask.Message { try await task.receive() }
}

/// 极简 WebSocket 客户端：auth -> 收 event -> 心跳；断线后由页面重新 connect。
/// 协议见后端 RealtimeWebSocketHandler。
@MainActor
public final class RealtimeClient {
    public static let shared = RealtimeClient()

    public var onEvent: ((_ event: String, _ payload: [String: Any]) -> Void)?

    private static let recentEventLimit = 1_024
    private var socket: (any RealtimeSocketTransport)?
    private var receiveTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private let socketFactory: @MainActor () -> any RealtimeSocketTransport
    private let heartbeatWait: @MainActor () async throws -> Void
    private var connectionGeneration: UInt64 = 0
    private var recentEventIDs: Set<String> = []
    private var recentEventOrder = [String?](repeating: nil, count: recentEventLimit)
    private var nextEventSlot = 0

    public convenience init() {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieStorage = nil
        let session = URLSession(configuration: config)
        self.init(socketFactory: {
            URLSessionRealtimeSocket(task: session.webSocketTask(with: AppConfig.realtimeURL))
        }, heartbeatWait: {
            try await Task.sleep(nanoseconds: 20_000_000_000)
        })
    }

    init(socketFactory: @escaping @MainActor () -> any RealtimeSocketTransport,
         heartbeatWait: @escaping @MainActor () async throws -> Void) {
        self.socketFactory = socketFactory
        self.heartbeatWait = heartbeatWait
    }

    public func connect(token: String) {
        disconnect()
        let connection = socketFactory()
        let generation = connectionGeneration
        socket = connection
        connection.resume()
        send(["type": "auth", "token": token], through: connection, generation: generation)
        receiveTask = Task { [weak self, connection] in
            while !Task.isCancelled {
                do {
                    // Keep this exact transport: an old task must never start reading a new account's socket.
                    let message = try await connection.receive()
                    guard !Task.isCancelled, let self,
                          self.isCurrent(connection, generation: generation) else { break }
                    switch message {
                    case .string(let text):
                        self.handle(text)
                    case .data(let data):
                        if let text = String(data: data, encoding: .utf8) { self.handle(text) }
                    @unknown default:
                        break
                    }
                } catch {
                    break
                }
            }
        }
        let waitForHeartbeat = heartbeatWait
        pingTask = Task { [weak self, connection] in
            while !Task.isCancelled {
                do { try await waitForHeartbeat() } catch { break }
                guard !Task.isCancelled, let self,
                      self.isCurrent(connection, generation: generation) else { break }
                self.send(["type": "ping"], through: connection, generation: generation)
            }
        }
    }

    public func resume(lastEventId: Int) {
        guard let socket else { return }
        send(["type": "resume", "lastEventId": lastEventId], through: socket, generation: connectionGeneration)
    }

    public func disconnect() {
        connectionGeneration &+= 1
        receiveTask?.cancel()
        pingTask?.cancel()
        receiveTask = nil
        pingTask = nil
        socket?.cancel()
        socket = nil
        recentEventIDs.removeAll(keepingCapacity: false)
        recentEventOrder = [String?](repeating: nil, count: Self.recentEventLimit)
        nextEventSlot = 0
    }

    private func send(_ object: [String: Any], through connection: any RealtimeSocketTransport, generation: UInt64) {
        guard isCurrent(connection, generation: generation),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return }
        connection.send(text)
    }

    private func isCurrent(_ connection: any RealtimeSocketTransport, generation: UInt64) -> Bool {
        connectionGeneration == generation && socket === connection
    }

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { return }
        if type == "event", let event = json["event"] as? String {
            let payload = json["payload"] as? [String: Any] ?? [:]
            if let eventID = payload["eventId"] as? String, !eventID.isEmpty {
                guard remember(eventID) else { return }
            }
            onEvent?(event, payload)
        }
    }

    /// Only IDs are retained, with fixed storage. Legacy frames without an ID remain compatible.
    private func remember(_ eventID: String) -> Bool {
        guard !recentEventIDs.contains(eventID) else { return false }
        if let evicted = recentEventOrder[nextEventSlot] { recentEventIDs.remove(evicted) }
        recentEventOrder[nextEventSlot] = eventID
        recentEventIDs.insert(eventID)
        nextEventSlot = (nextEventSlot + 1) % Self.recentEventLimit
        return true
    }
}
