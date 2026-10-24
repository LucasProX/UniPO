import SwiftUI
import UniPOCore
import UniPOUI

struct PostCardView: View {
    let post: PostView
    var onOpen: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                UserProfileLink(uid: post.author.uid, name: post.author.nickname) {
                    HStack(spacing: 10) {
                        UniAvatar(url: post.author.avatarUrl, name: post.author.nickname, size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 5) {
                                Text(post.author.nickname)
                                    .uniFont(14, weight: .bold)
                                    .foregroundStyle(UniColor.ink)
                                    .lineLimit(1)
                                if post.author.role != "USER" {
                                    Image(systemName: "checkmark.seal.fill")
                                        .uniFont(11)
                                        .foregroundStyle(UniColor.blue)
                                }
                            }
                            Text("Lv.\(post.author.level) \(post.author.levelTitle ?? "")")
                                .uniFont(11, weight: .semibold)
                                .foregroundStyle(UniColor.secondary)
                                .lineLimit(1)
                        }
                    }
                    .frame(minHeight: 44)
                }
                Spacer(minLength: 4)
                UniChip(boardLabel(post.board))
            }

            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 12) {
                    if images.isEmpty {
                        // 纯文字：按内容高度伸缩，最高不超过有图卡片（用隐藏的有图模板量出上限），超出部分省略。
                        CappedHeightLayout {
                            imageBody(nil).hidden()
                            textBody
                        }
                    } else {
                        imageBody(images)
                    }
                    HStack(spacing: 16) {
                        metric("heart", post.likeTotal)
                        metric("bubble.right", post.commentTotal)
                        metric("bookmark", post.favoriteTotal)
                        Spacer()
                        Text(RelativeTime.text(from: post.publishedAt ?? post.createdAt))
                            .uniFont(11, weight: .semibold)
                            .foregroundStyle(UniColor.tertiary)
                    }
                    .padding(.top, 2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                .fill(Color.white)
                .shadow(color: UniColor.ink.opacity(0.05), radius: 18, y: 8)
        )
        .overlay(
            RoundedRectangle(cornerRadius: UniRadius.card, style: .continuous)
                .stroke(UniColor.mist.opacity(0.6), lineWidth: 1)
        )
    }

    private var images: [String] { (post.images ?? []).filter { !$0.isEmpty } }

    private var summary: String {
        post.excerpt?.isEmpty == false ? post.excerpt! : post.content
    }

    /// 有图卡片的统一结构：标题占两行、摘要占两行、4:3 图片区、标签行占位。
    /// 传 nil 时画同样尺寸的空模板，用来给纯文字卡片量出高度上限。
    private func imageBody(_ urls: [String]?) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(post.title)
                .uniFont(18, weight: .bold)
                .foregroundStyle(UniColor.ink)
                .lineLimit(2, reservesSpace: true)
                .multilineTextAlignment(.leading)
            VStack(alignment: .leading, spacing: 10) {
                Text(summary)
                    .uniFont(13)
                    .foregroundStyle(UniColor.secondary)
                    .lineLimit(2, reservesSpace: true)
                    .multilineTextAlignment(.leading)
                Group {
                    if let urls {
                        PostMediaGrid(urls: urls, onTap: { _ in onOpen() })
                    } else {
                        Color.clear
                    }
                }
                .aspectRatio(4.0 / 3.0, contentMode: .fit)
                .frame(maxWidth: .infinity)
            }
            tagRow(reserve: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var textBody: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(post.title)
                .uniFont(18, weight: .bold)
                .foregroundStyle(UniColor.ink)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
            Text(post.content)
                .uniFont(15)
                .foregroundStyle(UniColor.inkSoft)
                .lineSpacing(4)
                .multilineTextAlignment(.leading)
                .truncationMode(.tail)
                // 高度不够时只压缩正文，标题和标签保持完整。
                .layoutPriority(-1)
            if !post.tagList.isEmpty || PostLinkChips.hasLinks(post) { tagRow(reserve: false) }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    private func tagRow(reserve: Bool) -> some View {
        HStack(spacing: 6) {
            // 卡片整体可点进详情，关联标签在这里只展示；详情页里的标签才能点开活动/任务。
            if let link = post.activity.map(PostLink.activity) ?? post.task.map(PostLink.task) {
                HStack(spacing: 3) {
                    Image(systemName: link.icon)
                    Text(link.ref.title).lineLimit(1)
                }
                .uniFont(11, weight: .semibold)
                .foregroundStyle(UniColor.blue)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(UniColor.blue.opacity(0.08), in: Capsule())
                .layoutPriority(1)
            }
            if post.tagList.isEmpty {
                if reserve && !PostLinkChips.hasLinks(post) { UniChip("标签").hidden() }
            } else {
                ForEach(post.tagList.prefix(3), id: \.self) { tag in
                    UniChip(tag).lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipped()
    }

    private func metric(_ icon: String, _ value: Int) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .uniFont(12, weight: .semibold)
            Text("\(value)")
                .uniFont(12, weight: .semibold)
        }
        .foregroundStyle(UniColor.secondary)
    }

    private func boardLabel(_ board: String) -> String {
        switch board {
        case "school": return "校 PO"
        case "college": return "院 PO"
        case "major": return "专业 PO"
        default: return "推荐"
        }
    }
}

/// 两个子视图：第一个是上限模板（不显示），第二个是内容。
/// 高度取 min(内容自然高度, 模板高度)，内容在这个高度内排版，放不下的文字由 Text 自己截断成省略号。
struct CappedHeightLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard subviews.count == 2 else { return .zero }
        let width = proposal.width ?? subviews[1].sizeThatFits(.unspecified).width
        let cap = subviews[0].sizeThatFits(ProposedViewSize(width: width, height: nil)).height
        let natural = subviews[1].sizeThatFits(ProposedViewSize(width: width, height: nil)).height
        return CGSize(width: width, height: min(cap, natural))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 2 else { return }
        subviews[0].place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
        subviews[1].place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }
}

enum RelativeTime {
    static func text(from raw: String?) -> String {
        guard let raw, let date = parse(raw) else { return "" }
        let seconds = Date().timeIntervalSince(date)
        if seconds < 60 { return "刚刚" }
        if seconds < 3600 { return "\(Int(seconds / 60)) 分钟前" }
        if seconds < 86400 { return "\(Int(seconds / 3600)) 小时前" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M/d HH:mm"
        return formatter.string(from: date)
    }

    private static func parse(_ raw: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = withFraction.date(from: raw) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: raw)
    }
}
