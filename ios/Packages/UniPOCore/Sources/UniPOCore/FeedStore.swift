import Foundation
import Observation

@Observable
@MainActor
public final class FeedStore {
    public typealias PageLoader = (String, String?, Int) async throws -> PostPage

    public private(set) var board = "recommend"
    public private(set) var posts: [PostView] = []
    public private(set) var hasMore = false
    public private(set) var isLoading = false
    public private(set) var isLoadingMore = false
    public private(set) var errorMessage: String?

    private var cursor: String?
    private var generation = 0
    private let loadPage: PageLoader

    /// 「关注」不是板块，走单独的关注流接口；其余按板块分页。
    public static let followingBoard = "following"

    public init(loadPage: @escaping PageLoader = { board, cursor, limit in
        board == FeedStore.followingBoard
            ? try await APIClient.shared.followingFeed(cursor: cursor, limit: limit)
            : try await APIClient.shared.feed(tab: board, cursor: cursor, limit: limit)
    }) {
        self.loadPage = loadPage
    }

    public func clear() {
        generation += 1
        posts = []
        cursor = nil
        hasMore = false
        isLoading = false
        isLoadingMore = false
        errorMessage = nil
    }

    public func refresh(board requestedBoard: String? = nil) async {
        let requestedBoard = requestedBoard ?? board
        if requestedBoard != board { posts = [] }
        board = requestedBoard
        generation += 1
        let requestGeneration = generation
        isLoading = true
        isLoadingMore = false
        errorMessage = nil
        cursor = nil
        hasMore = false
        do {
            let page = try await loadPage(requestedBoard, nil, 20)
            guard generation == requestGeneration else { return }
            posts = unique(page.items)
            cursor = page.nextCursor
            hasMore = page.hasMore && page.nextCursor != nil
        } catch {
            guard generation == requestGeneration else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "加载失败，请重试"
        }
        isLoading = false
    }

    public func loadMore() async {
        guard !isLoading, !isLoadingMore, hasMore, let cursor else { return }
        let requestGeneration = generation
        let requestedBoard = board
        isLoadingMore = true
        errorMessage = nil
        do {
            let page = try await loadPage(requestedBoard, cursor, 20)
            guard generation == requestGeneration else { return }
            posts = unique(posts + page.items)
            self.cursor = page.nextCursor
            hasMore = page.hasMore && page.nextCursor != nil
        } catch {
            guard generation == requestGeneration else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "加载失败，请重试"
        }
        isLoadingMore = false
    }

    /// 本人删除帖子后就地移除，不打断当前分页位置。
    public func remove(postID: Int64) {
        posts.removeAll { $0.id == postID }
    }

    /// 本人编辑帖子后就地替换；改完需要重新审核的帖子不再出现在信息流里。
    public func replace(_ post: PostView) {
        guard let index = posts.firstIndex(where: { $0.id == post.id }) else { return }
        if post.status == "pending" { posts.remove(at: index) } else { posts[index] = post }
    }

    private func unique(_ items: [PostView]) -> [PostView] {
        var ids = Set<Int64>()
        return items.filter { ids.insert($0.id).inserted }
    }
}
