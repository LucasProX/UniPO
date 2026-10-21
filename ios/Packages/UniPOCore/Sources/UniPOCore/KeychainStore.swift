import Foundation
import Security

public struct KeychainStore {
    private let service: String

    public init(service: String = "com.unipo.app") {
        self.service = service
    }

    public func save(_ value: String, account: String) throws {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let updated = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if updated == errSecSuccess { return }
        guard updated == errSecItemNotFound else { throw KeychainFailure(status: updated) }
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let added = SecItemAdd(attributes as CFDictionary, nil)
        guard added == errSecSuccess else { throw KeychainFailure(status: added) }
    }

    public func read(account: String) throws -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainFailure(status: status) }
        guard let data = item as? Data else { throw KeychainFailure(status: errSecDecode) }
        guard let value = String(data: data, encoding: .utf8) else { throw KeychainFailure(status: errSecDecode) }
        return value
    }

    public func delete(account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainFailure(status: status) }
    }
}

public struct KeychainFailure: LocalizedError {
    public let status: OSStatus

    public var errorDescription: String? {
        if status == errSecInteractionNotAllowed { return "安全存储暂不可用，请解锁设备后重试" }
        return "无法读取或保存登录信息，请稍后重试"
    }
}
