# API 参考（现状快照）

> 快照复核日期 2026-10-22。当前接口**已版本化**：base path 同时提供 `/api`（旧契约，保留兼容）与 `/api/v1`（新契约，前端已切）。

## 1. 通用约定

| 项 | 现状 |
|---|---|
| 认证 | App 使用 `Authorization: Bearer <access>`；access 默认 120 分钟，refresh 默认 14 天且只用于刷新/退出；JWT 的 `typ` 区分两者，持久设备会话与用户 `auth_version` 负责撤销 |
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
| `STATE_CONFLICT` | 409 | 资源状态或幂等标识已变化，客户端应刷新后决定是否重试 |
| `INTERNAL` | 500 | 服务端未预期异常（不会回显异常详情，详情在日志） |

## 2. 认证 `/api/auth`

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/register` | 公开 | 注册。必填 `email`（账号，任意字符串）+ `password` + `verificationCode`；`nickname` / `confirmPassword` 可选 |
| POST | `/login` | 公开 | 邮箱或用户名。`good` / `xiaoba` / `yuanhua` 三个别名只在本地 `seed-demo-data=true` 时有意义 |
| POST | `/logout` | 当前设备凭据 | 撤销该设备所有派生 token；access 过期时可只用有效 refresh；Cookie 写请求仍要求 CSRF |
| POST | `/logout-all` | 登录 | 撤销本人所有设备，并清当前 Cookie |
| POST | `/change-password` | 登录 | `oldPassword/password/confirmPassword?`，修改后撤销所有旧设备 |
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
| GET | `/{id}` | 公开 | 帖子详情，可见性与 feed 相同（同校、未删除、未拉黑），否则 404；通知里的 `postId` 用它打开 |
| GET | `/linked?activityId=\|taskId=&limit=` | 公开 | 关联到某场活动或某个主线任务的帖子（最新在前，默认 20、上限 50），二选一必填，否则 400 |
| GET | `/following?cursor=&limit=` | 登录 | 关注的人的同校帖子，按发布时间倒序，返回 `{items,nextCursor,hasMore,limit}`；游标为 `publishedAt\|id`，非法 400；未登录 401（路径在 GET 白名单内，Service 判断） |
| GET | `/search?q=&cursor=&limit=` | 公开 | 搜标题、正文、标签（ngram 全文索引，整词按短语匹配），可见性同信息流，按发布时间倒序；`q` 去引号后 2–40 字，否则 400；每 IP 每分钟 60 次 |
| POST | `/` | 登录 | 发帖；需先选学校；官方/置顶有角色限制 |
| DELETE | `/{id}` | 登录 | 软删除（`status=deleted`），仅作者 |
| GET | `/{id}/comments` | 公开 | 全部评论 + 楼中楼 |
| POST | `/{id}/comments` | 登录 | 评论（`parentId` 支持回复）；可选 `Idempotency-Key` |
| POST/DELETE | `/comments/{id}/like` | 登录 | 评论点赞（注意：路径在 `/api/posts` 下） |
| POST/DELETE | `/{id}/like` | 登录 | 帖子点赞 |
| POST/DELETE | `/{id}/favorite` | 登录 | 收藏 |
| POST | `/{id}/share` | 登录 | 分享计数 +1；可选 `Idempotency-Key` |

### 3.1 评论与分享幂等键

评论和分享可带 `Idempotency-Key` 请求头。允许值为 1–64 个 ASCII 字符，字符集为 `[A-Za-z0-9._:-]`；键在各自操作类型内按用户唯一，评论键与分享键互不冲突。

- 相同用户、相同键和相同操作内容的重试会返回第一次写入的结果，不重复增加计数、经验值或统计。
- 评论键绑定帖子、父评论和评论正文；分享键绑定帖子。将旧键用于不同内容或另一篇帖子会返回 400，编辑后的新操作应生成新键。
- 不带该头的旧客户端保持兼容，但请求失败后自行重发不具备去重保证。
- 幂等记录不绕过资源权限与状态检查。若首次操作已提交、响应丢失，之后帖子被删除或下架，再次重放可能得到 404；不会产生第二次副作用。

## 4. 用户 `/api/users`

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/me` | 登录 | 我的资料 |
| PUT | `/me` | 登录 | 改资料（昵称/头像/背景/学校/学院/专业/年级/简介） |
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
| GET | `/conversations` | 旧版全量会话列表，兼容保留 |
| GET | `/conversations/page?cursor=&limit=` | 会话游标分页；默认 20、上限 50 |
| GET | `/conversations/{id}` | 精确读取一条会话，不要求它位于首屏 |
| POST | `/conversations` | 按 `peerId` 或 `peerUid` 创建/复用会话 |
| GET | `/conversations/{id}/messages` | 旧版全量历史 + 标记已读，兼容保留 |
| GET | `/conversations/{id}/messages/page?cursor=&afterId=&limit=` | 历史或断线增量分页；`cursor` 与 `afterId` 互斥，默认 40、上限 50 |
| POST | `/conversations/{id}/messages` | 发消息；可带 `clientMessageId` 进行显式幂等重试 |
| GET | `/unread-count` | 未读数 |

会话游标绑定当前用户及完整活动排序键；历史游标绑定当前用户、会话和方向。普通历史页按消息 ID 倒序返回；`afterId` 首次请求固定当时最大消息 ID，并按 ID 正序返回本轮缺失消息，后续使用 `nextCursor` 继续。非法、跨用户或跨会话游标返回 400。

`clientMessageId` 为 1 至 64 位 ASCII 字母、数字及 `._:-`。同一用户以同一标识、会话和正文重试时返回原消息；换正文或会话返回 409 / `STATE_CONFLICT`。服务端响应会回传该字段，旧消息和旧客户端允许为空。

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
- `POST /api/auth/logout` 可选带 `{"refreshToken":"..."}`，撤销凭据所属设备的全部 access/refresh（含历史刷新结果）和 Cookie 载体；另一设备不受影响。有效 refresh 可以在 access 过期后完成退出。两份可验证凭据必须属于同一用户和设备。
- 刷新返回新的 token 字符串，保留同一设备 ID、身份版本和绝对期限；并发刷新不互相消费旧 refresh，不延长设备寿命。
- V20 起 access/refresh 必须含 `av/sid`。每次认证查询持久设备和用户版本；重置、改密、封禁、解封、降权、注销和 logout-all 后拒绝旧身份。旧格式 JWT/Cookie 需重新登录一次。
- `Authorization` 存在时不会回退 Cookie；未知身份状态不授权。部署不得混跑接受旧 JWT 的节点，回滚也必须保留新的撤销校验，见 [ADR-0003](adr/0003-authentication-revocation.md)。

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
其中 `/api/admin/users` 和其子路径仅允许 **ADMIN**，HTTP 与 Service 方法双重限制；普通运营不得提权、封号或读取完整用户列表。
**所有写操作都会写 `admin_audit_logs`**（actor / action / target / detail / requestId）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/analytics/overview` | 用户/帖子/已发布/评论总量 |
| GET | `/api/admin/users` | ADMIN 用户列表，不返回 passwordHash/authVersion |
| PUT | `/api/admin/users/{id}/status` | ADMIN 封禁/解封（仅 `active` / `disabled`），同时撤销旧身份 |
| PUT | `/api/admin/users/{id}/role` | ADMIN。**旧接口，保留兼容**（ADR-0006）：`ADMIN`/`USER` 改平台角色（`USER` 同时撤销此人全部授权）；运营角色撤销现有授权后，按此人当前资料里的学校/学院/专业新建一条授权（理由「旧接口授予」，到学年结束）；资料缺对应范围返回 400。同时撤销旧会话；审计在同一事务必需写入。新代码用 `/api/admin/role-grants` |
| GET | `/api/admin/role-grants?userUid=&scopeType=&scopeId=&includeInactive=` | ADMIN。授权列表（最多 200 条，默认只看有效的） |
| POST | `/api/admin/role-grants` | ADMIN。`{userUid, role: CONTENT_EDITOR\|SCHOOL_OPERATOR\|COLLEGE_OPERATOR\|MAJOR_OPERATOR\|MODERATOR, scopeId?, reason, expiresAt?}`；范围层级由角色决定（内容编辑不带 scopeId），范围不存在 400，同一人同角色同范围已有有效授权 409，`expiresAt` 省略为当前学年 8 月 31 日 23:59:59 |
| POST | `/api/admin/role-grants/{id}/revoke` | ADMIN。`{reason}`；已撤销 409。撤销后下一次请求即失去权限 |
| GET | `/api/admin/posts?keyword=&status=&limit=` | 帖子搜索（含已下架），**只返回调用者授权范围内的帖子**：学校授权看全校，学院授权看本院的院 PO 与专业 PO，专业授权看本专业的专业 PO。`PATCH /{id}` 改 `status` 需要下架权限、改 `pinned` 需要置顶权限、改 `official`/`dontMiss` 需要官方帖权限，均按帖子范围判断，越权 403 |
| PATCH | `/api/admin/posts/{id}` | 置顶/官方/免错过/上下架（部分更新） |
| GET/POST/PUT/DELETE | `/api/admin/spotlights[/{id}]` | 轮播位 CRUD（见 9.2） |
| GET | `/api/admin/reports/daily?days=14` | 近 N 天新增用户/发布帖子（缺失日期补 0） |
| GET | `/api/admin/monitor/summary` | 健康状态、运行时长、堆内存、实时连接、事件/限流计数 |
| GET | `/api/admin/audit-logs?limit=50` | 最近审计记录。2026-10-25 起与报表、监控、轮播管理一样**只给 ADMIN** |

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

**拉黑语义（DP03）**：双向取关，并禁止任一方向再次关注。只要任一方拉黑另一方，双方均不能通过服务端读取对方的作品、喜欢/收藏内容、评论或私信；信息流、今日热门和必看列表也排除被拉黑作者。主页保留 `blocked / blockedBy` 关系状态，供本人取消自己发起的拉黑；只有 `blocked=true` 时本人可取消，`blockedBy=true` 仅表示对方拉黑了自己。

私信会话列表和未读统计在拉黑期间隐藏对应会话；创建会话、读取历史、标记已读和发送均在产生任何副作用前拒绝。已有消息不删除，解除所有方向的拉黑后恢复读取。被拒绝的发送不得写入消息、更新会话时间或触发 WebSocket/APNs。私信事件不进入通用 WebSocket 断线重放缓存，重连后由受鉴权的消息 HTTP 接口补齐；已进入网络传输的在途帧及拉黑前已送达的通知无法撤回。

关注、拉黑与私信创建/读取/发送在同一用户对上按数据库锁顺序执行。拉黑先提交时，等待锁的请求必须按新关系拒绝；重叠的私信先持锁时可以先完成，再由拉黑提交。重复拉黑幂等，取消不会重建关注。双方状态必须为 active，注销提交后迟到的关系写入不得重新建立关注或拉黑。

会话列表在预览与未读数据组装之后批量重查权威关系，未读统计也在聚合后执行同样的检查。该检查之前已提交的拉黑必须过滤；检查之后已在途的 HTTP 响应不能撤回。查询失败返回失败，不把已准备的数据直接返回。跨模块查询不沿用业务事务旧快照或 MyBatis 缓存。

消息与 WebSocket/APNs 两条 Outbox 任务在同一数据库事务提交；HTTP 和 after-commit 不投递。独立 worker 在短事务领取并释放连接后才访问网络，失败退避重试，过期租约可由新 worker 领取。任务按事件 ID 与消息 ID 保持身份；过期 owner 的完成请求不改变新租约。持 pair 锁的消息事务使用当前连接检查关系，避免再借连接。

WebSocket `message` 事件的 payload 增加 `eventId`，其余 `conversationId / message` 保留。iOS 在当前连接内保存最多 1024 个 ID 去重；断开清空并拒绝旧连接迟到帧。缺少该字段的旧服务保持兼容，消息记录仍以 message.id 去重和同步。

推送按事件固定设备注册 ID 快照，每次最多处理 64 个设备，明确接受或永久无效的目标持久记进度，后续重试跳过。每次外部提交前重查设备归属；新增注册不追补旧快照。临时失败与永久失败分开保存，供应商等待要求参与重试计划。`DELIVERED` 不是用户设备收到或已读回执；无可用设备或关闭真实推送时同样可能处理结束。外部已接受但进度尚未保存时进程退出仍可能重试，不能承诺系统提醒恰好一次。

这些规则必须在服务端执行，不能依赖 iOS 隐藏按钮或本地过滤。post/message 模块通过 social 的公开 facade 获取关系及用户对锁，禁止直接依赖 `UserBlockMapper`。对应实现和验证状态见 [DP03 验收记录](DP03-VERIFICATION.md)。

### 9.5 成就系统

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/achievements/me` | 当前用户阶段、任务（含 `dueHint` 期限提示）、进度、徽章（需登录） |
| GET | `/api/achievements/tasks/{taskId}` | 任务详情（G05）：`steps`、`dueHint`、`sourceType`（PLATFORM / SCHOOL / COMMUNITY）、`sourceName`、`sourceUrl`、`reviewedAt`、`version`、完成状态、`completedVersion`、`updatedSinceCompletion`、可得徽章；不存在 404 |
| POST | `/api/achievements/tasks/{taskId}/complete` | 完成任务，幂等；发经验、按任务发徽章；记录完成时的任务版本 |
| GET | `/api/admin/achievement-tasks/{taskId}/guides` | 运营：任务的全部攻略关联（含下架、过期、别校），每条带帖子当前状态 |
| POST | `/api/admin/achievement-tasks/{taskId}/guides` | 运营：`{postId, sortOrder?}` 关联攻略；只能关联已发布且未过期的帖子（否则 409），每任务最多 10 篇，重复关联幂等；必需审计 |
| DELETE | `/api/admin/achievement-tasks/{taskId}/guides/{postId}` | 运营：取消关联；必需审计 |
| PUT | `/api/admin/posts/{id}/validity` | 运营：`{verifiedAt?, expiresAt?}` 整体替换核实日期与有效期，传空即清除；必需审计 |
| DELETE | `/api/achievements/tasks/{taskId}/complete` | 撤回自报完成，幂等；按累计经验回退，并收回只由该任务授予的徽章 |
| PUT | `/api/users/me/growth-background` | 保存成长背景 `{schoolId?, collegeId?, majorId?, entryYear}`（需登录） |
| GET | `/api/users/{uid}/growth` | 公开主页的成长：`level/levelTitle/xp/attendedCount/badges/recentActivities`；`termLabel` 只在本人或对方开启 `showGrade` 时返回；双方任一拉黑只返回等级 |
| GET | `/api/users/search?q=` | 按昵称（全文）或完整 UID 找人，UID 精确命中排第一，最多 20 条，返回 `FollowUserView`（`following` 为我是否已关注、`followedAt` 为空）；需登录，排除本人、非 active 与双向拉黑；每 IP 每分钟 60 次 |
| GET | `/api/users/me/capabilities` | 需登录。`{platformRole: ADMIN\|USER, grants:[{role, roleLabel, scopeType, scopeId, scopeName, expiresAt, …}], capabilities:[{capability: "activity.publish", scopes: ["SCHOOL:1"]}]}`；平台管理员的每个能力范围为 `PLATFORM`。客户端按它显示运营入口 |
| GET | `/api/users/me/blocks` | 我拉黑的人（最近在前，最多 200），返回 `{publicUid,nickname,avatarUrl,blockedAt}`；需登录。解除用 `DELETE /api/users/{uid}/block` |
| POST | `/api/feedback` | 意见反馈 `{category: bug\|suggestion\|content\|other, content 5–1000 字, appVersion?, device?}`，返回反馈 id；需登录，每 IP 每小时 10 次 |
| GET | `/api/admin/feedback?beforeId=` | 反馈列表（每页 50，按 id 倒序，`nextBeforeId` 翻页）；**仅 ADMIN** |
| GET | `/api/legal/{terms\|privacy}` | 公开。用户协议 / 隐私政策 `{kind,title,version,effectiveDate,content}`，`content` 是只含 `##` 标题、`-` 列表和 `**加粗**` 的 Markdown；未知类型 404 |

返回结构：`{ level, xp, completedTasks, totalTasks, currentStageCode, stages[], badges[], stageBasis, entryYear, termLabel, nextTask }`（后四个字段 2026-10-23 加）。
任务完成会写 `achievement_progress`，并按 `badge_code` 写 `user_badges`；重复调用不会重复加分。完成与撤回都先锁用户行，串行同一用户的进度和经验。

**当前阶段**：用户有 `entryYear` 时 `stageBasis=CALENDAR`，按学期序号（9 月开学，1 月仍算上学期，2–8 月为下学期；0 = 大一上）匹配 V26 的 `achievement_stages.term_from/term_to`；没有入学年份时 `stageBasis=PROGRESS`，沿用「第一个未完成阶段」。`nextTask` 是当前阶段第一个未完成任务，全部完成时为空。

**成长背景**：`entryYear` 必填，允许当年往前 10 年到明年。学校缺省沿用当前学校；学院、专业必须属于所选学校，专业有学院时必须与学院一致，只给专业会自动补学院。换学校不传学院专业时二者被清空。保存时按学期序号重算 `grade`（准大一 / 大一…大四 / 已毕业）。`UserProfile.entryYear` 对他人可见性跟随 `showGrade`。

```json
{
  "code": "OK",
  "data": {
    "level": 100,
    "xp": 100055,
    "completedTasks": 1,
    "totalTasks": 12,
    "currentStageCode": "freshman-1",
    "stages": [{ "title": "大一上", "tasks": [{ "id": 1, "title": "完成报到", "completed": false }] }],
    "badges": [{ "code": "freshman-start", "rarity": "COMMON", "earned": false }]
  }
}
```

徽章稀有度：`COMMON` / `RARE` / `EPIC` / `LEGENDARY` / `LIMITED`。
第一阶段只做通用主线；专业支线、活动徽章、算法推荐后续加表，不改接口。

### 9.6 手机号登录（iOS 主入口）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/sms-code` | 发送短信验证码，body `{ "phone": "13800138000" }` |
| POST | `/api/auth/sms-login` | 手机号 + 4 位验证码登录；未注册自动建号，body `{ "phone": "...", "code": "1234", "nickname": "可选" }` |

- **本地 mock**：`app.sms.mock-accept-any-code=true` 时不生成或保存固定码，任意格式正确的 4 位数字都通过；
  非 local/dev/test 打开会被 `StartupSafetyGuard` 拒绝启动。
- **真实短信通道还没接**（阿里云/腾讯云），非 mock 模式调用 `/sms-code` 会返回「短信服务未配置」。
  上线前必须接 provider + 短信签名/模板审核。
- 手机号用户 `users.email` 存手机号（该列本来就不校验邮箱格式），`password_hash` 是随机值。
- **本机号码一键登录**需要运营商号码认证 SDK（移动/联通/电信），iOS 没有原生 API 能直接读手机号。
  App 里用 `DevicePhoneNumber` 抽象：DEBUG 返回演示号码 `15500008888`，Release 返回 nil（自动落到手机号验证码页）；
  接入运营商 SDK 后只替换这个 provider。

### 9.6 校园活动（A1，2026-10-23）

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/activities/page?tab=recommend\|week&cursor=&limit=` | 公开 | 本校未结束的活动，按开始时间正序；week 为 7 天内开始；游标绑定 tab |
| GET | `/api/activities/{id}` | 公开 | 详情；只返回本校活动，访客按默认学校 |
| GET | `/api/activities/mine?state=upcoming\|history` | 登录 | 我报名（含候补）的活动；history 为已结束或已取消，按开始时间倒序 |
| GET | `/api/activities/organizer-profile` | 登录 | 能否发布、可面向的范围与奖励上限，供发布表单使用 |
| POST | `/api/activities` | 认证组织者 | 发布活动；普通用户 403，越权范围或超额奖励 400 |
| POST | `/api/activities/{id}/registrations` | 登录 | 报名；有名额为 REGISTERED，满员且允许候补为 WAITLISTED，否则 409；重复报名幂等 |
| DELETE | `/api/activities/{id}/registrations/me` | 登录 | 取消报名；已报名者取消时按报名先后递补一位候补；活动开始后 409 |

| GET | `/api/activities/{id}/checkin-code` | 发布者或管理员 | 签到大屏：`qrPayload`（`unipo://checkin?activity=&token=`，30 秒轮换）、4 位 `code`、`refreshAfterSeconds`、签到窗与已签到人数 |
| POST | `/api/activities/{id}/checkin` | 已报名者 | `{token}` 或 `{code}` 二选一；签到窗为开始前 30 分钟至结束；成功即发经验与金币，重复签到返回 `alreadyCheckedIn=true` 不重复发奖；候补者 409；错码 400；按 IP 每分钟 20 次限流 |
| GET | `/api/activities/organized?cursor=` | 登录 | 我发布的活动，按创建时间倒序 |
| GET | `/api/activities/{id}/registrations?filter=all\|registered\|pending\|waitlisted\|checked_in&cursor=` | 发布者或管理员 | 报名名单与各状态人数；不含手机号等账号信息 |
| POST | `/api/activities/{id}/registrations/{rid}/approve` · `/reject` | 发布者或管理员 | 审核待审核报名；通过时有名额进已报名，否则进候补；已处理过的 409 |
| POST | `/api/activities/{id}/registrations/{rid}/checkin` | 发布者或管理员 | 手动补签：签到开放后至活动结束后 24 小时，发同样的奖励，幂等 |
| POST | `/api/activities/{id}/registrations/{rid}/revoke-checkin` | 发布者或管理员 | `{reason}` 必填；签到后 24 小时内撤销，按实际发放扣回经验与金币（金币不扣成负数），撤销后不能再签到；写必需审计 |
| PUT | `/api/activities/{id}` | 发布者或管理员 | 开始前编辑；名额不能少于已报名人数，扩容自动递补候补；时间或地点变化通知所有报名者 |
| POST | `/api/activities/{id}/cancel` | 发布者或管理员 | `{reason}` 必填；已签到的奖励保留；通知所有报名者 |
| GET | `/api/notifications?cursor=` · `/unread-count` · POST `/read-all` | 登录 | 站内通知：审核结果、候补转正、变更、取消、开始前 24 小时与 1 小时提醒、签到撤销；关注的人发新帖（`type=FOLLOWING_POST`，`category=SOCIAL`，带 `postId`，单帖最多通知 2000 个粉丝，跳过拉黑） |
| GET | `/api/wallet/me?cursor=&limit=` | 登录 | 金币余额、今日已获得与每日上限（500）、流水倒序分页（cursor 为上一页最后一条流水 ID） |
| GET | `/api/growth/term-plan?termStart=` | 登录 | 本学期清单：活动（签到自动完成）+ 当期主线阶段 + 自己的计划，附 `summary{done,total,attended,activityXp,activityCoins}`；`termStart` 为 9 月 1 日或 2 月 1 日，默认当前学期，可往前到入学学期、往后一个学期，超出 400 |
| GET | `/api/growth/archive` | 登录 | 成长档案：按学期倒序的参加活动、获得徽章、完成的主线任务与计划数 |
| POST | `/api/growth/tasks` · PUT `/api/growth/tasks/{id}` | 登录 | 自己的计划 `{title, note?, dueAt?, termStart?}`；只能加到当前或下一个学期（否则 409），每学期最多 50 条；不发经验与金币 |
| POST / DELETE | `/api/growth/tasks/{id}/complete` | 本人 | 完成 / 撤回，幂等；别人的任务一律 404 |
| DELETE | `/api/growth/tasks/{id}` | 本人 | 删除，返回被删除的任务 |

`ActivityView` 主要字段：`phase`（OPEN / CLOSED / ONGOING / ENDED / CANCELLED，服务端按时间计算）、`myStatus`（NONE / REGISTERED / WAITLISTED / CANCELLED）、`myWaitlistPosition`、`eligible` 与 `ineligibleReason`、`audienceLabel`、`capacity`（为空不限）、`registeredCount`、`waitlistCount`、`rewardXp`、`rewardCoins`（签到后发放，A2 实现）、`recentAttendees`（最多 8 人）、`checkedIn`、`checkedInAt`、`checkinOpen`、`canManage`、`requireApproval`、`cancelReason`、`checkinRevoked`；`myStatus` 还可能是 PENDING（待审核）与 REJECTED（未通过，不能再报名）。签到时奖励写入报名行（`reward_xp_granted` / `reward_coins_granted`），金币按 `activity-checkin:报名 ID` 幂等入账，超过每日上限的部分不入账。

发布权限与上限：管理员和校级运营面向全校 / 学院 / 专业，每场最多 100 经验、200 金币；院级运营只能面向本院或本院专业，50 / 100；专业运营只能面向本专业，30 / 60。本校所有人都能看到活动，只有范围内的人能报名。名额计数在锁住活动行的事务内变更，并发报名不会超卖。

任务详情的 `guides` 只包含与查看者同校、已发布、未拉黑且未过期的关联帖子，按运营排序；`expiredGuideCount` 是可见但已过期而被隐藏的篇数，下架或别校的帖子不计入。`PostView` 新增 `verifiedAt`、`expiresAt`、`expired`（G06）；过期帖子照常可读，只是不再作为任务的有效攻略。

活动徽章（`activity-1` / `activity-5` / `activity-15`）出现在 `/api/achievements/me` 的 `badges` 里，由签到有效的累计场次推导，第 N 场的签到时间就是 `earnedAt`；撤销签到后自动变回未获得。

### 9.7 新用户资料完善（onboarding）

`PUT /api/users/me/onboarding`（需登录），引导最后一次性提交。2026-10-23 起 iOS 引导为 4 步，先调 `PUT /api/users/me/growth-background` 保存学校与入学年份，再提交本接口且不再传 `grade`（年级由成长背景推算）：

```json
{
  "nickname": "林小满",
  "gender": "female",
  "birthYear": 2006,
  "grade": "大一",
  "zodiac": "天秤",
  "mbti": "INFJ",
  "signature": "慢一点也没关系",
  "showGender": true,
  "showAge": false,
  "showGrade": true,
  "avatarUrl": "/api/media/27/content?v=..."
}
```

- `gender`：`male` / `female` / `other`；
- `birthYear`：1950 到当前年份 -10；
- `showGender/showAge/showGrade`：控制主页是否展示；
- 完成后 `onboardingCompleted=true`；新注册用户默认 false，已有用户默认 true；
- 头像先由 App 本地生成 PNG，再走 `/media/upload` 得到 URL。

### 9.8 个人主页数据

- `GET /api/users/me/stats`：关注/粉丝/作品等统计；
- `GET /api/users/me/posts` / `/likes` / `/post-favorites`：作品/喜欢/收藏三栏；
- `PUT /api/users/me`：头像更新只传 `avatarUrl`；2026-10-24 起「编辑资料」还可传 `nickname`、`bio`、`gender`（male/female/other）、`birthYear`（1950 至今年减 10）、`zodiac`（12 星座中文名，不带「座」）、`mbti`（16 型，大小写不敏感）、`showGender`、`showAge`、`showGrade`。字段省略或 `null` 不修改；`zodiac`、`mbti` 传空字符串清空；性别和出生年份不能清空；选项外的值 400；
- 主页展示字段来自 `UserProfile`：`gender` / `zodiac` / `mbti` / `birthYear` / `showGender` / `showAge` / `showGrade` / `backgroundUrl`。

**背景（2026-10-21 新增，V19）**：先通过 `/api/v1/media/upload?purpose=profile-background` 上传图片，
再向 `PUT /api/v1/users/me` 提交 `{"backgroundUrl":"上传返回的 URL"}`。省略该字段或传 `null` 不修改，
传空字符串恢复默认背景；最长 500 字符，只接受媒体内容路径或合法 HTTP(S) URL。
保存结果通过 `UserProfile.backgroundUrl` 返回，本人资料和公开主页使用同一字段。

**公开主页与关系**：`GET /api/v1/users/{uid}` 返回
`{profile, stats, following, mine, blocked, blockedBy, followedBy}`。
`following` 表示当前访问者关注对方，`followedBy` 表示对方关注当前访问者；两者都为 `true` 才是「朋友」。
仅 `following=true` 是「已关注」，仅 `followedBy=true` 仍显示「关注」；匿名访问或访问本人时两项都为 `false`。
关注/取关继续使用既有 `POST/DELETE /users/{uid}/follow`，没有独立的朋友接口。

非本人访问时，`profile.email`、兴趣与通知偏好、`lastLoginAt`、`onboardingCompleted` 返回 `null`；
`showGender/showAge/showGrade` 为 false 时，对应的 `gender/birthYear/grade` 也由服务端返回 `null`。
停用或注销账号的公开主页返回 404。当前 iOS 公开主页展示作品；他人喜欢和收藏的兼容接口仍保留。

### 9.9 推送设备

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/push/tokens` | 注册 APNs token，body `{ "token": "...", "platform": "ios", "environment": "sandbox" }` |
| DELETE | `/api/push/tokens?token=...` | 注销 token（退出/换设备时可选） |

- token 存 `device_tokens`，同一 token 只保留一条（换账号会覆盖）；
- 私信提交后由持久 worker 向收件人投递；失败保留重试或失败记录，不改变消息已保存的结果；
- `app.push.apns.enabled=false` 时用 `LoggingPushService`，只打日志；
- 打开后需要 `APNS_KEY_PATH` / `APNS_KEY_ID` / `APNS_TEAM_ID` / `APNS_TOPIC`。

### 9.10 账号注销

`DELETE /api/users/me`：软删除 + 匿名化。

- `users.status=deleted`，邮箱/手机号/昵称/头像/资料清空或替换；
- 关注、拉黑、device token 删除；持久身份版本与设备撤销同时提交，旧 Cookie/access/refresh 均失效；
- 不物理删用户行（帖子/评论/消息仍有引用）；
- 注销后登录返回「账号已被停用」。

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
| `event` | `event, [seq], ts, payload` | 可重放业务事件带 `seq`；私信仅在线投递，不带序号 |
| `pong` | `ts` | 心跳响应 |
| `error` | `code, message` | 统一错误码（如 `AUTH_UNAUTHENTICATED`） |

- 可重放事件带全局自增 `seq`，客户端只用这类序号推进 `lastEventId`。私信不进入 replay 缓存且不推进序号；断线后从受鉴权的会话/历史 API 同步。
- 心跳：客户端 25 秒发一次 `ping`，60 秒收不到 `pong` 主动重连（半开连接检测）。
- 多实例：事件先落 Redis Stream（每用户最近 500 条），再经 Redis Pub/Sub 广播给所有实例；
  单机则是进程内回调。切换只由 `STATE_BACKEND` 决定，业务代码不变。
- 推送只是加速通道：WS 断开时前端自动退回原有轮询，离线消息仍靠 `/messages/unread-count` 拉。
- 已绑定 WS 仍在全部上行及每条下行/重放帧前复验身份；空闲连接按 4 秒单调租约与 1 秒 watchdog 逻辑关闭，正常调度目标上界 5 秒。已进入传输的在途帧不能撤回，5 秒不等于底层 TCP 关闭帧保证；详见 ADR-0003。

## 11. 已知不一致

1. `GET /api/posts/interaction-notices` 在公开白名单里但方法要求登录 -> 401 而非 403。
2. 评论点赞有两套路径（见 §8）。
3. ~~全部列表无分页~~ 已补：`GET /api/v1/posts/page` 用游标分页（旧的 `/api/posts` 全量列表仍保留）。
4. ~~没有接口版本号~~ 已补：`/api/v1/**`，见 §1 的版本化说明。
5. ~~refresh 只靠单 token 黑名单撤销~~ V20 已加入持久设备和用户版本；刷新不延长绝对期限，见 ADR-0003。
6. ~~错误体没有业务错误码~~ 已统一 `code/message/data/requestId`，客户端按稳定 code 分支。
