# Web / App 双端接口方案（待拍板）

> 目标：Web 保持现在的形态，另外做一个**真正的原生 App**（不是 WebView 套壳），两边共用同一套后端能力。

## 1. 结论

**不要加 `/app` 路径拆分。走 `/api/v1` 单一契约 + 少量"客户端能力"端点。**

一句话理由：Web 和 App 的业务是同一套，差异在**能力**（刷新令牌、推送、直传、版本门槛），不在**领域**。按端拆路径会让业务逻辑复制一份，而这正是 notices / posts 双域曾经踩过的坑（notices 已于 2026-10-20 删除，同一张 `comments` 表两套接口、两套 Controller）。

## 2. 四种方案对比

| 方案 | 做法 | 优点 | 缺点 | 判断 |
|---|---|---|---|---|
| A | 保持 `/api`，不版本化 | 零改动 | App 发布后旧版本长期存在，无法改契约 | 不可行 |
| B | `/api/web/*` + `/api/app/*` | 边界清晰，端侧可自由定制 | 业务逻辑与 SQL 复制两份；修 bug 改两处；监控/缓存/日志按端分叉；未来小程序再加前缀 | 不推荐 |
| C | `/api/v1/*` 单一契约 + 客户端能力端点 | 一套业务、一份契约、机器校验、可演进 | 需要一次迁移（前端 baseURL + 后端注解） | **推荐** |
| D | 独立 BFF 服务 | 端侧聚合自由 | 多服务、多一跳、多套部署与鉴权 | 现阶段不需要，留作后期选项 |

## 3. "统一接口"要统一的到底是什么

不是 URL，而是四样东西：

1. **一份领域模型**：`Post` / `Comment` / `User` / `Conversation` 在两端字段完全一致。
2. **一份错误码表**：App 靠错误码做分支，不靠中文文案。
3. **一套横切规范**：分页、时间、枚举、空值、幂等。
4. **一份 OpenAPI 文件**作为唯一真相，两端各自生成客户端（见 §9）。

## 4. 契约规范（App 开发前必须落地）

### 4.1 版本与路径
- 统一前缀 `/api/v1`。
- 迁移策略：后端同时挂 `/api` 与 `/api/v1`（一个 `WebMvcConfigurer` 加路径前缀，或 Controller 注解改一次 + 过滤器兼容），Web 端 baseURL 改为 `/api/v1`，观察 2 个版本后下线 `/api`。
- 版本演进规则：**只做加法**。加字段、加端点不升版本；删字段、改语义、改类型才升 `v2`。

### 4.2 响应包
```json
{ "code": "OK", "message": "", "data": { }, "requestId": "..." }
```
错误：
```json
{ "code": "AUTH_INVALID_CREDENTIALS", "message": "邮箱或密码不正确", "data": null, "requestId": "..." }
```
- HTTP 状态码保留语义（400/401/403/404/409/413/429/500）。
- `requestId` 用于前后端联调与日志追踪。
- **不要**用 `{"code": 0}` 这种全 200 的风格。

错误码分层：`AUTH_*` / `POST_*` / `USER_*` / `MSG_*` / `MEDIA_*` / `RATE_LIMIT` / `VALIDATION_FAILED` / `INTERNAL`。

### 4.3 分页（强制游标）
```http
GET /api/v1/posts?tab=recommend&cursor=eyJpZCI6MTIzfQ&limit=20
```
```json
{ "code": "OK", "data": { "items": [ ], "nextCursor": "eyJpZCI6MTAzfQ", "hasMore": true } }
```
原因：offset 分页在 feed 插入新内容时会重复/漏条，App 的无限滚动体验会崩。`limit` 上限 50，默认 20。

私信、评论、通知全部同构。

### 4.4 时间
- 全部改为 `OffsetDateTime`，序列化为 ISO-8601 带偏移：`"2026-10-19T06:43:02+08:00"`。
- 数据库仍存 UTC（或继续 `DATETIME` 但 Service 层统一转换），**接口层必须带偏移**。
- 客户端负责本地化展示。
- 这是 P0 级改造：现在 `LocalDateTime` 无时区，App 在不同时区会显示错误时间，且无法做"3 分钟前"这类相对时间。

### 4.5 其它
- ID 保持 `Long`（当前量级远小于 2^53，JSON number 安全）。
- 空列表返回 `[]`，不返回 `null`。
- 写操作支持 `Idempotency-Key` 头（发帖、点赞幂等，防 App 弱网重试）。

## 5. 认证方案

现状（2026-10-20 已升级）：access token 120 分钟 + refresh token 14 天，都是 JWT（`typ` 区分），Web 存 `localStorage`，
登出把两者一起写进内存黑名单。**还没做**：refresh 轮换、`httpOnly` cookie、`refresh_tokens` 落库、`/auth/logout-all`、多设备管理。

目标：
| 项 | Web | App |
|---|---|---|
| access token | 15-30 分钟 | 15-30 分钟 |
| refresh token | 7-30 天，`httpOnly; Secure; SameSite=Lax` cookie | 30-90 天，存 Keychain / Keystore |
| 传输 | cookie 优先（迁移期可继续 Bearer） | `Authorization: Bearer` |
| 登出 | 服务端吊销 refresh + access jti 入黑名单 | 同左 |
| 多设备 | 允许，可查看/踢下线 | 同左 |

新增：
- `POST /api/v1/auth/refresh`（刷新并轮换 refresh token，旧 token 立即作废）。
- `POST /api/v1/auth/logout-all`。
- `GET/DELETE /api/v1/devices`（设备列表与踢下线）。
- `users` 表旁新增 `refresh_tokens` 与 `devices` 表。

存储依赖：黑名单、在线状态、限流计数**必须迁到 Redis**；现在单机 `ConcurrentHashMap` 在多实例下直接失效。

## 6. App 专属增量端点（仍在 `/api/v1` 下，不复制业务）

| 端点 | 用途 |
|---|---|
| `POST /auth/refresh`、`/auth/logout-all` | 令牌生命周期 |
| `GET /app/config` | 最低支持版本、强制更新、维护开关、功能开关（审核期隐藏模块） |
| `POST /devices`、`DELETE /devices/{id}` | 推送 token 注册（FCM / APNs） |
| `POST /media/presign` | 直传 MinIO 的预签名 URL，省后端带宽 |
| `GET /sync?since=` | 冷启动增量：未读数、新消息、新互动通知 |
| `GET /events`（SSE） | 消息 / 未读 / 通知的实时流 |
| `POST /posts/{id}/report`、`POST /users/{id}/block` | App 审核常要求的能力（现在都没有） |

> 注意最后一条：应用商店审核通常要求"举报/拉黑"入口，现在后端**没有任何举报与拉黑能力**，这会是 App 上架的硬门槛。

## 7. 实时化

现状：会话列表 3s、当前会话 1.8s、心跳 10s 轮询。

建议：**SSE 优先**。
- 服务端 `SseEmitter` / WebFlux，事件：`message.new` / `conversation.updated` / `unread.count` / `post.new`。
- Web 与 App 都能用（App 用 http 流，断线重连 + `Last-Event-ID` 补发）。
- 写操作仍走 REST。
- 只有在需要双向低延迟（如正在输入、语音）时才上 WebSocket。

## 8. 上传

现状：App 图片经后端转发（`MediaController`），5MB 上限，按文件头识别格式，无压缩。

目标：`POST /api/v1/media/presign` 返回 MinIO 预签名 PUT URL + 对象 key，客户端直传，完成后回调 `POST /api/v1/media/confirm` 落库。后端只做签名与校验，带宽成本归零。

## 9. 契约生成流水线

1. 后端已引入 springdoc → `/v3/api-docs`。
2. CI 里导出 `docs/openapi.json` 并在 PR 中做 diff（契约变更可见）。
3. Web：`openapi-typescript` 生成 `frontend/src/lib/api-types.ts`，替换手写 `types.ts`；用 `openapi-fetch` 生成调用层。
4. App：`openapi-generator` 生成 Dart（Flutter）或 Kotlin/Swift 客户端。

这一步做完，"统一接口"就是机器保证的，而不是靠人记。

## 10. 分期路线

| 阶段 | 内容 | 粗估 |
|---|---|---|
| 0 | 环境（JDK/Maven/Redis）+ P0 安全止血 | 3-5 天 |
| 1 | `/api/v1` 前缀 + 统一响应包 + 错误码 + 游标分页 + 时区 | 5-8 天 |
| 2 | 认证升级（refresh / 设备 / Redis）+ 限流 | 5-8 天 |
| 3 | App 能力端点（config / devices / presign / sync / SSE）+ 举报拉黑 | 5-8 天 |
| 4 | App 客户端开发（与 Web 共用 OpenAPI 生成的 SDK） | 按端计 |
| 并行 | Web 路由化（vue-router）+ 组件拆分 + 清假数据 | 5-10 天 |

## 11. 需要你拍板的 5 件事

1. ~~**路径**：接受 `/api/v1` 单一契约，还是坚持 `/app` 拆分？~~
   ✅ **已定（2026-10-20）：单一 `/api/v1` 契约**。按端裁剪只允许加薄 BFF，不复制业务 API。见 ADR-0001 D1。
2. ~~**App 技术栈**：Flutter / React Native(Expo) / 原生？~~
   ✅ **已定（2026-10-20）：iOS 先用 SwiftUI 原生**；Android 后置，优先 Kotlin/Compose 或 KMP 共享领域层，
   不为复用 UI 选跨端框架。见 ADR-0001 D2。
3. ~~**Web 认证**：是否接受从 `localStorage` 迁到 `httpOnly cookie`？~~
   ✅ **已定（2026-10-20）：Web 改 httpOnly Cookie + CSRF**；App 用 Bearer + Keychain。见 ADR-0001 D3。
4. ~~**实时**：SSE 够用，还是一步到位 WebSocket？~~
   ✅ **已定（2026-10-20）：WebSocket**，自定 JSON 信封 + ack + 断线续传。见 ADR-0001 D4。
5. ~~**notices 遗留域**：删除还是保留？~~ ✅ **已定：删除（2026-10-20 执行完毕）**。19 个 Java 文件 + 5 张表下线，见 `V11__drop_notices_domain.sql`。

> 以上决策的完整理由、代价与演进路线见 [`docs/adr/0001-platform-baseline.md`](adr/0001-platform-baseline.md)。

## 12. 风险

- **迁移期双路径**：`/api` 与 `/api/v1` 同时存在，必须设定下线时间点，否则永久双维护。
- **时区改造**：涉及所有 DTO、所有查询与前端格式化，建议一次性做完并配 golden 测试。
- **App 旧版本**：一旦发布，必须长期兼容 `v1`；所以 §4 的规范要在第一版 App 之前定死。
- **无 Redis 现状**：限流、黑名单、在线态、未读数缓存都依赖 Redis，需先加基础设施。
