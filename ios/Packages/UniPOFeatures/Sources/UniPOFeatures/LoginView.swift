import SwiftUI
import UIKit
import UniPOCore
import UniPOUI

struct LoginView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var phone = ""
    @State private var code = ""
    @State private var countdown = 0
    @State private var agreed = false
    @State private var agreement: Agreement?
    @State private var appeared = false
    @State private var countdownTask: Task<Void, Never>?
    @ScaledMetric(relativeTo: .caption2) private var agreementFontSize: CGFloat = 11
    @FocusState private var focusedField: Field?

    private enum Field {
        case phone, code
    }

    enum Agreement: String, Identifiable {
        case terms, privacy

        var id: String { rawValue }
        var title: String {
            self == .terms ? "用户协议" : "隐私政策"
        }
    }

    var body: some View {
        Group {
            if auth.isAuthenticated, auth.profile?.onboardingCompleted == false {
                OnboardingView()
                    .environment(auth)
            } else {
                loginBody
            }
        }
    }

    private var loginBody: some View {
        ZStack {
            LoginBackground(reduceMotion: reduceMotion)
                .onTapGesture { focusedField = nil }

            GeometryReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        topBar
                        Spacer(minLength: 24)
                        brand
                        Spacer(minLength: 28)
                        phoneContent
                            .padding(.horizontal, 28)
                        Spacer(minLength: 24)
                        agreementRow
                        footer
                    }
                    .frame(minHeight: max(0, proxy.size.height - 14))
                    .padding(.bottom, 14)
                }
                .scrollIndicators(.hidden)
                .scrollDismissesKeyboard(.interactively)
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            withAnimation(.easeOut(duration: 0.7)) { appeared = true }
        }
        .onDisappear { countdownTask?.cancel() }
        .sheet(item: $agreement) { kind in
            AgreementSheet(kind: kind)
                .presentationDetents([.medium, .large])
                .presentationCornerRadius(28)
                .presentationBackground(Color(hex: 0x0B0F17))
        }
        .task {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-focus-phone") {
                try? await Task.sleep(nanoseconds: 500_000_000)
                focusedField = .phone
            }
            if ProcessInfo.processInfo.arguments.contains("-agreement") {
                agreement = .terms
            }
            #endif
        }
    }

    private var topBar: some View {
        HStack {
            Button {
                auth.loginRequested = false
            } label: {
                Image(systemName: "chevron.left")
                    .uniFont(15, weight: .semibold)
                    .foregroundStyle(.white.opacity(0.8))
                    .frame(width: 36, height: 36)
                    .background(.white.opacity(0.08), in: Circle())
            }
            .buttonStyle(.plain)
            Spacer()
            if focusedField != nil {
                Button("完成") {
                    focusedField = nil
                }
                .uniFont(14, weight: .semibold)
                .foregroundStyle(.white.opacity(0.8))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.white.opacity(0.08), in: Capsule())
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 8)
    }

    private var brand: some View {
        VStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                    .fill(.white)
                    .frame(width: 76, height: 76)
                    .shadow(color: .white.opacity(0.18), radius: 30, y: 10)
                Text("PO")
                    .uniFont(24, weight: .heavy)
                    .foregroundStyle(.black)
            }
            .scaleEffect(appeared ? 1 : 0.9)

            Text("UniPO")
                .uniFont(32, weight: .heavy)
                .foregroundStyle(.white)
                .tracking(1)

            Text("大学生活缤纷多彩，开始探索吧")
                .uniFont(13)
                .foregroundStyle(.white.opacity(0.5))
        }
    }

    private var phoneContent: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(spacing: 5) {
                Text("手机号登录")
                    .uniFont(24, weight: .heavy)
                    .foregroundStyle(.white)
                    .accessibilityIdentifier("login.phone.title")
                Text("未注册的手机号会自动创建账号")
                    .uniFont(12)
                    .foregroundStyle(.white.opacity(0.45))
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)

            HStack(spacing: 0) {
                Text("+86")
                    .uniFont(16, weight: .semibold)
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.horizontal, 14)
                Rectangle().fill(.white.opacity(0.12)).frame(width: 1, height: 22)
                TextField("", text: $phone, prompt: Text("请输入手机号").foregroundStyle(.white.opacity(0.35)))
                    .focused($focusedField, equals: .phone)
                    .keyboardType(.numberPad)
                    .textContentType(.telephoneNumber)
                    .uniFont(17, weight: .semibold)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .accessibilityIdentifier("login.phone")
                    .onChange(of: phone) { _, newValue in
                        phone = String(newValue.filter { $0 >= "0" && $0 <= "9" }.prefix(11))
                    }
            }
            .frame(height: 54)
            .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous)
                    .stroke(.white.opacity(0.14), lineWidth: 1)
            )

            HStack(spacing: 10) {
                TextField("", text: $code, prompt: Text("4 位验证码").foregroundStyle(.white.opacity(0.35)))
                    .focused($focusedField, equals: .code)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .uniFont(17, weight: .semibold)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .accessibilityIdentifier("login.code")
                    .onChange(of: code) { _, newValue in
                        code = String(newValue.filter { $0 >= "0" && $0 <= "9" }.prefix(4))
                    }
                    .frame(height: 54)
                    .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous))

                Button {
                    Task { await sendCode() }
                } label: {
                    Group {
                        if auth.isSendingCode {
                            ProgressView().tint(.white)
                        } else {
                            Text(countdown > 0 ? "\(countdown)s" : "获取验证码")
                                .uniFont(13, weight: .semibold)
                        }
                    }
                    .foregroundStyle(countdown > 0 ? .white.opacity(0.35) : .white)
                    .frame(width: 100, height: 54)
                    .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: UniRadius.button, style: .continuous)
                            .stroke(.white.opacity(0.14), lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
                .disabled(!canRequestCode)
                .accessibilityIdentifier("login.send-code")
            }

            if let error = auth.errorMessage {
                Text(error)
                    .uniFont(12, weight: .semibold)
                    .foregroundStyle(.red.opacity(0.9))
                    .accessibilityIdentifier("login.error")
            }

            Button {
                Task { await submitPhone() }
            } label: {
                Group {
                    if auth.isLoggingIn {
                        ProgressView().tint(.black)
                    } else {
                        Text("登录 / 注册")
                            .uniFont(16, weight: .semibold)
                    }
                }
                .foregroundStyle(.black)
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background(.white, in: RoundedRectangle(cornerRadius: UniRadius.tile, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(!canSubmitPhone)
            .opacity(canSubmitPhone ? 1 : 0.5)
            .accessibilityIdentifier("login.submit")

            socialRow
                .frame(maxWidth: .infinity)
                .padding(.top, 4)
        }
    }

    private var socialRow: some View {
        VStack(spacing: 10) {
            HStack(spacing: 20) {
                socialButton(label: "微", name: "微信")
                socialButton(label: "Q", name: "QQ")
            }
        }
    }

    private func socialButton(label: String, name: String) -> some View {
        VStack(spacing: 5) {
            Text(label)
                .uniFont(17, weight: .bold)
                .foregroundStyle(.white.opacity(0.45))
                .frame(width: 46, height: 46)
                .background(.white.opacity(0.06), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.12), lineWidth: 1))
            Text(name)
                .uniFont(9, weight: .semibold)
                .foregroundStyle(.white.opacity(0.28))
        }
        .opacity(0.75)
    }

    private var agreementRow: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Button {
                agreed.toggle()
            } label: {
                Image(systemName: agreed ? "checkmark.circle.fill" : "circle")
                    .uniFont(18, weight: .semibold)
                    .foregroundStyle(agreed ? .white : .white.opacity(0.35))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .alignmentGuide(.firstTextBaseline) { dimensions in
                // Align the symbol centre with the first text line, while retaining a 44pt hit target.
                let font = UIFont.systemFont(ofSize: agreementFontSize, weight: .semibold)
                return dimensions[VerticalAlignment.center] + font.ascender - font.lineHeight / 2
            }
            .accessibilityLabel(agreed ? "已同意协议" : "同意用户协议和隐私政策")
            .accessibilityIdentifier("login.agreement")

            Text(agreementText)
                .font(.system(size: agreementFontSize, weight: .semibold))
                .foregroundStyle(.white.opacity(0.55))
                .tint(.white.opacity(0.9))
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .environment(\.openURL, OpenURLAction { url in
                    guard url.scheme == "unipo-agreement" else { return .discarded }
                    switch url.host {
                    case "terms": agreement = .terms
                    case "privacy": agreement = .privacy
                    default: return .discarded
                    }
                    return .handled
                })
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.horizontal, 20)
    }

    private var agreementText: AttributedString {
        var text = AttributedString("我已阅读并同意")
        var terms = AttributedString("《用户协议》")
        terms.link = URL(string: "unipo-agreement://terms")
        text.append(terms)
        text.append(AttributedString("和"))
        var privacy = AttributedString("《隐私政策》")
        privacy.link = URL(string: "unipo-agreement://privacy")
        text.append(privacy)
        return text
    }

    private var isValidPhone: Bool {
        phone.range(of: "^1[3-9][0-9]{9}$", options: .regularExpression) != nil
    }

    private var canRequestCode: Bool {
        isValidPhone && countdown == 0 && !auth.isSendingCode && !auth.isLoggingIn
    }

    private var canSubmitPhone: Bool {
        agreed && isValidPhone && code.count == 4 && !auth.isLoggingIn && !auth.isSendingCode
    }

    private func submitPhone() async {
        guard canSubmitPhone else { return }
        focusedField = nil
        await auth.smsLogin(phone: phone, code: code)
    }

    private var footer: some View {
        Text("UniPO · 让大学每一步都有答案")
            .uniFont(10, weight: .semibold)
            .foregroundStyle(.white.opacity(0.2))
            .padding(.top, 10)
    }

    private func sendCode() async {
        guard canRequestCode else { return }
        let success = await auth.requestSmsCode(phone: phone)
        guard success else { return }
        countdown = 60
        countdownTask?.cancel()
        countdownTask = Task {
            while countdown > 0, !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { break }
                countdown -= 1
            }
        }
    }
}

private struct LoginBackground: View {
    let reduceMotion: Bool
    @State private var drift = false

    var body: some View {
        ZStack {
            Color.black

            GeometryReader { proxy in
                ZStack {
                    Circle()
                        .fill(RadialGradient(colors: [.white.opacity(0.16), .clear],
                                             center: .center, startRadius: 0, endRadius: 220))
                        .frame(width: 420, height: 420)
                        .offset(x: drift ? -80 : -150, y: drift ? -140 : -60)

                    Circle()
                        .fill(RadialGradient(colors: [.white.opacity(0.07), .clear],
                                             center: .center, startRadius: 0, endRadius: 260))
                        .frame(width: 520, height: 520)
                        .offset(x: drift ? 150 : 60, y: drift ? 260 : 140)

                    grid
                        .opacity(0.07)
                }
                .frame(width: proxy.size.width, height: proxy.size.height)
                .blur(radius: 2)
                .animation(reduceMotion ? nil : .easeInOut(duration: 9).repeatForever(autoreverses: true),
                           value: drift)
            }

            LinearGradient(colors: [.black.opacity(0.2), .black.opacity(0.65), .black],
                           startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
        .onAppear {
            if !reduceMotion { drift = true }
        }
    }

    private var grid: some View {
        Canvas { context, size in
            let step: CGFloat = 28
            var path = Path()
            var x: CGFloat = 0
            while x < size.width {
                path.move(to: CGPoint(x: x, y: 0))
                path.addLine(to: CGPoint(x: x, y: size.height))
                x += step
            }
            var y: CGFloat = 0
            while y < size.height {
                path.move(to: CGPoint(x: 0, y: y))
                path.addLine(to: CGPoint(x: size.width, y: y))
                y += step
            }
            context.stroke(path, with: .color(.white), lineWidth: 0.5)
        }
    }
}

private struct AgreementSheet: View {
    let kind: LoginView.Agreement

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("协议内容暂不可用")
                        .uniFont(14)
                        .foregroundStyle(UniColor.secondary)
                    Text("请稍后再试。")
                        .uniFont(13)
                        .foregroundStyle(UniColor.secondary)
                        .lineSpacing(5)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
            .navigationTitle(kind.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }
}
