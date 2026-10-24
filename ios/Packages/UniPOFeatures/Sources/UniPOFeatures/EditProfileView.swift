import SwiftUI
import UniPOCore
import UniPOUI

/// 编辑资料：引导里填过的昵称、简介、性别、出生年份、星座、MBTI 和三个展示开关，之后都在这里改。
/// 只提交改动过的字段；学校、专业和入学年份走「成长背景」，年级由入学年份推算。
struct EditProfileView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss

    @State private var original: ProfileEdit
    @State private var edit: ProfileEdit
    @State private var isSaving = false
    @State private var errorMessage: String?
    @State private var confirmDiscard = false
    @State private var showBackground = false

    /// 作为单独弹出的页面时总显示「取消」；从设置里推入时用系统返回键，有改动才换成「取消」。
    private let modal: Bool

    init(profile: UserProfile, modal: Bool = false) {
        self.modal = modal
        let value = ProfileEdit(profile: profile)
        _original = State(initialValue: value)
        _edit = State(initialValue: value)
    }

    private var hasChanges: Bool { !edit.changes(from: original).isEmpty }

    var body: some View {
        List {
            Section {
                TextField("昵称", text: $edit.nickname)
                    .uniFont(15)
                    .textInputAutocapitalization(.never)
                VStack(alignment: .trailing, spacing: 4) {
                    TextField("一句话介绍自己", text: $edit.bio, axis: .vertical)
                        .uniFont(15)
                        .lineLimit(3...6)
                    Text("\(edit.trimmedBio.count)/\(ProfileEdit.bioLimit)")
                        .uniFont(11)
                        .foregroundStyle(edit.trimmedBio.count > ProfileEdit.bioLimit ? UniColor.danger : UniColor.tertiary)
                }
            } header: {
                Text("昵称与简介")
            }

            Section {
                Picker("性别", selection: Binding(get: { edit.gender ?? "" }, set: { edit.gender = $0.isEmpty ? nil : $0 })) {
                    if edit.gender == nil { Text("未填写").tag("") }
                    Text("男生").tag("male")
                    Text("女生").tag("female")
                    Text("其他").tag("other")
                }
                Picker("出生年份", selection: Binding(get: { edit.birthYear ?? 0 }, set: { edit.birthYear = $0 == 0 ? nil : $0 })) {
                    if edit.birthYear == nil { Text("未填写").tag(0) }
                    ForEach(ProfileEdit.birthYears(), id: \.self) { Text(String($0)).tag($0) }
                }
                Picker("星座", selection: $edit.zodiac) {
                    Text("不填").tag("")
                    ForEach(ProfileEdit.zodiacs, id: \.self) { Text($0 + "座").tag($0) }
                }
                Picker("MBTI", selection: $edit.mbti) {
                    Text("不填").tag("")
                    ForEach(ProfileEdit.mbtis, id: \.self) { Text($0).tag($0) }
                }
            } header: {
                Text("个人信息")
            } footer: {
                Text("性别和出生年份填写后不能清空，可以在下方选择不展示。")
            }

            Section {
                Toggle("在主页展示性别", isOn: $edit.showGender)
                Toggle("在主页展示年龄", isOn: $edit.showAge)
                Toggle("在主页展示年级", isOn: $edit.showGrade)
            } header: {
                Text("展示范围")
            } footer: {
                Text("关闭后，其他同学在你的主页上看不到对应信息。")
            }

            Section {
                Button { showBackground = true } label: {
                    HStack {
                        Text("学校、专业与入学年份").foregroundStyle(UniColor.ink)
                        Spacer()
                        Text(backgroundSummary)
                            .foregroundStyle(UniColor.tertiary)
                            .lineLimit(1)
                        Image(systemName: "chevron.right")
                            .uniFont(12, weight: .semibold)
                            .foregroundStyle(UniColor.tertiary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } header: {
                Text("成长背景")
            } footer: {
                Text("年级按入学年份推算，用来匹配你当前阶段的成长任务。")
            }
        }
        .uniFont(15)
        .scrollContentBackground(.hidden)
        .background(UniColor.paper)
        .navigationTitle("编辑资料")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(hasChanges)
        .toolbar {
            if hasChanges || modal {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { if hasChanges { confirmDiscard = true } else { dismiss() } }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if isSaving {
                    ProgressView()
                } else {
                    Button("保存") { Task { await save() } }
                        .fontWeight(.semibold)
                        .disabled(!hasChanges || edit.validationMessage != nil)
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if hasChanges, let message = edit.validationMessage {
                Text(message)
                    .uniFont(12, weight: .semibold)
                    .foregroundStyle(UniColor.danger)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(.ultraThinMaterial)
            }
        }
        .confirmationDialog("放弃这次修改？", isPresented: $confirmDiscard, titleVisibility: .visible) {
            Button("放弃修改", role: .destructive) { dismiss() }
            Button("继续编辑", role: .cancel) {}
        }
        .alert("保存失败", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("知道了", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
        .sheet(isPresented: $showBackground) {
            GrowthBackgroundSheet(profile: auth.profile) {}
                .environment(auth)
        }
    }

    private var backgroundSummary: String {
        guard let profile = auth.profile else { return "" }
        let parts = [profile.entryYear.map { "\($0) 级" }, profile.major ?? profile.college].compactMap { $0 }
            .filter { !$0.isEmpty }
        return parts.isEmpty ? "未填写" : parts.joined(separator: " · ")
    }

    private func save() async {
        let body = edit.changes(from: original)
        guard !body.isEmpty, edit.validationMessage == nil else { return }
        let owner = auth.profile?.id
        isSaving = true
        defer { isSaving = false }
        do {
            let updated = try await APIClient.shared.updateProfile(body)
            guard auth.profile?.id == owner else { return }
            auth.applyProfile(updated)
            original = ProfileEdit(profile: updated)
            edit = original
            dismiss()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "请稍后重试"
        }
    }
}
