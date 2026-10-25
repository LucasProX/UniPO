import SwiftUI
import UIKit
import UniPOCore
import UniPOUI

extension Notification.Name {
    /// object 为被删除帖子的 ID（Int64）；信息流和主页据此移除，不必整页刷新。
    static let unipoPostDeleted = Notification.Name("unipo.post.deleted")
    /// 本人发布成功；首页回到顶部刷新，让作者马上看到自己的 PO。
    static let unipoPostPublished = Notification.Name("unipo.post.published")
}

/// 举报的对象：用户、帖子或评论。
enum ReportTarget: Identifiable, Hashable {
    case user(uid: String, name: String)
    case post(id: Int64)
    case comment(id: Int64)

    var id: String {
        switch self {
        case .user(let uid, _): return "user-\(uid)"
        case .post(let id): return "post-\(id)"
        case .comment(let id): return "comment-\(id)"
        }
    }

    var reasonHeader: String {
        switch self {
        case .user(_, let name): return "举报 \(name) 的原因"
        case .post: return "举报这条 PO 的原因"
        case .comment: return "举报这条评论的原因"
        }
    }
}

/// 举报：固定原因 + 可选说明。提交成功只表示已受理，不承诺处理结果；重复举报同一对象不会重复建单。
struct ReportSheet: View {
    @Environment(\.dismiss) private var dismiss
    let target: ReportTarget

    init(target: ReportTarget) {
        self.target = target
    }

    init(uid: String, name: String) {
        self.target = .user(uid: uid, name: name)
    }

    @State private var category: ReportCategory?
    @State private var detail = ""
    @State private var isSending = false
    @State private var errorMessage: String?
    @State private var submitted = false

    var body: some View {
        NavigationStack {
            Group {
                if submitted {
                    VStack(spacing: 14) {
                        Image(systemName: "checkmark.shield.fill")
                            .uniFont(40).foregroundStyle(UniColor.blue)
                        Text("举报已提交").uniFont(20, weight: .bold).foregroundStyle(UniColor.ink)
                        Text("我们会尽快核实处理，处理期间内容可能仍然可见。你也可以在对方主页选择拉黑，不再看到 TA 的内容。")
                            .uniFont(13).foregroundStyle(UniColor.secondary)
                            .multilineTextAlignment(.center).padding(.horizontal, 30)
                        Button("完成") { dismiss() }
                            .buttonStyle(UniPrimaryButtonStyle()).padding(.horizontal, 40).padding(.top, 6)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    Form {
                        Section {
                            ForEach(ReportCategory.allCases) { item in
                                Button {
                                    category = item
                                } label: {
                                    HStack {
                                        Text(item.title).foregroundStyle(UniColor.ink)
                                        Spacer()
                                        if category == item {
                                            Image(systemName: "checkmark").foregroundStyle(UniColor.blue)
                                        }
                                    }
                                    .frame(minHeight: 36)
                                }
                            }
                        } header: {
                            Text(target.reasonHeader)
                        }
                        Section {
                            TextField("补充说明（可选，最多 500 字）", text: $detail, axis: .vertical)
                                .lineLimit(3...6)
                        } footer: {
                            Text("请勿填写手机号、密码等个人敏感信息。")
                        }
                        if let errorMessage {
                            Section { Text(errorMessage).foregroundStyle(UniColor.danger) }
                        }
                    }
                }
            }
            .navigationTitle("举报")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !submitted {
                    ToolbarItem(placement: .topBarLeading) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .topBarTrailing) {
                        if isSending { ProgressView() } else {
                            Button("提交") { Task { await submit() } }
                                .disabled(category == nil || detail.count > 500)
                        }
                    }
                }
            }
        }
    }

    private func submit() async {
        guard let category else { return }
        isSending = true
        errorMessage = nil
        defer { isSending = false }
        do {
            let note = detail.trimmingCharacters(in: .whitespacesAndNewlines)
            switch target {
            case .user(let uid, _): _ = try await APIClient.shared.reportUser(uid: uid, category: category, detail: note)
            case .post(let id): _ = try await APIClient.shared.reportPost(id: id, category: category, detail: note)
            case .comment(let id): _ = try await APIClient.shared.reportComment(id: id, category: category, detail: note)
            }
            submitted = true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "提交失败，请重试"
        }
    }
}

/// 系统分享面板；只有用户真的完成分享才回调，用于记分享次数。
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    let onComplete: () -> Void

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        controller.completionWithItemsHandler = { _, completed, _, _ in
            if completed { onComplete() }
        }
        return controller
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct ShareItem: Identifiable {
    let id = UUID()
    let text: String
}
