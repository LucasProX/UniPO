import XCTest
@testable import UniPOCore

@MainActor
final class FeedStoreTests: XCTestCase {
    private final class Loader {
        struct Request {
            let board: String
            let cursor: String?
            let completion: CheckedContinuation<PostPage, Error>
        }
        var requests: [Request] = []
        func load(board: String, cursor: String?, limit: Int) async throws -> PostPage {
            try await withCheckedThrowingContinuation { completion in
                requests.append(Request(board: board, cursor: cursor, completion: completion))
            }
        }
    }

    private func waitFor(_ loader: Loader, count: Int) async {
        for _ in 0..<1000 where loader.requests.count < count { await Task.yield() }
        XCTAssertEqual(loader.requests.count, count)
    }

    private func page(_ ids: [Int], cursor: String? = nil) throws -> PostPage {
        let objects: [[String: Any]] = ids.map { id in
            ["id": id, "board": "school", "title": "测试标题", "content": "测试内容",
             "author": ["id": 10, "uid": "fixture", "nickname": "测试作者", "role": "USER", "level": 1]]
        }
        var value: [String: Any] = ["items": objects, "hasMore": cursor != nil, "limit": 20]
        if let cursor { value["nextCursor"] = cursor }
        return try JSONDecoder().decode(PostPage.self, from: JSONSerialization.data(withJSONObject: value))
    }

    func testOnlyOneLoadMoreIsInFlightAndDuplicateItemsAreRemoved() async throws {
        let loader = Loader()
        let store = FeedStore(loadPage: loader.load)
        let initial = Task { await store.refresh() }
        await waitFor(loader, count: 1)
        loader.requests[0].completion.resume(returning: try page([1], cursor: "next"))
        await initial.value
        let first = Task { await store.loadMore() }
        await waitFor(loader, count: 2)
        await store.loadMore()
        XCTAssertEqual(loader.requests.count, 2)
        XCTAssertTrue(store.isLoadingMore)
        loader.requests[1].completion.resume(returning: try page([1, 2]))
        await first.value
        XCTAssertEqual(store.posts.map(\.id), [1, 2])
        XCTAssertFalse(store.isLoadingMore)
        XCTAssertFalse(store.hasMore)
    }

    func testLatePreviousBoardResponseCannotReplaceCurrentBoard() async throws {
        let loader = Loader()
        let store = FeedStore(loadPage: loader.load)
        let school = Task { await store.refresh(board: "school") }
        await waitFor(loader, count: 1)
        let major = Task { await store.refresh(board: "major") }
        await waitFor(loader, count: 2)
        loader.requests[1].completion.resume(returning: try page([2]))
        await major.value
        loader.requests[0].completion.resume(returning: try page([1], cursor: "stale"))
        await school.value
        XCTAssertEqual(store.board, "major")
        XCTAssertEqual(store.posts.map(\.id), [2])
        XCTAssertFalse(store.hasMore)
        XCTAssertFalse(store.isLoading)
    }

    func testRefreshDiscardsInFlightPreviousPage() async throws {
        let loader = Loader()
        let store = FeedStore(loadPage: loader.load)
        let initial = Task { await store.refresh() }
        await waitFor(loader, count: 1)
        loader.requests[0].completion.resume(returning: try page([1], cursor: "next"))
        await initial.value
        let more = Task { await store.loadMore() }
        await waitFor(loader, count: 2)
        let refresh = Task { await store.refresh() }
        await waitFor(loader, count: 3)
        loader.requests[2].completion.resume(returning: try page([3]))
        await refresh.value
        loader.requests[1].completion.resume(returning: try page([1, 2]))
        await more.value
        XCTAssertEqual(store.posts.map(\.id), [3])
        XCTAssertFalse(store.isLoadingMore)
    }

    func testAccountChangeClearsItemsAndRejectsPreviousAccountResponse() async throws {
        let loader = Loader()
        let store = FeedStore(loadPage: loader.load)
        let initial = Task { await store.refresh() }
        await waitFor(loader, count: 1)
        loader.requests[0].completion.resume(returning: try page([1], cursor: "private-page"))
        await initial.value
        let more = Task { await store.loadMore() }
        await waitFor(loader, count: 2)
        store.clear()
        loader.requests[1].completion.resume(returning: try page([2]))
        await more.value
        XCTAssertTrue(store.posts.isEmpty)
        XCTAssertFalse(store.hasMore)
        XCTAssertFalse(store.isLoadingMore)
    }

    func testFailedPaginationKeepsItemsAndRetriesTheSameCursor() async throws {
        let loader = Loader()
        let store = FeedStore(loadPage: loader.load)
        let initial = Task { await store.refresh() }
        await waitFor(loader, count: 1)
        loader.requests[0].completion.resume(returning: try page([1], cursor: "retry-this"))
        await initial.value
        let more = Task { await store.loadMore() }
        await waitFor(loader, count: 2)
        loader.requests[1].completion.resume(throwing: URLError(.notConnectedToInternet))
        await more.value
        XCTAssertEqual(store.posts.map(\.id), [1])
        XCTAssertNotNil(store.errorMessage)
        XCTAssertTrue(store.hasMore)
        let retry = Task { await store.loadMore() }
        await waitFor(loader, count: 3)
        XCTAssertEqual(loader.requests[2].cursor, "retry-this")
        loader.requests[2].completion.resume(returning: try page([2]))
        await retry.value
        XCTAssertEqual(store.posts.map(\.id), [1, 2])
        XCTAssertNil(store.errorMessage)
    }
}
