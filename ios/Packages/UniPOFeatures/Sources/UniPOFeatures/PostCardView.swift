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
                    Text(post.title)
                        .uniFont(18, weight: .bold)
                        .foregroundStyle(UniColor.ink)
                        // 标题永远占两行高度，一行标题的卡片不会比两行的矮。
                        .lineLimit(2, reservesSpace: true)
                        .multilineTextAlignment(.leading)

                    bodyArea

                    tagRow
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

    /// 正文与图片区域。尺寸由一个隐藏的「两行摘要 + 168pt 图片」模板决定，
    /// 纯文字、单图、多图三种卡片因此一样高（随 Dynamic Type 一起缩放，彼此仍然一致）。
    private var bodyArea: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(" ").uniFont(13).lineLimit(2, reservesSpace: true)
            Color.clear.frame(height: Self.mediaHeight)
        }
        .hidden()
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(alignment: .topLeading) {
            if images.isEmpty {
                // 纯文字：在同样大的区域里放更多正文，固定字数并由区域高度截断。
                Text(String(post.content.prefix(Self.textOnlyLimit)))
                    .uniFont(15)
                    .foregroundStyle(UniColor.inkSoft)
                    .lineSpacing(4)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .clipped()
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    Text(summary)
                        .uniFont(13)
                        .foregroundStyle(UniColor.secondary)
                        .lineLimit(2, reservesSpace: true)
                        .multilineTextAlignment(.leading)
                    PostMediaGrid(urls: images, onTap: { _ in onOpen() })
                        .frame(height: Self.mediaHeight)
                }
            }
        }
    }

    /// 标签行同样固定占位：没有标签的卡片留出同样高度。
    private var tagRow: some View {
        HStack(spacing: 6) {
            if post.tagList.isEmpty {
                UniChip("标签").hidden()
            } else {
                ForEach(post.tagList.prefix(3), id: \.self) { tag in
                    UniChip(tag).lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipped()
    }

    static let mediaHeight: CGFloat = 168
    static let textOnlyLimit = 160

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
