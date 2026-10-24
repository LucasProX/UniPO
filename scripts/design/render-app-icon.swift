// 生成 App 图标：白底、墨黑 #0B0F17 的系统字体（SF Pro Semibold）小写 "up"。
// "up" 的字形外框（u 的顶到 p 的底、u 的左缘到 p 的右缘）较长的一边占图标边长的 3/4，
// 并按字形外框居中，而不是按整行文字框。
// 用法：swift scripts/design/render-app-icon.swift ios/UniPO/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png
import AppKit
import CoreText

let output = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon-1024.png"
let size = 1024
let coverage: CGFloat = 0.75

func line(pointSize: CGFloat) -> CTLine {
    let font = NSFont.systemFont(ofSize: pointSize, weight: .semibold)
    let attrs: [NSAttributedString.Key: Any] = [
        .font: font,
        .foregroundColor: CGColor(srgbRed: 11 / 255, green: 15 / 255, blue: 23 / 255, alpha: 1),
        .kern: -pointSize * 0.01
    ]
    return CTLineCreateWithAttributedString(NSAttributedString(string: "up", attributes: attrs))
}

// 先用参考字号量出字形外框，再按比例换算到目标字号。
let reference: CGFloat = 400
let referenceBounds = CTLineGetBoundsWithOptions(line(pointSize: reference), .useGlyphPathBounds)
let target = CGFloat(size) * coverage
let pointSize = reference * target / max(referenceBounds.width, referenceBounds.height)
let finalLine = line(pointSize: pointSize)
let bounds = CTLineGetBoundsWithOptions(finalLine, .useGlyphPathBounds)

let space = CGColorSpace(name: CGColorSpace.sRGB)!
let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                        space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
let side = CGFloat(size)
context.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
context.fill(CGRect(x: 0, y: 0, width: side, height: side))
context.textPosition = CGPoint(x: (side - bounds.width) / 2 - bounds.minX,
                               y: (side - bounds.height) / 2 - bounds.minY)
CTLineDraw(finalLine, context)

// App 图标不能带透明通道：输出不透明的 RGB PNG。
let rep = NSBitmapImageRep(cgImage: context.makeImage()!)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: output))
print(String(format: "font %.1fpt, glyph box %.0f x %.0f px", pointSize, bounds.width, bounds.height))
