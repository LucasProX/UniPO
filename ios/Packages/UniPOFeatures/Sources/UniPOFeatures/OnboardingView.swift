import SwiftUI
import PhotosUI
import UniPOCore
import UniPOUI
import UIKit

struct OnboardingView: View {
    @Environment(AuthStore.self) private var auth

    @State private var step = 0
    @State private var nickname = ""
    @State private var gender = ""
    @State private var birthYear = 2006
    @State private var background = GrowthBackgroundStore()
    @State private var showGender = true
    @State private var showAge = true
    @State private var showGrade = true
    @State private var zodiac = ""
    @State private var mbti = ""
    @State private var signature = ""
    @State private var avatarIndex = 0
    @State private var pickedAvatar: UIImage?
    @State private var photoItem: PhotosPickerItem?
    @State private var cropImage: CropImage?
    @State private var isSaving = false
    @State private var errorMessage: String?

    private let zodiacs = ["白羊", "金牛", "双子", "巨蟹", "狮子", "处女", "天秤", "天蝎", "射手", "摩羯", "水瓶", "双鱼"]
    private let mbtis = ["INTJ", "INTP", "ENTJ", "ENTP", "INFJ", "INFP", "ENFJ", "ENFP",
                         "ISTJ", "ISFJ", "ESTJ", "ESFJ", "ISTP", "ISFP", "ESTP", "ESFP"]
    private let avatars: [AvatarPreset] = [
        AvatarPreset(emoji: "🦊", color1: 0x2F6BFF, color2: 0x0B0F17),
        AvatarPreset(emoji: "🐼", color1: 0xE9EDF4, color2: 0x6B7688),
        AvatarPreset(emoji: "🐳", color1: 0x0A84FF, color2: 0x14264A),
        AvatarPreset(emoji: "🌙", color1: 0x8B5CF6, color2: 0x0B0F17),
        AvatarPreset(emoji: "⚡️", color1: 0xC9A227, color2: 0x1A2230),
        AvatarPreset(emoji: "🍀", color1: 0x22C55E, color2: 0x0B0F17)
    ]

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                topBar
                progressBar
                TabView(selection: $step) {
                    stepOne.tag(0)
                    stepBackground.tag(1)
                    stepTwo.tag(2)
                    stepThree.tag(3)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .animation(.spring(response: 0.42, dampingFraction: 0.88), value: step)

                bottomBar
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            prefill()
            #if DEBUG
            let args = ProcessInfo.processInfo.arguments
            if let index = args.firstIndex(of: "-ob-step"), args.indices.contains(index + 1),
               let value = Int(args[index + 1]) {
                step = min(lastStep, max(0, value))
            }
            #endif
        }
        .task {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-ob-finish") {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                await finish()
            }
            #endif
        }
        .fullScreenCover(item: $cropImage) { crop in
            AvatarCropView(
                image: crop.image,
                onCancel: { cropImage = nil },
                onCrop: { image in
                    cropImage = nil
                    pickedAvatar = image
                    photoItem = nil
                }
            )
        }
    }

    private var topBar: some View {
        HStack {
            Button {
                if step > 0 { withAnimation { step -= 1 } }
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white.opacity(step > 0 ? 0.8 : 0))
                    .frame(width: 36, height: 36)
                    .background(.white.opacity(step > 0 ? 0.08 : 0), in: Circle())
            }
            .buttonStyle(.plain)
            .disabled(step == 0)
            Spacer()
            Text("\(step + 1) / \(lastStep + 1)")
                .font(.uniLabel(11))
                .foregroundStyle(.white.opacity(0.35))
        }
        .padding(.horizontal, 18)
        .padding(.top, 8)
    }

    private var progressBar: some View {
        HStack(spacing: 6) {
            ForEach(0...lastStep, id: \.self) { index in
                Capsule()
                    .fill(index <= step ? .white : .white.opacity(0.15))
                    .frame(height: 3)
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 14)
        .animation(.easeInOut(duration: 0.25), value: step)
    }

    private var stepOne: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("先让同学认识你吧")
                        .font(.system(size: 26, weight: .heavy))
                        .foregroundStyle(.white)
                    Text("这些信息会帮你找到同校、同专业的人")
                        .font(.uniBody(13))
                        .foregroundStyle(.white.opacity(0.45))
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("用户名").font(.uniLabel(11)).foregroundStyle(.white.opacity(0.45))
                    TextField("", text: $nickname, prompt: Text("给自己起个名字").foregroundStyle(.white.opacity(0.3)))
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .frame(height: 54)
                        .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("性别").font(.uniLabel(11)).foregroundStyle(.white.opacity(0.45))
                    HStack(spacing: 8) {
                        choiceChip("男生", selected: gender == "male") { gender = "male" }
                        choiceChip("女生", selected: gender == "female") { gender = "female" }
                        choiceChip("其他", selected: gender == "other") { gender = "other" }
                    }
                    visibilityToggle("在主页展示性别", isOn: $showGender)
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("出生年份").font(.uniLabel(11)).foregroundStyle(.white.opacity(0.45))
                    Picker("出生年份", selection: $birthYear) {
                        ForEach(1990...2012, id: \.self) { year in
                            Text(String(year) + " 年").tag(year)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .frame(height: 50)
                    .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }

                VStack(alignment: .leading, spacing: 10) {
                    visibilityToggle("在主页展示年龄", isOn: $showAge)
                    visibilityToggle("在主页展示年级", isOn: $showGrade)
                }
            }
            .padding(24)
        }
        .scrollIndicators(.hidden)
    }

    private var stepBackground: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("你在哪所学校、哪一级？")
                        .font(.system(size: 26, weight: .heavy))
                        .foregroundStyle(.white)
                    Text("我们按入学年份推算你现在所处的阶段，给出这个阶段该做的事")
                        .font(.uniBody(13))
                        .foregroundStyle(.white.opacity(0.45))
                }
                GrowthBackgroundForm(store: background, dark: true)
            }
            .padding(24)
        }
        .scrollIndicators(.hidden)
    }

    private var stepTwo: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("你的校园人设是什么？")
                        .font(.system(size: 26, weight: .heavy))
                        .foregroundStyle(.white)
                    Text("星座、MBTI、一句话签名，让主页有点你自己")
                        .font(.uniBody(13))
                        .foregroundStyle(.white.opacity(0.45))
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("星座").font(.uniLabel(11)).foregroundStyle(.white.opacity(0.45))
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                        ForEach(zodiacs, id: \.self) { item in
                            choiceChip(item, selected: zodiac == item) { zodiac = item }
                                .frame(maxWidth: .infinity)
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("MBTI").font(.uniLabel(11)).foregroundStyle(.white.opacity(0.45))
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                        ForEach(mbtis, id: \.self) { item in
                            choiceChip(item, selected: mbti == item) { mbti = item }
                                .frame(maxWidth: .infinity)
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("一句话签名").font(.uniLabel(11)).foregroundStyle(.white.opacity(0.45))
                    TextEditor(text: $signature)
                        .font(.uniBody(14))
                        .foregroundStyle(.white)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 90)
                        .padding(10)
                        .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
            .padding(24)
        }
        .scrollIndicators(.hidden)
    }

    private var stepThree: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("挑选一个最喜欢的头像吧！")
                        .font(.system(size: 26, weight: .heavy))
                        .foregroundStyle(.white)
                    Text("选好之后，就可以正式进入 PO 世界了")
                        .font(.uniBody(13))
                        .foregroundStyle(.white.opacity(0.45))
                }

                HStack {
                    Spacer()
                    Group {
                        if let pickedAvatar {
                            Image(uiImage: pickedAvatar)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 120, height: 120)
                                .clipShape(Circle())
                        } else {
                            ZStack {
                                Circle()
                                    .fill(LinearGradient(colors: [Color(hex: avatars[avatarIndex].color1), Color(hex: avatars[avatarIndex].color2)],
                                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                                    .frame(width: 120, height: 120)
                                Text(avatars[avatarIndex].emoji)
                                    .font(.system(size: 64))
                            }
                        }
                    }
                    .overlay(Circle().stroke(.white.opacity(0.2), lineWidth: 1))
                    Spacer()
                }
                .padding(.vertical, 8)

                PhotosPicker(selection: $photoItem, matching: .images) {
                    Label(pickedAvatar == nil ? "从相册选一张" : "重新从相册选", systemImage: "photo.on.rectangle")
                        .font(.uniLabel(12))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .onChange(of: photoItem) { _, newValue in
                    guard let newValue else { return }
                    Task {
                        if let data = try? await newValue.loadTransferable(type: Data.self),
                           let image = UIImage(data: data) {
                            cropImage = CropImage(image: image)
                        }
                    }
                }

                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 14), count: 3), spacing: 14) {
                    ForEach(Array(avatars.enumerated()), id: \.offset) { index, avatar in
                        Button {
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                                avatarIndex = index
                                pickedAvatar = nil
                            }
                        } label: {
                            ZStack {
                                Circle()
                                    .fill(LinearGradient(colors: [Color(hex: avatar.color1), Color(hex: avatar.color2)],
                                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                                    .frame(width: 76, height: 76)
                                Text(avatar.emoji).font(.system(size: 40))
                            }
                            .overlay(
                                Circle().stroke(avatarIndex == index ? .white : .white.opacity(0.1),
                                                lineWidth: avatarIndex == index ? 2 : 1)
                            )
                            .scaleEffect(avatarIndex == index ? 1.05 : 1)
                        }
                        .buttonStyle(.plain)
                    }
                }

                if let errorMessage {
                    Text(errorMessage)
                        .font(.uniLabel(12))
                        .foregroundStyle(.red.opacity(0.9))
                }
            }
            .padding(24)
        }
        .scrollIndicators(.hidden)
    }

    private var bottomBar: some View {
        VStack(spacing: 8) {
            Button {
                if step < lastStep {
                    withAnimation { step += 1 }
                } else {
                    Task { await finish() }
                }
            } label: {
                Group {
                    if isSaving {
                        ProgressView().tint(.black)
                    } else {
                        Text(step == lastStep ? "进入 PO 世界" : "下一步")
                            .font(.system(size: 17, weight: .semibold))
                    }
                }
                .foregroundStyle(.black)
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background(.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(isSaving || !canAdvance)
            .opacity(canAdvance ? 1 : 0.4)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
    }

    private let lastStep = 3

    private var canAdvance: Bool {
        switch step {
        case 0: return !nickname.trimmingCharacters(in: .whitespaces).isEmpty
        case 1: return background.schoolID != nil
        default: return true
        }
    }

    private func visibilityToggle(_ title: String, isOn: Binding<Bool>) -> some View {
        HStack {
            Text(title)
                .font(.uniLabel(11))
                .foregroundStyle(.white.opacity(0.6))
            Spacer()
            Button {
                withAnimation(.spring(response: 0.25, dampingFraction: 0.8)) {
                    isOn.wrappedValue.toggle()
                }
            } label: {
                ZStack(alignment: isOn.wrappedValue ? .trailing : .leading) {
                    Capsule()
                        .fill(isOn.wrappedValue ? .white : .white.opacity(0.15))
                        .frame(width: 48, height: 28)
                    Circle()
                        .fill(isOn.wrappedValue ? .black : .white.opacity(0.7))
                        .frame(width: 22, height: 22)
                        .padding(3)
                }
            }
            .buttonStyle(.plain)
        }
    }

    private func choiceChip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.uniLabel(12))
                .foregroundStyle(selected ? .black : .white.opacity(0.6))
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(selected ? .white : .white.opacity(0.07), in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func prefill() {
        guard let profile = auth.profile else { return }
        nickname = profile.nickname
        if let value = profile.gender { gender = value }
        if let value = profile.birthYear { birthYear = value }
        background = GrowthBackgroundStore(profile: profile)
    }

    private func finish() async {
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }
        var avatarUrl = auth.profile?.avatarUrl
        // 相册照片优先压成 JPEG（800x800 通常 <300KB），避免大 PNG 撞 5MB 上传上限；
        // emoji 预设还是 PNG，保留透明边缘。
        let upload: (data: Data, filename: String, mimeType: String)?
        if let pickedAvatar {
            if let jpeg = pickedAvatar.jpegData(compressionQuality: 0.9) {
                upload = (jpeg, "avatar.jpg", "image/jpeg")
            } else if let png = pickedAvatar.pngData() {
                upload = (png, "avatar.png", "image/png")
            } else {
                upload = nil
            }
        } else if let png = renderAvatar(avatars[avatarIndex]) {
            upload = (png, "avatar.png", "image/png")
        } else {
            upload = nil
        }

        guard let upload else {
            errorMessage = "头像处理失败，请重新选择一张照片"
            return
        }

        do {
            let uploaded = try await APIClient.shared.uploadImage(
                data: upload.data,
                filename: upload.filename,
                mimeType: upload.mimeType
            )
            avatarUrl = uploaded.url
        } catch {
            errorMessage = "头像上传失败：\((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)"
            return
        }

        // 先保存成长背景：它决定年级与当前阶段，失败时停在最后一步并可重试。
        guard let saved = await background.save() else {
            errorMessage = background.errorMessage ?? "成长背景保存失败，请返回上一步检查"
            return
        }
        auth.applyProfile(saved)

        do {
            try await auth.completeOnboarding(OnboardingInput(
                nickname: nickname.trimmingCharacters(in: .whitespaces),
                gender: gender.isEmpty ? nil : gender,
                birthYear: birthYear,
                grade: nil,
                zodiac: zodiac.isEmpty ? nil : zodiac,
                mbti: mbti.isEmpty ? nil : mbti,
                signature: signature.isEmpty ? nil : signature,
                showGender: showGender,
                showAge: showAge,
                showGrade: showGrade,
                avatarUrl: avatarUrl
            ))
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "保存失败，请稍后再试"
        }
    }

    private func renderAvatar(_ preset: AvatarPreset) -> Data? {
        let size = CGSize(width: 600, height: 600)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            let colors = [UIColor(rgb: preset.color1).cgColor, UIColor(rgb: preset.color2).cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]),
               let ctx = UIGraphicsGetCurrentContext() {
                ctx.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
            }
            let text = preset.emoji as NSString
            let attributes: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 320)]
            let textSize = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: (size.width - textSize.width) / 2,
                                  y: (size.height - textSize.height) / 2),
                      withAttributes: attributes)
        }
        return image.pngData()
    }
}

private struct AvatarPreset {
    let emoji: String
    let color1: UInt32
    let color2: UInt32
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255,
                  alpha: 1)
    }
}
