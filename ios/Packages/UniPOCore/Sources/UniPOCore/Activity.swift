import Foundation
import Observation

public struct ActivityOrganizer: Codable, Hashable {
    public let id: Int64
    public let uid: String
    public let nickname: String
    public let avatarUrl: String?
    public let role: String?
}

public struct ActivityAttendee: Codable, Hashable {
    public let uid: String
    public let nickname: String
    public let avatarUrl: String?
}

public struct ActivityView: Codable, Identifiable, Hashable {
    public let id: Int64
    public let title: String
    public let category: String
    public let coverUrl: String?
    public let description: String
    public let notice: String?
    public let location: String
    public let startsAt: String
    public let endsAt: String
    public let signupDeadline: String
    public let organizerName: String
    public let organizer: ActivityOrganizer?
    public let audienceScope: String
    public let audienceLabel: String
    public let capacity: Int?
    public let registeredCount: Int
    public let waitlistCount: Int
    public let waitlistEnabled: Bool
    public let rewardXp: Int
    public let rewardCoins: Int
    /** OPEN / CLOSED / ONGOING / ENDED / CANCELLED，由服务端计算。 */
    public let phase: String
    /** NONE / REGISTERED / WAITLISTED / CANCELLED */
    public let myStatus: String
    public let myWaitlistPosition: Int?
    public let eligible: Bool
    public let ineligibleReason: String?
    public let recentAttendees: [ActivityAttendee]
    public let checkedIn: Bool
    public let checkedInAt: String?
    /** 开始前 30 分钟到结束为签到时间窗。 */
    public let checkinOpen: Bool
    /** 发布者本人或管理员，可以打开签到码页面。 */
    public let canManage: Bool
    public let requireApproval: Bool
    public let cancelReason: String?
    /** 组织者撤销了我的签到；撤销后不能再签到。 */
    public let checkinRevoked: Bool

    public var isPending: Bool { myStatus == "PENDING" }
    public var isRejected: Bool { myStatus == "REJECTED" }

    public var isRegistered: Bool { myStatus == "REGISTERED" }
    public var isWaitlisted: Bool { myStatus == "WAITLISTED" }
    public var isFull: Bool { capacity.map { registeredCount >= $0 } ?? false }
    public var hasReward: Bool { rewardXp > 0 || rewardCoins > 0 }

    public var categoryLabel: String {
        switch category {
        case "LECTURE": return "讲座"
        case "COMPETITION": return "比赛"
        case "VOLUNTEER": return "志愿"
        case "SPORTS": return "体育"
        case "CULTURE": return "文艺"
        case "CLASS": return "班级"
        default: return "活动"
        }
    }

    public var phaseLabel: String {
        switch phase {
        case "OPEN": return "报名中"
        case "CLOSED": return "报名已截止"
        case "ONGOING": return "进行中"
        case "ENDED": return "已结束"
        case "CANCELLED": return "已取消"
        default: return ""
        }
    }
}

public struct ActivityCheckinResult: Codable {
    public let activity: ActivityView
    public let alreadyCheckedIn: Bool
    public let rewardXp: Int
    public let rewardCoins: Int
}

public struct ActivityOrganizerCheckin: Codable {
    public let qrPayload: String
    public let code: String
    public let refreshAfterSeconds: Int
    public let windowOpen: Bool
    public let windowOpensAt: String?
    public let windowClosesAt: String?
    public let checkedInCount: Int
    public let registeredCount: Int
}

/// 现场二维码内容：`unipo://checkin?activity=12&token=...`。系统相机扫到会打开 App，App 内扫码也走同一个解析。
public struct CheckinLink: Equatable {
    public let activityID: Int64
    public let token: String

    public init?(url: URL) {
        guard url.scheme == "unipo", url.host == "checkin",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let id = items.first(where: { $0.name == "activity" })?.value.flatMap(Int64.init), id > 0,
              let token = items.first(where: { $0.name == "token" })?.value,
              token.count == 22, token.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" })
        else { return nil }
        activityID = id
        self.token = token
    }

    public init?(string: String) {
        guard let url = URL(string: string.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        self.init(url: url)
    }
}

public struct WalletLedgerEntry: Codable, Identifiable, Hashable {
    public let id: Int64
    public let amount: Int
    public let balanceAfter: Int
    public let sourceType: String
    public let sourceId: Int64?
    public let reason: String
    public let createdAt: String?
}

public struct WalletView: Codable {
    public let balance: Int
    public let earnedToday: Int
    public let dailyCap: Int
    public let entries: [WalletLedgerEntry]
    public let nextCursor: String?
    public let hasMore: Bool
}

public struct ActivityRosterEntry: Codable, Identifiable, Hashable {
    public var id: Int64 { registrationId }
    public let registrationId: Int64
    public let uid: String
    public let nickname: String
    public let avatarUrl: String?
    public let college: String?
    public let major: String?
    public let grade: String?
    /** REGISTERED / WAITLISTED / PENDING / CANCELLED / REJECTED */
    public let status: String
    public let queuedAt: String?
    public let checkedInAt: String?
    public let checkinMethod: String?
    public let rewardXpGranted: Int
    public let rewardCoinsGranted: Int
    public let checkinRevokedAt: String?
    public let revokeReason: String?
    public let revocable: Bool
}

public struct ActivityRosterPage: Codable {
    public let items: [ActivityRosterEntry]
    public let nextCursor: String?
    public let hasMore: Bool
    public let registeredCount: Int
    public let waitlistCount: Int
    public let pendingCount: Int
    public let checkedInCount: Int
}

/// 编辑活动只发送改动过的字段；名额为空表示改为不限。
public struct UpdateActivityInput {
    public var title: String
    public var location: String
    public var description: String
    public var notice: String
    public var startsAt: Date
    public var endsAt: Date
    public var signupDeadline: Date
    public var capacity: Int?

    public init(activity: ActivityView) {
        title = activity.title
        location = activity.location
        description = activity.description
        notice = activity.notice ?? ""
        startsAt = ActivityTime.parse(activity.startsAt) ?? Date()
        endsAt = ActivityTime.parse(activity.endsAt) ?? Date()
        signupDeadline = ActivityTime.parse(activity.signupDeadline) ?? Date()
        capacity = activity.capacity
    }

    var body: [String: Any] {
        var value: [String: Any] = [
            "title": title.trimmingCharacters(in: .whitespacesAndNewlines),
            "location": location.trimmingCharacters(in: .whitespacesAndNewlines),
            "description": description.trimmingCharacters(in: .whitespacesAndNewlines),
            "notice": notice.trimmingCharacters(in: .whitespacesAndNewlines),
            "startsAt": ActivityTime.iso(startsAt),
            "endsAt": ActivityTime.iso(endsAt),
            "signupDeadline": ActivityTime.iso(signupDeadline)
        ]
        if let capacity { value["capacity"] = capacity } else { value["clearCapacity"] = true }
        return value
    }
}

public struct AppNotification: Codable, Identifiable, Hashable {
    public let id: Int64
    public let category: String
    public let type: String
    public let title: String
    public let body: String
    public let activityId: Int64?
    public let read: Bool
    public let createdAt: String?
    /// 与帖子相关的通知（关注的人发帖等）。
    public let postId: Int64?
}

public struct AppNotificationPage: Codable {
    public let items: [AppNotification]
    public let nextCursor: String?
    public let hasMore: Bool
    public let unreadCount: Int
}

public struct ActivityPage: Codable {
    public let items: [ActivityView]
    public let nextCursor: String?
    public let hasMore: Bool
    public let limit: Int
}

public struct ActivityOrganizerProfile: Codable {
    public let canPublish: Bool
    public let level: String?
    public let allowedScopes: [String]
    public let collegeId: Int64?
    public let collegeName: String?
    public let majorId: Int64?
    public let majorName: String?
    public let maxRewardXp: Int
    public let maxRewardCoins: Int
}

public struct CreateActivityInput {
    public var title = ""
    public var category = "LECTURE"
    public var organizerName = ""
    public var description = ""
    public var notice = ""
    public var location = ""
    public var startsAt: Date
    public var endsAt: Date
    public var signupDeadline: Date
    public var audienceScope = "SCHOOL"
    public var audienceCollegeId: Int64?
    public var audienceMajorId: Int64?
    public var audienceEntryYear: Int?
    public var capacity: Int?
    public var waitlistEnabled = true
    public var rewardXp = 0
    public var rewardCoins = 0
    public var requireApproval = false

    public init(now: Date = Date()) {
        let start = Calendar.current.date(byAdding: .day, value: 3, to: now) ?? now
        startsAt = start
        endsAt = start.addingTimeInterval(2 * 3600)
        signupDeadline = start.addingTimeInterval(-3600)
    }

    var body: [String: Any] {
        var value: [String: Any] = [
            "title": title.trimmingCharacters(in: .whitespacesAndNewlines),
            "category": category,
            "description": description.trimmingCharacters(in: .whitespacesAndNewlines),
            "location": location.trimmingCharacters(in: .whitespacesAndNewlines),
            "startsAt": ActivityTime.iso(startsAt),
            "endsAt": ActivityTime.iso(endsAt),
            "signupDeadline": ActivityTime.iso(signupDeadline),
            "audienceScope": audienceScope,
            "waitlistEnabled": waitlistEnabled,
            "rewardXp": rewardXp,
            "rewardCoins": rewardCoins,
            "requireApproval": requireApproval
        ]
        let name = organizerName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !name.isEmpty { value["organizerName"] = name }
        let noticeText = notice.trimmingCharacters(in: .whitespacesAndNewlines)
        if !noticeText.isEmpty { value["notice"] = noticeText }
        if let audienceCollegeId { value["audienceCollegeId"] = audienceCollegeId }
        if let audienceMajorId { value["audienceMajorId"] = audienceMajorId }
        if let audienceEntryYear { value["audienceEntryYear"] = audienceEntryYear }
        if let capacity { value["capacity"] = capacity }
        return value
    }
}

/// 活动时间统一按 Asia/Shanghai 展示，与服务端解释口径一致，不依赖设备时区。
public enum ActivityTime {
    public static let zone = TimeZone(identifier: "Asia/Shanghai")!

    public static func parse(_ raw: String?) -> Date? {
        guard let raw else { return nil }
        let fraction = ISO8601DateFormatter()
        fraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fraction.date(from: raw) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: raw)
    }

    public static func iso(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = zone
        return formatter.string(from: date)
    }

    /// 「11月2日 周日 14:00–16:00」；跨天时两端都带日期。
    public static func range(_ start: String, _ end: String) -> String {
        guard let s = parse(start), let e = parse(end) else { return "" }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let day = formatter("M月d日 EEE")
        let time = formatter("HH:mm")
        if calendar.isDate(s, inSameDayAs: e) {
            return "\(day.string(from: s)) \(time.string(from: s))–\(time.string(from: e))"
        }
        return "\(day.string(from: s)) \(time.string(from: s)) – \(day.string(from: e)) \(time.string(from: e))"
    }

    public static func short(_ raw: String) -> String {
        guard let date = parse(raw) else { return "" }
        return formatter("M月d日 HH:mm").string(from: date)
    }

    private static func formatter(_ format: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.timeZone = zone
        formatter.dateFormat = format
        return formatter
    }
}

/// 活动列表分页：一次只有一条在途请求，切换列表或刷新会作废旧响应，按 ID 去重。
@Observable
@MainActor
public final class ActivityListStore {
    public typealias PageLoader = (String, String?) async throws -> ActivityPage

    public private(set) var tab: String
    public private(set) var items: [ActivityView] = []
    public private(set) var hasMore = false
    public private(set) var isLoading = false
    public private(set) var isLoadingMore = false
    public private(set) var errorMessage: String?

    private var cursor: String?
    private var generation = 0
    private let loadPage: PageLoader

    public init(tab: String = "recommend", loadPage: @escaping PageLoader = { tab, cursor in
        if tab == "organized" { return try await APIClient.shared.organizedActivities(cursor: cursor) }
        if tab == "upcoming" || tab == "history" {
            return try await APIClient.shared.myActivities(state: tab, cursor: cursor)
        }
        return try await APIClient.shared.activities(tab: tab, cursor: cursor)
    }) {
        self.tab = tab
        self.loadPage = loadPage
    }

    public func refresh(tab requested: String? = nil) async {
        if let requested, requested != tab { tab = requested; items = [] }
        generation += 1
        let current = generation
        isLoading = true
        isLoadingMore = false
        errorMessage = nil
        defer { if generation == current { isLoading = false } }
        do {
            let page = try await loadPage(tab, nil)
            guard generation == current else { return }
            items = unique(page.items)
            cursor = page.nextCursor
            hasMore = page.hasMore && page.nextCursor != nil
        } catch {
            guard generation == current else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "活动加载失败，请重试"
        }
    }

    public func loadMore() async {
        guard !isLoading, !isLoadingMore, hasMore, let cursor else { return }
        let current = generation
        isLoadingMore = true
        defer { if generation == current { isLoadingMore = false } }
        do {
            let page = try await loadPage(tab, cursor)
            guard generation == current else { return }
            items = unique(items + page.items)
            self.cursor = page.nextCursor
            hasMore = page.hasMore && page.nextCursor != nil
        } catch {
            guard generation == current else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "活动加载失败，请重试"
        }
    }

    /// 详情页报名或取消后用服务端结果替换列表里的同一项。
    public func replace(_ activity: ActivityView) {
        if let index = items.firstIndex(where: { $0.id == activity.id }) { items[index] = activity }
    }

    public func clear() {
        generation += 1
        items = []
        cursor = nil
        hasMore = false
        isLoading = false
        isLoadingMore = false
        errorMessage = nil
    }

    private func unique(_ input: [ActivityView]) -> [ActivityView] {
        var seen = Set<Int64>()
        return input.filter { seen.insert($0.id).inserted }
    }
}
