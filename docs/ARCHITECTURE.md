# 系统架构

> 快照日期 2026-10-19，对应 commit `05a265d`。

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
| `users` | 账号、资料、`public_uid`、`role`、`operator_scope`、等级 XP |
| `user_preferences` | 兴趣标签、推送偏好 |
| `user_follows` | 关注关系 |
| `user_checkins` | 每日签到 |
| `schools` / `colleges` / `majors` | 学校 / 学院 / 专业三级组织 |
| `categories` / `tags` | 分类 / 标签字典，`/api/categories`、`/api/tags` 只读 |
| `verification_codes` | 邮箱验证码（V7） |

### 4.3 消息域
`conversations`（两人会话，`user_one_id < user_two_id` 去重）、`messages`（`read_flag` 布尔）。

### 4.4 媒体域
`media_objects`：`bucket` / `object_key` / `url` / `content_type` / `size_bytes` / `purpose`。
`url` 先存 `minio://bucket/key`，再由 `minio.publicEndpoint` 拼成可访问 URL 回写。

### 4.5 已删除的遗留域
~~`notices`、`notice_tags`、`user_notice_status`、`favorites`、`reminders`~~ —— 2026-10-20 由
`V11__drop_notices_domain.sql` 整体删除，对应 Java 文件（`NoticeController` / `NoticeService` /
`InteractionController` / `CommentController` 等 19 个）一并移除。

> 历史原因：项目最初是一套「校园通知/提醒」应用，后来整体重写成 PO 信息流，旧域从表到 Controller 保留了很久。
> 现在只有 `posts` 一套内容模型；`analytics_events` 仍保留但当前无人写入。

## 5. 前端结构

```text
frontend/src/
├─ main.ts            挂载 Vue + Pinia（Pinia 装了但没用）
├─ App.vue            4207 行，全部页面/状态/轮询/假数据
├─ style.css / tailwind
└─ lib/
   ├─ api.ts          axios 实例 + 各域封装 + 本地回退
   ├─ types.ts        手写业务类型（枚举收窄、字段必填）
├─ api-types.ts    由后端 /v3/api-docs 生成（npm run gen:api），只读
├─ api-contract.ts 类型断言，保证 types.ts 不落后于后端 DTO
   └─ ...
```

- 路由：**没有** vue-router。用 `ViewKey = 'home' | 'schedule' | 'compose' | 'messages' | 'profile' | 'user-profile'` 手写切换，无 URL、无前进后退、无深链接。
- 组件：除 App.vue 外几乎没有页面级组件；352 个顶层声明。
- 数据：接口失败时回落到 `fallbackPosts` / `fallbackComments` / `fallbackConversations` / `fallbackCourses`，首屏初始值也是假数据。
- 课程表（`schedule`）、公告轮播、spotlight 全部是前端硬编码，**后端没有任何对应接口**。

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
