import XCTest
@testable import UniPOCore

final class AppConfigTests: XCTestCase {
    func testProductionAndTunnelCredentialsAreIsolated() {
        let production = URL(string: "http://49.234.21.63/api/v1")!
        let test = URL(string: "http://127.0.0.1:18081/api/v1")!
        XCTAssertNotEqual(AppConfig.credentialService(for: production), AppConfig.credentialService(for: test))
    }

    func testDistinctTunnelPortsDoNotShareCredentials() {
        let first = URL(string: "http://127.0.0.1:18081/api/v1")!
        let second = URL(string: "http://127.0.0.1:8080/api/v1")!
        XCTAssertNotEqual(AppConfig.credentialService(for: first), AppConfig.credentialService(for: second))
    }

    func testTrailingSlashDoesNotCreateAnotherCredentialStore() {
        let base = URL(string: "http://127.0.0.1:18081/api/v1")!
        XCTAssertEqual(AppConfig.credentialService(for: base), AppConfig.credentialService(for: base.appendingPathComponent("")))
    }
}
