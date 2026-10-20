import Foundation

public extension Notification.Name {
    static let unipoPushToken = Notification.Name("unipo.push.token")
    static let unipoOpenConversation = Notification.Name("unipo.open.conversation")
}

public enum PushTokenStore {
    private static let key = "unipo.apns.token"

    public static var token: String? {
        get { UserDefaults.standard.string(forKey: key) }
        set {
            if let newValue {
                UserDefaults.standard.set(newValue, forKey: key)
            } else {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }
    }
}
