import Foundation
import Observation

/// 成长背景表单：学校 → 学院 → 专业逐级加载，入学年份决定服务端推算的当前阶段。
/// 选择上一级会清空下一级，迟到的旧级联响应不会覆盖新选择。
@Observable
@MainActor
public final class GrowthBackgroundStore {
    public struct Loaders {
        public var schools: () async throws -> [SchoolOption]
        public var colleges: (Int64) async throws -> [CollegeOption]
        public var majors: (Int64, Int64?) async throws -> [MajorOption]
        public var save: (Int64?, Int64?, Int64?, Int) async throws -> UserProfile

        public init(schools: @escaping () async throws -> [SchoolOption],
                    colleges: @escaping (Int64) async throws -> [CollegeOption],
                    majors: @escaping (Int64, Int64?) async throws -> [MajorOption],
                    save: @escaping (Int64?, Int64?, Int64?, Int) async throws -> UserProfile) {
            self.schools = schools
            self.colleges = colleges
            self.majors = majors
            self.save = save
        }

        public static let live = Loaders(
            schools: { try await APIClient.shared.schoolOptions() },
            colleges: { try await APIClient.shared.collegeOptions(schoolId: $0) },
            majors: { try await APIClient.shared.majorOptions(schoolId: $0, collegeId: $1) },
            save: { try await APIClient.shared.updateGrowthBackground(schoolId: $0, collegeId: $1, majorId: $2, entryYear: $3) }
        )
    }

    public private(set) var schools: [SchoolOption] = []
    public private(set) var colleges: [CollegeOption] = []
    public private(set) var majors: [MajorOption] = []
    public private(set) var schoolID: Int64?
    public private(set) var collegeID: Int64?
    public private(set) var majorID: Int64?
    public var entryYear: Int
    public private(set) var isLoading = false
    public private(set) var isSaving = false
    public private(set) var errorMessage: String?

    public let entryYears: [Int]
    private let loaders: Loaders
    private var cascade = 0

    public init(profile: UserProfile? = nil, today: Date = Date(), loaders: Loaders = .live) {
        let calendar = Calendar(identifier: .gregorian)
        let year = calendar.component(.year, from: today)
        let month = calendar.component(.month, from: today)
        let academicYear = month >= 9 ? year : year - 1
        entryYears = Array((year - 8)...(year + 1)).reversed()
        entryYear = profile?.entryYear ?? academicYear
        schoolID = profile?.schoolId
        collegeID = profile?.collegeId
        majorID = profile?.majorId
        self.loaders = loaders
    }

    public var schoolName: String? { schools.first { $0.id == schoolID }?.name }
    public var collegeName: String? { colleges.first { $0.id == collegeID }?.name }
    public var majorName: String? { majors.first { $0.id == majorID }?.name }
    public var canSave: Bool { schoolID != nil && !isSaving }

    public func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            schools = try await loaders.schools()
            if schoolID == nil || !schools.contains(where: { $0.id == schoolID }) {
                schoolID = schools.first?.id
                collegeID = nil
                majorID = nil
            }
            await reloadChildren()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "学校列表加载失败，请重试"
        }
    }

    public func selectSchool(_ id: Int64) async {
        guard id != schoolID else { return }
        schoolID = id
        collegeID = nil
        majorID = nil
        colleges = []
        majors = []
        await reloadChildren()
    }

    public func selectCollege(_ id: Int64?) async {
        guard id != collegeID else { return }
        collegeID = id
        majorID = nil
        majors = []
        await reloadMajors()
    }

    public func selectMajor(_ id: Int64?) {
        majorID = id
        if let id, let major = majors.first(where: { $0.id == id }), collegeID == nil {
            collegeID = major.collegeId
        }
    }

    /// 保存成功返回服务端资料；失败保留已选内容并给出可重试提示。
    public func save() async -> UserProfile? {
        guard canSave else { return nil }
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }
        do {
            return try await loaders.save(schoolID, collegeID, majorID, entryYear)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "保存失败，请重试"
            return nil
        }
    }

    private func reloadChildren() async {
        guard let schoolID else { return }
        cascade += 1
        let request = cascade
        do {
            let loaded = try await loaders.colleges(schoolID)
            guard request == cascade, schoolID == self.schoolID else { return }
            colleges = loaded
            if let collegeID, !loaded.contains(where: { $0.id == collegeID }) { self.collegeID = nil }
        } catch {
            guard request == cascade else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "学院列表加载失败，请重试"
            return
        }
        await reloadMajors()
    }

    private func reloadMajors() async {
        guard let schoolID else { return }
        cascade += 1
        let request = cascade
        let collegeID = self.collegeID
        do {
            let loaded = try await loaders.majors(schoolID, collegeID)
            guard request == cascade, schoolID == self.schoolID, collegeID == self.collegeID else { return }
            majors = loaded
            if let majorID, !loaded.contains(where: { $0.id == majorID }) { self.majorID = nil }
        } catch {
            guard request == cascade else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "专业列表加载失败，请重试"
        }
    }
}
