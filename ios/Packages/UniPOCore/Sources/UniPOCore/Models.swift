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

    public init(id: Int64, publicUid: String, nickname: String, avatarUrl: String?, role: String,
                level: Int, levelTitle: String?, xp: Int?, bio: String?, college: String?,
                major: String?, grade: String?, online: Bool?,
                gender: String? = nil, birthYear: Int? = nil, zodiac: String? = nil, mbti: String? = nil,
                showGender: Bool? = nil, showAge: Bool? = nil, showGrade: Bool? = nil,
                onboardingCompleted: Bool? = nil) {
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
}
