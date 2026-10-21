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

final class APIClientTests: XCTestCase {
    private func client() -> APIClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return APIClient(session: URLSession(configuration: config))
    }

    override func tearDown() {
        StubProtocol.handler = nil
        super.tearDown()
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
}
