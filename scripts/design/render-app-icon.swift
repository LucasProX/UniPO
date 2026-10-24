// 生成 App 图标：白底、墨黑 #0B0F17 的系统字体（SF Pro Semibold）"UP"（University + Post）。
// 用大写：小写 p 有下伸部，u 和 p 的上下边永远对不齐。
// 字体里圆底的 U 会比平底的 P 略微下探（视觉补偿），这里把 U 的轮廓等比缩到与 P 同高，
// 让两个字母的顶边、底边严格对齐；整组字形的宽占图标边长的 3/4，
// 并按字形轮廓（不是整行文字框）居中，所以左右留白相等、上下留白相等。
// 用法：swift scripts/design/render-app-icon.swift ios/UniPO/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png
import AppKit
import CoreText

let output = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon-1024.png"
let size = 1024
let coverage: CGFloat = 0.75

let font = NSFont.systemFont(ofSize: 400, weight: .semibold) as CTFont
func glyphPath(_ character: String) -> (path: CGPath, advance: CGFloat) {
    var unichar = Array(character.utf16)
    var glyph = CGGlyph()
    CTFontGetGlyphsForCharacters(font, &unichar, &glyph, 1)
    var advance = CGSize.zero
    CTFontGetAdvancesForGlyphs(font, .horizontal, &glyph, &advance, 1)
    return (CTFontCreatePathForGlyph(font, glyph, nil)!, advance.width)
}

let u = glyphPath("U")
let p = glyphPath("P")
let pBox = p.path.boundingBoxOfPath
let uBox = u.path.boundingBoxOfPath

// U 等比缩到与 P 同高，并与 P 的顶边、底边对齐。
let uScale = pBox.height / uBox.height
var uTransform = CGAffineTransform(translationX: 0, y: pBox.minY)
    .scaledBy(x: uScale, y: uScale)
    .translatedBy(x: -uBox.minX, y: -uBox.minY)
let uPath = u.path.copy(using: &uTransform)!
let uWidth = uBox.width * uScale

// 保持字体原有的字距：U 右边空隙 + P 左边空隙，再收紧 1%。
let gap = (u.advance - uBox.maxX) * uScale + pBox.minX - CTFontGetSize(font) * 0.01
var pTransform = CGAffineTransform(translationX: uWidth + gap - pBox.minX, y: 0)
let pPath = p.path.copy(using: &pTransform)!

let combined = CGMutablePath()
combined.addPath(uPath)
combined.addPath(pPath)
let box = combined.boundingBoxOfPath

// 宽占 3/4，按轮廓外框居中。
let side = CGFloat(size)
let scale = side * coverage / box.width
var place = CGAffineTransform(translationX: (side - box.width * scale) / 2, y: (side - box.height * scale) / 2)
    .scaledBy(x: scale, y: scale)
    .translatedBy(x: -box.minX, y: -box.minY)
let final = combined.copy(using: &place)!

let space = CGColorSpace(name: CGColorSpace.sRGB)!
let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                        space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
context.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
context.fill(CGRect(x: 0, y: 0, width: side, height: side))
context.setFillColor(CGColor(srgbRed: 11 / 255, green: 15 / 255, blue: 23 / 255, alpha: 1))
context.addPath(final)
context.fillPath()

// App 图标不能带透明通道：输出不透明的 RGB PNG。
let rep = NSBitmapImageRep(cgImage: context.makeImage()!)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: output))
let placed = final.boundingBoxOfPath
print(String(format: "U scaled %.3f, glyph box %.0f x %.0f px at (%.0f, %.0f)",
             uScale, placed.width, placed.height, placed.minX, placed.minY))
