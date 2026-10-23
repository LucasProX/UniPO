import XCTest
@testable import UniPOCore

@MainActor
final class ActivityTests: XCTestCase {
    private func activity(_ id: Int64, status: String = "NONE") -> [String: Any] {
        ["id": id, "title": "讲座 \(id)", "category": "LECTURE", "description": "介绍", "location": "图书馆",
         "startsAt": "2026-11-02T14:00:00+08:00", "endsAt": "2026-11-02T16:00:00+08:00",
         "signupDeadline": "2026-11-01T12:00:00+08:00", "organizerName": "计算机学院",
         "audienceScope": "SCHOOL", "audienceLabel": "全校", "capacity": 2, "registeredCount": 2,
         "waitlistCount": 1, "waitlistEnabled": true, "rewardXp": 30, "rewardCoins": 20, "phase": "OPEN",
         "myStatus": status, "eligible": true, "recentAttendees": [], "checkedIn": false,
         "checkinOpen": false, "canManage": false, "requireApproval": false, "checkinRevoked": false]
    }

    private func page(_ ids: [Int64], cursor: String? = nil) throws -> ActivityPage {
        var value: [String: Any] = ["items": ids.map { activity($0) }, "hasMore": cursor != nil, "limit": 20]
        if let cursor { value["nextCursor"] = cursor }
        return try JSONDecoder().decode(ActivityPage.self, from: JSONSerialization.data(withJSONObject: value))
    }

    func testDecodesServerViewAndDerivesLabels() throws {
        let view = try JSONDecoder().decode(ActivityView.self,
                                            from: JSONSerialization.data(withJSONObject: activity(1, status: "WAITLISTED")))
        XCTAssertTrue(view.isFull)
        XCTAssertTrue(view.isWaitlisted)
        XCTAssertEqual(view.categoryLabel, "讲座")
        XCTAssertEqual(view.phaseLabel, "报名中")
        XCTAssertEqual(ActivityTime.range(view.startsAt, view.endsAt), "11月2日 周一 14:00–16:00")
    }

    func testCreateBodyUsesShanghaiOffsetsAndOmitsEmptyOptionals() {
        var input = CreateActivityInput(now: Date(timeIntervalSince1970: 1_793_000_000))
        input.title = " 讲座 "
        input.notice = "  "
        let body = input.body
        XCTAssertEqual(body["title"] as? String, "讲座")
        XCTAssertNil(body["notice"])
        XCTAssertNil(body["capacity"])
        XCTAssertTrue((body["startsAt"] as? String)?.hasSuffix("+08:00") == true)
    }

    func testListStoreDropsStaleTabResponsesAndDeduplicates() async throws {
        var pending: [(String, CheckedContinuation<ActivityPage, Error>)] = []
        let store = ActivityListStore { tab, _ in
            try await withCheckedThrowingContinuation { pending.append((tab, $0)) }
        }
        let first = Task { await store.refresh() }
        for _ in 0..<1000 where pending.count < 1 { await Task.yield() }
        let second = Task { await store.refresh(tab: "week") }
        for _ in 0..<1000 where pending.count < 2 { await Task.yield() }
        pending[1].1.resume(returning: try page([3, 3, 4], cursor: "next"))
        await second.value
        pending[0].1.resume(returning: try page([1]))
        await first.value
        XCTAssertEqual(store.tab, "week")
        XCTAssertEqual(store.items.map(\.id), [3, 4])
        XCTAssertTrue(store.hasMore)
    }

    func testCheckinLinkAcceptsOnlyWellFormedUniPOLinks() {
        let token = String(repeating: "a", count: 21) + "_"
        XCTAssertEqual(CheckinLink(string: "unipo://checkin?activity=12&token=\(token)")?.activityID, 12)
        XCTAssertNil(CheckinLink(string: "https://checkin?activity=12&token=\(token)"))
        XCTAssertNil(CheckinLink(string: "unipo://checkin?activity=0&token=\(token)"))
        XCTAssertNil(CheckinLink(string: "unipo://checkin?activity=12&token=short"))
        XCTAssertNil(CheckinLink(string: "unipo://checkin?activity=12&token=\(String(repeating: "a", count: 21))%2F"))
    }

    func testUpdateInputClearsCapacityExplicitly() throws {
        let view = try JSONDecoder().decode(ActivityView.self, from: JSONSerialization.data(withJSONObject: activity(1)))
        var input = UpdateActivityInput(activity: view)
        XCTAssertEqual(input.capacity, 2)
        input.capacity = nil
        XCTAssertEqual(input.body["clearCapacity"] as? Bool, true)
        XCTAssertNil(input.body["capacity"])
        XCTAssertTrue((input.body["startsAt"] as? String)?.hasPrefix("2026-11-02T14:00:00") == true)
    }
}
