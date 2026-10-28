import Foundation

/// F14：发帖草稿，只存最近一份，按账号和 API 环境隔离。
///
/// 存在本机（UserDefaults），图片是已经上传好的地址。读取时账号不对就丢掉，不会把别人的草稿给新登录的人看；
/// 明确退出登录时清空（`AuthStore.logout`）。只有从底栏「发布」打开的空白发帖页用草稿，
/// 从活动、任务带着关联打开的和编辑已有帖子不读也不写。
public struct ComposeDraft: Codable, Equatable {
    public var board: String
    public var title: String
    public var content: String
    public var tags: [String]
    public var images: [String]
    public var linkKind: String?
    public var linkID: Int64?
    public var linkTitle: String?

    public init(board: String, title: String, content: String, tags: [String], images: [String], link: PostLink?) {
        self.board = board
        self.title = title
        self.content = content
        self.tags = tags
        self.images = images
        switch link {
        case .activity(let ref): linkKind = "activity"; linkID = ref.id; linkTitle = ref.title
        case .task(let ref): linkKind = "task"; linkID = ref.id; linkTitle = ref.title
        case nil: linkKind = nil; linkID = nil; linkTitle = nil
        }
    }

    public var link: PostLink? {
        guard let linkID, let linkTitle else { return nil }
        let ref = PostLinkRef(id: linkID, title: linkTitle)
        switch linkKind {
        case "activity": return .activity(ref)
        case "task": return .task(ref)
        default: return nil
        }
    }

    /// 什么都没写（只选了板块）不算草稿。
    public var isEmpty: Bool {
        title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && tags.isEmpty && images.isEmpty && link == nil
    }
}

public final class ComposeDraftStore {
    public static let shared = ComposeDraftStore()

    private struct Stored: Codable {
        let owner: Int64
        let environment: String
        let draft: ComposeDraft
    }

    private let defaults: UserDefaults
    private let key: String
    private let environment: String

    public init(defaults: UserDefaults = .standard, key: String = "unipo.compose.draft",
                environment: String = AppConfig.apiBaseURL.absoluteString) {
        self.defaults = defaults
        self.key = key
        self.environment = environment
    }

    public func load(owner: Int64) -> ComposeDraft? {
        guard let data = defaults.data(forKey: key),
              let stored = try? JSONDecoder().decode(Stored.self, from: data) else { return nil }
        guard stored.owner == owner, stored.environment == environment else {
            clear()
            return nil
        }
        return stored.draft
    }

    /// 空草稿等于清空。
    public func save(_ draft: ComposeDraft, owner: Int64) {
        guard !draft.isEmpty else { clear(); return }
        guard let data = try? JSONEncoder().encode(Stored(owner: owner, environment: environment, draft: draft)) else { return }
        defaults.set(data, forKey: key)
    }

    public func clear() {
        defaults.removeObject(forKey: key)
    }
}
