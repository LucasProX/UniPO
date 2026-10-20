import SwiftUI
import UniPOCore
import UniPOFeatures

@main
struct UniPOApp: App {
    @State private var auth = AuthStore.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(auth)
                .preferredColorScheme(.light)
        }
    }
}
