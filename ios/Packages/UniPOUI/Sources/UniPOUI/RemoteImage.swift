import SwiftUI
import UIKit
import UniPOCore

/// E04：图片加载与缓存。
///
/// 两层缓存：解码后的图放内存（NSCache，按像素字节计成本），下载的数据放磁盘（独立 URLCache，300MB）。
/// 后端带 `v=` 的媒体地址回 `max-age=30 天, immutable`，磁盘里有就不再联网，滑回来不会重新下载。
/// 同一地址同时只下载一次。只取公开图片，所以不带登录凭证，也不收发 Cookie（见 AGENTS.md「App 只走 Bearer」）。
public final class ImagePipeline: @unchecked Sendable {
    public static let shared = ImagePipeline()

    private let memory = NSCache<NSURL, UIImage>()
    private let session: URLSession
    private let lock = NSLock()
    private var inFlight: [URL: Task<UIImage, Error>] = [:]

    init() {
        memory.totalCostLimit = 64 * 1024 * 1024
        let configuration = URLSessionConfiguration.default
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpCookieStorage = nil
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent("UniPOImages", isDirectory: true)
        configuration.urlCache = URLCache(memoryCapacity: 8 * 1024 * 1024, diskCapacity: 300 * 1024 * 1024,
                                          directory: directory)
        configuration.timeoutIntervalForRequest = 30
        session = URLSession(configuration: configuration)
    }

    public func cachedImage(for url: URL) -> UIImage? {
        memory.object(forKey: url as NSURL)
    }

    public func image(for url: URL) async throws -> UIImage {
        if let cached = cachedImage(for: url) { return cached }
        let task: Task<UIImage, Error> = lock.withLock {
            if let running = inFlight[url] { return running }
            let created = Task { try await self.fetch(url) }
            inFlight[url] = created
            return created
        }
        return try await task.value
    }

    private func fetch(_ url: URL) async throws -> UIImage {
        defer { lock.withLock { inFlight[url] = nil } }
        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        guard let decoded = UIImage(data: data) else { throw URLError(.cannotDecodeContentData) }
        // 在后台线程把图解码好，免得第一次上屏时在主线程解码掉帧。
        let image = await decoded.byPreparingForDisplay() ?? decoded
        let cost = image.cgImage.map { $0.bytesPerRow * $0.height } ?? data.count
        memory.setObject(image, forKey: url as NSURL, cost: cost)
        return image
    }
}

/// 替代 `AsyncImage`：走 `ImagePipeline` 的缓存，并按显示尺寸向后端要缩小图。
/// 闭包拿到的仍是 `AsyncImagePhase`，调用处的写法和 `AsyncImage` 一样。
public struct RemoteImage<Content: View>: View {
    public enum Sizing: Equatable {
        /// 量自己被分到的格子（GeometryReader），按长边挑档位。只用在尺寸由外面给定的地方。
        case measured
        /// 调用方给出要显示的像素数，比如按原比例展示、高度由图片决定的地方。
        case pixels(CGFloat)
        /// 原图，比如全屏看图。
        case original
    }

    private let url: URL?
    private let sizing: Sizing
    private let content: (AsyncImagePhase) -> Content

    @Environment(\.displayScale) private var displayScale
    @State private var loaded: (url: URL, phase: AsyncImagePhase)?

    public init(url: URL?, sizing: Sizing = .measured, @ViewBuilder content: @escaping (AsyncImagePhase) -> Content) {
        self.url = url
        self.sizing = sizing
        self.content = content
    }

    public var body: some View {
        switch sizing {
        case .measured:
            GeometryReader { proxy in
                let points = max(proxy.size.width, proxy.size.height)
                // 垫一层 Color.clear：调用处加载中常常什么都不画（EmptyView），
                // 挂在 EmptyView 上的 .task 不会启动，图片就永远不会去下载。
                ZStack {
                    Color.clear
                    content(phase(for: points > 0 ? request(pixels: points * displayScale) : nil))
                }
                .frame(width: proxy.size.width, height: proxy.size.height)
                .task(id: points > 0 ? request(pixels: points * displayScale) : nil) {
                    await load(points > 0 ? request(pixels: points * displayScale) : nil)
                }
            }
        // 下面两种按内容自身尺寸排版，没法垫底：闭包在每个阶段都要画点东西（占位、进度），否则不会开始加载。
        case .pixels(let pixels):
            rendered(request(pixels: pixels))
        case .original:
            rendered(url)
        }
    }

    private func request(pixels: CGFloat) -> URL? {
        url.map { MediaURL.sized($0, pixels: Double(pixels)) }
    }

    private func rendered(_ request: URL?) -> some View {
        content(phase(for: request))
            .task(id: request) { await load(request) }
    }

    private func phase(for request: URL?) -> AsyncImagePhase {
        guard let request else { return .empty }
        if let image = ImagePipeline.shared.cachedImage(for: request) {
            return .success(Image(uiImage: image))
        }
        if let loaded, loaded.url == request { return loaded.phase }
        return .empty
    }

    private func load(_ request: URL?) async {
        guard let request, ImagePipeline.shared.cachedImage(for: request) == nil else { return }
        do {
            let image = try await ImagePipeline.shared.image(for: request)
            loaded = (request, .success(Image(uiImage: image)))
        } catch {
            if Task.isCancelled { return }
            loaded = (request, .failure(error))
        }
    }
}
