import XCTest
@testable import UniPOCore

final class ModerationTests: XCTestCase {
    private func kase(type: String, targetStatus: String?, categories: [String]) throws -> ReportCase {
        let json: [String: Any] = [
            "id": 1, "targetType": type, "targetId": 2, "targetStatus": targetStatus as Any, "status": "PENDING",
            "reportCount": categories.count, "version": 0,
            "categories": categories.map { ["category": $0, "label": $0, "count": 1] },
        ]
        let data = try JSONSerialization.data(withJSONObject: json.compactMapValues { $0 is NSNull ? nil : $0 })
        return try JSONDecoder().decode(ReportCase.self, from: data)
    }

    func testActionsDependOnTarget() throws {
        let pending = try kase(type: "POST", targetStatus: "pending", categories: ["filter:contact_lure"])
        XCTAssertEqual(CaseAction.available(for: pending), [.approveContent, .removeContent, .noViolation, .handled])
        XCTAssertTrue(pending.isSystemOnly)
        XCTAssertEqual(pending.targetLabel, "待审核 PO")

        let user = try kase(type: "USER", targetStatus: "active", categories: ["spam"])
        XCTAssertEqual(CaseAction.available(for: user), [.noViolation, .handled])
        XCTAssertFalse(user.isSystemOnly)

        let comment = try kase(type: "COMMENT", targetStatus: "visible", categories: ["harassment"])
        XCTAssertEqual(CaseAction.removeContent.title(for: comment), "隐藏评论")
        XCTAssertEqual(comment.targetStatusLabel, "正常显示")
    }

    func testCapabilities() throws {
        let data = Data(#"{"platformRole":"USER","grants":[],"capabilities":[{"capability":"report.handle","scopes":["SCHOOL:1"]}]}"#.utf8)
        let caps = try JSONDecoder().decode(Capabilities.self, from: data)
        XCTAssertTrue(caps.canHandleReports)
        XCTAssertFalse(caps.has("activity.publish"))
    }
}
