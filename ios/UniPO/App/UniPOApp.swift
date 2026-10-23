import SwiftUI
import UniPOCore
import UniPOFeatures

@main
struct UniPOApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var auth = AuthStore.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                // 支持到辅助功能第 3 档大字体；再往上系统列表和表单会挤成一行一字，
                // 自定义文字另有 UniType.maxScale 的上限。
                .dynamicTypeSize(...DynamicTypeSize.accessibility3)
                .environment(auth)
                .preferredColorScheme(.light)
        }
    }
}
