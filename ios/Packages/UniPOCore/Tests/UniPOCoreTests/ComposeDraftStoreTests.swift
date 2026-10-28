import XCTest
@testable import UniPOCore

final class ComposeDraftStoreTests: XCTestCase {
    private var defaults: UserDefaults!
    private let suite = "ComposeDraftStoreTests"

    override func setUp() {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
    }

    private func draft(title: String = "四级经验", link: PostLink? = nil) -> ComposeDraft {
        ComposeDraft(board: "college", title: title, content: "先报名再缴费", tags: ["四级"],
                     images: ["/api/media/1/content?v=a.jpg"], link: link)
    }

    func testRestoresOnlyForTheSameAccountAndEnvironment() {
        let store = ComposeDraftStore(defaults: defaults, environment: "http://test/api/v1")
        store.save(draft(link: .task(PostLinkRef(id: 9, title: "四级报名"))), owner: 7)
        let restored = store.load(owner: 7)
        XCTAssertEqual(restored?.title, "四级经验")
        XCTAssertEqual(restored?.link, .task(PostLinkRef(id: 9, title: "四级报名")))
        XCTAssertNil(ComposeDraftStore(defaults: defaults, environment: "https://prod/api/v1").load(owner: 7))
        XCTAssertNil(store.load(owner: 7), "另一个环境读过之后草稿已丢弃")
    }

    func testAnotherAccountNeverSeesTheDraft() {
        let store = ComposeDraftStore(defaults: defaults, environment: "e")
        store.save(draft(), owner: 7)
        XCTAssertNil(store.load(owner: 8))
        XCTAssertNil(store.load(owner: 7), "换号读取时就清掉了")
    }

    func testEmptyDraftClearsAndClearRemoves() {
        let store = ComposeDraftStore(defaults: defaults, environment: "e")
        store.save(draft(), owner: 7)
        store.save(ComposeDraft(board: "school", title: "  ", content: "", tags: [], images: [], link: nil), owner: 7)
        XCTAssertNil(store.load(owner: 7))
        store.save(draft(), owner: 7)
        store.clear()
        XCTAssertNil(store.load(owner: 7))
    }

    func testRarityFallsBackToCommon() {
        XCTAssertEqual(BadgeRarity(code: "EPIC").label, "史诗")
        XCTAssertEqual(BadgeRarity(code: "???"), .common)
        XCTAssertLessThan(BadgeRarity.rare.rank, BadgeRarity.legendary.rank)
    }
}
