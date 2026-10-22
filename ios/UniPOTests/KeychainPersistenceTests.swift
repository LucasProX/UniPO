import Foundation
import XCTest
import UniPOCore

final class KeychainPersistenceTests: XCTestCase {
    private var fixtureService = ""
    private var accountsToRemove: Set<String> = []

    override func setUpWithError() throws {
        try super.setUpWithError()
        fixtureService = "com.unipo.fixture." + UUID().uuidString
        accountsToRemove = []
    }

    override func tearDownWithError() throws {
        let store = KeychainStore(service: fixtureService)
        var cleanupFailure: Error?
        for account in accountsToRemove.sorted() {
            do { try keychainOperation("cleanup delete") { try store.delete(account: account) } }
            catch { if cleanupFailure == nil { cleanupFailure = error } }
        }
        accountsToRemove = []
        fixtureService = ""
        do { try super.tearDownWithError() }
        catch { if cleanupFailure == nil { cleanupFailure = error } }
        if let cleanupFailure { throw cleanupFailure }
    }

    func testSaveOverwriteAndDeletePersistAcrossStoreReconstruction() throws {
        let account = "fixture-session"
        let firstValue = "fixture-first-value"
        let replacementValue = "fixture-replacement-value"
        accountsToRemove.insert(account)

        try keychainOperation("save new item") {
            try KeychainStore(service: fixtureService).save(firstValue, account: account)
        }
        let firstRead = try keychainOperation("read reconstructed store") {
            try KeychainStore(service: fixtureService).read(account: account)
        }
        // Boolean assertions avoid placing fixture contents in failure output.
        XCTAssertTrue(firstRead == firstValue, "A reconstructed store must read the saved fixture")

        try keychainOperation("overwrite existing item") {
            try KeychainStore(service: fixtureService).save(replacementValue, account: account)
        }
        let replacementRead = try keychainOperation("read overwritten item") {
            try KeychainStore(service: fixtureService).read(account: account)
        }
        XCTAssertTrue(replacementRead == replacementValue, "Overwrite must survive store reconstruction")

        try keychainOperation("delete fixture item") {
            try KeychainStore(service: fixtureService).delete(account: account)
        }
        let deletedRead = try keychainOperation("read deleted item") {
            try KeychainStore(service: fixtureService).read(account: account)
        }
        XCTAssertTrue(deletedRead == nil, "A deleted fixture must not be read by a reconstructed store")
    }

    func testMissingFixtureItemReturnsNilWithoutCreatingOrDeletingAnything() throws {
        let value = try keychainOperation("read missing item") {
            try KeychainStore(service: fixtureService).read(account: "fixture-never-created")
        }
        XCTAssertTrue(value == nil, "An absent fixture must return nil")
    }

    private func keychainOperation<T>(
        _ operation: String,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ perform: () throws -> T
    ) throws -> T {
        do {
            return try perform()
        } catch let failure as KeychainFailure {
            XCTFail("Keychain " + operation + " failed; OSStatus=" + String(failure.status), file: file, line: line)
            throw failure
        }
    }
}
