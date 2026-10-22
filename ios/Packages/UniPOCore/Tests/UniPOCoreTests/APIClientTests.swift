import Foundation
import XCTest
@testable import UniPOCore

private final class StubProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, data) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }
    override func stopLoading() {}
}

private actor RejectionRecorder {
    var revisions: [UInt64] = []
    func record(_ revision: UInt64) { revisions.append(revision) }
}

private final class Delayed401Protocol: URLProtocol {
    static var onRequest: ((Delayed401Protocol) -> Void)?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { Self.onRequest?(self) }
    override func stopLoading() {}
    func reject() {
        let response = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(#"{"code":"AUTH_UNAUTHENTICATED","message":"","data":null}"#.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

final class APIClientTests: XCTestCase {
    private func client() -> APIClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return APIClient(session: URLSession(configuration: config))
    }

    private func unauthorizedBody() -> Data {
        Data(#"{"code":"AUTH_UNAUTHENTICATED","message":"请先登录","data":null}"#.utf8)
    }

    private func successBody(_ data: Any) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["code": "OK", "message": "", "data": data])
    }

    private func profileBody() throws -> Data {
        try successBody(["id": 1, "publicUid": "fixture-user", "nickname": "同学", "role": "USER", "level": 1])
    }

    private func requestBody(_ request: URLRequest) throws -> Data {
        if let body = request.httpBody { return body }
        // URLSession may replace httpBody with a stream before a URLProtocol sees it.
        let stream = try XCTUnwrap(request.httpBodyStream)
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: 1024)
            if count < 0 { throw stream.streamError ?? URLError(.cannotDecodeContentData) }
            if count == 0 { break }
            data.append(contentsOf: buffer.prefix(count))
        }
        return data
    }

    private func assertAuthorization(_ api: APIClient, equals expected: String?) async {
        let errorBody = unauthorizedBody()
        StubProtocol.handler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), expected)
            return (401, errorBody)
        }
        _ = try? await api.me()
    }

    override func tearDown() {
        StubProtocol.handler = nil
        Delayed401Protocol.onRequest = nil
        super.tearDown()
    }

    func testProtectedRequestAndUploadReportOnlyTheirAuthenticationRevision() async {
        let api = client()
        let recorder = RejectionRecorder()
        api.setUnauthorizedHandler { await recorder.record($0) }
        await api.setAccessToken("fixture-access", revision: 7)
        let error = unauthorizedBody()
        StubProtocol.handler = { _ in (401, error) }
        _ = try? await api.conversations()
        _ = try? await api.uploadImage(data: Data([0xFF, 0xD8, 0xFF]))
        let revisions = await recorder.revisions
        XCTAssertEqual(revisions, [7, 7])
    }

    func testCandidatesLoginRefreshLogoutAndGuest401NeverReportCurrentAuthenticationFailure() async {
        let api = client()
        let recorder = RejectionRecorder()
        api.setUnauthorizedHandler { await recorder.record($0) }
        await api.setAccessToken("fixture-current", revision: 4)
        let error = unauthorizedBody()
        StubProtocol.handler = { _ in (401, error) }
        _ = try? await api.me(accessToken: "fixture-candidate")
        _ = try? await api.login(email: "fixture", password: "fixture")
        _ = try? await api.refresh(refreshToken: "fixture")
        _ = try? await api.requestSmsCode(phone: "fixture")
        _ = try? await api.smsLogin(phone: "fixture", code: "fixture")
        await api.logout(accessToken: "fixture-old", refreshToken: "fixture")
        api.advanceAuthenticationRevision(5, clearToken: true)
        _ = try? await api.me()
        _ = try? await api.uploadImage(data: Data([0xFF, 0xD8, 0xFF]))
        let revisions = await recorder.revisions
        XCTAssertTrue(revisions.isEmpty)
    }

    func testLate401FromPreviousAuthenticationRevisionDoesNotNotifyTheNewAccount() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Delayed401Protocol.self]
        let api = APIClient(session: URLSession(configuration: config))
        let recorder = RejectionRecorder()
        api.setUnauthorizedHandler { await recorder.record($0) }
        await api.setAccessToken("fixture-a", revision: 1)
        let arrived = expectation(description: "Old authenticated request arrived")
        var pending: Delayed401Protocol?
        Delayed401Protocol.onRequest = { request in
            XCTAssertEqual(request.request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-a")
            pending = request
            arrived.fulfill()
        }
        let old = Task { _ = try? await api.conversations() }
        await fulfillment(of: [arrived], timeout: 2)
        api.advanceAuthenticationRevision(2, clearToken: true)
        await api.setAccessToken("fixture-b", revision: 2)
        try XCTUnwrap(pending).reject()
        await old.value
        let revisions = await recorder.revisions
        XCTAssertTrue(revisions.isEmpty)
    }

    func testForbiddenAndServerFailureDoNotTriggerAuthenticationRecovery() async {
        let api = client()
        let recorder = RejectionRecorder()
        api.setUnauthorizedHandler { await recorder.record($0) }
        await api.setAccessToken("fixture-access", revision: 3)
        let body = unauthorizedBody()
        for status in [403, 429, 500, 503] {
            StubProtocol.handler = { _ in (status, body) }
            _ = try? await api.conversations()
        }
        let revisions = await recorder.revisions
        XCTAssertTrue(revisions.isEmpty)
    }

    func testAllLoginEntrypointsOmitExpiredAuthorization() async {
        let api = client()
        await api.setAccessToken("expired-test-fixture")
        let error = Data("{\"code\":\"VALIDATION_FAILED\",\"message\":\"测试拒绝\",\"data\":null}".utf8)
        var paths: [String] = []
        StubProtocol.handler = { request in
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            paths.append(request.url!.lastPathComponent)
            return (400, error)
        }
        _ = try? await api.login(email: "fixture", password: "fixture")
        _ = try? await api.refresh(refreshToken: "fixture")
        _ = try? await api.requestSmsCode(phone: "fixture")
        _ = try? await api.smsLogin(phone: "fixture", code: "fixture")
        XCTAssertEqual(paths, ["login", "refresh", "sms-code", "sms-login"])
    }

    func testProtectedRequestsStillCarryBearer() async {
        let api = client()
        await api.setAccessToken("protected-test-fixture")
        StubProtocol.handler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer protected-test-fixture")
            return (401, Data("{\"code\":\"AUTH_UNAUTHENTICATED\",\"message\":\"请先登录\",\"data\":null}".utf8))
        }
        do {
            _ = try await api.me()
            XCTFail("Expected unauthorized")
        } catch let error as APIError {
            guard case .unauthorized(let message) = error else { return XCTFail("Wrong error") }
            XCTAssertEqual(message, "请先登录")
        } catch { XCTFail("Unexpected error") }
    }

    func testATSErrorExplainsServerConfigurationRatherThanUserNetwork() async {
        let api = client()
        StubProtocol.handler = { _ in throw URLError(.appTransportSecurityRequiresSecureConnection) }
        do {
            _ = try await api.feed()
            XCTFail("Expected transport failure")
        } catch let error as APIError {
            XCTAssertTrue(error.errorDescription?.contains("系统阻止") == true)
        } catch { XCTFail("Unexpected error") }
    }

    func testFollowAndUnfollowUseTheSamePublicUIDAndDistinctMethods() async throws {
        let api = client()
        var methods: [String] = []
        StubProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/users/profile-fixture/follow")
            methods.append(request.httpMethod ?? "")
            return (200, try JSONSerialization.data(withJSONObject: ["code": "OK", "message": "", "data": true]))
        }
        _ = try await api.setFollowing(uid: "profile-fixture", following: true)
        _ = try await api.setFollowing(uid: "profile-fixture", following: false)
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testSynchronousRevisionFenceRejectsAPreviousTokenSetter() async {
        let api = client()
        api.advanceAuthenticationRevision(2, clearToken: true)
        await api.setAccessToken("obsolete-fixture", revision: 1)

        await assertAuthorization(api, equals: nil)
    }

    func testOldRevisionCannotClearOrReplaceTheNewerToken() async {
        let api = client()
        await api.setAccessToken("new-fixture", revision: 2)
        await api.setAccessToken(nil, revision: 1)
        await api.setAccessToken("old-fixture", revision: 1)
        api.advanceAuthenticationRevision(1, clearToken: true)

        await assertAuthorization(api, equals: "Bearer new-fixture")
    }

    func testAuthenticatedOperationFencePreservesTokenUntilExplicitClear() async {
        let api = client()
        await api.setAccessToken("current-fixture", revision: 1)
        api.advanceAuthenticationRevision(2, clearToken: false)
        await assertAuthorization(api, equals: "Bearer current-fixture")

        api.advanceAuthenticationRevision(2, clearToken: true)
        await assertAuthorization(api, equals: nil)
    }

    func testCandidateMeUsesOnlyItsExplicitTokenAndDoesNotPublishIt() async {
        let api = client()
        await api.setAccessToken("current-fixture", revision: 2)
        let errorBody = unauthorizedBody()
        StubProtocol.handler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer candidate-fixture")
            return (401, errorBody)
        }
        _ = try? await api.me(accessToken: "candidate-fixture")

        await assertAuthorization(api, equals: "Bearer current-fixture")
    }

    func testLogoutUsesItsCapturedCredentialsWithoutChangingTheNewerToken() async {
        let api = client()
        await api.setAccessToken("new-fixture", revision: 2)
        let errorBody = unauthorizedBody()
        StubProtocol.handler = { request in
            XCTAssertEqual(request.url?.lastPathComponent, "logout")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer old-fixture")
            let body = try JSONSerialization.jsonObject(with: self.requestBody(request)) as? [String: String]
            XCTAssertEqual(body?["refreshToken"], "old-refresh-fixture")
            return (401, errorBody)
        }
        await api.logout(accessToken: "old-fixture", refreshToken: "old-refresh-fixture")

        await assertAuthorization(api, equals: "Bearer new-fixture")
    }

    func testExplicitAnonymousLogoutCannotFallBackToTheCurrentAccount() async {
        let api = client()
        await api.setAccessToken("new-fixture", revision: 2)
        let errorBody = unauthorizedBody()
        StubProtocol.handler = { request in
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            return (401, errorBody)
        }
        await api.logout(accessToken: nil, refreshToken: "old-refresh-fixture")

        await assertAuthorization(api, equals: "Bearer new-fixture")
    }

    func testNonSuccessHTTPStatusRejectsAnOtherwiseValidProfileEnvelope() async throws {
        let body = try profileBody()
        for status in [400, 403, 429, 500, 503] {
            let api = client()
            StubProtocol.handler = { _ in (status, body) }
            do {
                _ = try await api.me()
                XCTFail("A non-success HTTP response must not authenticate a user")
            } catch let error as APIError {
                guard case .server(let code, _, let actualStatus) = error else { return XCTFail("Wrong error") }
                XCTAssertEqual(actualStatus, status)
                XCTAssertNotEqual(code, "OK")
            } catch { XCTFail("Unexpected error") }
        }
    }

    func testNonJSONServerFailurePreservesHTTPStatusWithoutDisplayingItsBody() async {
        let api = client()
        StubProtocol.handler = { _ in (503, Data("<html>private-upstream-fixture</html>".utf8)) }
        do {
            _ = try await api.me()
            XCTFail("Expected HTTP failure")
        } catch let error as APIError {
            guard case .server(let code, let message, let status) = error else { return XCTFail("Wrong error") }
            XCTAssertEqual(code, "HTTP_ERROR")
            XCTAssertEqual(status, 503)
            XCTAssertFalse(message.contains("private-upstream-fixture"))
        } catch { XCTFail("Unexpected error") }
    }

    func testNonSuccessUploadStatusRejectsAnOtherwiseValidUploadEnvelope() async throws {
        let body = try successBody(["id": 1, "url": "/api/media/1/content"])
        for status in [403, 413, 500] {
            let api = client()
            StubProtocol.handler = { _ in (status, body) }
            do {
                _ = try await api.uploadImage(data: Data([0xFF, 0xD8, 0xFF]))
                XCTFail("A non-success upload must not produce an image URL")
            } catch let error as APIError {
                guard case .server(let code, _, let actualStatus) = error else { return XCTFail("Wrong error") }
                XCTAssertEqual(actualStatus, status)
                XCTAssertNotEqual(code, "OK")
            } catch { XCTFail("Unexpected error") }
        }
    }

    func testSuccessfulProfileAndUploadResponsesStillDecode() async throws {
        let api = client()
        let profile = try profileBody()
        StubProtocol.handler = { _ in (200, profile) }
        let user = try await api.me()
        XCTAssertEqual(user.publicUid, "fixture-user")

        let upload = try successBody(["id": 1, "url": "/api/media/1/content"])
        StubProtocol.handler = { _ in (201, upload) }
        let uploaded = try await api.uploadImage(data: Data([0xFF, 0xD8, 0xFF]))
        XCTAssertEqual(uploaded.url, "/api/media/1/content")
    }
}
