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

    public var likeTotal: Int { likeCount ?? 0 }
    public var commentTotal: Int { commentCount ?? 0 }
    public var favoriteTotal: Int { favoriteCount ?? 0 }
    public var tagList: [String] { tags ?? [] }
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
