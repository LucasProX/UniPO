import XCTest
@testable import UniPOCore

final class MediaURLTests: XCTestCase {
    private let media = URL(string: "http://49.234.21.63:18081/api/media/42/content?v=abc.jpg")!

    func testPicksTheSmallestSizeThatCoversTheCell() {
        XCTAssertEqual(MediaURL.sized(media, pixels: 156).query, "v=abc.jpg&size=360")
        XCTAssertEqual(MediaURL.sized(media, pixels: 540).query, "v=abc.jpg&size=720")
        XCTAssertEqual(MediaURL.sized(media, pixels: 1080).query, "v=abc.jpg&size=1080")
        XCTAssertEqual(MediaURL.sized(media, pixels: 1180).query, "v=abc.jpg&size=1080")
    }

    func testLargerThanTheBiggestSizeUsesTheOriginal() {
        XCTAssertEqual(MediaURL.sized(media, pixels: 1320), media)
        XCTAssertEqual(MediaURL.sized(media, pixels: 0), media)
    }

    func testOnlyRewritesOwnMediaAndReplacesAnExistingSize() {
        let direct = URL(string: "http://minio.local/bucket/post/1/a.jpg")!
        XCTAssertEqual(MediaURL.sized(direct, pixels: 300), direct)
        let other = URL(string: "https://example.com/api/media/abc/content")!
        XCTAssertEqual(MediaURL.sized(other, pixels: 300), other)
        let v1 = URL(string: "http://h/api/v1/media/7/content?size=1080&v=x")!
        XCTAssertEqual(MediaURL.sized(v1, pixels: 100).query, "v=x&size=360")
    }
}
