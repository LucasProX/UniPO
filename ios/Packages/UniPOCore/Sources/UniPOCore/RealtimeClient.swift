import Foundation

/// 极简 WebSocket 客户端：auth -> 收 event -> 心跳；断线后由页面重新 connect。
/// 协议见后端 RealtimeWebSocketHandler。
@MainActor
public final class RealtimeClient {
    public static let shared = RealtimeClient()

    public var onEvent: ((_ event: String, _ payload: [String: Any]) -> Void)?

    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private let session: URLSession

    public init() {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieStorage = nil
        session = URLSession(configuration: config)
    }

    public func connect(token: String) {
        disconnect()
        socket = session.webSocketTask(with: AppConfig.realtimeURL)
        socket?.resume()
        send(["type": "auth", "token": token])
        receiveTask = Task { [weak self] in
            await self?.receiveLoop()
        }
        pingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                await self?.send(["type": "ping"])
            }
        }
    }

    public func resume(lastEventId: Int) {
        send(["type": "resume", "lastEventId": lastEventId])
    }

    public func disconnect() {
        receiveTask?.cancel()
        pingTask?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
    }

    private func send(_ object: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return }
        socket?.send(.string(text)) { _ in }
    }

    private func receiveLoop() async {
        while !Task.isCancelled, let socket {
            do {
                let message = try await socket.receive()
                switch message {
                case .string(let text):
                    handle(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) { handle(text) }
                @unknown default:
                    break
                }
            } catch {
                break
            }
        }
    }

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { return }
        if type == "event", let event = json["event"] as? String {
            let payload = json["payload"] as? [String: Any] ?? [:]
            onEvent?(event, payload)
        }
    }
}
