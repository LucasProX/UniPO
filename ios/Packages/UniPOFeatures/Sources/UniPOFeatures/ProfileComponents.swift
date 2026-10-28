import SwiftUI
import ImageIO
import UIKit
import UniPOCore
import UniPOUI

/// Shared by the owner's page and the public profile, so a saved photo looks the same to visitors.
struct ProfileBackdrop: View {
    let url: String?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                defaultBackdrop
                if let imageURL = AppConfig.absoluteURL(url), !(url?.isEmpty ?? true) {
                    RemoteImage(url: imageURL) { phase in
                        if case .success(let image) = phase {
                            image.resizable()
                                .scaledToFill()
                                .frame(width: geometry.size.width, height: geometry.size.height)
                                .clipped()
                                .overlay {
                                    LinearGradient(
                                        stops: [
                                            .init(color: UniColor.paper.opacity(0.44), location: 0),
                                            .init(color: UniColor.paper.opacity(0.70), location: 0.50),
                                            .init(color: UniColor.paper.opacity(0.98), location: 1)
                                        ],
                                        startPoint: .top, endPoint: .bottom
                                    )
                                }
                        }
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
        }
        .accessibilityHidden(true)
    }

    private var defaultBackdrop: some View {
        ZStack {
            LinearGradient(
                stops: [
                    .init(color: UniColor.paper, location: 0),
                    .init(color: Color(hex: 0xEEF4FF), location: 0.34),
                    .init(color: UniColor.paper, location: 1)
                ],
                startPoint: .top, endPoint: .bottom
            )
            RadialGradient(
                colors: [UniColor.blue.opacity(0.12), .clear],
                center: .topTrailing, startRadius: 0, endRadius: 300
            )
            RadialGradient(
                colors: [Color(hex: 0xFFD9E4).opacity(0.24), .clear],
                center: .bottomLeading, startRadius: 0, endRadius: 260
            )
        }
    }
}

struct ProfileSegmentedControl: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var slider
    @Binding private var selection: Int
    private let titles: [String]
    private let counts: [Int]

    init(selection: Binding<Int>, titles: [String], counts: [Int] = []) {
        _selection = selection
        self.titles = titles
        self.counts = counts
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(titles.indices, id: \.self) { index in
                Button {
                    guard selection != index else { return }
                    withAnimation(reduceMotion ? nil : .spring(response: 0.38, dampingFraction: 0.86)) {
                        selection = index
                    }
                } label: {
                    HStack(spacing: 5) {
                        Text(titles[index])
                            .uniFont(13, weight: selection == index ? .semibold : .medium)
                        if counts.indices.contains(index), counts[index] > 0 {
                            Text("\(counts[index])")
                                .uniFont(9, weight: .bold)
                                .monospacedDigit()
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(
                                    selection == index ? Color.white.opacity(0.18) : UniColor.mist.opacity(0.9),
                                    in: Capsule()
                                )
                        }
                    }
                    .foregroundStyle(selection == index ? .white : UniColor.secondary)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background {
                        if selection == index {
                            Capsule()
                                .fill(UniColor.ink)
                                .matchedGeometryEffect(id: "profile-selection", in: slider)
                        }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selection == index ? .isSelected : [])
                .accessibilityIdentifier("profile.tab.\(index)")
            }
        }
        .padding(4)
        .background(Color.white.opacity(0.86), in: Capsule())
        .overlay(Capsule().stroke(.white.opacity(0.95), lineWidth: 1))
        .shadow(color: UniColor.ink.opacity(0.05), radius: 12, y: 4)
    }
}

enum ProfileBackgroundImage {
    enum Failure: LocalizedError {
        case tooLarge, unsupported
        var errorDescription: String? {
            switch self {
            case .tooLarge: return "图片较大，请选择小于 25MB 的图片"
            case .unsupported: return "这张图片暂时无法处理，请换一张试试"
            }
        }
    }

    /// Decode a thumbnail instead of materialising the full camera image in memory.
    static func jpegData(from data: Data) throws -> Data {
        guard data.count <= 25 * 1024 * 1024 else { throw Failure.tooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 2048,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { throw Failure.unsupported }
        let image = UIImage(cgImage: thumbnail)
        for quality in [0.86, 0.72, 0.58] {
            if let result = image.jpegData(compressionQuality: quality), result.count <= 5 * 1024 * 1024 {
                return result
            }
        }
        throw Failure.tooLarge
    }
}
