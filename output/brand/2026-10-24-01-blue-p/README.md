# UniPO · 01 原点 / P 品牌蓝

按选定的 01 路径原样改色。U、P 的顶边、底边、字距、75% 字组宽度与轮廓中心保持一致。

| 版本 | 背景 | U | P |
| --- | --- | --- | --- |
| 白底 | #FFFFFF | #0B0F17 | #2F6BFF |
| 黑底 | #0B0F17 | #FFFFFF | #2F6BFF |
| 单色着色 | 透明 | #FFFFFF | #FFFFFF |

## 文件

- `AppIcon-1024.png`：白底版，1024 × 1024，sRGB、RGB 不透明。
- `AppIcon-1024-dark.png`：墨黑底版，1024 × 1024，sRGB、RGB 不透明。
- `AppIcon-1024-tinted.png`：纯白字标 + 真透明底，供单色着色使用。
- `unipo-mark.svg` / `unipo-mark-dark.svg`：白底、黑底适用的透明背景路径源稿。
- `AppIcon-white.svg` / `AppIcon-dark.svg`：包含方形背景的矢量图稿。
- `unipo-mark.pdf`：两页 1024 × 1024 矢量源稿，第 1 页白底、第 2 页墨黑底。
- `unipo-wordmark-black.svg` / `unipo-wordmark-white.svg`：横版 UniPO 字标，字体已转路径，透明底；名义画布为 320 × 96。
- `unipo-wordmark-*@2x.png` / `unipo-wordmark-*@3x.png`：横版字标 2x / 3x，640 × 192 / 960 × 288，透明底。
- `UniPO-01-blue-p-preview.png`：两版并排预览，附 60pt / 29pt 取样。
- `UniPO-brand-guidelines.png`：一页使用规范。

源图均为满版方形，不预制圆角或阴影。圆角只出现在预览的小尺寸遮罩中。取样标注以 @1x 名义点数表示，聊天或预览窗口缩放不代表设备上的实际物理尺寸。

使用规范：图标最小 29 × 29pt；横版字标建议至少 80pt 宽。独立标志四周留白至少一根 U 主竖笔宽，约 81 / 1024 画板。不得拉伸、旋转、改变字距、替换字形、自行改色或增加特效。

复现：仓库根运行 `swift scripts/design/render-brand-assets.swift output/brand/2026-10-24-01-blue-p`。此次为设计资产交付，未替换应用工程内的现有图标。
