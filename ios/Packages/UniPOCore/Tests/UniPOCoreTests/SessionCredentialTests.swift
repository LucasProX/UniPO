import Foundation
import XCTest
@testable import UniPOCore

final class SessionCredentialTests: XCTestCase {
    private let bundleKey = "unipo.session"
    private let accessKey = "unipo.access"
    private let refreshKey = "unipo.refresh"

    func testBundleTakesPrecedenceOverConflictingLegacyCredentials() throws {
        let expected = credentials("current")
        let keychain = MemorySecureValueStore(values: [
            bundleKey: try encoded(expected),
            accessKey: "legacy-access-fixture",
            refreshKey: "legacy-refresh-fixture"
        ])
        let store = KeychainSessionCredentialStore(keychain: keychain)

        let loaded = try store.load()

        XCTAssertTrue(loaded == expected, "The complete bundle must win over legacy entries")
        XCTAssertEqual(keychain.operations, [.read(bundleKey)])
    }

    func testCompleteLegacyCredentialsAreReadWithoutMigratingThem() throws {
        let expected = credentials("legacy", requiresRefreshValidation: true)
        let original = [accessKey: expected.accessToken, refreshKey: expected.refreshToken]
        let keychain = MemorySecureValueStore(values: original)
        let store = KeychainSessionCredentialStore(keychain: keychain)

        let loaded = try store.load()

        XCTAssertTrue(loaded == expected, "Legacy credentials must remain available for validation")
        XCTAssertTrue(keychain.values == original, "Loading must not migrate or delete credentials")
        XCTAssertEqual(keychain.operations, [.read(bundleKey), .read(accessKey), .read(refreshKey)])
    }

    func testRefreshOnlyLegacyCredentialsRemainAvailableForRecovery() throws {
        let expected = SessionCredentials(
            accessToken: "", refreshToken: "legacy-refresh-fixture", requiresRefreshValidation: true
        )
        let original = [refreshKey: expected.refreshToken]
        let keychain = MemorySecureValueStore(values: original)
        let store = KeychainSessionCredentialStore(keychain: keychain)

        let loaded = try store.load()

        XCTAssertTrue(loaded == expected, "A surviving refresh credential must not be treated as signed out")
        XCTAssertTrue(keychain.values == original, "Reading a partial legacy session must not mutate it")
        XCTAssertEqual(keychain.operations, [.read(bundleKey), .read(accessKey), .read(refreshKey)])
    }

    func testAccessOnlyLegacyCredentialsStillRequireRefreshValidation() throws {
        let expected = SessionCredentials(
            accessToken: "legacy-access-fixture", refreshToken: "", requiresRefreshValidation: true
        )
        let original = [accessKey: expected.accessToken]
        let keychain = MemorySecureValueStore(values: original)
        let store = KeychainSessionCredentialStore(keychain: keychain)

        let loaded = try store.load()

        XCTAssertTrue(loaded == expected, "A legacy access entry cannot be trusted without refresh validation")
        XCTAssertTrue(keychain.values == original, "Loading a legacy access entry must not migrate it")
        XCTAssertEqual(keychain.operations, [.read(bundleKey), .read(accessKey), .read(refreshKey)])
    }

    func testMissingCredentialsReturnNilWithoutWriting() throws {
        let keychain = MemorySecureValueStore()
        let store = KeychainSessionCredentialStore(keychain: keychain)

        let loaded = try store.load()

        XCTAssertTrue(loaded == nil, "Only missing entries should represent an absent session")
        XCTAssertEqual(keychain.operations, [.read(bundleKey), .read(accessKey), .read(refreshKey)])
        XCTAssertTrue(keychain.values.isEmpty)
    }

    func testSaveWritesOnePairedJSONBundleBeforeRemovingLegacyEntries() throws {
        let expected = credentials("replacement")
        let keychain = MemorySecureValueStore(values: [
            accessKey: "legacy-access-fixture",
            refreshKey: "legacy-refresh-fixture"
        ])
        let store = KeychainSessionCredentialStore(keychain: keychain)

        try store.save(expected)

        XCTAssertEqual(keychain.operations, [.save(bundleKey), .delete(accessKey), .delete(refreshKey)])
        XCTAssertEqual(Set(keychain.values.keys), [bundleKey])
        let bundle = try XCTUnwrap(keychain.values[bundleKey], "Saving must create the session bundle")
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(bundle.utf8)) as? [String: Any],
            "The session bundle must encode the credential pair as JSON"
        )
        XCTAssertTrue(object["accessToken"] as? String == expected.accessToken, "The saved access credential must match")
        XCTAssertTrue(object["refreshToken"] as? String == expected.refreshToken, "The saved refresh credential must match")
        let reloaded = try KeychainSessionCredentialStore(keychain: keychain).load()
        XCTAssertTrue(reloaded == expected, "The saved credential pair must survive store reconstruction")
        XCTAssertTrue(reloaded?.requiresRefreshValidation == false, "A saved bundle is no longer a legacy session")
    }

    func testSavingLegacyCredentialsClearsTheRefreshValidationMarker() throws {
        let expected = credentials("legacy")
        let keychain = MemorySecureValueStore(values: [
            accessKey: expected.accessToken, refreshKey: expected.refreshToken
        ])
        let store = KeychainSessionCredentialStore(keychain: keychain)
        let legacy = try XCTUnwrap(store.load(), "The legacy entries must load before migration")
        XCTAssertTrue(legacy.requiresRefreshValidation)

        try store.save(legacy)
        let reloaded = try KeychainSessionCredentialStore(keychain: keychain).load()

        XCTAssertTrue(reloaded == expected, "The migrated bundle must preserve the pair and clear the legacy marker")
        XCTAssertTrue(reloaded?.requiresRefreshValidation == false)
        XCTAssertEqual(Set(keychain.values.keys), [bundleKey])
    }

    func testFailedSavePreservesExistingBundleAndLegacyCredentials() throws {
        let original = [
            bundleKey: try encoded(credentials("original")),
            accessKey: "legacy-access-fixture",
            refreshKey: "legacy-refresh-fixture"
        ]
        let keychain = MemorySecureValueStore(values: original)
        keychain.saveFailures[bundleKey] = .writeDenied
        let store = KeychainSessionCredentialStore(keychain: keychain)

        XCTAssertThrowsError(try store.save(credentials("replacement"))) { error in
            XCTAssertEqual(error as? StorageFailure, .writeDenied)
        }

        XCTAssertTrue(keychain.values == original, "A failed save must preserve every previous credential")
        XCTAssertEqual(keychain.operations, [.save(bundleKey)], "Do not delete entries before saving succeeds")
    }

    func testClearWritesPersistentTombstoneBeforeRemovingLegacyEntries() throws {
        let keychain = MemorySecureValueStore(values: [
            bundleKey: try encoded(credentials("current")),
            accessKey: "legacy-access-fixture",
            refreshKey: "legacy-refresh-fixture"
        ])
        let store = KeychainSessionCredentialStore(keychain: keychain)

        try store.clear()

        XCTAssertEqual(keychain.operations, [.save(bundleKey), .delete(accessKey), .delete(refreshKey)])
        XCTAssertTrue(keychain.values == [bundleKey: "null"], "Logout must leave a durable marker")
        let reloaded = try KeychainSessionCredentialStore(keychain: keychain).load()
        XCTAssertTrue(reloaded == nil, "Logout must survive store reconstruction")
    }

    func testLegacyDeletionFailuresCannotReviveAnOldAccountAfterClear() throws {
        for failingKeys in [[accessKey], [refreshKey], [accessKey, refreshKey]] {
            let keychain = MemorySecureValueStore(values: [
                bundleKey: try encoded(credentials("account-b")),
                accessKey: "account-a-access-fixture",
                refreshKey: "account-a-refresh-fixture"
            ])
            for key in failingKeys { keychain.deleteFailures[key] = .deleteDenied }
            let store = KeychainSessionCredentialStore(keychain: keychain)

            do { try store.clear() } catch {
                XCTAssertEqual(error as? StorageFailure, .deleteDenied)
            }

            XCTAssertEqual(keychain.operations, [.save(bundleKey), .delete(accessKey), .delete(refreshKey)])
            XCTAssertTrue(keychain.values[bundleKey] == "null", "Legacy cleanup cannot remove the logout marker")
            XCTAssertEqual(Set(keychain.values.keys), Set([bundleKey] + failingKeys))
            let reloaded = try KeychainSessionCredentialStore(keychain: keychain).load()
            XCTAssertTrue(reloaded == nil, "Remaining legacy credentials must never revive the old account")
            XCTAssertEqual(keychain.operations.last, .read(bundleKey))
        }
    }

    func testFailedTombstoneWritePreservesAllCredentialsWithoutDeletingLegacy() throws {
        let original = [
            bundleKey: try encoded(credentials("current")),
            accessKey: "legacy-access-fixture",
            refreshKey: "legacy-refresh-fixture"
        ]
        let keychain = MemorySecureValueStore(values: original)
        keychain.saveFailures[bundleKey] = .writeDenied
        let store = KeychainSessionCredentialStore(keychain: keychain)

        XCTAssertThrowsError(try store.clear()) { error in
            XCTAssertEqual(error as? StorageFailure, .writeDenied)
        }

        XCTAssertTrue(keychain.values == original, "A failed logout marker write must preserve the previous state")
        XCTAssertEqual(keychain.operations, [.save(bundleKey)], "Legacy deletion must wait for a durable logout marker")
    }

    func testTombstoneTakesPrecedenceOverLegacyCredentialsAcrossStoreReconstruction() throws {
        let original = [
            bundleKey: "null",
            accessKey: "account-a-access-fixture",
            refreshKey: "account-a-refresh-fixture"
        ]
        let keychain = MemorySecureValueStore(values: original)
        let firstStore = KeychainSessionCredentialStore(keychain: keychain)
        let firstLoad = try firstStore.load()
        let reconstructedStore = KeychainSessionCredentialStore(keychain: keychain)
        let secondLoad = try reconstructedStore.load()

        XCTAssertTrue(firstLoad == nil && secondLoad == nil, "The logout marker must suppress every legacy fallback")
        XCTAssertEqual(keychain.operations, [.read(bundleKey), .read(bundleKey)])
        XCTAssertTrue(keychain.values == original, "Loading the marker must not erase it")
    }

    func testInvalidBundleThrowsInsteadOfFallingBackToLegacyOrReturningNil() {
        for invalidBundle in ["not-json", "{}"] {
            let original = [
                bundleKey: invalidBundle,
                accessKey: "legacy-access-fixture",
                refreshKey: "legacy-refresh-fixture"
            ]
            let keychain = MemorySecureValueStore(values: original)
            let store = KeychainSessionCredentialStore(keychain: keychain)

            XCTAssertThrowsError(try store.load()) { error in
                XCTAssertTrue(error is DecodingError, "A corrupt bundle must surface a decoding error")
            }

            XCTAssertEqual(keychain.operations, [.read(bundleKey)])
            XCTAssertTrue(keychain.values == original, "A corrupt bundle must not be silently erased")
        }
    }

    func testReadPermissionErrorsAreNotTreatedAsMissingCredentials() {
        let allKeys = [bundleKey, accessKey, refreshKey]
        for (index, failingKey) in allKeys.enumerated() {
            let keychain = MemorySecureValueStore()
            keychain.readFailures[failingKey] = .readDenied
            let store = KeychainSessionCredentialStore(keychain: keychain)

            XCTAssertThrowsError(try store.load()) { error in
                XCTAssertEqual(error as? StorageFailure, .readDenied)
            }

            XCTAssertEqual(keychain.operations, allKeys.prefix(index + 1).map { .read($0) })
        }
    }

    private func credentials(_ label: String, requiresRefreshValidation: Bool = false) -> SessionCredentials {
        SessionCredentials(
            accessToken: label + "-access-fixture", refreshToken: label + "-refresh-fixture",
            requiresRefreshValidation: requiresRefreshValidation
        )
    }

    private func encoded(_ credentials: SessionCredentials) throws -> String {
        String(decoding: try JSONEncoder().encode(credentials), as: UTF8.self)
    }
}

private enum StorageFailure: Error, Equatable {
    case readDenied, writeDenied, deleteDenied
}

private final class MemorySecureValueStore: SecureValueStore {
    enum Operation: Equatable {
        case read(String), save(String), delete(String)
    }

    var values: [String: String]
    var readFailures: [String: StorageFailure] = [:]
    var saveFailures: [String: StorageFailure] = [:]
    var deleteFailures: [String: StorageFailure] = [:]
    private(set) var operations: [Operation] = []

    init(values: [String: String] = [:]) { self.values = values }

    func read(account: String) throws -> String? {
        operations.append(.read(account))
        if let failure = readFailures[account] { throw failure }
        return values[account]
    }

    func save(_ value: String, account: String) throws {
        operations.append(.save(account))
        if let failure = saveFailures[account] { throw failure }
        values[account] = value
    }

    func delete(account: String) throws {
        operations.append(.delete(account))
        if let failure = deleteFailures[account] { throw failure }
        values.removeValue(forKey: account)
    }
}
