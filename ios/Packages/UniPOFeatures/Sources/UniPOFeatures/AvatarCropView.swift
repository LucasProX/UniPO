import SwiftUI
import PhotosUI
import UIKit
import UniPOUI

struct CropImage: Identifiable {
    let id = UUID()
    let image: UIImage
}

/// 方形裁剪 + 圆形预览；输出 800x800 PNG，上传后由 UniAvatar 圆形展示。
struct AvatarCropView: View {
    let image: UIImage
    let onCancel: () -> Void
    let onCrop: (UIImage) -> Void

    @State private var zoom: CGFloat = 1
    @State private var baseZoom: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var baseOffset: CGSize = .zero
    @State private var cropSide: CGFloat = 300

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width - 48, geo.size.height - 260)
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 22) {
                    HStack {
                        Button("取消") { onCancel() }
                            .foregroundStyle(.white.opacity(0.7))
                        Spacer()
                        Text("调整头像")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(.white)
                        Spacer()
                        Button("使用") { onCrop(render(side: side)) }
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    .padding(.horizontal, 24)

                    Spacer(minLength: 0)

                    ZStack {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                            .frame(width: side, height: side)
                            .scaleEffect(zoom)
                            .offset(offset)
                            .frame(width: side, height: side)
                            .clipped()
                            .clipShape(Circle())
                            .overlay(Circle().stroke(.white.opacity(0.25), lineWidth: 1))
                            .contentShape(Circle())
                            .gesture(dragGesture(side: side))
                            .simultaneousGesture(magnifyGesture(side: side))
                    }
                    .frame(width: side, height: side)
                    .onAppear { cropSide = side }

                    Text("双指缩放 · 拖动调整位置")
                        .font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.4))

                    Slider(value: $zoom, in: 1...4)
                        .tint(.white)
                        .padding(.horizontal, 40)
                        .onChange(of: zoom) { _, _ in clampOffset(side: side) }

                    Spacer(minLength: 0)
                }
                .padding(.vertical, 20)
            }
        }
        .preferredColorScheme(.dark)
    }

    private func dragGesture(side: CGFloat) -> some Gesture {
        DragGesture()
            .onChanged { value in
                let bound = max(0, side * (zoom - 1) / 2)
                offset = CGSize(
                    width: min(bound, max(-bound, baseOffset.width + value.translation.width)),
                    height: min(bound, max(-bound, baseOffset.height + value.translation.height))
                )
            }
            .onEnded { _ in baseOffset = offset }
    }

    private func magnifyGesture(side: CGFloat) -> some Gesture {
        MagnificationGesture()
            .onChanged { value in
                zoom = min(4, max(1, baseZoom * value))
            }
            .onEnded { _ in
                baseZoom = zoom
                clampOffset(side: side)
            }
    }

    private func clampOffset(side: CGFloat) {
        let bound = max(0, side * (zoom - 1) / 2)
        offset = CGSize(
            width: min(bound, max(-bound, offset.width)),
            height: min(bound, max(-bound, offset.height))
        )
        baseOffset = offset
    }

    private func render(side: CGFloat) -> UIImage {
        let output = CGSize(width: 800, height: 800)
        let baseScale = max(output.width / image.size.width, output.height / image.size.height)
        let drawSize = CGSize(width: image.size.width * baseScale * zoom,
                              height: image.size.height * baseScale * zoom)
        let factor = output.width / max(side, 1)
        let origin = CGPoint(
            x: (output.width - drawSize.width) / 2 + offset.width * factor,
            y: (output.height - drawSize.height) / 2 + offset.height * factor
        )
        let renderer = UIGraphicsImageRenderer(size: output)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: origin, size: drawSize))
        }
    }
}

/// 相册选择 + 裁剪；点一下头像即可换。
struct AvatarPickerButton<Label: View>: View {
    let onPicked: (UIImage) -> Void
    @ViewBuilder let label: () -> Label

    @State private var selection: PhotosPickerItem?
    @State private var cropImage: CropImage?
    @State private var isLoading = false

    var body: some View {
        PhotosPicker(selection: $selection, matching: .images) {
            label()
                .overlay(alignment: .bottomTrailing) {
                    Image(systemName: "camera.fill")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 24, height: 24)
                        .background(.black.opacity(0.7), in: Circle())
                        .overlay(Circle().stroke(.white.opacity(0.3), lineWidth: 1))
                        .offset(x: 2, y: 2)
                }
                .overlay {
                    if isLoading {
                        ProgressView().tint(.white)
                    }
                }
        }
        .buttonStyle(.plain)
        .onChange(of: selection) { _, newValue in
            guard let newValue else { return }
            Task {
                isLoading = true
                defer { isLoading = false }
                if let data = try? await newValue.loadTransferable(type: Data.self),
                   let image = UIImage(data: data) {
                    cropImage = CropImage(image: image)
                }
            }
        }
        .fullScreenCover(item: $cropImage) { crop in
            AvatarCropView(
                image: crop.image,
                onCancel: { cropImage = nil },
                onCrop: { image in
                    cropImage = nil
                    selection = nil
                    onPicked(image)
                }
            )
        }
    }
}
