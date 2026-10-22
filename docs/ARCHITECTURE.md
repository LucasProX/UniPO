# 系统架构

> 快照日期 2026-10-19，对应 commit `05a265d`。**目标形态见 [ADR-0001](adr/0001-platform-baseline.md)（2026-10-20）**，
> 下文描述的是「现在实际长什么样」，两者之间的差距就是演进路线。

## 0. 目标形态（2026-10-20 起）

按百万日活设计、但单机必须能完整跑。四条基线：

1. **模块化单体**：内部按 auth / user / post / media / message / realtime / admin / infra 划边界，
   跨模块只走 facade + 领域事件；不提前拆微服务。
2. **单一 /api/v1 契约**：Web/iOS/未来 Android 共用；按端裁剪只加薄 BFF。
3. **认证双载体**：Web 用 httpOnly Cookie + CSRF，App 用 Bearer + Keychain，共用同一个会话/令牌服务。
4. **状态外部化**：会话、限流、在线态、撤销名单、实时广播都进 Redis；对象存储进 MinIO/S3；
   应用实例保持无状态 —— 这是「一台机器能跑、十台也能跑」的前提。

实时通道从 SSE 演进到 **WebSocket**（带 ack、心跳、断线续传）。目标可靠投递采用 Outbox；当前私信已移到提交后通知，但持久 Outbox 尚未交付。私信不进入通用 Redis Stream 重放，断线恢复走消息 HTTP 接口。
详见 ADR-0001。

### 2026 年 10 月 22 日增量

V20 的身份版本与设备记录由 MySQL 作为权威，access、refresh、Cookie 和实时连接统一校验，详见 ADR-0003。V21 加入互动幂等；V22 以用户对锁串行关注、拉黑和私信操作，详见 ADR-0004。独立 MySQL/Redis 验证与客户端边界分别记录在 DP02、DP03 验收文档。下方旧快照不能用于推断这些增量尚未实现或已经部署。

### 落地进度（2026-10-20）

| 能力 | 状态 |
|---|---|
| Redis 进单机 compose，会话语义外部化 | ✅ 已完成（`STATE_BACKEND=redis` 已实测） |
| Web httpOnly Cookie 会话 + CSRF 双提交 | ✅ 前后端完成（前端不再持有 token；写请求自动带 X-CSRF-Token） |
| 会话滑动过期 / 退出所有设备 | ✅ 已完成（`SessionStore` memory / redis 双实现） |
| WebSocket 实时通道 | ✅ 已完成（auth / resume / 心跳；Redis Stream + Pub/Sub） |
| 模块化拆包 + ArchUnit | 🔄 规则已立（9 条边界测试）；**media、message、post 完整迁移**（post 通过 PostQueryApi 收口），taxonomy 实体待随 user 迁移收口 |
| 管理后台（同 SPA `/admin`） | ✅ MVP：概览 / 内容审核 / 轮播运营 / 用户权限 / 报表 / 监控 / 设置占位；所有写操作有审计 |
| 可观测性（metrics / 告警） | 🔄 已接 Micrometer + `/actuator/prometheus`、liveness/readiness、自定义业务指标；Prometheus 走 compose `observability` profile。**Grafana 面板与告警规则未做** |

## 1. 拓扑

本机开发：
```text
浏览器 :5173
   |  (vite dev server, 代理 /api -> :8080)
   v
backend :8080  --->  MySQL :3306 (profile local-db) / 远程 MySQL
   |
   +---> MinIO（外部对象存储）
```

部署形态（单机 docker compose）：
```text
用户
 |  HTTPS
 v
Caddy :443/80 -> frontend(nginx) :80 -> /api/ -> backend :8080
                                                     |
                    MySQL 8（compose 服务 mysql:3306）<+--> MinIO（外部）
```

- MySQL 默认走 compose 网络里的 `mysql` 服务；要连外部库就把 `SPRING_DATASOURCE_URL` 显式设成那个地址——代码里**没有任何远端默认值**（见 `docs/ISSUES.md` R-4）。
- 本地开发时后端跑在宿主机、MySQL 用 `docker compose --profile local-db` 起在 `127.0.0.1:12306`。
- 所有前端请求都走同源 `/api`，由 `frontend/nginx.conf` 反代到 `backend:8080`；Caddy 只负责 TLS 与透传 `Authorization`。
- **默认没有任何外部有状态服务**：撤销名单（退出登录拉黑）、在线状态、限流计数都存在进程内，
  重启即清空。多实例部署时把 `STATE_BACKEND` 设成 `redis`，这三样会改存 Redis
  （`com.biecuoguo.state` 下各有一对 memory / redis 实现），代码调用方不用改。
  仍然**没有消息队列、没有 CDN**；媒体走 MinIO，但上传下载都经后端转发（`MediaController`）。

## 2. 一次请求的链路

1. 浏览器带 `Authorization: Bearer <jwt>`。
2. `JwtAuthenticationFilter` 解析 token -> 校验签名/过期/黑名单 -> 构造 `CurrentUser` 放进 `SecurityContext`。
3. `SecurityConfig` 判断路径是否放行（见 `docs/API.md` §3）。
4. Controller 通过 `SecurityUtils.currentUser()` 取用户，调 Service。
5. Service 用 MyBatis-Plus 的 `LambdaQueryWrapper` 直接拼 SQL，返回 `record` DTO。
6. Controller 包成 `ApiResponse.ok(data)`，JSON 为 `{"data": ...}`。

注意：没有统一的分页对象、没有请求 ID、没有链路追踪、没有审计日志。

## 3. 后端分层

| 层 | 目录 | 职责 | 风险点 |
|---|---|---|---|
| Controller | `web/` | 路由、参数绑定、包 `ApiResponse` | 个别接口在 Controller 直接调 `SecurityUtils`，鉴权与白名单不一致 |
| Service | `service/` | 业务、事务、权限判断 | 权限判断手写且分散；`PostService` 640 行，`toView` 是 N+1 源头 |
| Mapper | `mapper/` | MyBatis-Plus 接口 | 无 XML，复杂查询靠 Wrapper 拼 |
| Domain | `domain/` | 表实体 | 时间字段全是 `LocalDateTime` |
| DTO | `dto/` | Java `record` | 与前端手写类型双维护 |
| Security | `security/` | JWT、过滤器、黑名单、当前用户 | 黑名单与在线状态存内存 |
| Config | `config/` | `AppProperties`、CORS、Security、Jackson、MyBatis-Plus | 缺 `application-prod.yml` |
| Migration | `db/migration/` | Flyway V1–V10 | 遗留域与新域混在同一套迁移里 |

## 4. 数据模型

### 4.1 内容域（新，前端在用）
| 表 | 说明 | 关键字段 |
|---|---|---|
| `posts` | PO 主表 | `board`、`source_type`、`official`、`pinned`、`dont_miss`、`status`、四个计数、`published_at` |
| `post_images` | 帖子图片 | `post_id`、`url`、`sort_order`（最多 9 张） |
| `post_likes` / `post_favorites` / `post_shares` | 互动 | 唯一键避重 |
| `post_daily_stats` | 每日热度 | `stat_date` + 计数，`hot-today` 依赖它 |
| `comments` | 帖子评论 | `post_id`、`parent_id`、`featured`（`notice_id` 已随 V11 删除） |
| `comment_likes` | 评论点赞 | V5 |

### 4.2 用户与组织域
| 表 | 说明 |
|---|---|
| `users` | 账号、资料、`public_uid`、`role`、`operator_scope`、等级 XP；V17 增加 `gender` / `birth_year` / `zodiac` / `mbti` / 展示开关 / `onboarding_completed` |
| `user_preferences` | 兴趣标签、推送偏好 |
| `user_follows` | 关注关系 |
| `user_blocks` | 有方向的拉黑事实；访问权限按双向关系判断 |
| `social_relationship_pair_locks` | V22，同一用户对一条事务锁记录；顺序为先用户共享状态锁、后 pair 锁 |
| `user_checkins` | 每日签到 |
| `schools` / `colleges` / `majors` | 学校 / 学院 / 专业三级组织 |
| `categories` / `tags` | 分类 / 标签字典，`/api/categories`、`/api/tags` 只读 |
| `verification_codes` | 邮箱验证码（V7） |

### 4.3 消息域
`conversations`（两人会话，`user_one_id < user_two_id` 去重）、`messages`（`read_flag` 布尔）。

### 4.4 媒体域
`media_objects`：`bucket` / `object_key` / `url` / `content_type` / `size_bytes` / `purpose`。
`url` 先存 `minio://bucket/key`，再由 `minio.publicEndpoint` 拼成可访问 URL 回写。

**存储策略（2026-10-20 定）**：
- **开发阶段（现在）**：本地 MinIO，不引入云依赖；
- **内测/生产阶段**：切腾讯云 COS + CDN，图片下载不再经过 ECS 带宽；
- 切换时加 `ObjectStorage` 抽象（MinIO / COS 两个实现），`/api/media/...` 契约不变；
  旧数据先双读（MinIO 走代理、COS 走 CDN），稳定后再迁移。
- **不要现在就加 COS 依赖**：没有云凭证、没有 CDN 域名，提前接只会让本地开发变复杂。

### 4.5 推送域（V18）
`device_tokens`：`user_id` / `token` / `platform` / `environment` / `last_seen_at`，token 唯一。
发送走 `PushService`（`push.api`）：默认 `LoggingPushService`；配置 `app.push.apns.enabled=true` 后
换成 `ApnsPushService`（Pushy + .p8），私信发送用虚拟线程异步推送，不阻塞接口。

### 4.6 成就域（V15）
| 表 | 说明 |
|---|---|
| `achievement_stages` | 阶段（大一上…大四），`stage_order`、`boss` |
| `achievement_tasks` | 阶段任务，`xp`、`badge_code` |
| `achievement_progress` | 用户任务完成记录，唯一键 `(user_id, task_id)` |
| `badges` | 徽章字典：`rarity` / `icon` / `sort_order` |
| `user_badges` | 用户已获徽章，唯一键 `(user_id, badge_code)` |

### 4.7 已删除的遗留域
~~`notices`、`notice_tags`、`user_notice_status`、`favorites`、`reminders`~~ —— 2026-10-20 由
`V11__drop_notices_domain.sql` 整体删除，对应 Java 文件（`NoticeController` / `NoticeService` /
`InteractionController` / `CommentController` 等 19 个）一并移除。

> 历史原因：项目最初是一套「校园通知/提醒」应用，后来整体重写成 PO 信息流，旧域从表到 Controller 保留了很久。
> 现在只有 `posts` 一套内容模型；`analytics_events` 仍保留但当前无人写入。

## 5. 客户端结构

### 5.1 iOS（主战场）

```text
ios/
├─ project.yml                 XcodeGen；改设置改这里
├─ UniPO/
│  ├─ App/UniPOApp.swift       入口，注入 AuthStore
│  └─ Resources/Info.plist     API_BASE_URL / ATS / 启动屏
└─ Packages/
   ├─ UniPOCore/               网络、鉴权、Keychain、模型
   ├─ UniPOUI/                 Design Tokens、GlassSurface、通用组件
   └─ UniPOFeatures/           Home / Tools / Compose / Achievements / Profile
```

- 最低 iOS 17；用 iOS 26 SDK 构建。Liquid Glass Dock 在 iOS 26+ 用 `glassEffect`，17–25 退化 `.ultraThinMaterial`。
- 认证：Bearer + Keychain；`APIClient` 统一处理错误、401 和 token 注入；**URLSession 禁用 Cookie**，避免和后端 CSRF 过滤器打架。
- Tab：首页 / 工具 / 发PO（中间悬浮按钮）/ 成就 / 我的。
- 3. 新增页面：`UniPOFeatures` 下加 View，在 `RootView` 挂 Tab 或 NavigationStack。
- 实时：WebSocket 规划中，复用 `/api/v1/realtime/ws` 的 `auth` 帧 + `lastEventId` 续传。

### 5.2 Web（分享 / 兜底 / 后台）

```text
frontend/src/
├─ main.ts / App.vue           外壳 + 会话引导
├─ router/index.ts             路由（含 /admin 子树）
├─ layouts/                    AppShell / AdminShell
├─ views/                      Home / Compose / Messages / Profile / UserProfile / admin/*
├─ components/                 layout / post / modals / ui / profile
├─ stores/                     feed / auth / messages / schedule / compose / profile
└─ lib/                        api.ts / adminApi.ts / realtime.ts / postHelpers.ts
```

- 组件化已完成（App.vue 从 4207 行降到 61 行），路由化 + Pinia，首屏三态（loading / error / empty），不再有 fallback 假数据。
- Web 认证用 httpOnly Cookie + CSRF；App 用 Bearer + Keychain；两套入口共用 `/api/v1`。
- 管理后台在同一个 SPA 的 `/admin` 子树，懒加载 + 角色守卫。

## 6. 部署

| 文件 | 作用 |
|---|---|
| `docker-compose.yml` | `mysql`（profile `local-db`）、`backend`、`frontend`、`caddy`；backend 的 `environment` 显式列变量 |
| `Caddyfile` | `{$CADDY_SITE_ADDRESS}` 反代 `frontend:80`，透传 `Authorization` |
| `frontend/nginx.conf` | `/api/` -> `backend:8080`，SPA fallback |
| `deploy-mysql.sh` | 只起 MySQL（服务器 A） |
| `deploy-app.sh` | 校验 `.env` 与 `required_vars`，TCP 探测 MySQL，然后 `build` + `up` |
| `deploy-ip.sh` / `deploy.sh` | 无域名 / 兼容入口 |

环境变量分三处维护且**已经漏项**（`VERIFICATION_MOCK_ACCEPT_ANY_CODE` 不在 compose、不在 `.env.example`、不在 `required_vars`）。这是 P0 漏洞成立的直接原因。

## 7. 技术债地图

- 安全：见 `docs/ISSUES.md` §1。
- 性能：`PostService.toView` 每条 7 次 SQL；列表无 limit；私信全量加载。
- 结构：单内容域（posts）；前端已组件化；无测试无 CI。
- 可演进性：接口无版本；时间无时区；响应无分页元数据 —— 这三项会直接卡住 App 开发，方案见 `docs/MOBILE-API-PLAN.md`。
