import SwiftUI
import UniPOCore
import UniPOUI

struct AuthSheet: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss

    @State private var email = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("欢迎回来")
                        .font(.system(size: 28, weight: .heavy))
                        .foregroundStyle(UniColor.ink)
                    Text("登录后同步你的关注、成就和校园动态")
                        .font(.uniBody(13))
                        .foregroundStyle(UniColor.secondary)
                }

                VStack(spacing: 12) {
                    TextField("邮箱 / 账号", text: $email)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.emailAddress)
                        .padding(14)
                        .background(UniColor.paper, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                    SecureField("密码", text: $password)
                        .textContentType(.password)
                        .padding(14)
                        .background(UniColor.paper, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }

                if let error = auth.errorMessage {
                    Text(error)
                        .font(.uniLabel(12))
                        .foregroundStyle(UniColor.danger)
                }

                Button {
                    Task { await auth.login(email: email, password: password) }
                } label: {
                    if auth.isLoggingIn {
                        ProgressView().tint(.white)
                    } else {
                        Text("登录")
                    }
                }
                .buttonStyle(UniPrimaryButtonStyle())
                .disabled(auth.isLoggingIn)

                Spacer()
            }
            .padding(22)
            .navigationTitle("UniPO")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
            .onChange(of: auth.isAuthenticated) { _, signedIn in
                if signedIn { dismiss() }
            }
        }
    }
}
