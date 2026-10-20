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

后端要在 `127.0.0.1:8080` 跑着；模拟器与宿主机共享网络。
API 地址在 `UniPO/Resources/Info.plist` 的 `API_BASE_URL`，默认 `http://127.0.0.1:8080/api/v1`。

调试参数（仅 Debug）：

```bash
# 指定 Tab
xcrun simctl launch booted com.unipo.app -tab achievements
# 自动登录（避免每次手输）
xcrun simctl launch booted com.unipo.app -email admin@biecuoguo.local -password Admin@123456
# 登录页 / 手机号表单 / 手机号自动登录（本地验证码 123456）
xcrun simctl launch booted com.unipo.app -login
xcrun simctl launch booted com.unipo.app -login -phone-form
xcrun simctl launch booted com.unipo.app -login -sms-login
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

## 当前进度

- [x] 工程骨架 + SPM 模块
- [x] 黑白蓝设计 tokens
- [x] Liquid Glass Dock（5 Tab：首页 / 工具 / 发PO / 成就 / 我的）
- [x] 独立登录页（黑白 + 背景动画；手机号验证码登录；一键登录/微信/QQ 占位）
- [x] 首页 feed（真实 `/api/v1`、游标分页、下拉刷新）
- [x] 工具页入口（5 个工具，功能待接）
- [x] 成就页（阶段地图、徽章墙、进度动效，预览数据）
- [x] 我的页（登录态/访客态）
- [x] 发 PO（文字 + 板块 + 图片上传）
- [x] 帖子详情 / 评论 / 点赞 / 收藏
- [x] 消息列表 + 私信 + WebSocket（auth / 事件刷新）
- [x] 图片上传（PhotosPicker + multipart + MinIO）
- [x] 阶段/成就真实接口（V15：阶段/任务/进度/徽章）
- [ ] APNs、账号注销、TestFlight
