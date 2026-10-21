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
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundStyle(UniColor.ink)
                                    .lineLimit(1)
                                if post.author.role != "USER" {
                                    Image(systemName: "checkmark.seal.fill")
                                        .font(.system(size: 11))
                                        .foregroundStyle(UniColor.blue)
                                }
                            }
                            Text("Lv.\(post.author.level) \(post.author.levelTitle ?? "")")
                                .font(.uniLabel(11))
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
                    VStack(alignment: .leading, spacing: 8) {
                        Text(post.title)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(UniColor.ink)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                        Text(post.excerpt?.isEmpty == false ? post.excerpt! : post.content)
                            .font(.uniBody(13))
                            .foregroundStyle(UniColor.secondary)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                    }

                    if let cover = post.coverUrl, let url = AppConfig.absoluteURL(cover) {
                        AsyncImage(url: url) { phase in
                            if case .success(let image) = phase {
                                image.resizable().scaledToFill()
                            } else {
                                UniColor.mist
                            }
                        }
                        .frame(height: 168)
                        .frame(maxWidth: .infinity)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }

                    if !post.tagList.isEmpty {
                        HStack(spacing: 6) {
                            ForEach(post.tagList.prefix(3), id: \.self) { tag in
                                UniChip(tag)
                            }
                        }
                    }

                    HStack(spacing: 16) {
                        metric("heart", post.likeTotal)
                        metric("bubble.right", post.commentTotal)
                        metric("bookmark", post.favoriteTotal)
                        Spacer()
                        Text(RelativeTime.text(from: post.publishedAt ?? post.createdAt))
                            .font(.uniLabel(11))
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
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.white)
                .shadow(color: UniColor.ink.opacity(0.05), radius: 18, y: 8)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(UniColor.mist.opacity(0.6), lineWidth: 1)
        )
    }

    private func metric(_ icon: String, _ value: Int) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 12, weight: .semibold))
            Text("\(value)")
                .font(.uniLabel(12))
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
