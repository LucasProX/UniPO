import Foundation
import XCTest
@testable import UniPOCore

@MainActor
final class RealtimeClientTests: XCTestCase {
    private enum FixtureError: Error { case closed }

    @MainActor
    private final class Socket: RealtimeSocketTransport {
        var ignoreCancellation = false
        private(set) var receiveCalls = 0
        private(set) var sent: [[String: Any]] = []
        private(set) var cancelCalls = 0
        private var queued: [URLSessionWebSocketTask.Message] = []
        private var receiver: CheckedContinuation<URLSessionWebSocketTask.Message, Error>?
        private var waiters: [(Int, CheckedContinuation<Void, Never>)] = []
        var onSend: (([String: Any]) -> Void)?

        func resume() {}

        func cancel() {
            cancelCalls += 1
            if !ignoreCancellation {
                receiver?.resume(throwing: FixtureError.closed)
                receiver = nil
            }
        }

        func send(_ text: String) {
            let object = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] ?? [:]
            sent.append(object)
            onSend?(object)
        }

        func receive() async throws -> URLSessionWebSocketTask.Message {
            receiveCalls += 1
            let ready = waiters.filter { receiveCalls >= $0.0 }
            waiters.removeAll { receiveCalls >= $0.0 }
            ready.forEach { $0.1.resume() }
            if !queued.isEmpty { return queued.removeFirst() }
            return try await withCheckedThrowingContinuation { receiver = $0 }
        }

        func emit(_ message: URLSessionWebSocketTask.Message) {
            if let receiver {
                self.receiver = nil
                receiver.resume(returning: message)
            } else {
                queued.append(message)
            }
        }

        func waitForReceives(_ count: Int) async {
            guard receiveCalls < count else { return }
            await withCheckedContinuation { waiters.append((count, $0)) }
        }
    }

    @MainActor
    private final class HeartbeatGate {
        private(set) var calls: [CheckedContinuation<Void, Error>] = []
        private var waiters: [(Int, CheckedContinuation<Void, Never>)] = []
        private var resumed: Set<Int> = []

        func wait() async throws {
            try await withCheckedThrowingContinuation { continuation in
                calls.append(continuation)
                let ready = waiters.filter { calls.count >= $0.0 }
                waiters.removeAll { calls.count >= $0.0 }
                ready.forEach { $0.1.resume() }
            }
        }

        func waitForCalls(_ count: Int) async {
            guard calls.count < count else { return }
            await withCheckedContinuation { waiters.append((count, $0)) }
        }

        func release(_ index: Int) {
            guard resumed.insert(index).inserted else { return }
            calls[index].resume()
        }

        func close() {
            for index in calls.indices where resumed.insert(index).inserted {
                calls[index].resume(throwing: FixtureError.closed)
            }
        }
    }

    private func client(_ sockets: [Socket], heartbeat: (@MainActor () async throws -> Void)? = nil) -> RealtimeClient {
        var next = 0
        return RealtimeClient(socketFactory: {
            let socket = sockets[next]
            next += 1
            return socket
        }, heartbeatWait: heartbeat ?? { try await Task.sleep(nanoseconds: 20_000_000_000) })
    }

    private func frame(id: String? = nil, owner: String = "fixture", binary: Bool = false) -> URLSessionWebSocketTask.Message {
        var payload: [String: Any] = ["owner": owner]
        if let id { payload["eventId"] = id }
        let data = try! JSONSerialization.data(withJSONObject: ["type": "event", "event": "message", "payload": payload])
        return binary ? .data(data) : .string(String(decoding: data, as: UTF8.self))
    }

    func testDuplicateOutboxIDsAreIgnoredWhileDistinctAndLegacyFramesContinue() async {
        let socket = Socket()
        let client = client([socket])
        var owners: [String] = []
        client.onEvent = { _, payload in owners.append(payload["owner"] as! String) }
        client.connect(token: "fixture-token")
        socket.emit(frame(id: "first", owner: "first-copy"))
        socket.emit(frame(id: "first", owner: "repeated-copy"))
        socket.emit(frame(id: "second", owner: "distinct", binary: true))
        socket.emit(frame(owner: "legacy"))
        socket.emit(frame(owner: "legacy"))
        await socket.waitForReceives(6)

        XCTAssertEqual(owners, ["first-copy", "distinct", "legacy", "legacy"])
        client.disconnect()
    }

    func testTheBoundedCacheEvictsItsOldestIDAfter1024DistinctEvents() async {
        let socket = Socket()
        let client = client([socket])
        var delivered: [String] = []
        client.onEvent = { _, payload in delivered.append(payload["eventId"] as! String) }
        client.connect(token: "fixture-token")
        for index in 0...1_024 { socket.emit(frame(id: "event-\(index)")) }
        socket.emit(frame(id: "event-0"))
        socket.emit(frame(id: "event-1024"))
        await socket.waitForReceives(1_028)

        XCTAssertEqual(delivered.count, 1_026)
        XCTAssertEqual(delivered.last, "event-0", "The evicted ID is accepted again, while a retained ID is still deduplicated")
        client.disconnect()
    }

    func testReconnectClearsDeduplicationWithoutRemovingTheNewCallback() async {
        let first = Socket()
        let second = Socket()
        let client = client([first, second])
        var firstAccount = 0
        var secondAccount = 0
        client.onEvent = { _, _ in firstAccount += 1 }
        client.connect(token: "fixture-account-a")
        first.emit(frame(id: "same-id"))
        await first.waitForReceives(2)

        client.onEvent = { _, _ in secondAccount += 1 }
        client.connect(token: "fixture-account-b")
        second.emit(frame(id: "same-id"))
        await second.waitForReceives(2)

        XCTAssertEqual(firstAccount, 1)
        XCTAssertEqual(secondAccount, 1)
        XCTAssertEqual(first.cancelCalls, 1)
        client.disconnect()
    }

    func testALateOldSocketFrameCannotReachTheNewAccountOrPoisonItsIDCache() async {
        let first = Socket()
        first.ignoreCancellation = true
        let second = Socket()
        let client = client([first, second])
        client.connect(token: "fixture-account-a")
        await first.waitForReceives(1)
        let leaked = expectation(description: "Old private frame must never reach the new callback")
        leaked.isInverted = true
        var owners: [String] = []
        client.onEvent = { _, payload in
            let owner = payload["owner"] as! String
            owners.append(owner)
            if owner == "old-account" { leaked.fulfill() }
        }
        client.connect(token: "fixture-account-b")
        await second.waitForReceives(1)

        first.emit(frame(id: "shared-id", owner: "old-account"))
        second.emit(frame(id: "shared-id", owner: "new-account"))
        await second.waitForReceives(2)
        await fulfillment(of: [leaked], timeout: 0.05)

        XCTAssertEqual(owners, ["new-account"])
        XCTAssertEqual(first.receiveCalls, 1)
        XCTAssertEqual(second.receiveCalls, 2, "The old receive loop must never begin consuming the new transport")
        client.disconnect()
    }

    func testDisconnectRejectsLateFramesEvenWhenTheTransportIgnoresCancellation() async {
        let socket = Socket()
        socket.ignoreCancellation = true
        let client = client([socket])
        let leaked = expectation(description: "Disconnected frame must not be delivered")
        leaked.isInverted = true
        client.onEvent = { _, _ in leaked.fulfill() }
        client.connect(token: "fixture-token")
        await socket.waitForReceives(1)

        client.disconnect()
        socket.emit(frame(id: "late"))
        await fulfillment(of: [leaked], timeout: 0.05)
        XCTAssertEqual(socket.receiveCalls, 1)
    }

    func testAnOldHeartbeatCannotSendThroughTheNewConnectionAfterCancellation() async {
        let first = Socket()
        let second = Socket()
        let heartbeats = HeartbeatGate()
        let client = client([first, second], heartbeat: { try await heartbeats.wait() })
        client.connect(token: "fixture-account-a")
        await heartbeats.waitForCalls(1)
        client.connect(token: "fixture-account-b")
        await heartbeats.waitForCalls(2)
        let duplicatePing = expectation(description: "Cancelled heartbeat must not ping the new connection")
        duplicatePing.isInverted = true
        var pingCount = 0
        second.onSend = { payload in
            if payload["type"] as? String == "ping" {
                pingCount += 1
                if pingCount > 1 { duplicatePing.fulfill() }
            }
        }

        heartbeats.release(0) // The old wait deliberately ignores Task cancellation.
        heartbeats.release(1)
        await heartbeats.waitForCalls(3)
        await fulfillment(of: [duplicatePing], timeout: 0.05)

        XCTAssertEqual(pingCount, 1)
        XCTAssertEqual(first.sent.compactMap { $0["type"] as? String }, ["auth"])
        XCTAssertEqual(second.sent.compactMap { $0["type"] as? String }, ["auth", "ping"])
        client.disconnect()
        heartbeats.close()
    }
}
