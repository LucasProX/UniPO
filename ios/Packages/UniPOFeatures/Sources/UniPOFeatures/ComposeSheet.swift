import SwiftUI
import PhotosUI
import UIKit
import UniPOCore
import UniPOUI

struct ComposeSheet: View {
    @Environment(AuthStore.self) private var auth
    @Environment(\.dismiss) private var dismiss
    /// 从活动（签到后）或主线任务进入时带上的关联；用户可以去掉。
    var initialLink: PostLink? = nil
    var initialTitle: String = ""
    /// 从标签页进入时预填的标签（F09）。
    var initialTags: [String] = []
    /// 编辑已有的帖子（F13）：预填内容，板块不能改，保存后把新内容交给调用方。
    var editing: PostView? = nil
    var onSaved: (PostView) -> Void = { _ in }
    @State private var link: PostLink?
    @State private var linkInitialized = false
    @State private var pickingLink = false
    /// 打开时恢复了上次没发出去的草稿（F14）。
    @State private var restoredDraft = false

    @State private var board = "school"
    @State private var title = ""
    @State private var content = ""
    @State private var isSubmitting = false
    @State private var errorMessage: String?
    @State private var heldForReview = false
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var imageURLs: [String] = []
    @State private var tags: [String] = []
    @State private var tagDraft = ""
    @FocusState private var tagFocused: Bool
    @State private var isUploading = false
    @State private var uploadRequestID: UUID?
    @State private var submitRequestID: UUID?

    private var identityKey: String {
        "\(auth.authenticationRevision):\(auth.profile?.id ?? 0)"
    }

    private let boards = [("school", "校 PO"), ("college", "院 PO"), ("major", "专业 PO")]
    private let maxImages = 9
    private let maxTags = 5
    private let template = "遇到的问题：\n\n我的做法：\n\n结果怎么样：\n\n适合谁参考："

    /// 只有从底栏「发布」打开的空白发帖页用草稿；带着预设打开的、编辑已有帖子的都不读也不写，免得互相覆盖。
    private var usesDraft: Bool {
        editing == nil && initialLink == nil && initialTitle.isEmpty && initialTags.isEmpty
    }

    private var draft: ComposeDraft {
        ComposeDraft(board: board, title: title, content: content, tags: tags, images: imageURLs, link: link)
    }

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
            .navigationTitle(editing == nil ? "发一条校园 PO" : "编辑 PO")
            .onAppear {
                guard !linkInitialized else { return }
                linkInitialized = true
                if let editing {
                    board = editing.board
                    title = editing.title
                    content = editing.content
                    tags = editing.tagList
                    imageURLs = editing.images?.filter { !$0.isEmpty } ?? []
                    link = editing.link
                    return
                }
                link = initialLink
                if title.isEmpty { title = initialTitle }
                if tags.isEmpty { tags = initialTags }
                if usesDraft, let owner = auth.profile?.id, let saved = ComposeDraftStore.shared.load(owner: owner) {
                    board = saved.board
                    title = saved.title
                    content = saved.content
                    tags = saved.tags
                    imageURLs = saved.images
                    link = saved.link
                    restoredDraft = true
                }
            }
            .onChange(of: draft) { _, value in
                // 每次改动就存，关掉、切到后台、被系统回收都不会丢。
                guard usesDraft, linkInitialized, let owner = auth.profile?.id else { return }
                ComposeDraftStore.shared.save(value, owner: owner)
            }
            .sheet(isPresented: $pickingLink) {
                PostLinkPicker { link = $0 }
            }
            .onChange(of: pickerItems) { _, items in
                guard !items.isEmpty else { return }
                Task { await upload(items) }
            }
            .onChange(of: identityKey) { _, _ in
                uploadRequestID = nil
                submitRequestID = nil
                isUploading = false
                isSubmitting = false
                errorMessage = nil
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
            .alert("已提交审核", isPresented: $heldForReview) {
                Button("好的") { dismiss() }
            } message: {
                Text(editing == nil
                     ? "这条 PO 里有需要人工确认的内容，审核通过后会出现在信息流。你可以在「我的 → 作品」里看到它。"
                     : "修改后的内容需要人工确认，审核通过前只有你自己看得到这条 PO。")
            }
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
                            Text(editing == nil ? "发布" : "保存").fontWeight(.semibold)
                        }
                    }
                    .tint(canPublish ? UniColor.ink : UniColor.tertiary)
                    .disabled(!auth.isAuthenticated || !canPublish || isSubmitting || isUploading)
                }
            }
        }
    }

    private var editor: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if restoredDraft {
                    HStack(spacing: 8) {
                        Image(systemName: "doc.text").uniFont(12, weight: .semibold)
                        Text("已恢复上次没发出去的草稿").uniFont(12, weight: .semibold)
                        Spacer(minLength: 4)
                        Button("清空") { clearDraft() }
                            .uniFont(12, weight: .semibold)
                            .frame(minHeight: 32)
                    }
                    .foregroundStyle(UniColor.secondary)
                    .padding(.horizontal, 12)
                    .background(UniColor.paper, in: Capsule())
                }
                if let link {
                    HStack(spacing: 8) {
                        Image(systemName: link.icon)
                            .uniFont(12, weight: .semibold)
                        Text("\(link.label) · \(link.ref.title)")
                            .uniFont(12, weight: .semibold)
                            .lineLimit(1)
                        Spacer(minLength: 4)
                        Button {
                            self.link = nil
                        } label: {
                            Image(systemName: "xmark")
                                .uniFont(11, weight: .bold)
                                .frame(width: 44, height: 32)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("取消关联")
                    }
                    .foregroundStyle(UniColor.blue)
                    .padding(.leading, 12)
                    .background(UniColor.blue.opacity(0.08), in: Capsule())
                }
                HStack(spacing: 8) {
                    // 编辑时板块不能改：板块决定谁看得到，改了等于换一条帖子。
                    ForEach(editing == nil ? boards : boards.filter { $0.0 == board }, id: \.0) { item in
                        Button {
                            board = item.0
                        } label: {
                            Text(item.1)
                                .uniFont(12, weight: .semibold)
                                .foregroundStyle(board == item.0 ? .white : UniColor.secondary)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(Capsule().fill(board == item.0 ? UniColor.ink : UniColor.paper))
                        }
                        .buttonStyle(.plain)
                    }
                }

                TextField("标题：一句话说清楚", text: $title)
                    .uniFont(20, weight: .bold)
                    .padding(.vertical, 8)

                Divider()

                imageStrip

                TextEditor(text: $content)
                    .uniFont(15)
                    .frame(minHeight: 220)
                    .scrollContentBackground(.hidden)

                if let errorMessage {
                    Text(errorMessage)
                        .uniFont(12, weight: .semibold)
                        .foregroundStyle(UniColor.danger)
                }

                if content.isEmpty {
                    Button {
                        content = template
                    } label: {
                        Label("用经验模板写：问题、做法、结果、适合谁", systemImage: "list.bullet.rectangle")
                            .uniFont(12, weight: .semibold)
                            .foregroundStyle(UniColor.blue)
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                }

                tagEditor

                if link == nil {
                    Button { pickingLink = true } label: {
                        Label("关联活动或主线任务", systemImage: "link")
                            .uniFont(13, weight: .semibold)
                            .foregroundStyle(UniColor.blue)
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("让这条 PO 出现在活动或任务的经验里")
                }
            }
            .padding(18)
        }
    }

    private var imageStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(imageURLs, id: \.self) { url in
                    RemoteImage(url: AppConfig.absoluteURL(url)) { phase in
                        if case .success(let image) = phase {
                            image.resizable().scaledToFill()
                        } else {
                            UniColor.mist
                        }
                    }
                    .frame(width: 76, height: 76)
                    .clipShape(RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
                    .overlay(alignment: .topTrailing) {
                        Button {
                            imageURLs.removeAll { $0 == url }
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .uniFont(18)
                                .symbolRenderingMode(.palette)
                                .foregroundStyle(.white, UniColor.ink.opacity(0.7))
                                .frame(width: 32, height: 32)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("移除这张图片")
                    }
                }
                if isUploading {
                    ProgressView()
                        .frame(width: 76, height: 76)
                        .background(UniColor.paper, in: RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
                }
                if imageURLs.count < maxImages {
                    PhotosPicker(selection: $pickerItems, maxSelectionCount: maxImages - imageURLs.count,
                                 selectionBehavior: .ordered, matching: .images) {
                        VStack(spacing: 4) {
                            Image(systemName: "photo.badge.plus").uniFont(20, weight: .semibold)
                            Text("\(imageURLs.count)/\(maxImages)").uniFont(10, weight: .semibold)
                        }
                        .foregroundStyle(UniColor.blue)
                        .frame(width: 76, height: 76)
                        .background(UniColor.blue.opacity(0.07), in: RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
                    }
                    .disabled(isUploading)
                    .accessibilityLabel("添加图片，最多 \(maxImages) 张")
                }
            }
            .padding(.vertical, 2)
        }
    }

    private var tagEditor: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !tags.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(tags, id: \.self) { tag in
                            Button {
                                tags.removeAll { $0 == tag }
                            } label: {
                                HStack(spacing: 4) {
                                    Text("#\(tag)")
                                    Image(systemName: "xmark").uniFont(9, weight: .bold)
                                }
                                .uniFont(12, weight: .semibold)
                                .foregroundStyle(UniColor.ink)
                                .padding(.horizontal, 10).frame(minHeight: 32)
                                .background(UniColor.mist, in: Capsule())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("移除标签 \(tag)")
                        }
                    }
                }
            }
            if tags.count < maxTags {
                HStack(spacing: 8) {
                    Image(systemName: "number").foregroundStyle(UniColor.tertiary)
                    TextField("加标签，如 四级、选课（最多 \(maxTags) 个）", text: $tagDraft)
                        .uniFont(14)
                        .focused($tagFocused)
                        .submitLabel(.done)
                        .onSubmit(addTag)
                    if !tagDraft.trimmingCharacters(in: .whitespaces).isEmpty {
                        Button("添加", action: addTag).uniFont(12, weight: .semibold).foregroundStyle(UniColor.blue)
                    }
                }
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .background(UniColor.paper, in: RoundedRectangle(cornerRadius: UniRadius.control, style: .continuous))
            }
        }
    }

    private func addTag() {
        let cleaned = tagDraft.replacingOccurrences(of: "#", with: "")
            .replacingOccurrences(of: ",", with: "").replacingOccurrences(of: "，", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        tagDraft = ""
        guard !cleaned.isEmpty, tags.count < maxTags, !tags.contains(cleaned) else { return }
        tags.append(String(cleaned.prefix(12)))
    }

    private var signedOut: some View {
        VStack(spacing: 16) {
            Text(auth.isSessionRecoveryPending ? "正在确认登录状态" : "登录后才能发 PO")
                .uniFont(20, weight: .bold)
            if auth.isRestoring {
                ProgressView().tint(UniColor.blue)
            } else if auth.isSessionRecoveryPending {
                Text(auth.recoveryError ?? "请稍后重试")
                    .uniFont(13).foregroundStyle(UniColor.secondary)
                Button("重试") { Task { await auth.restore() } }
                    .buttonStyle(UniPrimaryButtonStyle()).padding(.horizontal, 40)
            } else {
                Text("登录后可以发帖、评论、关注同学。")
                    .uniFont(13)
                    .foregroundStyle(UniColor.secondary)
                Button("去登录") {
                    dismiss()
                    auth.requireLogin()
                }
                    .buttonStyle(UniPrimaryButtonStyle())
                    .padding(.horizontal, 40)
            }
        }
    }

    #if DEBUG
    private func uploadGeneratedImage() async {
        guard let userID = auth.profile?.id, !isUploading else { return }
        let revision = auth.authenticationRevision
        let requestID = UUID()
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
        uploadRequestID = requestID
        isUploading = true
        defer {
            if uploadRequestID == requestID { isUploading = false; uploadRequestID = nil }
        }
        do {
            let result = try await APIClient.shared.uploadImage(data: data, filename: "test.png", mimeType: "image/png")
            guard isCurrentIdentity(userID: userID, revision: revision), uploadRequestID == requestID else { return }
            imageURLs.append(result.url)
        } catch {
            guard isCurrentIdentity(userID: userID, revision: revision), uploadRequestID == requestID else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "图片上传失败"
        }
    }
    #endif

    /// 逐张上传，保持选择顺序。相册原图可能是 HEIC，统一转成最长边 2048 的 JPEG，
    /// 既能通过服务端文件头校验，也避免撞 5MB 上限。
    private func upload(_ items: [PhotosPickerItem]) async {
        guard let userID = auth.profile?.id, !isUploading else { return }
        let revision = auth.authenticationRevision
        let requestID = UUID()
        uploadRequestID = requestID
        isUploading = true
        errorMessage = nil
        defer {
            if uploadRequestID == requestID { isUploading = false; uploadRequestID = nil }
            pickerItems = []
        }
        var failed = 0
        for item in items {
            guard imageURLs.count < maxImages else { break }
            do {
                guard let data = try await item.loadTransferable(type: Data.self),
                      let jpeg = Self.normalizedJPEG(data) else { failed += 1; continue }
                guard isCurrentIdentity(userID: userID, revision: revision), uploadRequestID == requestID else { return }
                let result = try await APIClient.shared.uploadImage(data: jpeg)
                guard isCurrentIdentity(userID: userID, revision: revision), uploadRequestID == requestID else { return }
                if !imageURLs.contains(result.url) { imageURLs.append(result.url) }
            } catch {
                guard isCurrentIdentity(userID: userID, revision: revision), uploadRequestID == requestID else { return }
                failed += 1
            }
        }
        if failed > 0 { errorMessage = "有 \(failed) 张图片上传失败，可以重新添加" }
    }

    private static func normalizedJPEG(_ data: Data, maxSide: CGFloat = 2048) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let longest = max(image.size.width, image.size.height)
        let scale = longest > maxSide ? maxSide / longest : 1
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let rendered = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return rendered.jpegData(compressionQuality: 0.85)
    }

    private func submit() async {
        guard let userID = auth.profile?.id, canPublish, !isSubmitting, !isUploading else { return }
        let revision = auth.authenticationRevision
        let requestID = UUID()
        submitRequestID = requestID
        isSubmitting = true
        errorMessage = nil
        defer {
            if submitRequestID == requestID { isSubmitting = false; submitRequestID = nil }
        }
        do {
            if let editing {
                let updated = try await APIClient.shared.updatePost(
                    id: editing.id,
                    title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                    content: content.trimmingCharacters(in: .whitespacesAndNewlines),
                    tags: tags,
                    images: imageURLs,
                    link: link
                )
                guard isCurrentIdentity(userID: userID, revision: revision), submitRequestID == requestID else { return }
                onSaved(updated)
                NotificationCenter.default.post(name: .unipoPostUpdated, object: updated)
                if updated.status == "pending" && editing.status != "pending" { heldForReview = true } else { dismiss() }
                return
            }
            let created = try await APIClient.shared.createPost(
                title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                content: content.trimmingCharacters(in: .whitespacesAndNewlines),
                board: board,
                tags: tags,
                images: imageURLs,
                link: link
            )
            guard isCurrentIdentity(userID: userID, revision: revision), submitRequestID == requestID else { return }
            if usesDraft { ComposeDraftStore.shared.clear() }
            NotificationCenter.default.post(name: .unipoPostPublished, object: nil)
            if created.status == "pending" { heldForReview = true } else { dismiss() }
        } catch {
            guard isCurrentIdentity(userID: userID, revision: revision), submitRequestID == requestID else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "发布失败"
        }
    }

    private func clearDraft() {
        ComposeDraftStore.shared.clear()
        board = "school"
        title = ""
        content = ""
        tags = []
        imageURLs = []
        link = nil
        restoredDraft = false
    }

    private func isCurrentIdentity(userID: Int64, revision: UInt64) -> Bool {
        auth.profile?.id == userID && auth.authenticationRevision == revision
    }
}

extension PostLink {
    var icon: String {
        switch self {
        case .activity: return "flag.2.crossed"
        case .task: return "map"
        }
    }
}
