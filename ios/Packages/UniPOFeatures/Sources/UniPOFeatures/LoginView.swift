import SwiftUI
import UniPOCore
import UniPOUI

/// 本机号码读取。
///
/// iOS **没有**原生 API 能直接拿到本机手机号；国内 App 的「一键登录」都依赖运营商
/// 号码认证 SDK（移动/联通/电信）。这里留一个 provider：DEBUG 返回演示号码，
/// Release 返回 nil -> 自动落到手机号验证码登录。接入运营商 SDK 后只改这里。
enum DevicePhoneNumber {
    static var current: String? {
        #if DEBUG
        guard AppConfig.isLocalDevelopment else { return nil }
        if ProcessInfo.processInfo.arguments.contains("-no-phone") { return nil }
        return "15500008888"
        #else
        return nil
        #endif
    }

    static func masked(_ raw: String) -> String {
        let digits = raw.filter(\.isNumber)
        guard digits.count == 11 else { return "+86 \(raw)" }
        return "+86 \(digits.prefix(3))****\(digits.suffix(4))"
    }
}

struct LoginView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var mode: Mode
    @State private var devicePhone: String?
    @State private var phone = ""
    @State private var code = ""
    @State private var account = ""
    @State private var password = ""
    @State private var countdown = 0
    @State private var agreed = false
    @State private var agreement: Agreement?
    @State private var appeared = false
    @State private var countdownTask: Task<Void, Never>?
    @FocusState private var focusedField: Field?

    private enum Field {
        case phone, code, account, password
    }

    private enum Mode {
        case oneTap, phone, account
    }

    enum Agreement: String, Identifiable {
        case terms, privacy

        var id: String { rawValue }
        var title: String {
            self == .terms ? "用户协议" : "隐私政策"
        }
    }

    init() {
        let number = DevicePhoneNumber.current
        _devicePhone = State(initialValue: number)
        _mode = State(initialValue: AppConfig.isLocalDevelopment ? (number == nil ? .phone : .oneTap) : .account)
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
                VStack(spacing: 0) {
                    topBar
                    Spacer(minLength: 8)
                    brand
                    Spacer(minLength: 18)

                    Group {
                        if mode == .account {
                            accountContent
                        } else if mode == .oneTap, let devicePhone {
                            oneTapContent(number: devicePhone)
                        } else {
                            phoneContent
                        }
                    }
                    .padding(.horizontal, 28)
                    // 只有「一键登录」整体上移 10% 屏高；手机号表单更高，保持原位避免压到标题。
                    .offset(y: (mode == .oneTap && devicePhone != nil) ? -proxy.size.height * 0.10 : 0)

                    Spacer(minLength: 18)
                    agreementRow
                    footer
                }
                .padding(.bottom, 14)
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
            if ProcessInfo.processInfo.arguments.contains("-phone-form") {
                mode = .phone
            }
            if ProcessInfo.processInfo.arguments.contains("-focus-phone") {
                mode = .phone
                try? await Task.sleep(nanoseconds: 500_000_000)
                focusedField = .phone
            }
            if ProcessInfo.processInfo.arguments.contains("-agreement") {
                agreement = .terms
            }
            if ProcessInfo.processInfo.arguments.contains("-one-tap") {
                await oneTapLogin(devicePhone ?? "15500008888")
            }
            if AppConfig.isLocalDevelopment, ProcessInfo.processInfo.arguments.contains("-sms-login") {
                mode = .phone
                phone = "13900003333"
                code = "1234"
                try? await Task.sleep(nanoseconds: 800_000_000)
                await auth.smsLogin(phone: phone, code: code)
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
                    .font(.system(size: 15, weight: .semibold))
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
                .font(.system(size: 14, weight: .semibold))
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
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(.white)
                    .frame(width: 76, height: 76)
                    .shadow(color: .white.opacity(0.18), radius: 30, y: 10)
                Text("PO")
                    .font(.system(size: 24, weight: .heavy))
                    .foregroundStyle(.black)
            }
            .scaleEffect(appeared ? 1 : 0.9)

            Text("UniPO")
                .font(.system(size: 32, weight: .heavy))
                .foregroundStyle(.white)
                .tracking(1)

            Text("大学生活缤纷多彩，开始探索吧")
                .font(.uniBody(13))
                .foregroundStyle(.white.opacity(0.5))
        }
    }

    private func oneTapContent(number: String) -> some View {
        VStack(spacing: 22) {
            VStack(spacing: 8) {
                Text("本机号码")
                    .font(.uniLabel(11))
                    .foregroundStyle(.white.opacity(0.4))
                Text(DevicePhoneNumber.masked(number))
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(.white)
                    .monospacedDigit()
                Text("使用本机号码快捷登录")
                    .font(.uniBody(12))
                    .foregroundStyle(.white.opacity(0.4))
            }
            .padding(.vertical, 8)

            Button {
                Task { await oneTapLogin(number) }
            } label: {
                Group {
                    if auth.isLoggingIn {
                        ProgressView().tint(.black)
                    } else {
                        Text("一键登录")
                            .font(.system(size: 17, weight: .semibold))
                    }
                }
                .foregroundStyle(.black)
                .frame(maxWidth: .infinity)
                .frame(height: 56)
                .background(.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(!agreed || auth.isLoggingIn)
            .opacity(agreed ? 1 : 0.45)

            Button {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { mode = .phone }
            } label: {
                Text("不是本机号码？使用手机号验证码登录")
                    .font(.uniLabel(11))
                    .foregroundStyle(.white.opacity(0.45))
                    .underline()
            }
            .buttonStyle(.plain)

            if let error = auth.errorMessage {
                Text(error)
                    .font(.uniLabel(12))
                    .foregroundStyle(.red.opacity(0.9))
            }
        }
    }

    private var phoneContent: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 5) {
                Text("手机号登录")
                    .font(.system(size: 24, weight: .heavy))
                    .foregroundStyle(.white)
                Text("未注册的手机号会自动创建账号")
                    .font(.uniBody(12))
                    .foregroundStyle(.white.opacity(0.45))
            }

            HStack(spacing: 0) {
                Text("+86")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.horizontal, 14)
                Rectangle().fill(.white.opacity(0.12)).frame(width: 1, height: 22)
                TextField("", text: $phone, prompt: Text("请输入手机号").foregroundStyle(.white.opacity(0.35)))
                    .focused($focusedField, equals: .phone)
                    .keyboardType(.numberPad)
                    .textContentType(.telephoneNumber)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .onChange(of: phone) { _, newValue in
                        phone = String(newValue.filter(\.isNumber).prefix(11))
                    }
            }
            .frame(height: 54)
            .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(.white.opacity(0.14), lineWidth: 1)
            )

            HStack(spacing: 10) {
                TextField("", text: $code, prompt: Text("4 位验证码").foregroundStyle(.white.opacity(0.35)))
                    .focused($focusedField, equals: .code)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .onChange(of: code) { _, newValue in
                        code = String(newValue.filter(\.isNumber).prefix(4))
                    }
                    .frame(height: 54)
                    .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16, style: .continuous))

                Button {
                    Task { await sendCode() }
                } label: {
                    Group {
                        if auth.isSendingCode {
                            ProgressView().tint(.white)
                        } else {
                            Text(countdown > 0 ? "\(countdown)s" : "获取验证码")
                                .font(.system(size: 13, weight: .semibold))
                        }
                    }
                    .foregroundStyle(countdown > 0 ? .white.opacity(0.35) : .white)
                    .frame(width: 100, height: 54)
                    .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(.white.opacity(0.14), lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
                .disabled(!agreed || countdown > 0 || auth.isSendingCode || phone.count != 11)
            }

            if let error = auth.errorMessage {
                Text(error)
                    .font(.uniLabel(12))
                    .foregroundStyle(.red.opacity(0.9))
            }

            Button {
                Task { await auth.smsLogin(phone: phone, code: code) }
            } label: {
                Group {
                    if auth.isLoggingIn {
                        ProgressView().tint(.black)
                    } else {
                        Text("登录 / 注册")
                            .font(.system(size: 16, weight: .semibold))
                    }
                }
                .foregroundStyle(.black)
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background(.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(auth.isLoggingIn || !agreed || phone.count != 11 || code.count != 4)
            .opacity(agreed && phone.count == 11 && code.count == 4 ? 1 : 0.5)

            if devicePhone != nil {
                Button {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { mode = .oneTap }
                } label: {
                    Text("使用本机号码一键登录")
                        .font(.uniLabel(11))
                        .foregroundStyle(.white.opacity(0.45))
                        .underline()
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity)
            }

            socialRow
                .frame(maxWidth: .infinity)
                .padding(.top, 4)
            Button("使用账号密码登录") {
                focusedField = nil
                auth.errorMessage = nil
                mode = .account
            }
            .font(.uniLabel(12))
            .foregroundStyle(.white.opacity(0.75))
            .frame(maxWidth: .infinity, minHeight: 44)
        }
    }

    private var accountContent: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("账号登录")
                .font(.system(size: 24, weight: .heavy))
                .foregroundStyle(.white)
            Text("使用已有 UniPO 账号，不限邮箱格式")
                .font(.uniBody(12))
                .foregroundStyle(.white.opacity(0.5))
            TextField("账号", text: $account)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focusedField, equals: .account)
                .submitLabel(.next)
                .onSubmit { focusedField = .password }
                .accessibilityIdentifier("login.account")
                .padding(16)
                .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
            SecureField("密码", text: $password)
                .textContentType(.password)
                .focused($focusedField, equals: .password)
                .submitLabel(.go)
                .onSubmit { if canSubmitAccount { Task { await submitAccount() } } }
                .accessibilityIdentifier("login.password")
                .padding(16)
                .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
            if let error = auth.errorMessage {
                Text(error)
                    .font(.uniLabel(12))
                    .foregroundStyle(UniColor.danger)
                    .accessibilityIdentifier("login.error")
            }
            Button { Task { await submitAccount() } } label: {
                HStack {
                    Spacer()
                    if auth.isLoggingIn { ProgressView().tint(.black) } else { Text("登录").font(.uniLabel(16)) }
                    Spacer()
                }
                .foregroundStyle(.black)
                .frame(height: 54)
                .background(.white, in: RoundedRectangle(cornerRadius: 18))
            }
            .buttonStyle(.plain)
            .disabled(!canSubmitAccount)
            .opacity(canSubmitAccount ? 1 : 0.5)
            .accessibilityIdentifier("login.submit")
            Button("使用手机号验证码登录") {
                focusedField = nil
                auth.errorMessage = nil
                mode = .phone
            }
            .font(.uniLabel(12))
            .foregroundStyle(.white.opacity(0.75))
            .frame(maxWidth: .infinity, minHeight: 44)
        }
        .foregroundStyle(.white)
    }

    private var canSubmitAccount: Bool {
        agreed && !auth.isLoggingIn && !account.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !password.isEmpty
    }

    private func submitAccount() async {
        guard canSubmitAccount else { return }
        focusedField = nil
        await auth.login(email: account, password: password)
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
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(.white.opacity(0.45))
                .frame(width: 46, height: 46)
                .background(.white.opacity(0.06), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.12), lineWidth: 1))
            Text(name)
                .font(.uniLabel(9))
                .foregroundStyle(.white.opacity(0.28))
        }
        .opacity(0.75)
    }

    private var agreementRow: some View {
        HStack(alignment: .top, spacing: 8) {
            Button {
                agreed.toggle()
            } label: {
                Image(systemName: agreed ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(agreed ? .white : .white.opacity(0.35))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(agreed ? "已同意协议" : "同意用户协议和隐私政策")
            .accessibilityIdentifier("login.agreement")

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 0) {
                    Text("我已阅读并同意")
                        .foregroundStyle(.white.opacity(0.4))
                    Button("《用户协议》") { agreement = .terms }
                        .foregroundStyle(.white.opacity(0.9))
                    Text("和")
                        .foregroundStyle(.white.opacity(0.4))
                    Button("《隐私政策》") { agreement = .privacy }
                        .foregroundStyle(.white.opacity(0.9))
                }
                .font(.uniLabel(10))

            }
        }
        .padding(.horizontal, 28)
    }

    private var footer: some View {
        Text("UniPO · 让大学每一步都有答案")
            .font(.uniLabel(10))
            .foregroundStyle(.white.opacity(0.2))
            .padding(.top, 10)
    }

    private func sendCode() async {
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

    private func oneTapLogin(_ number: String) async {
        #if DEBUG
        guard AppConfig.isLocalDevelopment else {
            auth.errorMessage = "演示号码只用于本地开发，请使用账号登录"
            return
        }
        // 演示：没有运营商 SDK，直接用 mock 验证码登录本机演示号码。
        await auth.smsLogin(phone: number, code: "1234")
        #else
        auth.errorMessage = "一键登录需要接入运营商号码认证服务，请使用手机号验证码登录"
        #endif
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
                    Text("正文将在正式上线前补充。")
                        .font(.uniBody(14))
                        .foregroundStyle(UniColor.secondary)
                    Text("这里预留协议结构：账号与登录、内容发布规范、隐私与数据使用、未成年人保护、举报与申诉、账号注销。")
                        .font(.uniBody(13))
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
