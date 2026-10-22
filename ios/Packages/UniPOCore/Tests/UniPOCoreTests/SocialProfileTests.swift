import XCTest
@testable import UniPOCore

final class SocialProfileTests: XCTestCase {
    func testFriendsRequireBothDirections() {
        XCTAssertEqual(FollowRelationship(following: false, followedBy: false), .notFollowing)
        XCTAssertEqual(FollowRelationship(following: false, followedBy: true), .notFollowing)
        XCTAssertEqual(FollowRelationship(following: true, followedBy: false), .following)
        let friends = FollowRelationship(following: true, followedBy: true)
        XCTAssertEqual(friends, .friends)
        XCTAssertEqual(friends.title, "朋友")
        XCTAssertEqual(friends.symbolName, "arrow.left.arrow.right")
    }

    func testLegacyProfileDoesNotInventMutualFollowingOrBackground() throws {
        let result = try decode(followedBy: nil, background: nil)
        XCTAssertEqual(result.relationship, .following)
        XCTAssertNil(result.followedBy)
        XCTAssertNil(result.profile.backgroundUrl)
    }

    func testMutualFollowingAndBackgroundAreDecodedFromServer() throws {
        let background = "/api/media/42/content?v=cover.jpg"
        let result = try decode(followedBy: true, background: background)
        XCTAssertEqual(result.relationship, .friends)
        XCTAssertEqual(result.profile.backgroundUrl, background)
        XCTAssertEqual(result.profile.status, "active")
    }

    func testBlockDirectionsAreDecodedIndependently() throws {
        let myBlock = try decode(followedBy: nil, background: nil, blocked: true, blockedBy: false)
        XCTAssertTrue(myBlock.blocked)
        XCTAssertFalse(myBlock.blockedBy)

        let theirBlock = try decode(followedBy: nil, background: nil, blocked: false, blockedBy: true)
        XCTAssertFalse(theirBlock.blocked)
        XCTAssertTrue(theirBlock.blockedBy)
    }

    func testBlockMutationAppliesReturnedDirectionsAndKeepsRestrictionsUntilBothSidesUnblock() throws {
        let profile = try decode(followedBy: nil, background: nil)
        let blocked = profile.applying(blockStatus: BlockStatusView(blocked: true, blockedBy: false))
        XCTAssertTrue(blocked.isBlockedEitherWay)
        XCTAssertTrue(blocked.canManageBlock)

        let stillBlocked = blocked.applying(blockStatus: BlockStatusView(blocked: false, blockedBy: true))
        XCTAssertTrue(stillBlocked.isBlockedEitherWay)
        XCTAssertFalse(stillBlocked.canManageBlock)

        let unblocked = stillBlocked.applying(blockStatus: BlockStatusView(blocked: false, blockedBy: false))
        XCTAssertFalse(unblocked.isBlockedEitherWay)
        XCTAssertTrue(unblocked.canManageBlock)
    }

    func testBlockedByOnlyProfileHasNoBlockManagementAction() throws {
        let profile = try decode(followedBy: nil, background: nil, blocked: false, blockedBy: true)
        XCTAssertTrue(profile.isBlockedEitherWay)
        XCTAssertFalse(profile.canManageBlock)
    }

    private func decode(followedBy: Bool?, background: String?, blocked: Bool = false, blockedBy: Bool = false) throws -> PublicUserProfile {
        var profile: [String: Any] = [
            "id": 7, "publicUid": "profile-fixture", "nickname": "同学", "role": "USER", "level": 1, "status": "active"
        ]
        if let background { profile["backgroundUrl"] = background }
        let keys = ["favorites", "likedPosts", "sharedPosts", "completed", "comments", "upcoming", "avoidedRisks", "following", "followers", "posts"]
        var body: [String: Any] = [
            "profile": profile, "stats": Dictionary(uniqueKeysWithValues: keys.map { ($0, 0) }),
            "following": true, "mine": false, "blocked": blocked, "blockedBy": blockedBy
        ]
        if let followedBy { body["followedBy"] = followedBy }
        return try JSONDecoder().decode(PublicUserProfile.self, from: JSONSerialization.data(withJSONObject: body))
    }
}
