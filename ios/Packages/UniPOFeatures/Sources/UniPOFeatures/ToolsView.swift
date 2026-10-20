import SwiftUI
import UniPOUI

struct ToolsView: View {
    @State private var preview: ToolItem?

    private let tools: [ToolItem] = [
        ToolItem(title: "图片转 PDF", subtitle: "报名材料、作业、证明", icon: "doc.richtext", tint: UniColor.blue),
        ToolItem(title: "PDF 合并拆分", subtitle: "材料整理、论文附件", icon: "doc.on.doc", tint: UniColor.ink),
        ToolItem(title: "PDF 压缩", subtitle: "绕过上传大小限制", icon: "arrow.down.doc", tint: UniColor.blueBright),
        ToolItem(title: "图片格式转换", subtitle: "HEIC / PNG / JPG / WebP", icon: "photo.on.rectangle.angled", tint: UniColor.inkSoft),
        ToolItem(title: "证件照尺寸", subtitle: "报名、考试、简历", icon: "person.crop.rectangle", tint: UniColor.blue)
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("工具")
                        .font(.system(size: 30, weight: .heavy))
                        .foregroundStyle(UniColor.ink)
                    Text("全部本地处理，文件不上传服务器")
                        .font(.uniBody(13))
                        .foregroundStyle(UniColor.secondary)
                }

                LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                    ForEach(tools) { tool in
                        Button { preview = tool } label: {
                            VStack(alignment: .leading, spacing: 12) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .fill(tool.tint.opacity(0.10))
                                        .frame(width: 44, height: 44)
                                    Image(systemName: tool.icon)
                                        .font(.system(size: 19, weight: .semibold))
                                        .foregroundStyle(tool.tint)
                                }
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(tool.title)
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundStyle(UniColor.ink)
                                    Text(tool.subtitle)
                                        .font(.uniLabel(11))
                                        .foregroundStyle(UniColor.secondary)
                                        .multilineTextAlignment(.leading)
                                }
                                Spacer(minLength: 0)
                                Text("每月 5 次免费")
                                    .font(.uniLabel(10))
                                    .foregroundStyle(UniColor.tertiary)
                            }
                            .frame(maxWidth: .infinity, minHeight: 150, alignment: .topLeading)
                            .padding(16)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 22, style: .continuous)
                                    .stroke(UniColor.mist.opacity(0.7), lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                HStack(spacing: 10) {
                    Image(systemName: "lock.shield")
                        .foregroundStyle(UniColor.blue)
                    Text("本地处理意味着断网也能用，文件不会离开你的手机。")
                        .font(.uniBody(12))
                        .foregroundStyle(UniColor.secondary)
                }
                .padding(14)
                .background(UniColor.blue.opacity(0.06), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .padding(.horizontal, 18)
            .padding(.top, 10)
            .padding(.bottom, 120)
        }
        .scrollIndicators(.hidden)
        .sheet(item: $preview) { tool in
            VStack(spacing: 16) {
                Image(systemName: tool.icon)
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(tool.tint)
                Text(tool.title)
                    .font(.system(size: 20, weight: .bold))
                Text("工具正在接入，先把这个入口留好。")
                    .font(.uniBody(14))
                    .foregroundStyle(UniColor.secondary)
                Button("知道了") { preview = nil }
                    .buttonStyle(UniPrimaryButtonStyle())
                    .padding(.top, 8)
            }
            .padding(24)
            .presentationDetents([.height(260)])
            .presentationCornerRadius(28)
        }
    }
}

struct ToolItem: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let icon: String
    let tint: Color
}
