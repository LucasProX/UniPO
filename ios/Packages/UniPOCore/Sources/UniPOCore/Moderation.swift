import Foundation

/// 当前用户能做什么、在哪些范围（`GET /users/me/capabilities`）。客户端只据此决定显示哪些运营入口。
public struct Capabilities: Codable, Hashable {
    public struct Scope: Codable, Hashable {
        public let capability: String
        public let scopes: [String]
    }

    public let platformRole: String
    public let capabilities: [Scope]

    public func has(_ capability: String) -> Bool {
        capabilities.contains { $0.capability == capability && !$0.scopes.isEmpty }
    }

    /// 能处理举报、审核待发布的帖子。
    public var canHandleReports: Bool { has("report.handle") }
}

/// 举报处理单（O01）。version 用于领取、处置时的并发校验。
public struct ReportCase: Codable, Identifiable, Hashable {
    public struct CategoryCount: Codable, Hashable {
        public let category: String
        public let label: String
        public let count: Int
    }

    public let id: Int64
    public let targetType: String
    public let targetId: Int64
    public let targetStatus: String?
    public let targetUserUid: String?
    public let targetUserNickname: String?
    public let status: String
    public let reportCount: Int
    public let categories: [CategoryCount]
    public let snapshot: String?
    public let firstReportedAt: String?
    public let lastReportedAt: String?
    public let assigneeUid: String?
    public let assigneeNickname: String?
    public let claimedAt: String?
    public let resolution: String?
    public let resolutionNote: String?
    public let handledAt: String?
    public let version: Int

    public var isOpen: Bool { status == "PENDING" || status == "IN_PROGRESS" }

    public var targetLabel: String {
        switch targetType {
        case "POST": return targetStatus == "pending" ? "待审核 PO" : "PO"
        case "COMMENT": return "评论"
        default: return "用户"
        }
    }

    /// 只有系统识别、没有用户举报的单。
    public var isSystemOnly: Bool { categories.allSatisfy { $0.category.hasPrefix("filter:") } }

    public var isPendingPost: Bool { targetType == "POST" && targetStatus == "pending" }

    public var statusLabel: String {
        switch status {
        case "PENDING": return "待领取"
        case "IN_PROGRESS": return "处理中"
        case "RESOLVED": return "已处理"
        default: return "已驳回"
        }
    }

    public var resolutionLabel: String? {
        switch resolution {
        case "REMOVED": return targetType == "COMMENT" ? "已隐藏评论" : "已下架"
        case "APPROVED": return "已通过审核"
        case "NO_VIOLATION": return "未违规"
        case "HANDLED": return "已用其他方式处理"
        default: return nil
        }
    }

    /// 对象此刻的状态（帖子 / 评论 / 用户），帮审核员判断是否已被别处处理。
    public var targetStatusLabel: String? {
        switch targetStatus {
        case "published", "visible", "active": return "正常显示"
        case "pending": return "待审核"
        case "removed": return "已下架"
        case "hidden": return "已隐藏"
        case "deleted": return "已删除"
        case "disabled": return "已封禁"
        default: return nil
        }
    }

    public var categorySummary: String {
        categories.map { $0.count > 1 ? "\($0.label) ×\($0.count)" : $0.label }.joined(separator: "、")
    }
}

public struct ReportCasePage: Codable, Hashable {
    public let items: [ReportCase]
    public let nextBeforeId: Int64?
}

public struct ReportCaseDetail: Codable, Hashable {
    public struct Report: Codable, Identifiable, Hashable {
        public let id: Int64
        public let source: String
        public let category: String
        public let categoryLabel: String
        public let detail: String?
        public let reporterUid: String?
        public let createdAt: String?

        public var isSystem: Bool { source == "FILTER" }
    }

    public let kase: ReportCase
    public let reports: [Report]
}

/// 处置方式，与服务端 `ResolveRequest.action` 一致。
public enum CaseAction: String, CaseIterable, Identifiable {
    case removeContent = "REMOVE_CONTENT"
    case approveContent = "APPROVE_CONTENT"
    case noViolation = "NO_VIOLATION"
    case handled = "HANDLED"

    public var id: String { rawValue }

    public func title(for kase: ReportCase) -> String {
        switch self {
        case .removeContent: return kase.targetType == "COMMENT" ? "隐藏评论" : "下架"
        case .approveContent: return "通过审核"
        case .noViolation: return "未违规"
        case .handled: return "已用其他方式处理"
        }
    }

    /// 这张单可以选哪些处置。
    public static func available(for kase: ReportCase) -> [CaseAction] {
        var actions: [CaseAction] = []
        if kase.isPendingPost { actions.append(.approveContent) }
        if kase.targetType != "USER" { actions.append(.removeContent) }
        actions.append(.noViolation)
        actions.append(.handled)
        return actions
    }
}
