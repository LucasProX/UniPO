import XCTest
@testable import UniPOCore

final class MessageTimelineTests: XCTestCase {
    private let now = ActivityTime.parse("2026-10-28T15:00:00+08:00")!

    func testSeparatesOnlyAfterAFiveMinuteGap() {
        XCTAssertTrue(MessageTimeline.showsTime(previous: nil, current: "2026-10-28T14:00:00+08:00"))
        XCTAssertFalse(MessageTimeline.showsTime(previous: "2026-10-28T14:00:00+08:00", current: "2026-10-28T14:04:59+08:00"))
        XCTAssertTrue(MessageTimeline.showsTime(previous: "2026-10-28T14:00:00+08:00", current: "2026-10-28T14:05:00+08:00"))
        XCTAssertFalse(MessageTimeline.showsTime(previous: nil, current: nil))
    }

    func testLabelsGetLongerTheFurtherBack() {
        XCTAssertEqual(MessageTimeline.label("2026-10-28T09:05:00+08:00", now: now), "09:05")
        XCTAssertEqual(MessageTimeline.label("2026-10-27T23:30:00+08:00", now: now), "昨天 23:30")
        XCTAssertEqual(MessageTimeline.label("2026-10-21T14:05:00+08:00", now: now), "10月21日 14:05")
        XCTAssertEqual(MessageTimeline.label("2025-10-21T14:05:00+08:00", now: now), "2025年10月21日 14:05")
        XCTAssertEqual(MessageTimeline.label("not a date", now: now), "")
    }
}
