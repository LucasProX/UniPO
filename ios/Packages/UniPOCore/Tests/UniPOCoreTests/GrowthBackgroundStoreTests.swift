import XCTest
@testable import UniPOCore

@MainActor
final class GrowthBackgroundStoreTests: XCTestCase {
    private func decode<T: Decodable>(_ value: Any) throws -> T {
        try JSONDecoder().decode(T.self, from: JSONSerialization.data(withJSONObject: value))
    }

    private func profile() throws -> UserProfile {
        try decode(["id": 7, "publicUid": "u7", "nickname": "新生", "role": "USER", "level": 1,
                    "schoolId": 1, "entryYear": 2026])
    }

    private func loaders(saved: @escaping (Int64?, Int64?, Int64?, Int) -> Void = { _, _, _, _ in }) throws
        -> GrowthBackgroundStore.Loaders {
        let schools: [SchoolOption] = try decode([["id": 1, "name": "试点大学"], ["id": 2, "name": "第二大学"]])
        let colleges: [Int64: [CollegeOption]] = [
            1: try decode([["id": 10, "schoolId": 1, "name": "计算机学院"]]),
            2: try decode([["id": 20, "schoolId": 2, "name": "设计学院"]])
        ]
        let majors: [Int64: [MajorOption]] = [
            10: try decode([["id": 100, "schoolId": 1, "collegeId": 10, "name": "软件工程"]]),
            20: try decode([["id": 200, "schoolId": 2, "collegeId": 20, "name": "视觉传达"]])
        ]
        let profile = try profile()
        return .init(
            schools: { schools },
            colleges: { colleges[$0] ?? [] },
            majors: { school, college in college.flatMap { majors[$0] } ?? (school == 1 ? majors[10]! : majors[20]!) },
            save: { school, college, major, year in saved(school, college, major, year); return profile })
    }

    func testDefaultsToCurrentAcademicYearAndFirstSchool() async throws {
        let october = DateComponents(calendar: Calendar(identifier: .gregorian), year: 2026, month: 10, day: 23).date!
        let store = GrowthBackgroundStore(today: october, loaders: try loaders())
        XCTAssertEqual(store.entryYear, 2026)
        XCTAssertEqual(store.entryYears.first, 2027)
        await store.load()
        XCTAssertEqual(store.schoolID, 1)
        XCTAssertEqual(store.colleges.map(\.id), [10])
        XCTAssertEqual(store.majors.map(\.id), [100])

        let march = DateComponents(calendar: Calendar(identifier: .gregorian), year: 2027, month: 3, day: 1).date!
        XCTAssertEqual(GrowthBackgroundStore(today: march, loaders: try loaders()).entryYear, 2026)
    }

    func testChangingSchoolClearsCollegeAndMajor() async throws {
        let store = GrowthBackgroundStore(loaders: try loaders())
        await store.load()
        await store.selectCollege(10)
        store.selectMajor(100)
        await store.selectSchool(2)
        XCTAssertNil(store.collegeID)
        XCTAssertNil(store.majorID)
        XCTAssertEqual(store.colleges.map(\.id), [20])
    }

    func testPickingMajorFillsItsCollegeAndSaveSendsSelection() async throws {
        var sent: (Int64?, Int64?, Int64?, Int)?
        let store = GrowthBackgroundStore(profile: try profile(), loaders: try loaders { sent = ($0, $1, $2, $3) })
        await store.load()
        store.selectMajor(100)
        XCTAssertEqual(store.collegeID, 10)
        store.entryYear = 2025
        let saved = await store.save()
        XCTAssertNotNil(saved)
        XCTAssertEqual(sent?.0, 1)
        XCTAssertEqual(sent?.1, 10)
        XCTAssertEqual(sent?.2, 100)
        XCTAssertEqual(sent?.3, 2025)
    }
}
