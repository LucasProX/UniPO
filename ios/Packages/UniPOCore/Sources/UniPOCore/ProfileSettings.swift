import Foundation

/// 「编辑资料」页的可编辑字段。保存时只提交和原资料不同的字段，
/// 没改的字段不发，避免把服务端更新过的值用旧值覆盖回去。
public struct ProfileEdit: Equatable {
    public static let zodiacs = ["白羊", "金牛", "双子", "巨蟹", "狮子", "处女", "天秤", "天蝎", "射手", "摩羯", "水瓶", "双鱼"]
    public static let mbtis = ["INTJ", "INTP", "ENTJ", "ENTP", "INFJ", "INFP", "ENFJ", "ENFP",
                               "ISTJ", "ISFJ", "ESTJ", "ESFJ", "ISTP", "ISFP", "ESTP", "ESFP"]
    public static let nicknameLimit = 40
    public static let bioLimit = 300

    public var nickname: String
    public var bio: String
    /// male / female / other；nil 表示还没填过（服务端不支持清空性别，只能隐藏）。
    public var gender: String?
    public var birthYear: Int?
    /// 空字符串表示不填。
    public var zodiac: String
    public var mbti: String
    public var showGender: Bool
    public var showAge: Bool
    public var showGrade: Bool

    public init(profile: UserProfile) {
        nickname = profile.nickname
        bio = profile.bio ?? ""
        gender = profile.gender.flatMap { $0.isEmpty ? nil : $0 }
        birthYear = profile.birthYear
        zodiac = profile.zodiac ?? ""
        mbti = profile.mbti ?? ""
        showGender = profile.showGender != false
        showAge = profile.showAge != false
        showGrade = profile.showGrade != false
    }

    public static func birthYears(now: Date = Date(), calendar: Calendar = .current) -> [Int] {
        let latest = calendar.component(.year, from: now) - 10
        return Array((1950...latest).reversed())
    }

    public var trimmedNickname: String { nickname.trimmingCharacters(in: .whitespacesAndNewlines) }
    public var trimmedBio: String { bio.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// 不能保存时给出原因；能保存返回 nil。
    public var validationMessage: String? {
        if trimmedNickname.isEmpty { return "昵称不能为空" }
        if trimmedNickname.count > Self.nicknameLimit { return "昵称最多 \(Self.nicknameLimit) 个字" }
        if trimmedBio.count > Self.bioLimit { return "简介最多 \(Self.bioLimit) 个字" }
        return nil
    }

    /// 与原资料相比改动过的字段，键名与 `PUT /users/me` 一致。
    public func changes(from original: ProfileEdit) -> [String: Any] {
        var body: [String: Any] = [:]
        if trimmedNickname != original.trimmedNickname { body["nickname"] = trimmedNickname }
        if trimmedBio != original.trimmedBio { body["bio"] = trimmedBio }
        if let gender, gender != original.gender { body["gender"] = gender }
        if let birthYear, birthYear != original.birthYear { body["birthYear"] = birthYear }
        if zodiac != original.zodiac { body["zodiac"] = zodiac }
        if mbti != original.mbti { body["mbti"] = mbti }
        if showGender != original.showGender { body["showGender"] = showGender }
        if showAge != original.showAge { body["showAge"] = showAge }
        if showGrade != original.showGrade { body["showGrade"] = showGrade }
        return body
    }
}

public struct BlockedUser: Codable, Identifiable, Hashable {
    public let publicUid: String
    public let nickname: String
    public let avatarUrl: String?
    public let blockedAt: String?
    public var id: String { publicUid }
}

public struct LegalDocument: Codable, Hashable {
    public let kind: String
    public let title: String
    public let version: String
    public let effectiveDate: String
    public let content: String
}

public enum FeedbackCategory: String, CaseIterable, Identifiable {
    case bug, suggestion, content, other
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .bug: return "问题"
        case .suggestion: return "建议"
        case .content: return "内容"
        case .other: return "其他"
        }
    }
}
