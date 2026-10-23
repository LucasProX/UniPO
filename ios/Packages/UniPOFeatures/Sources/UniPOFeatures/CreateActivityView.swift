import SwiftUI
import UniPOCore
import UniPOUI

struct ActivityListTab: Identifiable {
    let id: String
}

/// 认证组织者发布官方活动。范围与奖励上限以服务端返回的组织者资料为准，最终仍由服务端校验。
struct CreateActivityView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    let profile: ActivityOrganizerProfile

    @State private var input = CreateActivityInput()
    @State private var limitCapacity = true
    @State private var capacityText = "50"
    @State private var colleges: [CollegeOption] = []
    @State private var majors: [MajorOption] = []
    @State private var isSubmitting = false
    @State private var errorMessage: String?
    @State private var published: ActivityView?

    private let categories = [("LECTURE", "讲座"), ("COMPETITION", "比赛"), ("VOLUNTEER", "志愿"),
                              ("SPORTS", "体育"), ("CULTURE", "文艺"), ("CLASS", "班级"), ("OTHER", "其他")]

    var body: some View {
        NavigationStack {
            Form {
                Section("基本信息") {
                    TextField("活动标题", text: $input.title)
                    Picker("类型", selection: $input.category) {
                        ForEach(categories, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    TextField("主办方（默认用你的名字）", text: $input.organizerName)
                    TextField("地点", text: $input.location)
                }
                Section("时间") {
                    DatePicker("开始", selection: $input.startsAt, in: Date()...)
                    DatePicker("结束", selection: $input.endsAt, in: input.startsAt...)
                    DatePicker("报名截止", selection: $input.signupDeadline, in: Date()...input.startsAt)
                }
                Section {
                    Picker("面向", selection: $input.audienceScope) {
                        ForEach(profile.allowedScopes, id: \.self) { Text(scopeTitle($0)).tag($0) }
                    }
                    if input.audienceScope == "COLLEGE" {
                        Picker("学院", selection: $input.audienceCollegeId) {
                            Text("请选择").tag(Int64?.none)
                            ForEach(allowedColleges) { Text($0.name).tag(Optional($0.id)) }
                        }
                    }
                    if input.audienceScope == "MAJOR" {
                        Picker("专业", selection: $input.audienceMajorId) {
                            Text("请选择").tag(Int64?.none)
                            ForEach(allowedMajors) { Text($0.name).tag(Optional($0.id)) }
                        }
                    }
                    Picker("年级", selection: $input.audienceEntryYear) {
                        Text("不限").tag(Int?.none)
                        ForEach(entryYears, id: \.self) { Text("\($0) 级").tag(Optional($0)) }
                    }
                } header: {
                    Text("报名范围")
                } footer: {
                    Text("本校所有同学都能看到这个活动，只有范围内的同学可以报名。")
                }
                Section("名额") {
                    Toggle("限制名额", isOn: $limitCapacity)
                    if limitCapacity {
                        TextField("名额", text: $capacityText).keyboardType(.numberPad)
                        Toggle("满员后允许候补", isOn: $input.waitlistEnabled)
                    }
                    Toggle("报名需要我审核", isOn: $input.requireApproval)
                }
                Section {
                    Stepper("经验 +\(input.rewardXp)", value: $input.rewardXp, in: 0...profile.maxRewardXp, step: 5)
                    Stepper("金币 +\(input.rewardCoins)", value: $input.rewardCoins, in: 0...profile.maxRewardCoins, step: 5)
                } header: {
                    Text("签到奖励")
                } footer: {
                    Text("你的身份每场最多可设 \(profile.maxRewardXp) 经验、\(profile.maxRewardCoins) 金币，签到成功后发放。")
                }
                Section("介绍与须知") {
                    TextField("活动介绍", text: $input.description, axis: .vertical).lineLimit(4...10)
                    TextField("报名须知（可选）", text: $input.notice, axis: .vertical).lineLimit(2...6)
                }
                if let errorMessage {
                    Section { Text(errorMessage).foregroundStyle(UniColor.danger) }
                }
            }
            .navigationTitle("发布活动")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    if isSubmitting { ProgressView() } else {
                        Button("发布") { Task { await submit() } }.disabled(!canSubmit)
                    }
                }
            }
            .task { await loadOptions() }
            .onChange(of: input.startsAt) { _, start in
                if input.endsAt <= start { input.endsAt = start.addingTimeInterval(2 * 3600) }
                if input.signupDeadline > start { input.signupDeadline = start.addingTimeInterval(-3600) }
            }
            .onChange(of: input.audienceScope) { _, _ in
                input.audienceCollegeId = profile.allowedScopes.first == "COLLEGE" ? profile.collegeId : nil
                input.audienceMajorId = profile.allowedScopes == ["MAJOR"] ? profile.majorId : nil
            }
            .alert("活动已发布", isPresented: Binding(get: { published != nil }, set: { if !$0 { dismiss() } })) {
                Button("好的") { dismiss() }
            } message: {
                Text("本校同学现在可以在「校园活动」里看到并报名。")
            }
        }
        .onAppear {
            input.audienceScope = profile.allowedScopes.first ?? "SCHOOL"
            if profile.allowedScopes.first == "COLLEGE" { input.audienceCollegeId = profile.collegeId }
            if profile.allowedScopes == ["MAJOR"] { input.audienceMajorId = profile.majorId }
        }
    }

    private var entryYears: [Int] {
        let year = Calendar(identifier: .gregorian).component(.year, from: Date())
        return Array((year - 4)...year).reversed()
    }

    private var allowedColleges: [CollegeOption] {
        profile.level == "COLLEGE" ? colleges.filter { $0.id == profile.collegeId } : colleges
    }

    private var allowedMajors: [MajorOption] {
        switch profile.level {
        case "MAJOR": return majors.filter { $0.id == profile.majorId }
        case "COLLEGE": return majors.filter { $0.collegeId == profile.collegeId }
        default: return majors
        }
    }

    private var canSubmit: Bool {
        !input.title.trimmingCharacters(in: .whitespaces).isEmpty
            && !input.location.trimmingCharacters(in: .whitespaces).isEmpty
            && !input.description.trimmingCharacters(in: .whitespaces).isEmpty
            && (!limitCapacity || Int(capacityText).map { $0 > 0 } == true)
            && (input.audienceScope != "COLLEGE" || input.audienceCollegeId != nil)
            && (input.audienceScope != "MAJOR" || input.audienceMajorId != nil)
    }

    private func scopeTitle(_ scope: String) -> String {
        switch scope {
        case "COLLEGE": return "学院"
        case "MAJOR": return "专业"
        default: return "全校"
        }
    }

    private func loadOptions() async {
        guard let schoolId = auth.profile?.schoolId else { return }
        colleges = (try? await APIClient.shared.collegeOptions(schoolId: schoolId)) ?? []
        majors = (try? await APIClient.shared.majorOptions(schoolId: schoolId, collegeId: nil)) ?? []
    }

    private func submit() async {
        var request = input
        request.capacity = limitCapacity ? Int(capacityText) : nil
        if !limitCapacity { request.waitlistEnabled = false }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }
        do {
            published = try await APIClient.shared.createActivity(request)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "发布失败，请检查后重试"
        }
    }
}
