import XCTest
@testable import UniPOCore

@MainActor
final class GrowthPlanTests: XCTestCase {
    private func participation(_ id: Int64, status: String = "PUBLISHED", registration: String = "REGISTERED",
                               checkedIn: Bool = false, revoked: Bool = false) -> [String: Any] {
        ["activityId": id, "title": "活动 \(id)", "category": "LECTURE", "location": "图书馆", "organizerName": "学院",
         "startsAt": "2026-10-23T14:00:00+08:00", "endsAt": "2099-10-23T16:00:00+08:00",
         "activityStatus": status, "registrationStatus": registration, "checkedIn": checkedIn,
         "checkinRevoked": revoked, "xpGranted": checkedIn ? 30 : 0, "coinsGranted": checkedIn ? 25 : 0]
    }

    private func plan(_ start: String, label: String, done: Int = 1) throws -> TermPlan {
        let value: [String: Any] = [
            "termStart": start, "termEnd": "2027-01-31", "termLabel": label, "termIndex": 2, "current": true,
            "previousTermStart": "2026-02-01", "canAddTasks": true,
            "summary": ["done": done, "total": 4, "attended": 1, "activityXp": 30, "activityCoins": 25],
            "activities": [participation(1, checkedIn: true), participation(2, status: "CANCELLED")],
            "officialStage": ["id": 3, "code": "sophomore", "title": "大二", "order": 2, "boss": false,
                              "tasks": [["id": 9, "title": "参加社团", "xp": 20, "completed": false]],
                              "completedCount": 0, "totalCount": 1],
            "personalTasks": [["id": 5, "termStart": start, "title": "背单词", "dueAt": "2020-01-01T08:00:00+08:00",
                               "completed": false, "createdAt": "2026-10-23T12:00:00+08:00"]]
        ]
        return try JSONDecoder().decode(TermPlan.self, from: JSONSerialization.data(withJSONObject: value))
    }

    func testDecodesServerPlanAndDerivesItemState() throws {
        let value = try plan("2026-09-01", label: "大二上学期")
        XCTAssertNil(value.nextTermStart)
        XCTAssertEqual(value.summary.fraction, 0.25)
        XCTAssertEqual(value.activities.map(\.statusLabel), ["已签到", "活动已取消"])
        XCTAssertEqual(value.activities.map(\.countsTowardPlan), [true, false])
        XCTAssertTrue(value.personalTasks[0].isOverdue)
        XCTAssertEqual(value.officialStage?.tasks.first?.xp, 20)
    }

    func testParticipationLabelsCoverEveryRegistrationState() throws {
        func decode(_ raw: [String: Any]) throws -> GrowthParticipation {
            try JSONDecoder().decode(GrowthParticipation.self, from: JSONSerialization.data(withJSONObject: raw))
        }
        XCTAssertEqual(try decode(participation(1, registration: "WAITLISTED")).statusLabel, "候补中")
        XCTAssertEqual(try decode(participation(1, registration: "PENDING")).statusLabel, "待审核")
        let rejected = try decode(participation(1, registration: "REJECTED"))
        XCTAssertEqual(rejected.statusLabel, "未通过")
        XCTAssertFalse(rejected.countsTowardPlan)
        let revoked = try decode(participation(1, revoked: true))
        XCTAssertEqual(revoked.statusLabel, "签到已撤销")
        XCTAssertFalse(revoked.countsTowardPlan)
        XCTAssertEqual(try decode(participation(1)).statusLabel, "已报名")
    }

    func testSwitchingTermDiscardsTheOlderResponse() async throws {
        var pending: [(String?, CheckedContinuation<TermPlan, Error>)] = []
        let store = TermPlanStore { start in
            try await withCheckedThrowingContinuation { pending.append((start, $0)) }
        }
        let first = Task { await store.load() }
        for _ in 0..<1000 where pending.count < 1 { await Task.yield() }
        let second = Task { await store.load(termStart: "2027-02-01") }
        for _ in 0..<1000 where pending.count < 2 { await Task.yield() }
        XCTAssertEqual(pending.map(\.0), [nil, "2027-02-01"])
        pending[1].1.resume(returning: try plan("2027-02-01", label: "大二下学期"))
        await second.value
        pending[0].1.resume(returning: try plan("2026-09-01", label: "大二上学期"))
        await first.value
        XCTAssertEqual(store.termStart, "2027-02-01")
        XCTAssertEqual(store.plan?.termLabel, "大二下学期")
        XCTAssertFalse(store.isLoading)
    }

    func testFailedMutationKeepsThePlanAndSuccessfulOneReloads() async throws {
        var calls = 0
        let store = TermPlanStore { _ in
            calls += 1
            return try self.plan("2026-09-01", label: "大二上学期", done: calls)
        }
        await store.load()
        XCTAssertEqual(store.plan?.summary.done, 1)

        let failed = await store.perform { throw APIError.server(code: "CONFLICT", message: "每个学期最多 50 个自定义任务", status: 409) }
        XCTAssertFalse(failed)
        XCTAssertEqual(store.errorMessage, "每个学期最多 50 个自定义任务")
        XCTAssertEqual(store.plan?.summary.done, 1)

        let succeeded = await store.perform {}
        XCTAssertTrue(succeeded)
        XCTAssertNil(store.errorMessage)
        XCTAssertEqual(store.plan?.summary.done, 2)
        XCTAssertFalse(store.isMutating)
    }

    func testResetDropsInFlightResults() async throws {
        var pending: CheckedContinuation<TermPlan, Error>?
        let store = TermPlanStore { _ in try await withCheckedThrowingContinuation { pending = $0 } }
        let loading = Task { await store.load() }
        for _ in 0..<1000 where pending == nil { await Task.yield() }
        store.reset()
        pending?.resume(returning: try plan("2026-09-01", label: "大二上学期"))
        await loading.value
        XCTAssertNil(store.plan)
        XCTAssertFalse(store.isLoading)
    }

    func testTaskInputTrimsAndOmitsEmptyFields() {
        var input = PersonalTaskInput(title: "  背单词  ", note: "   ")
        XCTAssertTrue(input.isValid)
        XCTAssertEqual(input.body["title"] as? String, "背单词")
        XCTAssertNil(input.body["note"])
        XCTAssertNil(input.body["dueAt"])
        input.title = String(repeating: "一", count: 41)
        XCTAssertFalse(input.isValid)
        input.title = "   "
        XCTAssertFalse(input.isValid)
    }
}
