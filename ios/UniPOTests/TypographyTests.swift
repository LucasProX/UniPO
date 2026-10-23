import SwiftUI
import XCTest
@testable import UniPOUI

final class TypographyTests: XCTestCase {
    func testDesignSizesFollowTheClosestSystemTextStyle() {
        XCTAssertEqual(UniType.textStyle(for: 10), .caption2)
        XCTAssertEqual(UniType.textStyle(for: 11), .caption2)
        XCTAssertEqual(UniType.textStyle(for: 12), .caption)
        XCTAssertEqual(UniType.textStyle(for: 13), .footnote)
        XCTAssertEqual(UniType.textStyle(for: 15), .subheadline)
        XCTAssertEqual(UniType.textStyle(for: 16), .callout)
        XCTAssertEqual(UniType.textStyle(for: 17), .body)
        XCTAssertEqual(UniType.textStyle(for: 20), .title3)
        XCTAssertEqual(UniType.textStyle(for: 22), .title2)
        XCTAssertEqual(UniType.textStyle(for: 28), .title)
        XCTAssertEqual(UniType.textStyle(for: 64), .largeTitle)
    }

    func testScalingIsCappedSoFixedLayoutsDoNotBreak() {
        XCTAssertEqual(UniType.clamped(13, base: 13), 13)
        XCTAssertEqual(UniType.clamped(40, base: 13), 26, "正文最多 2 倍")
        XCTAssertEqual(UniType.clamped(60, base: 22), 22 * 1.6, accuracy: 0.001)
        XCTAssertEqual(UniType.clamped(100, base: 30), 30 * 1.35, accuracy: 0.001, "大标题最多 1.35 倍")
        XCTAssertEqual(UniType.clamped(9, base: 13), 13 * 0.85, accuracy: 0.001, "最小字号也有下限")
    }
}
