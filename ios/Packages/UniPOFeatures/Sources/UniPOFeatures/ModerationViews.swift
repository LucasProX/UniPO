import SwiftUI
import UniPOCore
import UniPOUI

/// 审核队列：只给有 report.handle 授权的人（「我的」页的「审核」入口）。
/// 范围由服务端按授权过滤，客户端不做任何范围判断。操作说明见 docs/MODERATION-RUNBOOK.md。
struct ModerationQueueView: View {
    enum Tab: String, CaseIterable, Identifiable {
        case open, resolved, rejected
        var id: String { rawValue }
        var title: String {
            switch self {
            case .open: return "待处理"
            case .resolved: return "已处理"
            case .rejected: return "已驳回"
            }
        }
        var status: String? {
            switch self {
            case .open: return nil
            case .resolved: return "RESOLVED"
            case .rejected: return "REJECTED"
            }
        }
    }

    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    @State private var tab: Tab = .open
    @State private var cases: [ReportCase] = []
    @State private var nextBeforeId: Int64?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var generation = 0

    var body: some View {
        NavigationStack {
            List {
                Picker("范围", selection: $tab) {
                    ForEach(Tab.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 8, trailing: 0))

                if isLoading && cases.isEmpty {
                    UniLoadingState("加载中…").listRowBackground(Color.clear)
                } else if let errorMessage, cases.isEmpty {
                    UniErrorState(errorMessage) { Task { await load(reset: true) } }.listRowBackground(Color.clear)
                } else if cases.isEmpty {
                    UniEmptyState(icon: "checkmark.shield", title: tab == .open ? "没有待处理的单" : "还没有记录",
                                  message: tab == .open ? "用户举报、待审核的帖子和系统识别的可疑评论会出现在这里" : nil)
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(cases) { kase in
                        NavigationLink {
                            ReportCaseDetailView(caseID: kase.id) { Task { await load(reset: true) } }
                                .environment(auth)
                        } label: {
                            row(kase)
                        }
                        .onAppear {
                            if kase.id == cases.last?.id, nextBeforeId != nil { Task { await load(reset: false) } }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(UniColor.paper)
            .navigationTitle("审核")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("完成") { dismiss() } }
            }
            .refreshable { await load(reset: true) }
            .task(id: tab) { await load(reset: true) }
        }
    }

    private func row(_ kase: ReportCase) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                ModerationChip(text: kase.targetLabel, tint: kase.isPendingPost ? UniColor.gold : UniColor.blue)
                if kase.isSystemOnly {
                    ModerationChip(text: "系统识别", tint: UniColor.secondary)
                } else {
                    Text("\(kase.reportCount) 人举报").uniFont(11, weight: .semibold).foregroundStyle(UniColor.danger)
                }
                Spacer()
                Text(kase.status == "IN_PROGRESS" ? "\(kase.assigneeNickname ?? "有人")处理中" : (kase.resolutionLabel ?? kase.statusLabel))
                    .uniFont(11, weight: .semibold)
                    .foregroundStyle(kase.status == "IN_PROGRESS" ? UniColor.gold : UniColor.secondary)
                    .lineLimit(1)
            }
            Text(kase.snapshot?.replacingOccurrences(of: "\n", with: " ") ?? "（无内容摘录）")
                .uniFont(14)
                .foregroundStyle(UniColor.ink)
                .lineLimit(2)
            HStack {
                Text(kase.categorySummary).lineLimit(1)
                Spacer()
                Text(RelativeTime.text(from: kase.lastReportedAt))
            }
            .uniFont(11)
            .foregroundStyle(UniColor.tertiary)
        }
        .padding(.vertical, 4)
    }

    private func load(reset: Bool) async {
        generation += reset ? 1 : 0
        let requestGeneration = generation
        if reset { isLoading = true }
        errorMessage = nil
        defer { if requestGeneration == generation { isLoading = false } }
        do {
            let page = try await APIClient.shared.reportCases(status: tab.status, beforeId: reset ? nil : nextBeforeId)
            guard requestGeneration == generation else { return }
            cases = reset ? page.items : cases + page.items.filter { item in !cases.contains { $0.id == item.id } }
            nextBeforeId = page.nextBeforeId
        } catch {
            guard requestGeneration == generation else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "加载失败，请重试"
        }
    }
}

/// 一张处理单：内容摘录、每条举报、领取与处置。处置理由必填，会进审计。
struct ReportCaseDetailView: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    let caseID: Int64
    let onChange: () -> Void

    @State private var detail: ReportCaseDetail?
    @State private var errorMessage: String?
    @State private var isWorking = false
    @State private var pendingAction: CaseAction?
    @State private var reason = ""

    var body: some View {
        List {
            if let detail {
                let kase = detail.kase
                Section {
                    HStack(spacing: 6) {
                        ModerationChip(text: kase.targetLabel, tint: kase.isPendingPost ? UniColor.gold : UniColor.blue)
                        if !kase.isPendingPost, let status = kase.targetStatusLabel {
                            ModerationChip(text: status, tint: UniColor.secondary)
                        }
                        Spacer()
                        Text(kase.statusLabel).uniFont(12, weight: .semibold).foregroundStyle(UniColor.secondary)
                    }
                    if let name = kase.targetUserNickname {
                        LabeledContent(kase.targetType == "USER" ? "被举报人" : "作者", value: name)
                            .uniFont(14)
                    }
                    Text(kase.snapshot ?? "（无内容摘录）")
                        .uniFont(15)
                        .foregroundStyle(UniColor.ink)
                        .textSelection(.enabled)
                } header: {
                    Text("被举报的内容")
                } footer: {
                    Text("摘录是举报那一刻的内容；作者之后修改或删除，以这里为准。")
                }

                Section("举报记录（\(detail.reports.count)）") {
                    ForEach(detail.reports) { report in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(report.categoryLabel).uniFont(13, weight: .semibold).foregroundStyle(UniColor.ink)
                                Spacer()
                                Text(report.isSystem ? "系统识别" : "用户举报")
                                    .uniFont(11).foregroundStyle(UniColor.tertiary)
                            }
                            if let note = report.detail, !note.isEmpty {
                                Text(note).uniFont(13).foregroundStyle(UniColor.inkSoft)
                            }
                            Text(RelativeTime.text(from: report.createdAt)).uniFont(11).foregroundStyle(UniColor.tertiary)
                        }
                        .padding(.vertical, 2)
                    }
                }

                actionSection(kase)
            } else if let errorMessage {
                UniErrorState(errorMessage) { Task { await load() } }.listRowBackground(Color.clear)
            } else {
                UniLoadingState("加载中…").listRowBackground(Color.clear)
            }
        }
        .scrollContentBackground(.hidden)
        .background(UniColor.paper)
        .navigationTitle("处理单 #\(caseID)")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .sheet(item: $pendingAction) { action in
            if let kase = detail?.kase { reasonSheet(action, kase: kase) }
        }
        .alert("操作没有完成", isPresented: Binding(get: { errorMessage != nil && detail != nil },
                                                set: { if !$0 { errorMessage = nil } })) {
            Button("知道了", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    @ViewBuilder
    private func actionSection(_ kase: ReportCase) -> some View {
        let mine = kase.assigneeUid != nil && kase.assigneeUid == auth.profile?.publicUid
        if !kase.isOpen {
            Section("结果") {
                LabeledContent("处理结果", value: kase.resolutionLabel ?? kase.statusLabel)
                if let note = kase.resolutionNote { Text(note).uniFont(13).foregroundStyle(UniColor.inkSoft) }
                LabeledContent("时间", value: RelativeTime.text(from: kase.handledAt))
            }
        } else if kase.status == "PENDING" {
            Section {
                Button {
                    Task { await claim(kase) }
                } label: {
                    HStack {
                        Spacer()
                        if isWorking { ProgressView() } else { Text("领取这张单").uniFont(15, weight: .semibold) }
                        Spacer()
                    }
                }
                .disabled(isWorking)
            } footer: {
                Text("领取后其他审核员不能处理这张单。")
            }
        } else if mine {
            Section {
                ForEach(CaseAction.available(for: kase)) { action in
                    Button(role: action == .removeContent ? .destructive : nil) {
                        reason = ""
                        pendingAction = action
                    } label: {
                        Text(action.title(for: kase)).uniFont(15, weight: .semibold)
                    }
                    .disabled(isWorking)
                }
            } header: {
                Text("处置")
            } footer: {
                Text("举报人会收到结果通知；下架或隐藏时，作者会收到原因。")
            }
        } else {
            Section {
                Text("\(kase.assigneeNickname ?? "其他审核员") 正在处理这张单。")
                    .uniFont(14).foregroundStyle(UniColor.secondary)
            }
        }
    }

    private func reasonSheet(_ action: CaseAction, kase: ReportCase) -> some View {
        NavigationStack {
            Form {
                Section {
                    TextField("写下判断依据（必填，会记入审计）", text: $reason, axis: .vertical)
                        .lineLimit(3...6)
                } footer: {
                    Text("只写事实，不要写举报人信息。")
                }
                Section("常用理由") {
                    ForEach(presets(action), id: \.self) { preset in
                        Button(preset) { reason = preset }
                    }
                }
            }
            .navigationTitle(action.title(for: kase))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("取消") { pendingAction = nil } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("确认") {
                        let text = reason.trimmingCharacters(in: .whitespacesAndNewlines)
                        pendingAction = nil
                        Task { await resolve(kase, action: action, reason: text) }
                    }
                    .fontWeight(.semibold)
                    .disabled(reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func presets(_ action: CaseAction) -> [String] {
        switch action {
        case .removeContent: return ["引流或广告", "诈骗或虚假信息", "人身攻击或骚扰", "色情低俗", "泄露他人隐私"]
        case .approveContent: return ["正常二手转让", "正常组队或找搭子", "正常经验分享"]
        case .noViolation: return ["内容正常，未发现违规", "属于正常讨论或观点分歧"]
        case .handled: return ["已私下提醒作者", "已交给平台管理员处理账号"]
        }
    }

    private func load() async {
        errorMessage = nil
        do {
            detail = try await APIClient.shared.reportCase(id: caseID)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "加载失败，请重试"
        }
    }

    private func claim(_ kase: ReportCase) async {
        isWorking = true
        defer { isWorking = false }
        do {
            _ = try await APIClient.shared.claimReportCase(id: kase.id, version: kase.version)
            onChange()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "领取失败"
        }
        await load()
    }

    private func resolve(_ kase: ReportCase, action: CaseAction, reason: String) async {
        isWorking = true
        defer { isWorking = false }
        do {
            _ = try await APIClient.shared.resolveReportCase(id: kase.id, version: kase.version, action: action, reason: reason)
            onChange()
            dismiss()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "处置失败"
            await load()
        }
    }
}

struct ModerationChip: View {
    let text: String
    let tint: Color

    var body: some View {
        Text(text)
            .uniFont(11, weight: .semibold)
            .foregroundStyle(tint)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(tint.opacity(0.1), in: Capsule())
    }
}
