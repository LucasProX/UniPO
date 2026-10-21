# UniPO iOS

原生 SwiftUI 客户端。最低 iOS 17.0，使用 iOS 26 SDK 构建；Liquid Glass 只在 iOS 26+ 启用，
旧系统自动退化为 `.ultraThinMaterial`。

## 跑起来

```bash
cd ios
brew install xcodegen      # 只第一次
xcodegen generate
open UniPO.xcodeproj
```

或者命令行构建：

```bash
cd ios
xcodebuild -project UniPO.xcodeproj -scheme UniPO \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath .build build CODE_SIGNING_ALLOWED=NO
```

安装到模拟器：

```bash
xcrun simctl boot 'iPhone 17'
xcrun simctl install booted .build/Build/Products/Debug-iphonesimulator/UniPO.app
xcrun simctl launch booted com.unipo.app
```

API 地址由 `project.yml` 的 `UNIPO_API_BASE_URL` 构建设置写入 Info.plist。
**Debug 默认 `http://127.0.0.1:18081/api/v1`**，通过 SSH 隧道连接服务器独立测试实例；
**Release 仍为 `http://49.234.21.63/api/v1`**，正式发布前还需 HTTPS 与真实短信通道。
先在仓库根运行并保持该终端打开：

```bash
bash scripts/connect-test-api.sh
```

测试服务与数据库已在服务器部署，重开 Mac 或隧道断开后需重新连接。当前已有隧道时不要重复启动相同端口。
环境与操作说明见 [`TEST-ENVIRONMENT.md`](../docs/TEST-ENVIRONMENT.md)。
`NSExceptionDomains` 只为当前测试 IP 配置 HTTP 例外，未全局关闭 ATS。正式发布前切换 HTTPS 并移除此测试例外。

2026-10-21 已在 iPhone 17 / iOS 26.5 模拟器读取服务器信息流和评论。详细证据和未覆盖流程见
[`IOS-SERVER-INTEGRATION.md`](../docs/IOS-SERVER-INTEGRATION.md)。

调试参数（仅 Debug）：

```bash
# 指定 Tab
xcrun simctl launch booted com.unipo.app -tab achievements

# 默认手机号登录页
xcrun simctl launch booted com.unipo.app -login
xcrun simctl launch booted com.unipo.app -login -focus-phone
xcrun simctl launch booted com.unipo.app -login -agreement
```

## 结构

```
ios/
  project.yml                 XcodeGen 配置（改工程设置改这里，不要手改 pbxproj）
  UniPO/
    App/UniPOApp.swift        入口
    Resources/Info.plist      API_BASE_URL / ATS / 启动屏
  Packages/
    UniPOCore/                网络、鉴权、Keychain、模型（无 UI 依赖）
    UniPOUI/                  Design Tokens、玻璃材质、通用组件
    UniPOFeatures/            Home / Tools / Compose / Achievements / Profile
```

依赖方向：`Features -> UI + Core`，`UI -> 无`，`Core -> 无`。App 只负责组装。
新增页面：`Packages/UniPOFeatures/Sources/UniPOFeatures/XxxView.swift`，在 `RootView` 里挂 Tab 或路由。

## 关键约定

- **App 只走 Bearer，不用 Cookie**。`URLSession` 配置为 `httpShouldSetCookies = false`、`httpCookieStorage = nil`。
  后端登录响应会下发 `unipo_sid`，如果 URLSession 自动存了它，后续写请求就会以 Cookie 身份通过、
  但缺少 `X-CSRF-Token`，后端返回 403（"请求校验失败"）。这个坑已经踩过一次。
- **token 只进 Keychain**，不写 UserDefaults、不写日志。
- **登录/刷新请求不带旧 Authorization**，否则过期 token 会让后端直接 403。
- **Liquid Glass 用 availability 包住**：`if #available(iOS 26.0, *)` 用 `glassEffect`，否则 `ultraThinMaterial`。
- **iOS 17 兼容**：新增 API 必须先查 `#available`，不能因为本机是 iOS 26 就只用新 API。
- **不要手改 `UniPO.xcodeproj`**：改 `project.yml` 后跑 `xcodegen generate`。

## 登录页说明

- 只有手机号验证码页面，不显示账号密码页或一键演示号码入口；标题与说明居中。
- 测试环境任意合规的 11 位手机号点“获取验证码”会调用后端并成功倒计时；不发送真实短信，任意 4 位数字都可登录或创建普通测试账号。
- 协议默认不勾选。获取测试验证码不代表接受协议，最终登录仍需用户主动同意；勾选与首行文字对齐，长文本可换行。
- 测试与生产令牌按 API 地址存入不同 Keychain service，不互相带入。协议正文和真实短信通道仍需在正式发布前完成。

## 主页与评论

- 帖子作者、评论作者、会话列表及聊天标题的头像/昵称统一由 `UserProfileLink` 打开主页；
  按 `publicUid` 判断本人，本人进入 `ProfileView`，他人进入 `PublicProfileView`。
- 本人主页使用浅色杂志风，作品/喜欢/收藏以胶囊滑块切换，并尊重 Reduce Motion；公开主页展示作品。
  「朋友」仅在服务端返回 `following=true` 且 `followedBy=true` 时显示；仅自己关注对方时显示「已关注」，仅被对方关注时仍显示「关注」。
- 在个人设置中从相册选择背景，压缩为 JPEG 后上传媒体，再保存 `backgroundUrl`；支持恢复默认。
  本人与访客共用 `ProfileBackdrop`，界面以服务端保存结果为准，失败时保留原背景并显示错误。
- 评论输入框使用品牌蓝与浅色卡片，支持多行输入、长度提示、发送中防重复提交；失败保留输入并提供重试。

2026-10-21：V19 背景字段与公开主页关系字段已部署至独立 test，生产未改。
接口冒烟与 iPhone 17 / iOS 26.5 模拟器上的本轮 UI 回归已通过：三标签点选/横向拖动、
相册背景上传和访客展示、关注/朋友状态、头像主页入口、私信导航，以及游客评论草稿登录后保留。
测试修改的用户背景已恢复为默认 `null`，两条关注关系已恢复原状；本轮未通过 UI 发送私信。
最低系统 iOS 17 尚未实机验证。逐项结果见 [SOCIAL-PROFILE-VERIFICATION.md](../docs/SOCIAL-PROFILE-VERIFICATION.md)。

## 核心回归测试

登录恢复区分网络/服务故障与凭据被拒绝：前者保留 Keychain 并提供重试，后者按认证响应处理。
只有服务端验证通过才显示登录后的私人资料。退出与换号会作废旧操作，access/refresh 使用同一 Keychain 条目配对保存，退出标记优先于旧格式回退。
旧版双条目迁移会校验双方身份；旧 access 失效而无法确认配对时，需要重新登录一次。
详细实施与验收见 [SESSION-RECOVERY-VERIFICATION.md](../docs/SESSION-RECOVERY-VERIFICATION.md)。

```bash
# 仓库根运行；Core 的 macOS 14 测试平台仅用于验证 Foundation/状态逻辑，不改变 App 的 iOS 17 基线
swift test --package-path ios/Packages/UniPOCore
```

覆盖登录请求不携带旧凭据、受保护请求保留 Bearer、ATS 错误提示、分页并发、切换板块的迟到响应、
刷新和退出后丢弃旧响应、失败重试、重复帖子去重，以及环境间 Keychain 隔离。
社交主页测试覆盖双向关注判定、背景字段解码，以及旧响应缺少 `followedBy/backgroundUrl` 时的兼容行为。
测试使用本地 URLProtocol 或内存数据，不访问服务器、不写生产数据。

连通测试隧道后可运行 `python3 scripts/smoke-test-login.py`，会在隔离测试库创建普通测试账号并验证任意 4 位码、错误格式和生产侧拒绝测试 token，不打印凭据。
`python3 scripts/smoke-social-profile.py` 验证上述主页接口链路，仅连接 `127.0.0.1:18081` 的独立测试实例，
会创建测试账号、图片、帖子和评论，不打印凭据。
2026-10-21 本轮结果：后端 69 项、Core 15 项、Bash 冒烟 42 项通过，iOS Debug 构建及前端 lint/build 通过。

## APNs

1. Apple Developer → Keys → 新建 APNs Key（.p8），记下 Key ID；
2. 后端配置：
   ```
   APNS_ENABLED=true
   APNS_KEY_PATH=/secrets/AuthKey_XXXX.p8
   APNS_KEY_ID=XXXXXXXXXX
   APNS_TEAM_ID=XXXXXXXXXX
   APNS_TOPIC=com.unipo.app
   APNS_PRODUCTION=false
   ```
3. 模拟器没有真实 APNs token，用 `simctl push` 验通知 UI：
   ```bash
   xcrun simctl push booted com.unipo.app payload.json
   ```
   payload 里带 `"data": {"conversationId": "1"}`，点击通知会打开对应会话。

## 当前进度

- [x] 工程骨架 + SPM 模块
- [x] 黑白蓝设计 tokens
- [x] Liquid Glass Dock（5 Tab：首页 / 工具 / 发PO / 成就 / 我的）
- [x] 独立登录页（黑白 + 背景动画；一键登录/手机号验证码；协议勾选；微信/QQ 占位）
- [x] 3 步新手引导（资料 / 人设 / 头像，横向滑动 + 进度条，完成后进入首页）
- [x] 个人主页（浅色杂志风、作品/喜欢/收藏滑块、背景保存与公开展示；本轮模拟器交互验证通过）
- [x] 头像相册选择 + 圆形裁剪缩放（新手引导和主页头像都可换）
- [x] 首页 feed（真实 `/api/v1`、游标分页、下拉刷新）
- [x] 工具页入口（5 个工具，功能待接）
- [x] 成就页（阶段地图、徽章墙、进度动效，预览数据）
- [x] 我的页（登录后专属；未登录点击入口直接打开登录页）
- [x] 发 PO（文字 + 板块 + 图片上传）
- [x] 帖子详情 / 评论 / 点赞 / 收藏（评论品牌输入框、身份入口主页、登录后保留评论草稿；本轮模拟器交互验证通过）
- [x] 消息列表 + 私信 + WebSocket（auth / 事件刷新）
- [x] 图片上传（PhotosPicker + multipart + MinIO）
- [x] 阶段/成就真实接口（V15：阶段/任务/进度/徽章）
- [x] 消息未读角标（15s 轮询 + 推送打开会话）
- [x] APNs 注册 / 前台通知 / 点击跳转（真实推送需 .p8；模拟器可用 `simctl push`）
- [x] 账号注销（我的 → 注销账号）
- [ ] TestFlight
