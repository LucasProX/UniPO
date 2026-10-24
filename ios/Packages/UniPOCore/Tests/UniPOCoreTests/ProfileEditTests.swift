import XCTest
@testable import UniPOCore

final class ProfileEditTests: XCTestCase {
    private func profile() -> UserProfile {
        UserProfile(id: 1, publicUid: "u1", nickname: "同学", avatarUrl: nil, role: "USER", level: 1, levelTitle: nil,
                    xp: nil, bio: "原简介", college: nil, major: nil, grade: "大二", online: nil,
                    gender: "female", birthYear: 2006, zodiac: "白羊", mbti: nil,
                    showGender: false, showAge: nil, showGrade: true)
    }

    func testUnchangedEditSendsNothing() {
        let original = ProfileEdit(profile: profile())
        var edit = original
        edit.nickname = " 同学 "
        edit.bio = "原简介\n"
        XCTAssertTrue(edit.changes(from: original).isEmpty)
        XCTAssertFalse(original.showGender)
        XCTAssertTrue(original.showAge, "未设置的展示开关按默认展示处理")
    }

    func testOnlyChangedFieldsAreSent() {
        let original = ProfileEdit(profile: profile())
        var edit = original
        edit.nickname = "新名字"
        edit.zodiac = ""
        edit.mbti = "ENFP"
        edit.showGender = true
        let body = edit.changes(from: original)
        XCTAssertEqual(Set(body.keys), ["nickname", "zodiac", "mbti", "showGender"])
        XCTAssertEqual(body["zodiac"] as? String, "")
        XCTAssertEqual(body["showGender"] as? Bool, true)
    }

    func testValidation() {
        var edit = ProfileEdit(profile: profile())
        edit.nickname = "  "
        XCTAssertEqual(edit.validationMessage, "昵称不能为空")
        edit.nickname = String(repeating: "名", count: 41)
        XCTAssertNotNil(edit.validationMessage)
        edit.nickname = "名字"
        edit.bio = String(repeating: "字", count: 301)
        XCTAssertEqual(edit.validationMessage, "简介最多 300 个字")
        edit.bio = ""
        XCTAssertNil(edit.validationMessage)
    }

    func testBirthYearsStopTenYearsBeforeNow() {
        let now = Calendar(identifier: .gregorian).date(from: DateComponents(year: 2026, month: 10, day: 24))!
        let years = ProfileEdit.birthYears(now: now, calendar: Calendar(identifier: .gregorian))
        XCTAssertEqual(years.first, 2016)
        XCTAssertEqual(years.last, 1950)
    }
}
