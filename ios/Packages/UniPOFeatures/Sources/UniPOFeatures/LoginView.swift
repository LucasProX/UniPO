import SwiftUI
import UniPOCore
import UniPOUI

struct LoginView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var mode: Mode = .choice
    @State private var phone = ""
    @State private var code = ""
    @State private var countdown = 0
    @State private var showOneTapInfo = false
    @State private var appeared = false
    @State private var countdownTask: Task<Void, Never>?

    private enum Mode {
        case choice, phone
    }

    var body: some View {
        ZStack {
            LoginBackground(reduceMotion: reduceMotion)

            VStack(spacing: 0) {
                topBar
                Spacer(minLength: 12)
                brand
                Spacer(minLength: 20)

                Group {
                    if mode == .choice {
                        choiceContent
                            .transition(.opacity.combined(with: .move(edge: .bottom)))
                    } else {
                        phoneContent
                            .transition(.opacity.combined(with: .move(edge: .trailing)))
                    }
                }
                .padding(.horizontal, 28)

                Spacer(minLength: 24)
                footer
            }
            .padding(.bottom, 18)
        }
        .preferredColorScheme(.dark)
        .onAppear {
            withAnimation(.easeOut(duration: 0.7)) { appeared = true }
        }
        .onDisappear { countdownTask?.cancel() }
        .task {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-phone-form") {
                mode = .phone
            }
            if ProcessInfo.processInfo.arguments.contains("-sms-login") {
                mode = .phone
                phone = "13800138000"
                code = "123456"
                try? await Task.sleep(nanoseconds: 800_000_000)
                await auth.smsLogin(phone: phone, code: code)
            }
            #endif
        }
        .alert("本机号码一键登录", isPresented: $showOneTapInfo) {
            Button("使用手机号登录") {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { mode = .phone }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("一键登录需要接入运营商号码认证服务（移动/联通/电信），当前版本还没接入。先用手机号验证码登录，体验是一样的。")
        }
    }

    private var topBar: some View {
        HStack {
            Spacer()
            Button {
                auth.loginRequested = false
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white.opacity(0.72))
                    .frame(width: 34, height: 34)
                    .background(.white.opacity(0.08), in: Circle())
            }
            .buttonStyle(.plain)
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

            Text("大学四年的成长路线图")
                .font(.uniBody(13))
                .foregroundStyle(.white.opacity(0.5))
        }
    }

    private var choiceContent: some View {
        VStack(spacing: 12) {
            Button {
                showOneTapInfo = true
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "iphone.gen3")
                        .font(.system(size: 17, weight: .semibold))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("本机号码一键登录")
                            .font(.system(size: 16, weight: .semibold))
                        Text("运营商认证 · 即将接入")
                            .font(.uniLabel(10))
                            .opacity(0.55)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .bold))
                        .opacity(0.5)
                }
                .foregroundStyle(.black)
                .padding(.horizontal, 18)
                .padding(.vertical, 15)
                .background(.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            }
            .buttonStyle(.plain)

            Button {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { mode = .phone }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "number")
                        .font(.system(size: 16, weight: .semibold))
                    Text("手机号验证码登录")
                        .font(.system(size: 16, weight: .semibold))
                    Spacer()
                    Text("推荐")
                        .font(.uniLabel(9))
                        .foregroundStyle(.white.opacity(0.5))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 18)
                .padding(.vertical, 15)
                .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(.white.opacity(0.14), lineWidth: 1)
                )
            }
            .buttonStyle(.plain)

            socialRow
                .padding(.top, 8)
        }
    }

    private var phoneContent: some View {
        VStack(alignment: .leading, spacing: 14) {
            Button {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { mode = .choice }
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "chevron.left")
                    Text("返回")
                }
                .font(.uniLabel(12))
                .foregroundStyle(.white.opacity(0.55))
            }
            .buttonStyle(.plain)

            Text("手机号登录")
                .font(.system(size: 24, weight: .heavy))
                .foregroundStyle(.white)
            Text("未注册的手机号会自动创建账号")
                .font(.uniBody(12))
                .foregroundStyle(.white.opacity(0.45))

            HStack(spacing: 0) {
                Text("+86")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.horizontal, 14)
                Rectangle().fill(.white.opacity(0.12)).frame(width: 1, height: 22)
                TextField("", text: $phone, prompt: Text("请输入手机号").foregroundStyle(.white.opacity(0.35)))
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
                TextField("", text: $code, prompt: Text("6 位验证码").foregroundStyle(.white.opacity(0.35)))
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .onChange(of: code) { _, newValue in
                        code = String(newValue.filter(\.isNumber).prefix(6))
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
                .disabled(countdown > 0 || auth.isSendingCode || phone.count != 11)
            }

            #if DEBUG
            Text("本地测试验证码：123456")
                .font(.uniLabel(10))
                .foregroundStyle(.white.opacity(0.35))
            #endif

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
            .disabled(auth.isLoggingIn || phone.count != 11 || code.count != 6)
            .opacity(phone.count == 11 && code.count == 6 ? 1 : 0.5)
        }
    }

    private var socialRow: some View {
        VStack(spacing: 10) {
            HStack(spacing: 18) {
                socialButton(label: "微", name: "微信")
                socialButton(label: "Q", name: "QQ")
            }
            Text("第三方登录即将支持")
                .font(.uniLabel(10))
                .foregroundStyle(.white.opacity(0.3))
        }
        .padding(.top, 6)
    }

    private func socialButton(label: String, name: String) -> some View {
        VStack(spacing: 5) {
            Text(label)
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(.white.opacity(0.5))
                .frame(width: 46, height: 46)
                .background(.white.opacity(0.06), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.12), lineWidth: 1))
            Text(name)
                .font(.uniLabel(9))
                .foregroundStyle(.white.opacity(0.3))
        }
        .opacity(0.75)
    }

    private var footer: some View {
        VStack(spacing: 6) {
            Text("登录即代表同意《用户协议》与《隐私政策》")
                .font(.uniLabel(10))
                .foregroundStyle(.white.opacity(0.35))
            Text("UniPO · 让大学每一步都有答案")
                .font(.uniLabel(10))
                .foregroundStyle(.white.opacity(0.2))
        }
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
