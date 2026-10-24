// UniPO brand exploration. Source outlines, RGB PNGs and presentation boards.
// Run: swift scripts/design/explore-brand.swift output/brand-exploration/2026-10-24
import AppKit
import CoreText
import Foundation

let outputRoot = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "output/brand-exploration/2026-10-24"
let fm = FileManager.default
try fm.createDirectory(atPath: outputRoot, withIntermediateDirectories: true)
let ink = NSColor(srgbRed: 11/255, green: 15/255, blue: 23/255, alpha: 1)
let blue = NSColor(srgbRed: 47/255, green: 107/255, blue: 1, alpha: 1)
let paper = NSColor(srgbRed: 247/255, green: 248/255, blue: 251/255, alpha: 1)
let mist = NSColor(srgbRed: 233/255, green: 237/255, blue: 244/255, alpha: 1)
let white = NSColor.white
let muted = ink.withAlphaComponent(0.52)
let rgb = CGColorSpace(name: CGColorSpace.sRGB)!

struct Shape { var path: CGPath; var accent: Bool = false; var evenOdd: Bool = false }
struct Concept {
    var id: String; var name: String; var english: String; var rationale: String
    var group: String; var strengths: String; var caution: String; var shapes: [Shape]
}
func path(_ draw: (CGMutablePath) -> Void) -> CGPath { let p = CGMutablePath(); draw(p); return p }
func move(_ p: CGMutablePath, _ x: CGFloat, _ y: CGFloat) { p.move(to: CGPoint(x:x,y:y)) }
func line(_ p: CGMutablePath, _ x: CGFloat, _ y: CGFloat) { p.addLine(to: CGPoint(x:x,y:y)) }
func curve(_ p: CGMutablePath, _ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat, _ x: CGFloat, _ y: CGFloat) {
    p.addCurve(to:CGPoint(x:x,y:y),control1:CGPoint(x:x1,y:y1),control2:CGPoint(x:x2,y:y2))
}
func rect(_ x: CGFloat,_ y: CGFloat,_ w: CGFloat,_ h: CGFloat) -> CGPath { CGPath(rect:CGRect(x:x,y:y,width:w,height:h),transform:nil) }
func circle(_ x: CGFloat,_ y: CGFloat,_ r: CGFloat) -> CGPath { CGPath(ellipseIn:CGRect(x:x-r,y:y-r,width:2*r,height:2*r),transform:nil) }
func transformed(_ p: CGPath,_ t: CGAffineTransform) -> CGPath { var a=t; return p.copy(using:&a)! }
func fit(_ shapes: [Shape], width: CGFloat = 768, maxHeight: CGFloat = 768) -> [Shape] {
    var bounds = CGRect.null
    for s in shapes { bounds = bounds.union(s.path.boundingBoxOfPath) }
    let k = min(width/bounds.width,maxHeight/bounds.height)
    let t = CGAffineTransform(translationX:(1024-bounds.width*k)/2,y:(1024-bounds.height*k)/2)
        .scaledBy(x:k,y:k).translatedBy(x:-bounds.minX,y:-bounds.minY)
    return shapes.map { Shape(path:transformed($0.path,t),accent:$0.accent,evenOdd:$0.evenOdd) }
}
func sfGlyph(_ letter: String,_ weight: NSFont.Weight = .semibold,_ condensed: Bool = false) -> CGPath {
    var f = NSFont.systemFont(ofSize:500,weight:weight)
    if condensed { f = NSFontManager.shared.convert(f,toHaveTrait:.condensedFontMask) }
    let font=f as CTFont; var chars=Array(letter.utf16); var glyph=CGGlyph()
    CTFontGetGlyphsForCharacters(font,&chars,&glyph,1)
    let p=CTFontCreatePathForGlyph(font,glyph,nil)!, b=p.boundingBoxOfPath
    return transformed(p,CGAffineTransform(a:1,b:0,c:0,d:-1,tx:-b.minX,ty:b.maxY))
}
func sfPair() -> [Shape] {
    let u=sfGlyph("U"),p=sfGlyph("P"); let ub=u.boundingBoxOfPath,pb=p.boundingBoxOfPath
    let k=pb.height/ub.height
    return fit([Shape(path:transformed(u,CGAffineTransform(scaleX:k,y:k))),
                Shape(path:transformed(p,CGAffineTransform(translationX:ub.width*k+48,y:0)))])
}
func geometricU(_ x: CGFloat = 0,_ width: CGFloat = 350,_ height: CGFloat = 540,_ thickness: CGFloat = 100,_ round: CGFloat = 158) -> CGPath {
    path { p in
        move(p,x,0);line(p,x+thickness,0);line(p,x+thickness,height-round)
        curve(p,x+thickness,height-thickness,x+thickness,height-thickness,x+width/2,height-thickness)
        curve(p,x+width-thickness,height-thickness,x+width-thickness,height-thickness,x+width-thickness,height-round)
        line(p,x+width-thickness,0);line(p,x+width,0);line(p,x+width,height-round)
        curve(p,x+width,height-54,x+width-54,height,x+width/2,height)
        curve(p,x+54,height,x,height-54,x,height-round);p.closeSubpath()
    }
}
func geometricP(_ x: CGFloat,_ width: CGFloat = 350,_ height: CGFloat = 540,_ thickness: CGFloat = 100,_ bowl: CGFloat = 328) -> CGPath {
    path { p in
        move(p,x,0);line(p,x+width-bowl/2,0)
        curve(p,x+width-45,0,x+width,45,x+width,bowl/2)
        curve(p,x+width,bowl-45,x+width-45,bowl,x+width-bowl/2,bowl)
        line(p,x+thickness,bowl);line(p,x+thickness,height);line(p,x,height);p.closeSubpath()
        move(p,x+thickness,thickness);line(p,x+width-bowl/2,thickness)
        curve(p,x+width-thickness+3,thickness,x+width-thickness,thickness+25,x+width-thickness,bowl/2)
        curve(p,x+width-thickness,bowl-thickness-25,x+width-thickness+3,bowl-thickness,x+width-bowl/2,bowl-thickness)
        line(p,x+thickness,bowl-thickness);p.closeSubpath()
    }
}
var concepts:[Concept]=[
    Concept(id:"01",name:"原点",english:"Essential",rationale:"University 与 Post 化成清晰的 UP，以原生字体的比例与准确留白，建立安静、可信的第一印象。",group:"A",strengths:"识别直接，适合长期使用",caution:"个性最克制，需由产品体验积累辨识度",shapes:sfPair()),
    Concept(id:"02",name:"圆方",english:"Soft Geometry",rationale:"以同一组圆角、笔画和负空间重绘 U 与 P，让成长与表达拥有一致而温和的秩序。",group:"A",strengths:"几何统一，亲近但不幼态",caution:"方圆比例需要在真机上继续微调",shapes:fit([Shape(path:geometricU()),Shape(path:geometricP(402),evenOdd:true)])),
    Concept(id:"03",name:"联结",english:"Common Ground",rationale:"让清晰的 U 与 P 在底部轻轻相连，表达个人成长与同校经验彼此承接，连接本身就是品牌记忆。",group:"A",strengths:"保留 UP 阅读，以一处短桥形成记忆",caution:"连接处比独立字标更厚重",shapes:[]),
    Concept(id:"04",name:"书刊",english:"Editorial",rationale:"以高而修长的 UP、平整顶底和舒展字距呈现成长档案的书刊感，清醒而有骨架。",group:"A",strengths:"纵向张力鲜明，带有内容品牌气质",caution:"比其他 UP 方案更严肃",shapes:fit([Shape(path:geometricU(0,298,630,80,145)),Shape(path:geometricP(380,300,630,80,330),evenOdd:true)])),
    Concept(id:"05",name:"新句",english:"A New Entry",rationale:"以微微张开的人文 UP 和一枚蓝色句点，表达开放的校园视野：每一次 PO，都是成长的新记录。",group:"A",strengths:"字形舒展，微小蓝点成为记录的落款",caution:"蓝点是次级记忆，不承担字母识别",shapes:[])
]
let linkedU=path { p in
    move(p,0,0);line(p,96,0);line(p,96,340)
    curve(p,96,420,121,444,175,444);curve(p,229,444,254,420,254,340)
    line(p,254,0);line(p,350,0);line(p,350,540);line(p,175,540)
    curve(p,60,540,0,480,0,340);p.closeSubpath()
}
concepts[2].shapes=fit([Shape(path:linkedU),Shape(path:geometricP(396,350,540,96,330),evenOdd:true),Shape(path:rect(340,466,66,74))])
let humanU=path { p in
    move(p,0,0);line(p,94,0);line(p,112,320)
    curve(p,115,394,139,430,180,430);curve(p,221,430,245,394,248,320)
    line(p,266,0);line(p,360,0);line(p,342,330)
    curve(p,336,449,278,510,180,510);curve(p,82,510,24,449,18,330);p.closeSubpath()
}
let humanP=path { p in
    move(p,420,0);line(p,601,0);curve(p,721,0,784,57,784,161)
    curve(p,784,273,716,327,600,327);line(p,516,327);line(p,510,510);line(p,420,510);p.closeSubpath()
    move(p,516,86);line(p,516,242);line(p,593,242)
    curve(p,653,242,690,213,690,162);curve(p,690,111,653,86,593,86);p.closeSubpath()
}
concepts[4].shapes=fit([Shape(path:humanU),Shape(path:humanP,evenOdd:true),Shape(path:circle(749,487,17),accent:true)])

// Small path reader for collaborators' original vector symbols (absolute commands only).
func parsePath(_ d: String) -> CGPath {
    let re=try! NSRegularExpression(pattern:"[MmLlHhVvCcQqZz]|[-+]?(?:[0-9]*\\.)?[0-9]+(?:[eE][-+]?[0-9]+)?")
    let text=d as NSString
    let tokens=re.matches(in:d,range:NSRange(location:0,length:text.length)).map { text.substring(with:$0.range) }
    let p=CGMutablePath();var i=0;var command="";var current=CGPoint.zero
    func number() -> CGFloat { defer { i += 1 }; return CGFloat(Double(tokens[i])!) }
    while i<tokens.count {
        if tokens[i].rangeOfCharacter(from:.letters) != nil { command=tokens[i];i+=1 }
        switch command {
        case "M": current=CGPoint(x:number(),y:number());p.move(to:current);command="L"
        case "L": current=CGPoint(x:number(),y:number());p.addLine(to:current)
        case "H": current.x=number();p.addLine(to:current)
        case "V": current.y=number();p.addLine(to:current)
        case "C": let c1=CGPoint(x:number(),y:number()),c2=CGPoint(x:number(),y:number());current=CGPoint(x:number(),y:number());p.addCurve(to:current,control1:c1,control2:c2)
        case "Q": let c=CGPoint(x:number(),y:number());current=CGPoint(x:number(),y:number());p.addQuadCurve(to:current,control:c)
        case "Z","z": p.closeSubpath();command=""
        default: fatalError("Unsupported path command: \(command)")
        }
    }
    return p
}
func readSymbolShapes(_ svg: String) -> [Shape] {
    let re=try! NSRegularExpression(pattern:"<path\\b[^>]*>")
    let src=svg as NSString
    return re.matches(in:svg,range:NSRange(location:0,length:src.length)).map { m in
        let tag=src.substring(with:m.range)
        let dre=try! NSRegularExpression(pattern:"\\bd=[\"']([^\"']+)[\"']")
        let tm=dre.firstMatch(in:tag,range:NSRange(location:0,length:(tag as NSString).length))!
        let d=(tag as NSString).substring(with:tm.range(at:1))
        return Shape(path:parsePath(d),accent:tag.lowercased().contains("#2f6bff"),evenOdd:tag.contains("evenodd"))
    }
}
let symbolURL=URL(fileURLWithPath:outputRoot).appendingPathComponent("symbol-concepts.json")
if fm.fileExists(atPath:symbolURL.path) {
    let items=try JSONSerialization.jsonObject(with:Data(contentsOf:symbolURL)) as! [[String:Any]]
    let footers:[String:(String,String)]=[
        "06":("经验汇成同一本书，内容品牌气质鲜明","更偏知识沉淀，社交感相对含蓄"),
        "07":("中庭负空间，建立独立的校园场域符号","有取景框联想，初期需要搭配 UniPO 字标"),
        "08":("几何节奏最鲜明，便于延伸整套视觉系统","语义需要由“经验接力”的品牌表达建立"),
        "09":("用引文表达真实经验，成熟而有记忆点","偏重 PO 分享，成长语义较含蓄"),
        "10":("小写 po 更亲近，像学生自己的签名","主动离开 UP 概念，保留 p 的下伸部")
    ]
    for c in items {let id=c["id"] as! String;let footer=footers[id]!
        concepts.append(Concept(id:id,name:c["name"] as! String,english:c["english"] as! String,rationale:c["rationale"] as! String,group:"B",strengths:footer.0,caution:footer.1,shapes:readSymbolShapes(c["svg"] as! String))) }
}

func pathData(_ p: CGPath) -> String {
    var parts:[String]=[]
    func n(_ v:CGFloat)->String { String(format:"%.3f",Double(v)).replacingOccurrences(of:"\\.?0+$",with:"",options:.regularExpression) }
    func pt(_ p:CGPoint)->String { "\(n(p.x)) \(n(p.y))" }
    p.applyWithBlock { pointer in
        let e=pointer.pointee
        switch e.type {
        case .moveToPoint: parts.append("M \(pt(e.points[0]))")
        case .addLineToPoint: parts.append("L \(pt(e.points[0]))")
        case .addQuadCurveToPoint: parts.append("Q \(pt(e.points[0])) \(pt(e.points[1]))")
        case .addCurveToPoint: parts.append("C \(pt(e.points[0])) \(pt(e.points[1])) \(pt(e.points[2]))")
        case .closeSubpath: parts.append("Z")
        @unknown default: break
        }
    }
    return parts.joined(separator:" ")
}
func svg(_ c:Concept,_ background:String? = "#FFFFFF",_ foreground:String = "#0B0F17") -> String {
    let bg=background.map { "<rect width=\"1024\" height=\"1024\" fill=\"\($0)\"/>" } ?? ""
    let shapes=c.shapes.map { "<path d=\"\(pathData($0.path))\" fill=\"\($0.accent ? "#2F6BFF":foreground)\" fill-rule=\"\($0.evenOdd ? "evenodd":"nonzero")\"/>" }.joined()
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1024 1024\" width=\"1024\" height=\"1024\"><title>UniPO \(c.id) \(c.english)</title>\(bg)\(shapes)</svg>"
}
func drawMark(_ ctx:CGContext,_ c:Concept,_ r:CGRect,_ dark:Bool=false,_ tinted:Bool=false) {
    ctx.saveGState();ctx.translateBy(x:r.minX,y:r.minY);ctx.scaleBy(x:r.width/1024,y:r.height/1024)
    for s in c.shapes { ctx.setFillColor((s.accent && !tinted ? blue : (dark ? white : ink)).cgColor);ctx.addPath(s.path);ctx.drawPath(using:s.evenOdd ? .eoFill:.fill) }
    ctx.restoreGState()
}
func bitmap(_ width:Int,_ height:Int,_ draw:(CGContext)->Void) -> CGImage {
    let ctx=CGContext(data:nil,width:width,height:height,bitsPerComponent:8,bytesPerRow:0,space:rgb,bitmapInfo:CGImageAlphaInfo.noneSkipLast.rawValue)!
    ctx.translateBy(x:0,y:CGFloat(height));ctx.scaleBy(x:1,y:-1)
    ctx.setFillColor(white.cgColor);ctx.fill(CGRect(x:0,y:0,width:width,height:height));draw(ctx);return ctx.makeImage()!
}
func savePNG(_ image:CGImage,_ file:String) throws {
    let rep=NSBitmapImageRep(cgImage:image)
    try rep.representation(using:.png,properties:[:])!.write(to:URL(fileURLWithPath:file))
}
func fill(_ ctx:CGContext,_ r:CGRect,_ color:NSColor,_ radius:CGFloat=0) {
    ctx.setFillColor(color.cgColor)
    if radius>0 { ctx.addPath(CGPath(roundedRect:r,cornerWidth:radius,cornerHeight:radius,transform:nil));ctx.fillPath() } else {ctx.fill(r)}
}
func text(_ ctx:CGContext,_ value:String,_ x:CGFloat,_ y:CGFloat,_ size:CGFloat,_ weight:NSFont.Weight = .regular,_ color:NSColor=ink,_ maxWidth:CGFloat=2000) {
    ctx.saveGState();NSGraphicsContext.saveGraphicsState();NSGraphicsContext.current=NSGraphicsContext(cgContext:ctx,flipped:true)
    let paragraph=NSMutableParagraphStyle();paragraph.lineSpacing=size*0.22
    let attr:[NSAttributedString.Key:Any]=[.font:NSFont.systemFont(ofSize:size,weight:weight),.foregroundColor:color,.paragraphStyle:paragraph]
    (value as NSString).draw(in:CGRect(x:x,y:y,width:maxWidth,height:size*6),withAttributes:attr)
    NSGraphicsContext.restoreGraphicsState();ctx.restoreGState()
}
func rule(_ ctx:CGContext,_ x:CGFloat,_ y:CGFloat,_ width:CGFloat) { fill(ctx,CGRect(x:x,y:y,width:width,height:1),mist) }
func appIcon(_ ctx:CGContext,_ c:Concept,_ r:CGRect,_ mask:Bool=true,_ dark:Bool=false) {
    ctx.saveGState()
    if mask {ctx.addPath(CGPath(roundedRect:r,cornerWidth:r.width*0.222,cornerHeight:r.height*0.222,transform:nil));ctx.clip()}
    fill(ctx,r,dark ? ink:white);drawMark(ctx,c,r,dark);ctx.restoreGState()
}
func genericIcon(_ ctx:CGContext,_ x:CGFloat,_ y:CGFloat,_ s:CGFloat,_ kind:Int) {
    fill(ctx,CGRect(x:x,y:y,width:s,height:s),white,s*0.222)
    let box=CGRect(x:x+s*0.25,y:y+s*0.25,width:s*0.5,height:s*0.5)
    ctx.saveGState();ctx.setStrokeColor(ink.withAlphaComponent(0.60).cgColor);ctx.setLineWidth(s*0.038);ctx.setLineCap(.round);ctx.setLineJoin(.round)
    switch kind%6 {
    case 0: ctx.addPath(CGPath(roundedRect:box,cornerWidth:3,cornerHeight:3,transform:nil));ctx.strokePath();ctx.move(to:CGPoint(x:box.minX,y:box.minY+s*0.16));ctx.addLine(to:CGPoint(x:box.maxX,y:box.minY+s*0.16));ctx.strokePath()
        text(ctx,"24",box.minX+s*0.05,box.minY+s*0.18,s*0.26,.medium,ink)
    case 1: ctx.addEllipse(in:box);ctx.strokePath();ctx.move(to:CGPoint(x:box.midX,y:box.minY+s*0.08));ctx.addLine(to:CGPoint(x:box.midX,y:box.midY));ctx.addLine(to:CGPoint(x:box.maxX-s*0.10,y:box.midY));ctx.strokePath()
    case 2: ctx.addPath(CGPath(roundedRect:box,cornerWidth:3,cornerHeight:3,transform:nil));ctx.strokePath();for i in 0..<3 {ctx.move(to:CGPoint(x:box.minX+s*0.08,y:box.minY+s*(0.12+CGFloat(i)*0.12)));ctx.addLine(to:CGPoint(x:box.maxX-s*0.08,y:box.minY+s*(0.12+CGFloat(i)*0.12)))};ctx.strokePath()
    case 3: ctx.addEllipse(in:box);ctx.strokePath();ctx.move(to:CGPoint(x:box.midX-s*0.10,y:box.midY+s*0.10));ctx.addLine(to:CGPoint(x:box.midX+s*0.10,y:box.midY-s*0.10));ctx.strokePath()
    case 4: ctx.addPath(CGPath(roundedRect:box,cornerWidth:4,cornerHeight:4,transform:nil));ctx.strokePath();ctx.move(to:CGPoint(x:box.minX,y:box.minY));ctx.addLine(to:CGPoint(x:box.midX,y:box.midY));ctx.addLine(to:CGPoint(x:box.maxX,y:box.minY));ctx.strokePath()
    default: ctx.addEllipse(in:box.insetBy(dx:s*0.09,dy:s*0.09));ctx.strokePath();ctx.addEllipse(in:box);ctx.strokePath()
    }
    ctx.restoreGState()
}
func phone(_ ctx:CGContext,_ c:Concept,_ x:CGFloat,_ y:CGFloat,_ scale:CGFloat = 1) {
    ctx.saveGState();ctx.translateBy(x:x,y:y);ctx.scaleBy(x:scale,y:scale)
    fill(ctx,CGRect(x:0,y:0,width:342,height:716),ink,52)
    let screen=CGRect(x:8,y:8,width:326,height:700)
    ctx.saveGState();ctx.addPath(CGPath(roundedRect:screen,cornerWidth:45,cornerHeight:45,transform:nil));ctx.clip();fill(ctx,screen,mist)
    // Flat two-tone architectural wallpaper, intentionally shared across all concepts.
    ctx.setFillColor(paper.cgColor);ctx.addEllipse(in:CGRect(x:-220,y:175,width:660,height:660));ctx.fillPath()
    text(ctx,"9:41",30,25,17,.semibold)
    fill(ctx,CGRect(x:122,y:21,width:100,height:27),ink,14)
    for i in 0..<4 {fill(ctx,CGRect(x:269+CGFloat(i)*4,y:38-CGFloat(i)*2,width:2.5,height:CGFloat(i+1)*2.5),ink,1)}
    ctx.setStrokeColor(ink.cgColor);ctx.setLineWidth(1);ctx.stroke(CGRect(x:293,y:30,width:18,height:9));fill(ctx,CGRect(x:295,y:32,width:13,height:5),ink,1)
    let labels=["日历","时钟","备忘录","提醒事项","UniPO","邮件","设置","相机","文件","地图","图书","快捷指令"]
    for i in 0..<12 {
        let col=i%4,row=i/4,px=27+CGFloat(col)*76,py=92+CGFloat(row)*99
        if i==4 {appIcon(ctx,c,CGRect(x:px,y:py,width:60,height:60))} else {genericIcon(ctx,px,py,60,i)}
        let label=labels[i];text(ctx,label,px-5,py+67,11,.regular,ink,75)
    }
    fill(ctx,CGRect(x:139,y:573,width:64,height:24),white.withAlphaComponent(0.72),12);text(ctx,"⌕ 搜索",150,579,10,.regular,ink)
    fill(ctx,CGRect(x:19,y:619,width:304,height:76),white.withAlphaComponent(0.6),27)
    for i in 0..<4 {genericIcon(ctx,33+CGFloat(i)*76,629,55,i+3)}
    fill(ctx,CGRect(x:118,y:701,width:106,height:4),ink,2)
    ctx.restoreGState();ctx.restoreGState()
}
func drawBoard(_ ctx:CGContext,_ c:Concept) {
    fill(ctx,CGRect(x:0,y:0,width:1800,height:1200),paper)
    text(ctx,"UniPO",66,42,29,.semibold)
    text(ctx,c.group=="A" ? "A / UP 字标探索" : "B / 品牌符号探索",252,50,16,.medium,muted)
    text(ctx,"BRAND EXPLORATION  /  2026.10",1380,51,13,.medium,muted)
    rule(ctx,66,103,1668)
    text(ctx,c.id,66,130,22,.medium,muted)
    text(ctx,c.name,122,121,38,.medium)
    text(ctx,c.english,122,174,17,.regular,muted)
    text(ctx,c.rationale,66,222,21,.regular,ink,1145)
    text(ctx,"1024 × 1024  /  原始方形图稿",66,321,14,.medium,muted)
    fill(ctx,CGRect(x:66,y:360,width:650,height:650),white)
    drawMark(ctx,c,CGRect(x:66,y:360,width:650,height:650))
    text(ctx,"实际尺寸检查",782,321,14,.medium,muted)
    text(ctx,"60 pt",782,367,13,.medium,muted)
    appIcon(ctx,c,CGRect(x:782,y:406,width:60,height:60))
    text(ctx,"29 pt",904,367,13,.medium,muted)
    appIcon(ctx,c,CGRect(x:904,y:406,width:29,height:29))
    text(ctx,"同一轮廓等比缩小",782,497,14,.regular,muted)
    rule(ctx,782,545,364)
    text(ctx,"反白检查",782,582,14,.medium,muted)
    appIcon(ctx,c,CGRect(x:782,y:626,width:162,height:162),false,true)
    text(ctx,"UniPO",782,827,46,.semibold)
    text(ctx,"字标风格参考 · 选定后统一定制",782,891,13,.regular,muted)
    text(ctx,"iOS 主屏示意 · 同一灰阶环境",1249,321,14,.medium,muted)
    phone(ctx,c,1249,355)
    rule(ctx,66,1100,1668)
    text(ctx,c.strengths,66,1131,16,.medium)
    text(ctx,"取舍："+c.caution,669,1131,14,.regular,muted,1000)
}

var manifest:[[String:Any]]=[]
for c in concepts {
    let dir=outputRoot+"/"+c.id+"-"+c.english.lowercased().replacingOccurrences(of:" ",with:"-")
    try fm.createDirectory(atPath:dir,withIntermediateDirectories:true)
    try svg(c).write(toFile:dir+"/concept.svg",atomically:true,encoding:.utf8)
    try svg(c,nil).write(toFile:dir+"/mark.svg",atomically:true,encoding:.utf8)
    let image=bitmap(1024,1024) { drawMark($0,c,CGRect(x:0,y:0,width:1024,height:1024)) }
    try savePNG(image,dir+"/AppIcon-1024.png")
    try savePNG(bitmap(1800,1200) {drawBoard($0,c)},dir+"/presentation.png")
    for s in [60,29] { try savePNG(bitmap(s,s) {drawMark($0,c,CGRect(x:0,y:0,width:s,height:s))},dir+"/size-\(s).png") }
    var box=CGRect.null;for s in c.shapes {box=box.union(s.path.boundingBoxOfPath)}
    print("\(c.id) \(c.name) bounds \(box), shapes \(c.shapes.count)")
    manifest.append(["id":c.id,"name":c.name,"english":c.english,"group":c.group,"rationale":c.rationale,"strengths":c.strengths,"caution":c.caution,"directory":dir,"svg":svg(c),"markSVG":svg(c,nil),"bounds":[box.minX,box.minY,box.width,box.height]])
}
let overview=bitmap(2400,1460) { ctx in
    fill(ctx,CGRect(x:0,y:0,width:2400,height:1460),paper)
    text(ctx,"UniPO",72,47,45,.semibold)
    text(ctx,"十种方向，一个向上的校园。",286,60,27,.regular)
    text(ctx,"01—05  围绕 UP 字标       06—10  从品牌气质出发",72,133,20,.regular,muted)
    text(ctx,"2026.10  /  CONCEPT STUDY",1885,68,17,.medium,muted)
    rule(ctx,72,190,2256)
    for (i,c) in concepts.enumerated() {
        let col=i%5,row=i/5,x=72+CGFloat(col)*457,y=239+CGFloat(row)*591
        fill(ctx,CGRect(x:x,y:y,width:418,height:418),white)
        drawMark(ctx,c,CGRect(x:x,y:y,width:418,height:418))
        text(ctx,c.id+"  "+c.name,x,y+441,25,.medium)
        text(ctx,c.english,x,y+479,17,.regular,muted)
        appIcon(ctx,c,CGRect(x:x+315,y:y+449,width:60,height:60))
        appIcon(ctx,c,CGRect(x:x+389,y:y+449,width:29,height:29))
    }
    text(ctx,"大写 UP 的顶底与轮廓居中均按路径校准。小图为 60 / 29 像素取样；各方案详页包含 iOS 主屏效果。",72,1400,16,.regular,muted)
}
try savePNG(overview,outputRoot+"/UniPO-10-concepts.png")
for g in ["A","B"] {
    let subset=concepts.filter {$0.group==g}
    let img=bitmap(1800,800) { ctx in
        fill(ctx,CGRect(x:0,y:0,width:1800,height:800),paper)
        text(ctx,g=="A" ? "A / 从 UP 出发" : "B / 从品牌气质出发",58,44,34,.medium)
        text(ctx,g=="A" ? "University + Post → UP" : "成长 · 同校 · 记录 · 连接",58,101,18,.regular,muted)
        for (i,c) in subset.enumerated() {
            let x=58+CGFloat(i)*348
            fill(ctx,CGRect(x:x,y:172,width:292,height:292),white)
            drawMark(ctx,c,CGRect(x:x,y:172,width:292,height:292))
            text(ctx,c.id+"  "+c.name,x,493,25,.medium)
            text(ctx,c.english,x,533,15,.regular,muted)
            appIcon(ctx,c,CGRect(x:x,y:588,width:60,height:60))
            appIcon(ctx,c,CGRect(x:x+88,y:588,width:29,height:29))
            text(ctx,"60",x,667,12,.regular,muted);text(ctx,"29",x+88,667,12,.regular,muted)
        }
    }
    try savePNG(img,outputRoot+"/UniPO-group-\(g).png")
}
let data=try JSONSerialization.data(withJSONObject:manifest,options:[.prettyPrinted,.sortedKeys,.withoutEscapingSlashes])
try data.write(to:URL(fileURLWithPath:outputRoot+"/manifest.json"))
var mediaBox=CGRect(x:0,y:0,width:1800,height:1200)
let consumer=CGDataConsumer(url:URL(fileURLWithPath:outputRoot+"/UniPO-10-concepts.pdf") as CFURL)!
let pdf=CGContext(consumer:consumer,mediaBox:&mediaBox,[kCGPDFContextTitle:"UniPO / Ten Identity Directions",kCGPDFContextCreator:"UniPO Design Exploration"] as CFDictionary)!
for c in concepts {pdf.beginPDFPage(nil);pdf.saveGState();pdf.translateBy(x:0,y:1200);pdf.scaleBy(x:1,y:-1);drawBoard(pdf,c);pdf.restoreGState();pdf.endPDFPage()}
pdf.closePDF()
print("Exported \(concepts.count) concepts to \(outputRoot)")
