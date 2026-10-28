import Foundation

public struct APIEnvelope<T: Decodable>: Decodable {
    public let code: String
    public let message: String
    public let data: T?
    public let requestId: String?
}

public struct AuthSession: Codable, Equatable {
    public let token: String
    public let refreshToken: String
    public let expiresInSeconds: Int
    public let user: UserProfile

    public init(token: String, refreshToken: String, expiresInSeconds: Int, user: UserProfile) {
        self.token = token
        self.refreshToken = refreshToken
        self.expiresInSeconds = expiresInSeconds
        self.user = user
    }
}

public struct UserProfile: Codable, Identifiable, Hashable {
    public let id: Int64
    public let publicUid: String
    public let nickname: String
    public let avatarUrl: String?
    public let role: String
    public let level: Int
    public let levelTitle: String?
    public let xp: Int?
    public let bio: String?
    public let college: String?
    public let major: String?
    public let grade: String?
    public let online: Bool?
    public let gender: String?
    public let birthYear: Int?
    public let zodiac: String?
    public let mbti: String?
    public let showGender: Bool?
    public let showAge: Bool?
    public let showGrade: Bool?
    public let onboardingCompleted: Bool?
    public let backgroundUrl: String?
    public let status: String?
    public let schoolId: Int64?
    public let collegeId: Int64?
    public let majorId: Int64?
    /** 入学年份；为空表示成长背景尚未补充。 */
    public let entryYear: Int?

    public init(id: Int64, publicUid: String, nickname: String, avatarUrl: String?, role: String,
                level: Int, levelTitle: String?, xp: Int?, bio: String?, college: String?,
                major: String?, grade: String?, online: Bool?,
                gender: String? = nil, birthYear: Int? = nil, zodiac: String? = nil, mbti: String? = nil,
                showGender: Bool? = nil, showAge: Bool? = nil, showGrade: Bool? = nil,
                onboardingCompleted: Bool? = nil, backgroundUrl: String? = nil, status: String? = nil,
                schoolId: Int64? = nil, collegeId: Int64? = nil, majorId: Int64? = nil, entryYear: Int? = nil) {
        self.id = id
        self.publicUid = publicUid
        self.nickname = nickname
        self.avatarUrl = avatarUrl
        self.role = role
        self.level = level
        self.levelTitle = levelTitle
        self.xp = xp
        self.bio = bio
        self.college = college
        self.major = major
        self.grade = grade
        self.online = online
        self.gender = gender
        self.birthYear = birthYear
        self.zodiac = zodiac
        self.mbti = mbti
        self.showGender = showGender
        self.showAge = showAge
        self.showGrade = showGrade
        self.onboardingCompleted = onboardingCompleted
        self.backgroundUrl = backgroundUrl
        self.status = status
        self.schoolId = schoolId
        self.collegeId = collegeId
        self.majorId = majorId
        self.entryYear = entryYear
    }
}

public struct AuthorView: Codable, Hashable {
    public let id: Int64
    public let uid: String
    public let nickname: String
    public let avatarUrl: String?
    public let role: String
    public let level: Int
    public let levelTitle: String?
    public let school: String?
    public let college: String?
    public let major: String?
    public let grade: String?
    public let online: Bool?
}

public struct UserStatsView: Codable {
    public let favorites: Int
    public let likedPosts: Int
    public let sharedPosts: Int
    public let completed: Int
    public let comments: Int
    public let upcoming: Int
    public let avoidedRisks: Int
    public let following: Int
    public let followers: Int
    public let posts: Int
}

public enum FollowRelationship: Equatable {
    case notFollowing, following, friends

    public init(following: Bool, followedBy: Bool) {
        self = following ? (followedBy ? .friends : .following) : .notFollowing
    }

    public var title: String {
        switch self {
        case .notFollowing: return "关注"
        case .following: return "已关注"
        case .friends: return "朋友"
        }
    }

    public var symbolName: String {
        switch self {
        case .notFollowing: return "plus"
        case .following: return "checkmark"
        case .friends: return "arrow.left.arrow.right"
        }
    }
}

public struct PublicUserProfile: Codable {
    public let profile: UserProfile
    public let stats: UserStatsView
    public let following: Bool
    public let mine: Bool
    public let blocked: Bool
    public let blockedBy: Bool
    public let followedBy: Bool?

    public var relationship: FollowRelationship {
        FollowRelationship(following: following, followedBy: followedBy == true)
    }

    public var isBlockedEitherWay: Bool { blocked || blockedBy }

    public var canManageBlock: Bool { blocked || !blockedBy }

    public func applying(blockStatus: BlockStatusView) -> PublicUserProfile {
        PublicUserProfile(profile: profile, stats: stats, following: following, mine: mine,
                          blocked: blockStatus.blocked, blockedBy: blockStatus.blockedBy,
                          followedBy: followedBy)
    }
}

public struct BlockStatusView: Codable, Equatable {
    public let blocked: Bool
    public let blockedBy: Bool

    public init(blocked: Bool, blockedBy: Bool) {
        self.blocked = blocked
        self.blockedBy = blockedBy
    }

    public var isBlockedEitherWay: Bool { blocked || blockedBy }
}

public struct PostView: Codable, Identifiable, Hashable {
    public let id: Int64
    public let board: String
    public let sourceType: String?
    public let title: String
    public let content: String
    public let excerpt: String?
    public let coverUrl: String?
    public let images: [String]?
    public let riskLevel: String?
    public let official: Bool?
    public let dontMiss: Bool?
    public let pinned: Bool?
    public let deadlineAt: String?
    public let tags: [String]?
    public let likeCount: Int?
    public let commentCount: Int?
    public let favoriteCount: Int?
    public let shareCount: Int?
    public let liked: Bool?
    public let favorited: Bool?
    public let status: String?
    public let publishedAt: String?
    public let createdAt: String?
    public let author: AuthorView
    /// 运营最近一次核实日期 yyyy-MM-dd（G06）。
    public let verifiedAt: String?
    /// 攻略有效期；过期后不再作为任务的有效攻略。
    public let expiresAt: String?
    public let expired: Bool?
    /// 关联的校园活动 / 主线任务（联动）。
    public let activity: PostLinkRef?
    public let task: PostLinkRef?
    /// 作者最近一次编辑的时间（F13）；没编辑过为空。
    public var editedAt: String?

    public var likeTotal: Int { likeCount ?? 0 }
    public var commentTotal: Int { commentCount ?? 0 }
    public var favoriteTotal: Int { favoriteCount ?? 0 }
    public var tagList: [String] { tags ?? [] }

    /// 帖子当前挂的关联（编辑时带回发帖页）。
    public var link: PostLink? {
        if let activity { return .activity(activity) }
        if let task { return .task(task) }
        return nil
    }
}

/// 标签页头部（F09）：同校可见的帖子数和最常关联的主线任务。
public struct TagSummary: Codable, Hashable {
    public let name: String
    public let postCount: Int
    public let tasks: [PostLinkRef]
}

public struct PostLinkRef: Codable, Hashable {
    public let id: Int64
    public let title: String

    public init(id: Int64, title: String) {
        self.id = id
        self.title = title
    }
}

/// 发帖时挂载的对象：活动或主线任务，二选一。
public enum PostLink: Hashable {
    case activity(PostLinkRef)
    case task(PostLinkRef)

    public var ref: PostLinkRef {
        switch self {
        case .activity(let ref), .task(let ref): return ref
        }
    }

    public var label: String {
        switch self {
        case .activity: return "活动"
        case .task: return "主线任务"
        }
    }
}

/// 他人主页上的成长展示。
public struct PublicGrowth: Codable, Hashable {
    public let level: Int
    public let levelTitle: String?
    public let xp: Int
    public let termLabel: String?
    public let attendedCount: Int
    public let badges: [AchievementBadgeView]
    public let recentActivities: [PublicGrowthActivity]
    /// 对方选的主页称号（F15）。
    public var title: AchievementBadgeView?
}

public struct PublicGrowthActivity: Codable, Hashable, Identifiable {
    public let id: Int64
    public let title: String
    public let category: String?
    public let startsAt: String
}

public struct PostPage: Codable {
    public let items: [PostView]
    public let nextCursor: String?
    public let hasMore: Bool
    public let limit: Int
}

public struct CommentView: Codable, Identifiable, Hashable {
    public let id: Int64
    public let postId: Int64
    public let parentId: Int64?
    public let content: String
    public let likeCount: Int?
    public let replyCount: Int?
    public let liked: Bool?
    public let createdAt: String?
    public let author: AuthorView
}

public struct ConversationView: Codable, Identifiable, Hashable {
    public let id: Int64
    public let peer: AuthorView
    public let lastMessage: String?
    public let unreadCount: Int?
    public let lastMessageAt: String?
    public let updatedAt: String?
}

public struct MessageView: Codable, Identifiable, Hashable {
    public let id: Int64
    public let conversationId: Int64
    public let senderId: Int64
    public let receiverId: Int64
    public let content: String
    public let read: Bool?
    public let createdAt: String?
    public let clientMessageId: String?

    public init(id: Int64, conversationId: Int64, senderId: Int64, receiverId: Int64,
                content: String, read: Bool?, createdAt: String?, clientMessageId: String? = nil) {
        self.id = id
        self.conversationId = conversationId
        self.senderId = senderId
        self.receiverId = receiverId
        self.content = content
        self.read = read
        self.createdAt = createdAt
        self.clientMessageId = clientMessageId
    }
}

public struct ConversationPage: Decodable {
    public let items: [ConversationView]
    public let nextCursor: String?
    public let hasMore: Bool
    public let limit: Int
}

public struct MessagePage: Decodable {
    public let items: [MessageView]
    public let nextCursor: String?
    public let hasMore: Bool
    public let limit: Int
}

public struct OnboardingInput {
    public var nickname: String
    public var gender: String?
    public var birthYear: Int?
    public var grade: String?
    public var zodiac: String?
    public var mbti: String?
    public var signature: String?
    public var showGender: Bool
    public var showAge: Bool
    public var showGrade: Bool
    public var avatarUrl: String?

    public init(nickname: String, gender: String?, birthYear: Int?, grade: String?,
                zodiac: String?, mbti: String?, signature: String?,
                showGender: Bool, showAge: Bool, showGrade: Bool, avatarUrl: String?) {
        self.nickname = nickname
        self.gender = gender
        self.birthYear = birthYear
        self.grade = grade
        self.zodiac = zodiac
        self.mbti = mbti
        self.signature = signature
        self.showGender = showGender
        self.showAge = showAge
        self.showGrade = showGrade
        self.avatarUrl = avatarUrl
    }
}

public struct SmsCodeResponse: Codable {
    public let sent: Bool
    public let expiresInSeconds: Int
}

public struct UploadResponse: Codable {
    public let id: Int64
    public let url: String
    public let objectKey: String?
    public let contentType: String?
    public let sizeBytes: Int?
}

public struct AchievementTaskView: Codable, Identifiable, Hashable {
    public let id: Int64
    public let title: String
    public let description: String?
    public let xp: Int
    public let completed: Bool
    public let completedAt: String?
    /// 期限提示，例如「退改选截止前」；具体日期以学校通知为准。
    public let dueHint: String?
}

/// 主线任务详情（G05）：步骤、期限、来源与复核日期、内容版本。
public struct AchievementTaskDetail: Codable, Identifiable, Hashable {
    public let id: Int64
    public let stageCode: String?
    public let stageTitle: String?
    public let title: String
    public let description: String?
    public let xp: Int
    public let steps: [String]
    public let dueHint: String?
    /** PLATFORM / SCHOOL / COMMUNITY */
    public let sourceType: String
    public let sourceName: String
    public let sourceUrl: String?
    public let reviewedAt: String?
    public let version: Int
    public let completed: Bool
    public let completedAt: String?
    public let completedVersion: Int?
    /// 完成之后任务内容又更新过；完成事实保留，只做提示。
    public let updatedSinceCompletion: Bool
    public let badge: AchievementBadgeView?
    /// 同校、已发布、未过期的关联攻略（G06）。
    public let guides: [PostView]?
    /// 关联了但已过有效期、因此不再展示的攻略数。
    public let expiredGuideCount: Int?

    public var sourceLabel: String {
        switch sourceType {
        case "SCHOOL": return "学校官方"
        case "COMMUNITY": return "同学经验"
        default: return "平台整理"
        }
    }
}

public struct AchievementStageView: Codable, Identifiable, Hashable {
    public let id: Int64
    public let code: String
    public let title: String
    public let subtitle: String?
    public let order: Int
    public let boss: Bool
    public let tasks: [AchievementTaskView]
    public let completedCount: Int
    public let totalCount: Int
}

public struct AchievementBadgeView: Codable, Identifiable, Hashable {
    public var id: String { code }
    public let code: String
    public let title: String
    public let description: String?
    public let rarity: String
    public let icon: String
    public let earned: Bool
    public let earnedAt: String?
}

/// 徽章稀有度（规划 §6.2）：普通、稀有、史诗、传说、限定。
public enum BadgeRarity: String, CaseIterable {
    case common = "COMMON", rare = "RARE", epic = "EPIC", legendary = "LEGENDARY", limited = "LIMITED"

    public init(code: String) { self = BadgeRarity(rawValue: code) ?? .common }

    public var label: String {
        switch self {
        case .common: return "普通"
        case .rare: return "稀有"
        case .epic: return "史诗"
        case .legendary: return "传说"
        case .limited: return "限定"
        }
    }

    /// 排序用：越稀有越靠后。
    public var rank: Int { Self.allCases.firstIndex(of: self) ?? 0 }
}

public extension AchievementBadgeView {
    var rarityLevel: BadgeRarity { BadgeRarity(code: rarity) }
}

public struct AchievementView: Codable {
    public let level: Int
    public let xp: Int
    public let completedTasks: Int
    public let totalTasks: Int
    public let currentStageCode: String?
    public let stages: [AchievementStageView]
    public let badges: [AchievementBadgeView]
    /** CALENDAR：按入学年份推算；PROGRESS：缺入学年份，按第一个未完成阶段。旧服务端为空。 */
    public let stageBasis: String?
    public let entryYear: Int?
    public let termLabel: String?
    public let nextTask: AchievementTaskView?
    /// 自己选的主页称号（F15）；没选或徽章已失效为空。
    public var title: AchievementBadgeView?

    public var currentStage: AchievementStageView? { stages.first { $0.code == currentStageCode } }
    public var needsBackground: Bool { stageBasis != "CALENDAR" }
}

public struct SchoolOption: Codable, Identifiable, Hashable {
    public let id: Int64
    public let name: String
    public let code: String?
    public let city: String?
}

public struct CollegeOption: Codable, Identifiable, Hashable {
    public let id: Int64
    public let schoolId: Int64
    public let name: String
    public let code: String?
}

public struct MajorOption: Codable, Identifiable, Hashable {
    public let id: Int64
    public let schoolId: Int64
    public let collegeId: Int64?
    public let name: String
    public let code: String?
}

public struct InteractionNoticeView: Codable, Identifiable, Hashable {
    public let id: Int64
    /** like / favorite / comment / reply */
    public let type: String
    public let actor: AuthorView
    public let post: PostView?
    public let commentContent: String?
    public let parentCommentId: Int64?
    public let createdAt: String?

    /** 不同类型来自不同表，数字 id 可能重复；列表用类型加 id 区分。 */
    public var listID: String { "\(type)-\(id)" }
}

public struct FollowUserView: Codable, Identifiable, Hashable {
    public let id: Int64
    public let publicUid: String
    public let nickname: String
    public let avatarUrl: String?
    public let level: Int?
    public let levelTitle: String?
    public let college: String?
    public let major: String?
    public let grade: String?
    public let following: Bool
    public let followedAt: String?
}

public struct ShareResponse: Codable {
    public let url: String
    public let shareCount: Int?
}

/** 举报分类的稳定 key 由后端校验；标题只用于展示。 */
/// 我提交过的一条举报和处理结果。result 是给举报人看的结果文字，不含处理人和理由。
public struct MyReport: Codable, Identifiable, Hashable {
    public let id: Int64
    public let targetType: String
    public let excerpt: String?
    public let category: String
    public let categoryLabel: String
    public let status: String
    public let result: String
    public let createdAt: String?
    public let handledAt: String?

    public var isOpen: Bool { status == "PENDING" || status == "IN_PROGRESS" }

    public var targetLabel: String {
        switch targetType {
        case "POST": return "PO"
        case "COMMENT": return "评论"
        default: return "用户"
        }
    }
}

public struct MyReportPage: Codable, Hashable {
    public let items: [MyReport]
    public let nextBeforeId: Int64?
}

/// 举报受理回执：只有编号和状态，不含处理细节。
public struct ReportReceipt: Codable, Hashable {
    public let id: Int64
    public let status: String
}

public enum ReportCategory: String, CaseIterable, Identifiable {
    case spam, fraud, harassment, porn, impersonation, other
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .spam: return "垃圾广告"
        case .fraud: return "诈骗或虚假信息"
        case .harassment: return "骚扰或人身攻击"
        case .porn: return "色情低俗"
        case .impersonation: return "冒充他人"
        case .other: return "其他问题"
        }
    }
}
