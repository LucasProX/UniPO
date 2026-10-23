import Foundation
import Observation

/// 成长视角下的一条活动报名；checkedIn 表示签到有效（未被撤销）。
public struct GrowthParticipation: Codable, Identifiable, Hashable {
    public let activityId: Int64
    public let title: String
    public let category: String
    public let location: String
    public let organizerName: String?
    public let startsAt: String
    public let endsAt: String
    /** PUBLISHED / CANCELLED */
    public let activityStatus: String
    /** REGISTERED / WAITLISTED / PENDING / REJECTED */
    public let registrationStatus: String
    public let checkedIn: Bool
    public let checkedInAt: String?
    public let checkinRevoked: Bool
    public let xpGranted: Int
    public let coinsGranted: Int

    public var id: Int64 { activityId }

    /// 与服务端 GrowthPlanService.countsTowardPlan 一致：被拒、已取消、被撤销的不算进完成度。
    public var countsTowardPlan: Bool {
        if checkedIn { return true }
        return activityStatus == "PUBLISHED" && registrationStatus != "REJECTED" && !checkinRevoked
    }

    public var statusLabel: String {
        if checkedIn { return "已签到" }
        if checkinRevoked { return "签到已撤销" }
        if activityStatus == "CANCELLED" { return "活动已取消" }
        switch registrationStatus {
        case "WAITLISTED": return "候补中"
        case "PENDING": return "待审核"
        case "REJECTED": return "未通过"
        default:
            if let end = ActivityTime.parse(endsAt), end < Date() { return "未签到" }
            return "已报名"
        }
    }
}

public struct PersonalTask: Codable, Identifiable, Hashable {
    public let id: Int64
    public let termStart: String
    public let title: String
    public let note: String?
    public let dueAt: String?
    public let completed: Bool
    public let completedAt: String?
    public let createdAt: String

    public var isOverdue: Bool {
        guard !completed, let due = ActivityTime.parse(dueAt) else { return false }
        return due < Date()
    }
}

public struct TermSummary: Codable, Hashable {
    public let done: Int
    public let total: Int
    public let attended: Int
    public let activityXp: Int
    public let activityCoins: Int

    public var fraction: Double { total == 0 ? 0 : Double(done) / Double(total) }
}

/// 本学期清单：已报名的活动 + 当期主线任务 + 个人任务。
public struct TermPlan: Codable, Hashable {
    public let termStart: String
    public let termEnd: String
    public let termLabel: String
    public let termIndex: Int?
    public let current: Bool
    public let previousTermStart: String?
    public let nextTermStart: String?
    public let canAddTasks: Bool
    public let summary: TermSummary
    public let activities: [GrowthParticipation]
    public let officialStage: AchievementStageView?
    public let personalTasks: [PersonalTask]
}

public struct GrowthArchiveTerm: Codable, Identifiable, Hashable {
    public let termStart: String
    public let termLabel: String
    public let current: Bool
    public let activities: [GrowthParticipation]
    public let badges: [AchievementBadgeView]
    public let officialCompleted: Int
    public let personalCompleted: Int
    public let activityXp: Int
    public let activityCoins: Int

    public var id: String { termStart }
    public var isEmpty: Bool {
        activities.isEmpty && badges.isEmpty && officialCompleted == 0 && personalCompleted == 0
    }
}

public struct GrowthArchive: Codable, Hashable {
    public let attendedTotal: Int
    public let badgeTotal: Int
    public let terms: [GrowthArchiveTerm]
}

public struct PersonalTaskInput: Hashable {
    public var title: String
    public var note: String
    public var dueAt: Date?
    /// 新建时归属的学期起始日；为空放进当前学期。编辑时忽略。
    public var termStart: String?

    public init(title: String = "", note: String = "", dueAt: Date? = nil, termStart: String? = nil) {
        self.title = title
        self.note = note
        self.dueAt = dueAt
        self.termStart = termStart
    }

    public static let maxTitle = 40
    public static let maxNote = 200

    public var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }
    public var isValid: Bool {
        !trimmedTitle.isEmpty && trimmedTitle.count <= Self.maxTitle && note.count <= Self.maxNote
    }

    var body: [String: Any] {
        var body: [String: Any] = ["title": trimmedTitle]
        let note = note.trimmingCharacters(in: .whitespacesAndNewlines)
        if !note.isEmpty { body["note"] = note }
        if let dueAt { body["dueAt"] = ActivityTime.iso(dueAt) }
        if let termStart { body["termStart"] = termStart }
        return body
    }
}

/// 成长页「本学期」的请求状态：切学期、刷新和切账号会作废旧响应；改动后重新拉取，
/// 完成度统一以服务端汇总为准，客户端不自己加减。
@MainActor
@Observable
public final class TermPlanStore {
    public typealias Loader = (String?) async throws -> TermPlan

    public private(set) var plan: TermPlan?
    /// nil 表示当前学期。
    public private(set) var termStart: String?
    public private(set) var isLoading = false
    public private(set) var isMutating = false
    public private(set) var errorMessage: String?

    private var generation = 0
    private let loader: Loader

    public init(loader: @escaping Loader = { try await APIClient.shared.termPlan(termStart: $0) }) {
        self.loader = loader
    }

    public func load(termStart requested: String? = nil) async {
        if requested != termStart {
            termStart = requested
            plan = nil
        }
        generation += 1
        let current = generation
        isLoading = true
        errorMessage = nil
        defer { if generation == current { isLoading = false } }
        do {
            let value = try await loader(termStart)
            guard generation == current else { return }
            plan = value
        } catch {
            guard generation == current else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "清单加载失败，请重试"
        }
    }

    public func showPrevious() async {
        guard let previous = plan?.previousTermStart else { return }
        await load(termStart: previous)
    }

    public func showNext() async {
        guard let next = plan?.nextTermStart else { return }
        await load(termStart: next)
    }

    public func showCurrent() async {
        await load(termStart: nil)
    }

    /// 执行一次改动，成功后按当前学期重新拉取；失败保留现有清单并给出提示。
    @discardableResult
    public func perform(_ action: () async throws -> Void) async -> Bool {
        guard !isMutating else { return false }
        isMutating = true
        defer { isMutating = false }
        do {
            try await action()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "操作失败，请重试"
            return false
        }
        await load(termStart: termStart)
        return true
    }

    /// 退出或换号：作废在途请求并清空。
    public func reset() {
        generation += 1
        plan = nil
        termStart = nil
        isLoading = false
        errorMessage = nil
    }
}
