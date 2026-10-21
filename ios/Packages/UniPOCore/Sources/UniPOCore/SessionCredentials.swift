import Foundation

struct SessionCredentials: Codable, Equatable {
    let accessToken: String
    let refreshToken: String
    let requiresRefreshValidation: Bool

    init(accessToken: String, refreshToken: String, requiresRefreshValidation: Bool = false) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.requiresRefreshValidation = requiresRefreshValidation
    }

    private enum CodingKeys: String, CodingKey { case accessToken, refreshToken, requiresRefreshValidation }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        accessToken = try values.decode(String.self, forKey: .accessToken)
        refreshToken = try values.decode(String.self, forKey: .refreshToken)
        requiresRefreshValidation = try values.decodeIfPresent(Bool.self, forKey: .requiresRefreshValidation) ?? false
    }
}

protocol SessionCredentialStore {
    func load() throws -> SessionCredentials?
    func save(_ credentials: SessionCredentials) throws
    func clear() throws
}

protocol SecureValueStore {
    func read(account: String) throws -> String?
    func save(_ value: String, account: String) throws
    func delete(account: String) throws
}

extension KeychainStore: SecureValueStore {}

struct KeychainSessionCredentialStore: SessionCredentialStore {
    private let keychain: any SecureValueStore
    private let bundleKey = "unipo.session"
    private let legacyAccessKey = "unipo.access"
    private let legacyRefreshKey = "unipo.refresh"

    init(service: String = AppConfig.credentialService(for: AppConfig.apiBaseURL)) {
        keychain = KeychainStore(service: service)
    }

    init(keychain: any SecureValueStore) { self.keychain = keychain }

    func load() throws -> SessionCredentials? {
        if let value = try keychain.read(account: bundleKey) {
            if value.trimmingCharacters(in: .whitespacesAndNewlines) == "null" { return nil }
            return try JSONDecoder().decode(SessionCredentials.self, from: Data(value.utf8))
        }
        // Older installs stored two entries. Migrate only after the server validates them.
        let access = try keychain.read(account: legacyAccessKey)
        let refresh = try keychain.read(account: legacyRefreshKey)
        guard access != nil || refresh != nil else { return nil }
        return SessionCredentials(accessToken: access ?? "", refreshToken: refresh ?? "", requiresRefreshValidation: true)
    }

    func save(_ credentials: SessionCredentials) throws {
        // One Keychain item keeps access and refresh from different accounts from mixing.
        let validated = SessionCredentials(accessToken: credentials.accessToken, refreshToken: credentials.refreshToken)
        let data = try JSONEncoder().encode(validated)
        try keychain.save(String(decoding: data, as: UTF8.self), account: bundleKey)
        try? keychain.delete(account: legacyAccessKey)
        try? keychain.delete(account: legacyRefreshKey)
    }

    func clear() throws {
        // A persistent signed-out marker must win over any legacy key left by a failed delete.
        try keychain.save("null", account: bundleKey)
        var failure: Error?
        for account in [legacyAccessKey, legacyRefreshKey] {
            do { try keychain.delete(account: account) }
            catch { if failure == nil { failure = error } }
        }
        if let failure { throw failure }
    }
}
