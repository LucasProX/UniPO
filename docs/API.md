# API 参考（现状快照）

> 快照日期 2026-10-20，commit 见 git log。当前接口**已版本化**：base path 同时提供 `/api`（旧契约，保留兼容）与 `/api/v1`（新契约，前端已切）。

## 1. 通用约定

| 项 | 现状 |
|---|---|
| 认证 | `Authorization: Bearer <jwt>`；无状态、无刷新令牌、有效期 10080 分钟 |
| 成功响应 | HTTP 200，body `{"code":"OK","message":"","data": <T>, "requestId":"e2075084cb304be9"}` |
| 错误响应 | 4xx/5xx，body `{"code":"AUTH_INVALID_CREDENTIALS","message":"中文提示","data":null,"requestId":"...","timestamp":"ISO-8601 Instant"}` |
| 业务错误码 | 见 §1.3。**App 端 switch `code`，不要匹配中文 `message`**（文案随时会改） |
| requestId | 每个请求分配一个，同时回写响应头 `X-Request-Id` 并进服务端日志 MDC；用户报障时拿它对日志 |
| 版本前缀 | `/api` 与 `/api/v1` 等价，由 `security/ApiVersionPrefixFilterConfig.java` 把 `/api/v1/**` 改写为 `/api/**` 实现。`/api/v1` 下的 v1 独有端点（目前只有 `posts/page`）不会被改写，自己挂前缀 |
| 分页 | **仅 `/api/v1/posts/page`**，游标分页（见 §1.2）。其余列表仍全量返回 |
| 时间 | `OffsetDateTime`，ISO-8601 **带偏移**：`"2026-10-19T15:22:01+08:00"`。偏移由 `app.time-zone`（默认 `Asia/Shanghai`）决定 |
| 未登录访问受保护接口 | 401 `{"code":"AUTH_UNAUTHENTICATED","message":"请先登录",...}` |
| 富文本 | 帖子 `content` 直接是 HTML 片段，前端用 `sanitizeRichHtml` 清洗后 `v-html` |
| Swagger | `/swagger-ui.html`、`/v3/api-docs` 已放行（springdoc） |

### 1.1 `/api/v1` 演进规则（只做加法）

- 加字段、加端点、加可选参数：**不升版本**。
- 删字段、改字段语义、改字段类型、改排序规则：**升 `v2`**。
- 老版本至少保留 2 个发布周期再下线。

## 1.2 游标分页（`GET /api/v1/posts/page`）

| 参数 | 默认 | 上限 | 说明 |
|---|---|---|---|
| `tab` | `recommend` | — | `recommend` / `school` / `college` / `major` |
| `cursor` | 无 | — | 上一页返回的 `nextCursor`，**原样回传，不要解析或拼接** |
| `limit` | 20 | 50 | 每页条数 |

响应：

```json
{
  "code": "OK",
  "message": "",
  "requestId": "e2075084cb304be9",
  "data": {
    "items": ["...PostView..."],
    "nextCursor": "false|false|false|186|34|2026-10-19T15:22:01|5",
    "hasMore": true,
    "limit": 20
  }
}
```

- `nextCursor` 是上一页最后一条的**完整排序键**（`pinned|official|dontMiss|likeCount|commentCount|publishedAt|id`，全 DESC）。之所以不只编码 `id`：排序按热度/权重排，id 小的帖子可能排在 id 大的前面，只按 `id < cursor` 过滤会把排在前面的帖子重复吐出来。
- `hasMore` 为 `false` 时 `nextCursor` 为 `null`，不要继续请求。
- 游标非法返回 **400** `{"code":"VALIDATION_FAILED","message":"分页游标无效",...}`（不会静默降级成第一页）。
- 同一 tab 内翻页；换 tab 必须重新从第一页开始，游标和排序键是按 tab 的过滤条件生成的。

枚举值：
- `board`：`recommend` / `school` / `college` / `major`
- `role`：`USER` / `OPERATOR` / `SCHOOL_OPERATOR` / `COLLEGE_OPERATOR` / `MAJOR_OPERATOR` / `ADMIN`
- `status`(post)：`published` / `deleted`

### 1.3 业务错误码（`web/ErrorCode.java`）

`code` 是**稳定契约**：只新增，不改名、不改含义。HTTP 状态码同时保留语义。

| code | HTTP | 含义 |
|---|---|---|
| `AUTH_INVALID_CREDENTIALS` | 401 | 邮箱或密码不正确 |
| `AUTH_UNAUTHENTICATED` | 401 | 未登录 / token 失效 |
| `AUTH_FORBIDDEN` | 403 | 已登录但无权限 |
| `AUTH_VERIFICATION_CODE_INVALID` | 400 | 验证码错或过期 |
| `AUTH_EMAIL_TAKEN` | 400 | 邮箱已注册 |
| `AUTH_NICKNAME_TAKEN` | 400 | 昵称被占用 |
| `POST_NOT_FOUND` | 404 | 帖子不存在或已删除 |
| `POST_DELETE_FORBIDDEN` | 403 | 只能删自己的帖子 |
| `POST_SCHOOL_REQUIRED` | 400 | 发帖前必须先选学校 |
| `COMMENT_NOT_FOUND` | 404 | 评论不存在或已删除 |
| `USER_NOT_FOUND` | 404 | 用户不存在 |
| `USER_ALREADY_FOLLOWED` | 400 | 重复关注 |
| `USER_NOT_FOLLOWED` | 400 | 未关注就取关 |
| `CONVERSATION_NOT_FOUND` | 404 | 会话不存在 |
| `MSG_EMPTY` | 400 | 消息内容为空 |
| `MEDIA_UPLOAD_FAILED` | 500 | 上传失败 |
| `MEDIA_TOO_LARGE` | 413 | 超过 5MB |
| `VALIDATION_FAILED` | 400 | 参数不完整/格式错（含非法分页游标） |
| `DUPLICATE` | 400 | 名称或账号已被占用 |
| `NOT_FOUND` | 404 | 通用资源不存在 |
| `RATE_LIMITED` | 429 | 触发限流；响应头带 `Retry-After`（秒），`message` 里也写了要等多久 |
| `INTERNAL` | 500 | 服务端未预期异常（不会回显异常详情，详情在日志） |

## 2. 认证 `/api/auth`

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/register` | 公开 | 注册。必填 `email`（账号，任意字符串）+ `password` + `verificationCode`；`nickname` / `confirmPassword` 可选 |
| POST | `/login` | 公开 | 邮箱或用户名。`good` / `xiaoba` / `yuanhua` 三个别名只在本地 `seed-demo-data=true` 时有意义 |
| POST | `/logout` | 登录 | 把 jti 加入内存黑名单 |
| POST | `/heartbeat` | 登录 | 在线心跳（内存 Map） |
| GET | `/me` | 登录 | 当前用户 |
| POST | `/verification-code` | 公开 | 发验证码。真实模式走 SMTP 真发信；`mock-accept-any-code=true`（本地）时不发信、不落库，任意 4 位数字可用 |
| POST | `/verification-code/verify` | 公开 | 单独校验验证码（注册/重置密码内部也会校验） |
| POST | `/reset-password` | 公开 | 重置密码；**mock 模式下任意 4 位数字通过** |
| GET | `/email-availability?email=&purpose=` | 公开 | 邮箱是否可用；`purpose=reset-password` 时等价于"该邮箱是否已注册" |
| GET | `/nickname-availability?nickname=` | 公开 | 昵称是否可用 |

## 3. PO 信息流 `/api/posts`

> 2026-10-20 已消除 N+1：`list` / `hotToday` / `dontMiss` / `interactionNotices` 的
> 作者、图片、学校/学院/专业字典、当前用户点赞收藏，全部改为 `in(...)` 批量查 +
> `groupingBy` 归组，一次请求的 SQL 条数从 O(N) 降到常数（实测 12 帖 72 → 7 条，
> 6 会话 18 → 4 条）。**新增列表接口必须沿用这个模式**，不要回到 per-row 查询。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/?tab=recommend\|school\|college\|major` | 公开 | 主 feed，**全量返回**（旧契约，`/api` 独有），按 pinned/official/dontMiss/热度/时间排序。新代码请用 `/api/v1/posts/page` |
| GET | `/page?tab=&cursor=&limit=` | 公开 | 主 feed，**游标分页**（`/api/v1` 独有，见 §1.2）。返回 `{items, nextCursor, hasMore, limit}` |
| GET | `/boards` | 公开 | 四个板块 + 各自总数 |
| GET | `/hot-today` | 公开 | 今日热帖；无数据时回落到全量 recommend |
| GET | `/dont-miss` | 公开 | 别错过（带 deadline） |
| GET | `/interaction-notices` | 登录 | 互动通知；**路径命中 GET 白名单，未登录实际 401** |
| POST | `/` | 登录 | 发帖；需先选学校；官方/置顶有角色限制 |
| DELETE | `/{id}` | 登录 | 软删除（`status=deleted`），仅作者 |
| GET | `/{id}/comments` | 公开 | 全部评论 + 楼中楼 |
| POST | `/{id}/comments` | 登录 | 评论（`parentId` 支持回复） |
| POST/DELETE | `/comments/{id}/like` | 登录 | 评论点赞（注意：路径在 `/api/posts` 下） |
| POST/DELETE | `/{id}/like` | 登录 | 帖子点赞 |
| POST/DELETE | `/{id}/favorite` | 登录 | 收藏 |
| POST | `/{id}/share` | 登录 | 分享计数 +1 |

## 4. 用户 `/api/users`

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/me` | 登录 | 我的资料 |
| PUT | `/me` | 登录 | 改资料（昵称/头像/学校/学院/专业/年级/简介） |
| PUT | `/me/preferences` | 登录 | 兴趣与偏好 |
| GET | `/me/stats` | 登录 | 发帖/获赞/关注统计 |
| GET | `/me/check-in` | 登录 | 今日是否已签到 |
| POST | `/me/check-in` | 登录 | 签到 |
| GET | `/me/posts` | 登录 | 我的 PO |
| GET | `/me/likes` | 登录 | 我赞过的 |
| GET | `/me/post-favorites` | 登录 | 我收藏的 |
| GET | `/{uid}` | 公开 | 他人主页（`uid` = `public_uid`） |
| GET | `/{uid}/posts` | 公开 | 他人 PO |
| GET | `/{uid}/likes` | 公开 | 他人赞过的 |
| GET | `/{uid}/post-favorites` | 公开 | 他人收藏 |
| POST/DELETE | `/{uid}/follow` | 登录 | 关注 / 取关 |

## 5. 私信 `/api/messages`（全部登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/conversations` | 会话列表（每个会话 3 次额外 SQL） |
| POST | `/conversations` | 按 `peerId` 或 `peerUid` 创建/复用会话 |
| GET | `/conversations/{id}/messages` | **全量**消息 + 标记已读 |
| POST | `/conversations/{id}/messages` | 发消息 |
| GET | `/unread-count` | 未读数 |

## 6. 媒体 `/api/media`

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/upload` (multipart: `file`, `purpose`) | 登录 | **按文件头**识别 JPEG/PNG/GIF/WebP（改扩展名或 Content-Type 都会被拒）；上限 5MB |
| GET | `/{id}/content? v=` | 公开 | 经后端流式转发 MinIO；带 `v` 时 30 天强缓存 |

## 7. 选项与分类（全部公开）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/categories` `/api/tags` | 遗留域的分类 / 标签 |
| GET | `/api/schools` | 学校列表 |
| GET | `/api/options/schools` | 下拉选项 |
| GET | `/api/options/colleges?schoolId=` | 学院选项 |
| GET | `/api/options/majors?schoolId=&collegeId=` | 专业选项 |
| GET | `/api/options/grades` | 年级选项 |

## 8. 已删除的遗留域

`notices` / `notices/{id}/comments` / `favorites` / `reminders` / `comments/{id}/like` 这一整套端点
已于 2026-10-20 随 notices 域清理整体下线（`NoticeController`、`InteractionController`、
`CommentController` 三个文件删除，`V11__drop_notices_domain.sql` 删表）。现在再调这些路径会得到
**404**，不是 401/403。

保留的相邻端点：`GET /api/categories`、`GET /api/tags`、`GET /api/schools`（campus taxonomy，与 notices 无关），
以及 `GET|POST /api/posts/{id}/comments`、`POST|DELETE /api/posts/comments/{id}/like`（帖子评论，在 `PostController` 里）。

## 9. 鉴权白名单（`security/SecurityConfig.java`）

公开：
- `/actuator/health`、`/swagger-ui/**`、`/v3/api-docs/**`
- `/api/auth/register`、`/login`、`/refresh`、`/verification-code`、`/verification-code/verify`、`/reset-password`、`/email-availability`、`/nickname-availability`
- `GET /api/categories`、`/api/tags`、`/api/schools`
- `GET /api/posts/**`、`GET /api/options/**`
- `GET /api/media/*/content`
- `GET /api/users/*`、`/api/users/*/posts`、`/likes`、`/post-favorites`

其余需登录；` /api/admin/**` 需要 `OPERATOR / SCHOOL_OPERATOR / COLLEGE_OPERATOR / MAJOR_OPERATOR / ADMIN` 之一。

> 隐患：公开的 `GET /api/users/{uid}` 系列可被用于批量枚举用户；`email-availability?purpose=reset-password` 可直接判定邮箱是否注册。

### 9.1 两种 token（2026-10-20 起）

`POST /api/auth/login`、`/register`、`/refresh` 都返回同一个结构：

```json
{"code":"OK","data":{"token":"<access>","refreshToken":"<refresh>","expiresInSeconds":7200,"user":{...}}}
```

| | 有效期 | 用途 | 存哪 |
|---|---|---|---|
| access（`typ=access`） | `JWT_EXPIRATION_MINUTES`，默认 120 分钟 | 放进 `Authorization: Bearer` 调接口 | Web：**不用**（走 httpOnly Cookie 会话）／App：Keychain |
| refresh（`typ=refresh`） | `JWT_REFRESH_EXPIRATION_MINUTES`，默认 20160 分钟（14 天） | 只能调 `POST /api/auth/refresh` | Web：**不用**／App：Keychain |

> Web 端登录后服务端下发 `unipo_sid`（HttpOnly）+ `unipo_csrf`（可读）两个 Cookie，
> 会话存 Redis，7 天滑动过期；写请求由前端自动带 `X-CSRF-Token`。
> 响应体里的 token 只给 App 用，Web 忽略。

- **两种 token 不能混用**：拿 refresh 当 access 调业务接口是 401，拿 access 去 `/auth/refresh` 也是 401。
- `expiresInSeconds` 是 access 的剩余秒数，客户端可据此提前刷新，不必等 401。
- `POST /api/auth/logout` 可选带 `{"refreshToken":"..."}`，把 access 与 refresh 一起拉黑；不带也能退出（只拉黑 access）。
- 刷新**不轮换** refresh token：换出来的 refresh 还是原来那一个，直到过期或退出登录。原因见 DEVLOG 同名条目（拉黑表是进程内的，轮换会在多标签页/多实例下误踢人）。

### 9.2 首页轮播位（spotlights）

**公开只读**：`GET /api/spotlights`（`/api/v1/spotlights` 等价）。返回当前生效的条目：
`enabled=true` 且处于 `startsAt/endsAt` 时间窗内，按 `weight` 倒序、同权重按 id 倒序，最多 5 条。
没有数据返回空数组，前端不渲染——**不要在这里兜底默认文案**。

| 字段 | 说明 |
|---|---|
| `title` / `subtitle` / `badge` | 文案，标题必填（≤80 字） |
| `imageUrl` | 可为空；为空时前端用品牌渐变兜底，不依赖外链图片 |
| `linkUrl` | 站内路径（`/...`）或外链；前端据此路由/新窗口打开 |
| `source` | `MANUAL`（运营手动）或 `ALGORITHM`（算法位，预留） |
| `referencePostId` | 算法位指回帖子；手动位为空 |
| `weight` | 越大越靠前；运营置顶就调大 |
| `startsAt` / `endsAt` | 投放时间窗，为空表示不限制 |

**后台管理**（需要 `OPERATOR / SCHOOL_OPERATOR / COLLEGE_OPERATOR / MAJOR_OPERATOR / ADMIN`）：

- `GET /api/admin/spotlights`：全部（含已下线）
- `POST /api/admin/spotlights`：新建
- `PUT /api/admin/spotlights/{id}`：编辑
- `DELETE /api/admin/spotlights/{id}`：删除

> 目前只有 API，还没有管理后台页面。算法推荐接进来时**不要改公开接口**：
> 要么让算法任务写 `source='ALGORITHM'` 的行，要么在 `SpotlightService.active()` 里合并算法结果；
> 两条路都保留 `weight` 作为运营置顶的优先级。

### 9.3 管理后台 API（MVP）

统一前缀 `/api/admin`，需要 `OPERATOR / SCHOOL_OPERATOR / COLLEGE_OPERATOR / MAJOR_OPERATOR / ADMIN` 之一。
**所有写操作都会写 `admin_audit_logs`**（actor / action / target / detail / requestId）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/analytics/overview` | 用户/帖子/已发布/评论总量 |
| GET | `/api/admin/users` | 用户列表 |
| PUT | `/api/admin/users/{id}/status` | 封禁/解封（`active` / `disabled`） |
| PUT | `/api/admin/users/{id}/role` | 改角色与运营范围 |
| GET | `/api/admin/posts?keyword=&status=&limit=` | 帖子搜索（含已下架） |
| PATCH | `/api/admin/posts/{id}` | 置顶/官方/免错过/上下架（部分更新） |
| GET/POST/PUT/DELETE | `/api/admin/spotlights[/{id}]` | 轮播位 CRUD（见 9.2） |
| GET | `/api/admin/reports/daily?days=14` | 近 N 天新增用户/发布帖子（缺失日期补 0） |
| GET | `/api/admin/monitor/summary` | 健康状态、运行时长、堆内存、实时连接、事件/限流计数 |
| GET | `/api/admin/audit-logs?limit=50` | 最近审计记录 |

`PATCH /api/admin/posts/{id}` 请求体：`{"pinned":true,"official":false,"dontMiss":true,"status":"removed"}`，
只更新传入的字段；`status` 只允许 `published / removed / draft`。

前端页面在同一个 SPA 的 `/admin` 下（`layouts/AdminShell.vue` + `views/admin/*`），
路由守卫 + 后端 `hasAnyRole` 双层校验。

### 9.4 关注 / 粉丝 / 拉黑 / 举报

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/users/{uid}/followers` | 粉丝列表（匿名可看，登录时带 `following` 标记） |
| GET | `/api/users/{uid}/following` | 关注列表 |
| DELETE | `/api/users/me/followers/{followerUid}` | 移除自己的粉丝 |
| GET | `/api/users/me/followers/new-count` | 上次打开粉丝列表后新增的粉丝数 |
| POST | `/api/users/me/followers/seen` | 把 `users.followers_seen_at` 推到当前时间 |
| POST / DELETE | `/api/users/{uid}/block` | 拉黑 / 取消拉黑；拉黑会双向取关 |
| POST | `/api/users/{uid}/report` | 举报用户 |

**新粉丝 +n**：服务端用 `users.followers_seen_at` 做游标，第一次没有该值时为 0（不补历史），
打开粉丝列表后调用 `/seen` 清零，跨设备一致。

**列表项**（`FollowUserView`）：id / publicUid / nickname / avatarUrl / role / level / levelTitle /
college / major / grade / following / followedAt；不返回 email 等敏感字段。

**举报分类**（后端校验，稳定 key）：`spam` 垃圾广告、`fraud` 诈骗虚假信息、`harassment` 人身攻击、
`porn` 色情低俗、`impersonation` 冒充他人、`other` 其他。请求体：

```json
{ "category": "spam", "detail": "补充说明（选填，≤500 字）" }
```

**拉黑的语义（MVP）**：双向取关、禁止再关注；公开主页返回 `blocked / blockedBy`，
前端据此隐藏关注/私信入口。**信息流过滤还没接**（被拉黑用户的帖子仍可能出现在推荐里），
下一步在 `PostService` 的可见性条件里排除双向拉黑关系。

## 10. 前端调用映射（`frontend/src/lib/api.ts`）

| 前端方法 | 实际请求 |
|---|---|
| `campusApi.listPosts(tab)` | `GET /api/posts?tab=` |
| `campusApi.boards()` | `GET /api/posts/boards` |
| `campusApi.hotToday()` / `dontMiss()` | 同名接口 |
| `postApi.create / like / favorite / comments` | `/api/posts/**` |
| `authApi.login / me / heartbeat / logout` | `/api/auth/**` |
| `userApi.profile / stats / checkIn` | `/api/users/me/**` |
| `mediaApi.upload` | `POST /api/media/upload` |
| 消息推送长连接 | `WS /api/v1/realtime/ws`（WebSocket；连上先收 `ready`，新私信是 `event: message`） |
| 会话兜底轮询 | `GET /api/messages/conversations`（3s，WS 连上后不发请求） |
| 当前会话兜底轮询 | `GET /api/messages/conversations/{id}/messages`（1.8s，同上） |

> `api.ts` 里的 `localApi` 在 DEV 且同源请求失败时直连 `http://127.0.0.1:8080/api`，仅用于本地调试。

### 10.1 消息推送（WebSocket）

`WS /api/v1/realtime/ws`（开发环境走 Vite 代理，`ws: true`）。JSON 文本帧。

**鉴权**（两种，任选其一）：

- 浏览器：握手自动带 httpOnly 会话 Cookie（`unipo_sid`），握手拦截器直接解析出用户；
- App / 脚本：连上后发 `{"type":"auth","token":"<access token>"}`。没认证成功的连接只收到 `auth-required` / `error`。

**客户端 → 服务端**

| type | 字段 | 说明 |
|---|---|---|
| `auth` | `token` | Bearer 登录（有 Cookie 时不需要） |
| `resume` | `lastEventId` | 断线重连后续传，服务端补发该序号之后最多 100 条 |
| `ping` | — | 应用层心跳，服务端回 `pong` |
| `ack` | `seq` | 客户端确认已渲染（当前只记日志，不做服务端重发） |

**服务端 → 客户端**

| type | 字段 | 说明 |
|---|---|---|
| `ready` | `userId, ts` | 认证成功、可以收事件 |
| `event` | `event, seq, ts, payload` | 业务事件；目前只有 `message` |
| `pong` | `ts` | 心跳响应 |
| `error` | `code, message` | 统一错误码（如 `AUTH_UNAUTHENTICATED`） |

- 事件带全局自增 `seq`，客户端记住它；重连带 `lastEventId` 就能补上断线期间的消息。
- 心跳：客户端 25 秒发一次 `ping`，60 秒收不到 `pong` 主动重连（半开连接检测）。
- 多实例：事件先落 Redis Stream（每用户最近 500 条），再经 Redis Pub/Sub 广播给所有实例；
  单机则是进程内回调。切换只由 `STATE_BACKEND` 决定，业务代码不变。
- 推送只是加速通道：WS 断开时前端自动退回原有轮询，离线消息仍靠 `/messages/unread-count` 拉。

## 11. 已知不一致

1. `GET /api/posts/interaction-notices` 在公开白名单里但方法要求登录 -> 401 而非 403。
2. 评论点赞有两套路径（见 §8）。
3. ~~全部列表无分页~~ 已补：`GET /api/v1/posts/page` 用游标分页（旧的 `/api/posts` 全量列表仍保留）。
4. ~~没有接口版本号~~ 已补：`/api/v1/**`，见 §1 的版本化说明。
5. refresh token **不轮换**，也不落库——进程内黑名单重启即清空（见 ISSUES Q-4）。
5. 错误体没有业务错误码，App 只能靠中文 message 判断类型。
