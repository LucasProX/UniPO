import SwiftUI
import UniPOCore
import UniPOUI

struct ComposeSheet: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss

    @State private var board = "school"
    @State private var title = ""
    @State private var content = ""
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    private let boards = [("school", "校 PO"), ("college", "院 PO"), ("major", "专业 PO")]

    private var canPublish: Bool {
        !title.trimmingCharacters(in: .whitespaces).isEmpty
            && !content.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        NavigationStack {
            Group {
                if auth.isAuthenticated {
                    editor
                } else {
                    signedOut
                }
            }
            .navigationTitle("发一条校园 PO")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await submit() }
                    } label: {
                        if isSubmitting {
                            ProgressView()
                        } else {
                            Text("发布").fontWeight(.semibold)
                        }
                    }
                    .tint(canPublish ? UniColor.ink : UniColor.tertiary)
                    .disabled(!canPublish || isSubmitting)
                }
            }
        }
    }

    private var editor: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 8) {
                    ForEach(boards, id: \.0) { item in
                        Button {
                            board = item.0
                        } label: {
                            Text(item.1)
                                .font(.uniLabel(12))
                                .foregroundStyle(board == item.0 ? .white : UniColor.secondary)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(Capsule().fill(board == item.0 ? UniColor.ink : UniColor.paper))
                        }
                        .buttonStyle(.plain)
                    }
                }

                TextField("标题：一句话说清楚", text: $title)
                    .font(.system(size: 20, weight: .bold))
                    .padding(.vertical, 8)

                Divider()

                TextEditor(text: $content)
                    .font(.uniBody(15))
                    .frame(minHeight: 220)
                    .scrollContentBackground(.hidden)

                if let errorMessage {
                    Text(errorMessage)
                        .font(.uniLabel(12))
                        .foregroundStyle(UniColor.danger)
                }

                Text("附件功能仅对金V开放，后续开放 PDF 上传。")
                    .font(.uniLabel(11))
                    .foregroundStyle(UniColor.tertiary)
            }
            .padding(18)
        }
    }

    private var signedOut: some View {
        VStack(spacing: 16) {
            Text("登录后才能发 PO")
                .font(.system(size: 20, weight: .bold))
            Text("登录后可以发帖、评论、关注同学。")
                .font(.uniBody(13))
                .foregroundStyle(UniColor.secondary)
            Button("去登录") { dismiss() }
                .buttonStyle(UniPrimaryButtonStyle())
                .padding(.horizontal, 40)
        }
    }

    private func submit() async {
        isSubmitting = true
        errorMessage = nil
        do {
            _ = try await APIClient.shared.createPost(
                title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                content: content.trimmingCharacters(in: .whitespacesAndNewlines),
                board: board
            )
            dismiss()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "发布失败"
        }
        isSubmitting = false
    }
}
