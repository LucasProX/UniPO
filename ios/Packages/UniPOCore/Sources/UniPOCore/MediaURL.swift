import Foundation

/// E04：给后端媒体地址挑缩小图档位。
///
/// 后端的 `size` 是「短边不超过多少像素」，客户端按格子的长边（点数 × 屏幕倍数）挑最近的一档，
/// 铺满也不会糊；比最大一档还大就用原图。只改写本服务的 `/api/media/{id}/content`，
/// 其他地址（对象存储直链、第三方图片）原样返回。
public enum MediaURL {
    public static let sizes = [360, 720, 1080]

    /// 允许略微放大：差一成以内肉眼看不出来，换来少下一档原图。
    static let tolerance = 1.1

    public static func sized(_ url: URL, pixels: Double) -> URL {
        guard pixels > 0, isServerMedia(url),
              let size = sizes.first(where: { Double($0) * tolerance >= pixels }),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return url
        }
        var items = (components.queryItems ?? []).filter { $0.name != "size" }
        items.append(URLQueryItem(name: "size", value: String(size)))
        components.queryItems = items
        return components.url ?? url
    }

    static func isServerMedia(_ url: URL) -> Bool {
        url.path.range(of: #"^/api(/v1)?/media/[0-9]+/content$"#, options: .regularExpression) != nil
    }
}
