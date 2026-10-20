import SwiftUI
import PhotosUI
import UIKit
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
    @State private var pickerItem: PhotosPickerItem?
    @State private var imageURLs: [String] = []
    @State private var isUploading = false

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
            .onChange(of: pickerItem) { _, item in
                Task { await upload(item) }
            }
            .task(id: auth.isAuthenticated) {
                #if DEBUG
                if auth.isAuthenticated,
                   ProcessInfo.processInfo.arguments.contains("-upload-image"),
                   imageURLs.isEmpty {
                    await uploadGeneratedImage()
                }
                #endif
            }
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

                HStack(spacing: 10) {
                    PhotosPicker(selection: $pickerItem, matching: .images) {
                        HStack(spacing: 6) {
                            Image(systemName: "photo.on.rectangle.angled")
                            Text(imageURLs.isEmpty ? "添加图片" : "换一张")
                        }
                        .font(.uniLabel(12))
                        .foregroundStyle(UniColor.blue)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(UniColor.blue.opacity(0.08), in: Capsule())
                    }

                    if isUploading {
                        ProgressView().tint(UniColor.ink)
                    }

                    ForEach(imageURLs, id: \.self) { url in
                        AsyncImage(url: AppConfig.absoluteURL(url)) { phase in
                            if case .success(let image) = phase {
                                image.resizable().scaledToFill()
                            } else {
                                UniColor.mist
                            }
                        }
                        .frame(width: 44, height: 44)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                    Spacer()
                }

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

    #if DEBUG
    private func uploadGeneratedImage() async {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 600, height: 400))
        let image = renderer.image { context in
            UIColor(red: 0.18, green: 0.42, blue: 1, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 600, height: 400))
            let text = "UniPO" as NSString
            text.draw(at: CGPoint(x: 40, y: 170), withAttributes: [
                .font: UIFont.systemFont(ofSize: 56, weight: .bold),
                .foregroundColor: UIColor.white
            ])
        }
        guard let data = image.pngData() else { return }
        isUploading = true
        defer { isUploading = false }
        do {
            let result = try await APIClient.shared.uploadImage(data: data, filename: "test.png", mimeType: "image/png")
            imageURLs = [result.url]
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "图片上传失败"
        }
    }
    #endif

    private func upload(_ item: PhotosPickerItem?) async {
        guard let item else { return }
        isUploading = true
        defer { isUploading = false }
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                errorMessage = "读取图片失败"
                return
            }
            let result = try await APIClient.shared.uploadImage(data: data)
            imageURLs = [result.url]
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "图片上传失败"
        }
    }

    private func submit() async {
        isSubmitting = true
        errorMessage = nil
        do {
            _ = try await APIClient.shared.createPost(
                title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                content: content.trimmingCharacters(in: .whitespacesAndNewlines),
                board: board,
                images: imageURLs
            )
            dismiss()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "发布失败"
        }
        isSubmitting = false
    }
}
