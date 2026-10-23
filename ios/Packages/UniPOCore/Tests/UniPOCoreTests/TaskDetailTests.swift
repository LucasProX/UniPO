import XCTest
@testable import UniPOCore

final class TaskDetailTests: XCTestCase {
    func testDecodesServerDetailAndLabelsSource() throws {
        let raw: [String: Any] = [
            "id": 3, "stageCode": "freshman-1", "stageTitle": "大一上", "title": "确认本学期选课", "xp": 20,
            "steps": ["登录教务系统", "截图保存课表"], "dueHint": "退改选截止前", "sourceType": "PLATFORM",
            "sourceName": "UniPO 编辑部整理", "reviewedAt": "2026-10-23", "version": 2, "completed": true,
            "completedAt": "2026-10-23T17:20:00+08:00", "completedVersion": 1, "updatedSinceCompletion": true
        ]
        let detail = try JSONDecoder().decode(AchievementTaskDetail.self, from: JSONSerialization.data(withJSONObject: raw))
        XCTAssertEqual(detail.steps.count, 2)
        XCTAssertEqual(detail.sourceLabel, "平台整理")
        XCTAssertTrue(detail.updatedSinceCompletion)
        XCTAssertNil(detail.badge)
    }

    func testTaskListStaysDecodableWithoutDueHint() throws {
        let raw: [String: Any] = ["id": 1, "title": "完成报到", "xp": 20, "completed": false]
        let task = try JSONDecoder().decode(AchievementTaskView.self, from: JSONSerialization.data(withJSONObject: raw))
        XCTAssertNil(task.dueHint)
    }
}
