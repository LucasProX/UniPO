// Export the selected 01 outline, with the P in brand blue.
// swift scripts/design/render-brand-assets.swift [output-directory]
import AppKit
import CoreText
import Foundation

let output = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "output/brand/2026-10-24-01-blue-p"
let source = "output/brand-exploration/2026-10-24/01-essential/mark.svg"
let fm = FileManager.default
try fm.createDirectory(atPath:output,withIntermediateDirectories:true)
let ink = NSColor(srgbRed:11.0/255,green:15.0/255,blue:23.0/255,alpha:1)
let blue = NSColor(srgbRed:47.0/255,green:107.0/255,blue:1,alpha:1)
let white = NSColor.white
let paper = NSColor(srgbRed:247.0/255,green:248.0/255,blue:251.0/255,alpha:1)
let mist = NSColor(srgbRed:233.0/255,green:237.0/255,blue:244.0/255,alpha:1)
let rgb = CGColorSpace(name:CGColorSpace.sRGB)!
func regex(_ pattern:String,_ s:String) -> [NSTextCheckingResult] {
    try! NSRegularExpression(pattern:pattern).matches(in:s,range:NSRange(location:0,length:(s as NSString).length))
}
func parsePath(_ d:String) -> CGPath {
    let tokens=regex("[MLHVCQZ]|[-+]?(?:[0-9]*\\.)?[0-9]+(?:[eE][-+]?[0-9]+)?",d).map { (d as NSString).substring(with:$0.range) }
    let path=CGMutablePath();var i=0;var cmd="";var point=CGPoint.zero
    func number() -> CGFloat {defer {i+=1};return CGFloat(Double(tokens[i])!)}
    while i<tokens.count {
        if tokens[i].rangeOfCharacter(from:.letters) != nil {cmd=tokens[i];i+=1}
        switch cmd {
        case "M":point=CGPoint(x:number(),y:number());path.move(to:point);cmd="L"
        case "L":point=CGPoint(x:number(),y:number());path.addLine(to:point)
        case "H":point.x=number();path.addLine(to:point)
        case "V":point.y=number();path.addLine(to:point)
        case "Q":let c=CGPoint(x:number(),y:number());point=CGPoint(x:number(),y:number());path.addQuadCurve(to:point,control:c)
        case "C":let c1=CGPoint(x:number(),y:number()),c2=CGPoint(x:number(),y:number());point=CGPoint(x:number(),y:number());path.addCurve(to:point,control1:c1,control2:c2)
        case "Z":path.closeSubpath();cmd=""
        default:fatalError("Unsupported command \(cmd)")
        }
    }
    return path
}
let sourceSVG=try String(contentsOfFile:source,encoding:.utf8)
let pathStrings=regex("\\bd=\"([^\"]+)\"",sourceSVG).map {(sourceSVG as NSString).substring(with:$0.range(at:1))}
precondition(pathStrings.count==2,"The selected 01 mark must have separate U and P paths")
let paths=pathStrings.map(parsePath)
func drawMark(_ ctx:CGContext,_ r:CGRect,_ dark:Bool=false,_ tinted:Bool=false) {
    ctx.saveGState();ctx.translateBy(x:r.minX,y:r.minY);ctx.scaleBy(x:r.width/1024,y:r.height/1024)
    for (i,p) in paths.enumerated() {
        ctx.setFillColor((tinted ? white : (i==1 ? blue : (dark ? white:ink))).cgColor)
        ctx.addPath(p);ctx.fillPath()
    }
    ctx.restoreGState()
}
func fill(_ ctx:CGContext,_ r:CGRect,_ color:NSColor,_ radius:CGFloat=0) {
    ctx.setFillColor(color.cgColor)
    if radius>0 {ctx.addPath(CGPath(roundedRect:r,cornerWidth:radius,cornerHeight:radius,transform:nil));ctx.fillPath()} else {ctx.fill(r)}
}
func bitmap(_ w:Int,_ h:Int,_ alpha:Bool=false,_ draw:(CGContext)->Void) -> CGImage {
    let info=alpha ? CGImageAlphaInfo.premultipliedLast.rawValue : CGImageAlphaInfo.noneSkipLast.rawValue
    let ctx=CGContext(data:nil,width:w,height:h,bitsPerComponent:8,bytesPerRow:0,space:rgb,bitmapInfo:info)!
    ctx.clear(CGRect(x:0,y:0,width:w,height:h));ctx.translateBy(x:0,y:CGFloat(h));ctx.scaleBy(x:1,y:-1)
    draw(ctx);return ctx.makeImage()!
}
func png(_ image:CGImage,_ filename:String) throws {
    let rep=NSBitmapImageRep(cgImage:image)
    try rep.representation(using:.png,properties:[:])!.write(to:URL(fileURLWithPath:output+"/"+filename))
}
func icon(_ ctx:CGContext,_ r:CGRect,_ dark:Bool=false,_ mask:Bool=false) {
    ctx.saveGState()
    if mask {ctx.addPath(CGPath(roundedRect:r,cornerWidth:r.width*0.222,cornerHeight:r.height*0.222,transform:nil));ctx.clip()}
    fill(ctx,r,dark ? ink:white);drawMark(ctx,r,dark);ctx.restoreGState()
}
func text(_ ctx:CGContext,_ s:String,_ x:CGFloat,_ y:CGFloat,_ size:CGFloat,_ weight:NSFont.Weight = .regular,_ color:NSColor=ink,_ width:CGFloat=1400) {
    ctx.saveGState();NSGraphicsContext.saveGraphicsState();NSGraphicsContext.current=NSGraphicsContext(cgContext:ctx,flipped:true)
    let paragraph=NSMutableParagraphStyle();paragraph.lineSpacing=size*0.24
    (s as NSString).draw(in:CGRect(x:x,y:y,width:width,height:size*8),withAttributes:[.font:NSFont.systemFont(ofSize:size,weight:weight),.foregroundColor:color,.paragraphStyle:paragraph])
    NSGraphicsContext.restoreGraphicsState();ctx.restoreGState()
}
func markSVG(_ dark:Bool=false,_ background:Bool=false,_ tinted:Bool=false)->String {
    let bg=background ? "<rect width=\"1024\" height=\"1024\" fill=\"\(dark ? "#0B0F17":"#FFFFFF")\"/>" : ""
    let shapes=pathStrings.enumerated().map {i,d in "<path d=\"\(d)\" fill=\"\(tinted ? "#FFFFFF":(i==1 ? "#2F6BFF":(dark ? "#FFFFFF":"#0B0F17")))\"/>"}.joined()
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1024\" height=\"1024\" viewBox=\"0 0 1024 1024\"><title>UniPO UP - \(dark ? "Dark":"Light")</title>\(bg)\(shapes)</svg>\n"
}
func save(_ s:String,_ name:String) throws {try s.write(toFile:output+"/"+name,atomically:true,encoding:.utf8)}
for dark in [false,true] {
    let name=dark ? "AppIcon-1024-dark.png":"AppIcon-1024.png"
    try png(bitmap(1024,1024) {icon($0,CGRect(x:0,y:0,width:1024,height:1024),dark)},name)
    try save(markSVG(dark),dark ? "unipo-mark-dark.svg":"unipo-mark.svg")
    try save(markSVG(dark,true),dark ? "AppIcon-dark.svg":"AppIcon-white.svg")
    for s in [29,60] {
        try png(bitmap(s,s) {icon($0,CGRect(x:0,y:0,width:s,height:s),dark)},"AppIcon-\(s)\(dark ? "-dark":"").png")
    }
}
try png(bitmap(1024,1024,true) {drawMark($0,CGRect(x:0,y:0,width:1024,height:1024),true,true)},"AppIcon-1024-tinted.png")
try save(markSVG(false,false,true),"unipo-mark-tinted.svg")

// Keep the PDF vector: each page is the unchanged 1024 outline on its own background.
var media=CGRect(x:0,y:0,width:1024,height:1024)
let consumer=CGDataConsumer(url:URL(fileURLWithPath:output+"/unipo-mark.pdf") as CFURL)!
let pdf=CGContext(consumer:consumer,mediaBox:&media,[kCGPDFContextTitle:"UniPO UP / Light and Dark",kCGPDFContextCreator:"UniPO"] as CFDictionary)!
for dark in [false,true] {
    pdf.beginPDFPage(nil);pdf.saveGState();pdf.translateBy(x:0,y:1024);pdf.scaleBy(x:1,y:-1)
    icon(pdf,media,dark);pdf.restoreGState();pdf.endPDFPage()
}
pdf.closePDF()

// Matching UniPO wordmark; convert every glyph to outlines before export.
func wordmarkPath() -> CGPath {
    let font=NSFont.systemFont(ofSize:220,weight:.semibold)
    let line=CTLineCreateWithAttributedString(NSAttributedString(string:"UniPO",attributes:[.font:font]))
    let all=CGMutablePath()
    for run in CTLineGetGlyphRuns(line) as! [CTRun] {
        let count=CTRunGetGlyphCount(run);var glyphs=Array(repeating:CGGlyph(),count:count);var positions=Array(repeating:CGPoint.zero,count:count)
        CTRunGetGlyphs(run,CFRange(location:0,length:0),&glyphs);CTRunGetPositions(run,CFRange(location:0,length:0),&positions)
        let attrs=CTRunGetAttributes(run) as NSDictionary
        let runFont=attrs[kCTFontAttributeName] as! CTFont
        for i in 0..<count {if let p=CTFontCreatePathForGlyph(runFont,glyphs[i],nil) {
            all.addPath(p,transform:CGAffineTransform(a:1,b:0,c:0,d:-1,tx:positions[i].x,ty:-positions[i].y))
        }}
    }
    let b=all.boundingBoxOfPath;let k=min(288/b.width,68/b.height)
    var t=CGAffineTransform(translationX:(320-b.width*k)/2,y:(96-b.height*k)/2).scaledBy(x:k,y:k).translatedBy(x:-b.minX,y:-b.minY)
    return all.copy(using:&t)!
}
func pathData(_ p:CGPath)->String {
    var parts:[String]=[]
    func n(_ x:CGFloat)->String {String(format:"%.4f",Double(x)).replacingOccurrences(of:"\\.?0+$",with:"",options:.regularExpression)}
    func pt(_ p:CGPoint)->String {"\(n(p.x)) \(n(p.y))"}
    p.applyWithBlock {ptr in let e=ptr.pointee
        switch e.type {
        case .moveToPoint:parts.append("M \(pt(e.points[0]))")
        case .addLineToPoint:parts.append("L \(pt(e.points[0]))")
        case .addQuadCurveToPoint:parts.append("Q \(pt(e.points[0])) \(pt(e.points[1]))")
        case .addCurveToPoint:parts.append("C \(pt(e.points[0])) \(pt(e.points[1])) \(pt(e.points[2]))")
        case .closeSubpath:parts.append("Z")
        @unknown default:break
        }
    }
    return parts.joined(separator:" ")
}
let wordmark=wordmarkPath()
for isWhite in [false,true] {
    let name=isWhite ? "white":"black",hex=isWhite ? "#FFFFFF":"#0B0F17"
    try save("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"320\" height=\"96\" viewBox=\"0 0 320 96\"><title>UniPO wordmark</title><path fill=\"\(hex)\" d=\"\(pathData(wordmark))\"/></svg>\n","unipo-wordmark-\(name).svg")
    for multiplier in [2,3] {
        try png(bitmap(320*multiplier,96*multiplier,true) {ctx in
            ctx.scaleBy(x:CGFloat(multiplier),y:CGFloat(multiplier));ctx.setFillColor((isWhite ? white:ink).cgColor);ctx.addPath(wordmark);ctx.fillPath()
        },"unipo-wordmark-\(name)@\(multiplier)x.png")
    }
}
try png(bitmap(2200,1380) {ctx in
    fill(ctx,CGRect(x:0,y:0,width:2200,height:1380),paper)
    text(ctx,"UniPO / 01 原点",64,37,34,.semibold)
    text(ctx,"P · 品牌蓝 #2F6BFF",1655,48,22,.medium)
    text(ctx,"白底 · 墨黑 U ＋ 品牌蓝 P",64,111,22,.medium)
    text(ctx,"墨黑底 · 白色 U ＋ 品牌蓝 P",1152,111,22,.medium)
    icon(ctx,CGRect(x:64,y:164,width:984,height:984))
    icon(ctx,CGRect(x:1152,y:164,width:984,height:984),true)
    for (i,dark) in [false,true].enumerated() {
        let x:CGFloat=i==0 ? 64:1152
        icon(ctx,CGRect(x:x,y:1210,width:60,height:60),dark,true)
        icon(ctx,CGRect(x:x+103,y:1210,width:29,height:29),dark,true)
        text(ctx,"60 pt",x,1290,15,.regular);text(ctx,"29 pt",x+103,1290,15,.regular)
        text(ctx,"1024 × 1024 / sRGB / 不透明",x+251,1230,19,.regular)
    }
},"UniPO-01-blue-p-preview.png")

try png(bitmap(1600,1400) {ctx in
    fill(ctx,CGRect(x:0,y:0,width:1600,height:1400),white)
    text(ctx,"UniPO / 标志使用规范",70,45,42,.semibold)
    text(ctx,"University + Post = UP",70,107,21,.regular)
    text(ctx,"01 原点 · P 品牌蓝",1198,64,18,.medium)
    fill(ctx,CGRect(x:70,y:156,width:1460,height:2),mist)
    text(ctx,"配色",70,191,26,.semibold)
    let colors:[(NSColor,String)]=[(ink,"墨黑 #0B0F17"),(blue,"品牌蓝 #2F6BFF"),(paper,"纸白 #F7F8FB"),(mist,"雾灰 #E9EDF4")]
    for (i,pair) in colors.enumerated() {
        let x:CGFloat=70+CGFloat(i)*184
        fill(ctx,CGRect(x:x,y:246,width:144,height:70),pair.0)
        text(ctx,pair.1,x,332,15,.regular)
    }
    text(ctx,"浅色版：白底 #FFFFFF，U 墨黑，P 品牌蓝。\n深色版：墨黑底，U 白色 #FFFFFF，P 品牌蓝。",70,389,22,.regular,ink,750)
    icon(ctx,CGRect(x:1020,y:207,width:220,height:220))
    icon(ctx,CGRect(x:1270,y:207,width:220,height:220),true)
    text(ctx,"字形与安全区",70,506,26,.semibold)
    text(ctx,"U、P 顶底对齐，保持原始字形、比例与字距。\nApp 图标中字组宽为画板的 75%，按轮廓居中；\n重要内容位于中心 80% 范围内。",70,564,22,.regular,ink,770)
    text(ctx,"独立标志四周至少留出一根 U 主竖笔的宽度\n（约 81 / 1024 画板）。图标使用既定留白。",70,688,22,.regular,ink,770)
    fill(ctx,CGRect(x:1044,y:492,width:410,height:410),paper)
    drawMark(ctx,CGRect(x:1044,y:492,width:410,height:410))
    ctx.setStrokeColor(mist.cgColor);ctx.setLineWidth(2);ctx.setLineDash(phase:0,lengths:[8,7]);ctx.stroke(CGRect(x:1085,y:533,width:328,height:328));ctx.setLineDash(phase:0,lengths:[])
    text(ctx,"虚线为中心 80% 安全区",1090,920,17,.regular)
    text(ctx,"最小尺寸",70,807,26,.semibold)
    text(ctx,"App 图标最小 29 × 29 pt；横版字标建议至少 80 pt 宽。\n按目标屏幕倍率导出，避免把小位图放大。",70,866,22,.regular,ink,850)
    icon(ctx,CGRect(x:70,y:972,width:60,height:60),false,true)
    icon(ctx,CGRect(x:173,y:972,width:29,height:29),false,true)
    text(ctx,"60 pt",70,1048,16,.regular);text(ctx,"29 pt",173,1048,16,.regular)
    text(ctx,"禁止用法",70,1112,26,.semibold)
    text(ctx,"不拉伸、旋转、换字形或改变字距；不自行改色；不添加渐变、描边、阴影或立体效果。\n不放在影响辨识的复杂背景上。满版图稿不预制圆角，圆角仅用于系统遮罩与预览。",70,1170,22,.regular,ink,1460)
    text(ctx,"PNG 为 sRGB；标准图标不透明。着色版与横版字标保留透明通道。SVG / PDF 的标志均为路径。",70,1321,17,.regular)
},"UniPO-brand-guidelines.png")

let metadata:[String:Any]=["selectedConcept":"01 Essential","source":source,"colors":["ink":"#0B0F17","blue":"#2F6BFF","white":"#FFFFFF"],"canvas":[1024,1024],"bounds":[128.0,281.979,768.0,460.042],"wordmarkCanvas":[320,96],"wordmarkRasterScales":[2,3]]
try JSONSerialization.data(withJSONObject:metadata,options:[.prettyPrinted,.sortedKeys,.withoutEscapingSlashes]).write(to:URL(fileURLWithPath:output+"/metadata.json"))
print("Exported selected 01: light, dark, tinted, outlined vectors, wordmarks and guidelines to \(output)")
