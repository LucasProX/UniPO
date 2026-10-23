import SwiftUI
import UniPOCore
import UniPOUI

/// 学校 / 学院 / 专业 / 入学年份。深色用于新手引导，浅色用于之后的补充与修改。
struct GrowthBackgroundForm: View {
    @Bindable var store: GrowthBackgroundStore
    var dark = false

    private var primary: Color { dark ? .white : UniColor.ink }
    private var secondary: Color { dark ? .white.opacity(0.45) : UniColor.secondary }
    private var field: Color { dark ? .white.opacity(0.07) : .white }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            picker("入学年份", value: "\(store.entryYear) 级") {
                ForEach(store.entryYears, id: \.self) { year in
                    Button("\(year) 级") { store.entryYear = year }
                }
            }
            picker("学校", value: store.schoolName ?? (store.isLoading ? "加载中…" : "请选择学校")) {
                ForEach(store.schools) { school in
                    Button(school.name) { Task { await store.selectSchool(school.id) } }
                }
            }
            picker("学院（可跳过）", value: store.collegeName ?? "暂不选择") {
                Button("暂不选择") { Task { await store.selectCollege(nil) } }
                ForEach(store.colleges) { college in
                    Button(college.name) { Task { await store.selectCollege(college.id) } }
                }
            }
            picker("专业（可跳过）", value: store.majorName ?? "暂不选择") {
                Button("暂不选择") { store.selectMajor(nil) }
                ForEach(store.majors) { major in
                    Button(major.name) { store.selectMajor(major.id) }
                }
            }
            Text("入学年份决定你现在处在大学的哪个阶段；学校和专业决定你看到哪些校 PO 与专业 PO。之后可以在「成长」里修改。")
                .uniFont(12)
                .foregroundStyle(secondary)
                .fixedSize(horizontal: false, vertical: true)
            if let error = store.errorMessage {
                HStack(spacing: 10) {
                    Text(error).uniFont(12, weight: .semibold).foregroundStyle(dark ? .red.opacity(0.9) : UniColor.danger)
                    Button("重试") { Task { await store.load() } }
                        .uniFont(12, weight: .semibold).foregroundStyle(dark ? .white : UniColor.blue)
                }
            }
        }
        .task(id: ObjectIdentifier(store)) { if store.schools.isEmpty { await store.load() } }
    }

    private func picker<Content: View>(_ title: String, value: String,
                                       @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).uniFont(11, weight: .semibold).foregroundStyle(secondary)
            Menu {
                content()
            } label: {
                HStack {
                    Text(value)
                        .uniFont(16, weight: .semibold)
                        .foregroundStyle(primary)
                        .lineLimit(1)
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down")
                        .uniFont(12, weight: .semibold)
                        .foregroundStyle(secondary)
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 50)
                .background(field, in: RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
                .overlay {
                    if !dark {
                        RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous)
                            .stroke(UniColor.mist, lineWidth: 1)
                    }
                }
            }
            .accessibilityLabel(title)
            .accessibilityValue(value)
        }
    }
}

/// 已有账号补充或修改成长背景。
struct GrowthBackgroundSheet: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    @State private var store: GrowthBackgroundStore
    let onSaved: () -> Void

    init(profile: UserProfile?, onSaved: @escaping () -> Void) {
        _store = State(initialValue: GrowthBackgroundStore(profile: profile))
        self.onSaved = onSaved
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("你的成长背景")
                            .uniFont(24, weight: .heavy)
                            .foregroundStyle(UniColor.ink)
                        Text("按入学年份推算当前阶段，修改后已完成的任务记录会保留。")
                            .uniFont(13)
                            .foregroundStyle(UniColor.secondary)
                    }
                    GrowthBackgroundForm(store: store)
                }
                .padding(20)
            }
            .background(UniColor.paper)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if store.isSaving {
                        ProgressView()
                    } else {
                        Button("保存") {
                            Task {
                                guard let profile = await store.save() else { return }
                                auth.applyProfile(profile)
                                onSaved()
                                dismiss()
                            }
                        }
                        .disabled(!store.canSave)
                    }
                }
            }
        }
    }
}
