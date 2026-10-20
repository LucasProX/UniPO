# 问题清单（本地开发模式版）

> 快照 2026-10-19。**工作模式已变更**：线上服务器 115.190.3.204 不再使用，后续开发全部在本地 Docker + 本地库进行。
> 因此优先级重新排序：**"能不能跑通" > "上线后会不会被打"**。

## 0. 优先级定义（本次重排后）

| 级别 | 含义 | 现在处理吗 |
|---|---|---|
| **B — 阻塞** | 本地跑不起来，连界面都看不到 | ✅ 立刻做 |
| **D — 开发体验** | 能跑但改起来痛苦（N+1、无分页、单文件前端） | 顺手做 / 逐步做 |
| **R — 上线阻断** | 只要不对外提供服务就不影响，**一旦部署上线必须先在** | ⏸ 先记着 |
| **Q — 质量** | 测试、CI、错误码、时区等长期项 | ⏸ 有空做 |

> 原 P0 三项（验证码放行、后门账号、管理员密码重置）全部归入 **R**：它们只在"对外开放"时才是灾难。代码层仍是硬伤，上线前必须清零，见 §3。

## 1. B — 当前阻塞项（本地跑通）

**B 类已清零（2026-10-19）**：JDK 21 + Maven 3.9.16 已装、前端 `npm run build` 通过、
Docker Desktop 29.8.2 + WSL 2.7.13 起得来、MySQL 8.4（`127.0.0.1:12306`）与 MinIO 容器正常、
后端 Flyway 自动建表 V1–V11、前端真实数据渲染，登录/发帖/图片上传实测通过。
完整启动顺序见 [`LOCAL-DEV.md`](LOCAL-DEV.md)。

## 2. R — 上线阻断项（原 P0，现在不阻塞本地开发）

### R-1 验证码默认放行，任意账号可被接管
证据：
- `backend/src/main/resources/application.yml:51`：`mock-accept-any-code: \${VERIFICATION_MOCK_ACCEPT_ANY_CODE:true}`（默认 true）
- `docker-compose.yml` 的 backend `environment`、`.env.example`、`deploy-app.sh` 的 `required_vars` **三处都没有**这个变量
- `VerificationService` 的 mock 分支只校验"4 位数字"

攻击链（3 个请求）：
1. `GET /api/auth/email-availability?email=目标&purpose=reset-password` → 确认账号存在
2. `POST /api/auth/verification-code` → 服务端"发码"但不校验
3. `POST /api/auth/reset-password` + 任意 4 位数字 → 密码被改

修复（有顺序依赖）：**先接邮件通道**（SMTP / Resend / SES；现在 `mock=false` 时验证码只打日志，用户收不到），再把默认值改 `false`、本地 profile 显式 `true`，最后补齐三处配置。

> **✅ 已修复（2026-10-20）**：
>
> 1. `application.yml` 默认值改为 `false`，本地由 `application-local.yml` 显式打开；
> 2. 新增 `config/StartupSafetyGuard.java`：非 `local/dev/test` profile 下打开该开关会**直接拒绝启动**
>    （实测：无 profile + `VERIFICATION_MOCK_ACCEPT_ANY_CODE=true` → `IllegalStateException: 拒绝启动...`）；
> 3. **真实 SMTP 通道已接**：`service/VerificationMailer.java` 走 `spring-boot-starter-mail`，
>    配置来自 `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD`（见 `.env.example`）；
> 4. `StartupSafetyGuard` 追加一条：`mock=false` 但没有 SMTP 时也**拒绝启动** ——
>    否则注册/找回密码永远发不出码，而进程起得来、接口返回 200，是个彻底静默的故障。
>
> **验证码字段现在是必填的**（两种模式下都必填）：开发模式只是让任意 4 位数字通过，不再允许留空。
>
> **仍待做**：拿一个真实邮箱（QQ / 163 / Gmail + 应用专用密码）跑一遍完整注册，确认邮件能收到、
> 码能通过、10 分钟过期生效。代码路径已通，但没对真实 SMTP 服务器实测过。

### R-2 内置后门账号
证据：`AuthService.java:22-31` 常量 `good/good`、`xiaoba/xiaoba`、`yuanhua/yuanhua`；`AuthService.java:176-178` 启动即创建（`CommandLineRunner`）；`AuthService.java:226-243`、`263-274` 存在即重置密码；`login()` 对这三个用户名做邮箱特判。
影响：任何人可登录为 `SCHOOL_OPERATOR` / `COLLEGE_OPERATOR` 发"官方"帖。
修复：删 `CommandLineRunner` 与三个 `ensure*` 方法，演示数据改为一次性 SQL 脚本。

> **✅ 已修复（2026-10-20）**：三个演示账号的创建已收进 `app.bootstrap.seed-demo-data`（默认 `false`），本地由 `application-local.yml` 打开；生产 profile 打开会被 `StartupSafetyGuard` 拒绝启动。**注意**：这只阻止「新建」，已经跑过旧版本的库需要手工清理 —— 见文末 §清理存量演示账号。

### R-3 管理员密码每次启动被重置
证据：`AuthService.java:197` 每次启动把 admin 密码写成 `BOOTSTRAP_ADMIN_PASSWORD`；`docker-compose.yml` 默认 `Admin@123456`。
修复：仅当用户不存在时创建；加管理员改密接口；compose 去掉默认值。

> **✅ 已修复（2026-10-20）**：`AuthService.run()` 现在只在管理员账号不存在时写入密码，已存在则完全不碰 `password_hash` 和 `nickname`，只在缺 `publicUid` / `role` / `status` 时补字段。实测重启前后 id=5 的 `password_hash` 与 `updated_at` 完全一致。**仍待做**：管理员改密接口、compose 去掉 `Admin@123456` 默认值。

### R-4 默认配置指向远端库（已更正，风险降级）
证据：`application.yml:14` 与 `application-local.yml:3` 默认都是 `jdbc:mysql://115.190.3.204:12306/biecuoguo`。
**更正**：先前"本机可连通"是假阳性——本机 Clash/Mihomo TUN（fake-ip 198.18.0.0/15）接管了所有 TCP，连 `192.0.2.1:80` 都"连得上"。外部 6 节点实测：ping 通、**TCP 12306 全部超时**、TCP 22 超时、TCP 80 开放但返回 404。
现状：该库不可达，本地启动只会连接超时。一旦放开端口，`spring-boot:run` 会对远端库跑 Flyway 并触发 `AuthService` 启动逻辑。
修复：本地靠 `.env` 覆盖（见 §1）；长期把默认值改成 `127.0.0.1`，缺失即启动失败。

> **✅ 已修复（2026-10-20）**：代码和脚本里已经不存在任何指向 `115.190.3.204` 的默认值。
> - `backend/src/main/resources/application.yml` 默认 `jdbc:mysql://127.0.0.1:3306/biecuoguo`
> （本机标准 MySQL 端口，连不上就是连不上，不会再摸到一台陌生库）。
> - `application-local.yml` 默认 `127.0.0.1:12306`，正好对上 `docker compose --profile local-db` 的宿主机映射。
> - `docker-compose.yml` 默认 `mysql:3306`（compose 服务名，整栈 `docker compose up` 可直接用）。
> - `deploy-app.sh` / `deploy-ip.sh` **彻底去掉默认值**：`SPRING_DATASOURCE_URL` 进了 `required_vars`，缺失直接退出。
>   部署脚本猜数据库地址这件事本身就是错的——猜错一次就是对着别人的库跑 Flyway。
> - `.env.example` 改成 `127.0.0.1:12306`，并在注释里写明「后端在容器里时改成 `mysql:3306`」。
> 实测：改完后 `mvn package` + `scripts/smoke.ps1` 仍然 34/34 全绿（本地 `.env` 覆盖不受影响）。

### R-5 JWT 签名密钥有写死的默认值
证据：`application.yml:80` 默认 `change-this-secret-to-a-very-long-random-string-32-plus`；
`.env.example:29` 是 `change_me_to_a_long_random_secret_at_least_32_chars`；
`docker-compose.yml:70` 是 `please-change-this-to-a-very-long-random-secret-32`。三个地方三个值，但**全都是公开的**。
`security/JwtService.java:56-62` 还会把短于 32 字节的密钥补 `0` 凑长度——一个 3 位密钥被补成 32 字节，看起来合规，强度还是 3 位。

攻击链：不需要注册、不需要验证码。拿仓库里这个默认值自己签一个 `{"sub":"5","role":"ADMIN"}`，
`Authorization: Bearer <伪造>` 直接就是管理员。**不留任何异常日志**，因为服务器认为这是合法 token。

> **✅ 已修复（2026-10-20）**：
> - `application.yml` 的默认值改成空 `${JWT_SECRET:}`，本地由 `application-local.yml` 提供专用值；
> - `docker-compose.yml` 改成 `${JWT_SECRET:?...}`，缺失时 compose 直接报错，不会再悄悄用一个公开密钥启动；
> - `.env.example` 改成空值 + `openssl rand -base64 48` 的生成提示；`deploy-app.sh` 本来就在 `required_vars` 里；
> - `config/StartupSafetyGuard.java` 新增第 0 条检查（profile 无关）：空值 / 三个历史示例值 / 短于 32 字符 / 非开发环境用了本地专用值 —— 四种情况全部**拒绝启动**，
>   异常信息里刻意不打印密钥本身（会进日志）；
> - `security/JwtService.java` 不再补 `0`，密钥不合格直接抛异常。
>
> 实测：空值 / 示例值 / `abc123` / 非开发 profile + 本地值，四种都 exit=1 并给出对应文案；
> `local` profile 正常启动，登录拿到 token、`/auth/me` 校验通过，`smoke.ps1` 34/34。
> **仍待做**：无刷新令牌、10 天有效期（`JWT_EXPIRATION_MINUTES` 默认 10080）——属于 Q 项。

## 3. D — 开发体验（影响日常协作，建议边做边改）

| 编号 | 问题 | 证据 | 影响 |
|---|---|---|---|
| D-1 ✅ | ~~N+1 查询~~ **已清零** | `toViews` 现在批量查作者/图片/点赞/收藏/字典表，一条 feed 固定 5 条 SQL | 100 条 feed 从 ~700 次降到 5 次 |
| D-2 ✅ | ~~列表无分页~~ **已做游标分页** | `GET /api/v1/posts/page?limit=&cursor=`，`limit` 默认 20、上限 50，游标编码完整排序键 | 数据量再大也只查一页 |
| D-3 | ~~匿名视角取随机用户~~ ✅ **已修复（2026-10-20）** | 未登录现在就是 `viewer = null`，学校隔离统一走 `app.default-school-id`（`APP_DEFAULT_SCHOOL_ID`，默认 1）；`defaultViewer()` 已删除，`AuthService` 里四处硬编码的 `1L` 也收进同一个配置 | 匿名 feed 确定且可配置 |
| D-4 ✅ | ~~前端 4207 行单文件~~ **已组件化（2026-10-20）** | `App.vue` 降到 61 行外壳，拆出 `layouts/AppShell.vue` + 6 个 `views/` + `components/{layout,modals,post,ui}` + 6 个 Pinia store + `vue-router` | Web/App 共用同一批 store 与 `lib/*.ts` 纯函数 |
| D-5 ✅ | ~~前端假数据兜底~~ **已删除（2026-10-19）** | 原来 `fallbackPosts` / `fallbackComments` / `fallbackCourses` 共 380 行假数据全部移除；首屏改为 **加载中 / 加载失败+重试 / 空态** 三态 | 接口挂了会明确报错，不再伪装 |
| D-6 | 轮询过密（**仍在**） | 已随组件化挪进 store：会话列表 3s（`stores/messages.ts`）、当前会话 1.8s、在线心跳 10s | 实时化（SSE/WebSocket）之前先别再加轮询 |
| D-7 ✅ | ~~notices 遗留域~~ **已删除（2026-10-20）** | 删掉 19 个 Java 文件（`NoticeController` / `CommentController` / `InteractionController` / `NoticeService` / `InteractionService` / `CommentService` / 5 个 domain / 5 个 mapper / 3 个 DTO）+ `V11__drop_notices_domain.sql` 掉了 `notices` / `notice_tags` / `user_notice_status` / `favorites` / `reminders` 和 `comments.notice_id`；`AdminService` 概览改按 posts 口径 | 只剩一套内容模型，`comments` 只认 `post_id` |
| D-8 ✅ | ~~db/local 与 H2 是死代码~~ **已删（2026-10-20）** | `backend/src/main/resources/db/local/`（10 个文件）连目录一起删了，`pom.xml` 里的 `com.h2database:h2` 也去掉，`mvn package` 仍然绿 | — |

## 4. Q — 质量项（长期）

已完成（2026-10 前）：兜底 `Exception` handler 有了（`GlobalExceptionHandler:122`，且不吐异常 message）；
业务错误码有了（`web/ErrorCode.java`）；时间字段已全改 `OffsetDateTime`；
JWT 默认 secret 已删（见 **R-5**）。

### 真正剩下的

| 编号 | 问题 | 现状 | 影响 |
|---|---|---|---|
| Q-1 ✅ | ~~无限流~~ **已修（2026-10-20）** | `web/RateLimitFilter.java` 按「来源 IP + 接口」限流，命中回 429 + `Retry-After`：
登录 30/5min、验证码 20/10min、注册 20/1h、改密 20/10min、上传 30/5min、发帖 20/5min。
固定窗口，计数存在 `RateLimitStore`（默认进程内，`STATE_BACKEND=redis` 时共享） | 脚本刷不动了；**仍待做**：按账号锁定（防单账号慢速撞库）、私信发送还没限 |
| Q-2 ✅ | ~~JWT 无刷新令牌~~ **已加（2026-10-20）** | access 120 分钟（`JWT_EXPIRATION_MINUTES`）+ refresh 20160 分钟（`JWT_REFRESH_EXPIRATION_MINUTES`），靠 JWT 的 `typ` 声明区分，混用直接 401；
`POST /api/auth/refresh` 换新 access；`POST /api/auth/logout` 把两者一起拉黑；前端 401 自动单飞刷新并重放原请求。
**仍待做**：refresh 轮换（要等 Q-4 的共享存储）、`httpOnly` cookie、`refresh_tokens` 落库以便「踢掉所有设备」 | — |
| Q-3 ✅ | ~~上传只校验 `Content-Type`~~ **已修（2026-10-20）** | 新增 `service/ImageSignature.java` 按文件头识别 JPEG/PNG/GIF/WebP，扩展名和 `Content-Type` 都由识别结果决定（不再采信客户端）；上限从 20MB 收到 **5MB**；`purpose` 只允许 `[A-Za-z0-9_-]{1,20}` | 实测：HTML/SVG 改请求头冒充 `image/png` 都是 400；6MB 图片 413；真 PNG 200 |
| Q-4 ✅ | ~~单机状态~~ **已做成可切换（2026-10-20）** | 撤销名单 / 在线状态 / 限流计数 统一走 `com.biecuoguo.state` 下的
`RevocationStore` / `PresenceStore` / `RateLimitStore`，由 `STATE_BACKEND=memory|redis` 选实现；
`redis` 模式下启动就 ping，连不上直接拒绝启动；三个接口调用点（`TokenBlacklistService` / `PresenceService` / `RateLimitService`）签名未变。
**实测**：两个实例（8080/8081）共用 Redis 时，A 退出登录后同一 token 在 B 上 401；memory 模式下是 200（这就是原来的 bug）。
**仍待做**：默认还是 memory（单实例够用），起多副本要显式 `STATE_BACKEND=redis` + `--profile redis`；Redis 挂了限流放行、拉黑失败按未登录处理 | — |
| Q-5 ✅ | ~~无 CI~~ **CI + 单测 + lint 全部落地（2026-10-20）** | `.github/workflows/ci.yml` 四个 job，GitHub Actions 最新 run `39363868902` **4/4 全绿**（首次绿是 `39360886056`）：
`后端编译`（mvn package）/ `前端类型检查与构建`（npm ci + npm run build，vue-tsc 抓类型错）/ `脚本与换行自查`（.sh 必须 LF、.ps1 必须 CRLF、`.env` 不能入库）/
`启动 + 接口冒烟`（真 MySQL 8.4 service + 起 jar + health/feed 游标/登录拿 token/带 token 调用/405/404 六条断言）。
**已补（2026-10-20）**：后端单测 **22 个**（`backend/src/test/java/`：JWT 两种 token 不可混用 + 短密钥拒签 + 过期拒收、
图片文件头识别 + HTML/SVG 拒绝、时区边界、限流窗口/fail-open、三个内存 store），CI 的 `后端编译` job 改成 `mvn -B package`（跑测试）；
前端加了 **eslint 基线**（`frontend/eslint.config.js`，只开 no-debugger / no-var / prefer-const / eqeqeq 等一小撮，`npm run lint`），CI `前端` job 加一步。
**故意没上**：官方 recommended（几百条纯风格告警）、prettier（会和现有手写风格打架）、CI 里跑完整 `smoke.ps1`（依赖 Windows PowerShell + `curl.exe`，要移植成 bash） |
| Q-6 ✅ | ~~类型手写，与后端 DTO 已漂移~~ **已上契约（2026-10-20）** | `npm run gen:api` 用 `openapi-typescript` 把 `/v3/api-docs` 生成到
`frontend/src/api-types.ts`；`frontend/src/api-contract.ts` 用类型断言要求手写 `types.ts` 与生成类型字段名/类型完全一致，
`npm run build` 不过就是漂移；CI 冒烟 job 会重生成并 `git diff --exit-code`。
**第一次跑就抓到真漂移**：`UserProfile` 少 10 个字段（goalTags / interestCategories / preferUrgent / preferHighRisk /
preferSameMajor / emailNotificationEnabled / browserNotificationEnabled / remindBeforeHours / createdAt / lastLoginAt），已补。
**没做的**：没把 `types.ts` 直接换成生成类型（生成的全是可选字段、枚举退化成 `string`，组件里会丢类型安全），也没换 `openapi-fetch` 调用层 | —

### 已解决但留着记录

- ~~`GlobalExceptionHandler` 无兜底 `Exception` handler~~ → `GlobalExceptionHandler.java:122` 有兜底，且按项目规矩不把异常 message 吐给客户端。
- ~~错误体无业务错误码~~ → `web/ErrorCode.java`，`code` 是稳定契约。
- ~~时间字段全是 `LocalDateTime`~~ → 出参统一 `OffsetDateTime`，转换只走 `config/AppTime.java`。
- ~~JWT 默认 secret~~ → 见 R-5。

## 5. 建议顺序（本地开发模式）

1. **跑通本地链路**：Docker → MySQL → 后端 → 前端 → 打开 `http://127.0.0.1:5173` 看到真实数据（不再吃 fallback）。
2. **建 `.env`**，把 datasource 指向本地，顺手把 `R-4` 的坑封死。
3. **D-5 去假数据 + D-4 拆前端**：这是后续所有 Web/App 工作的地基。
4. **D-1 / D-2 分页与批量查询**：App 上线前必须做，趁数据量小改代价最低。
5. ~~**R-1 / R-2 / R-3**~~ ✅ **已完成（2026-10-20）**：默认值翻到安全侧 + `StartupSafetyGuard` 启动拦截 + `VerificationMailer` 接上真实 SMTP。**部署前仍需**拿真实邮箱跑一遍注册，确认邮件能收到。
6. ~~**D-7 清理 notices 域**~~ ✅ **已完成（2026-10-20）**。
7. **Q 项**：随功能迭代逐步补，优先"时区"与"错误码"（App 契约依赖）。

## 5.1 清理存量演示账号（已部署过旧版本的库要手工做一次）

R-2 修复后只是**不再新建**这三个账号。如果某个库在旧版本下启动过，`good` / `school-op@example.com` /
`college-op@example.com` 已经躺在里面了，需要手工清掉。先查，再删：

```sql
-- 1) 确认存在（生产上应该查出来 0 行）
SELECT id, email, role, status FROM users
WHERE email IN ('good@biecuoguo.local', 'school-op@example.com', 'college-op@example.com');

-- 2) 硬删（先备份；这三个 id 可能被 posts.author_id 引用）
DELETE FROM users
WHERE email IN ('good@biecuoguo.local', 'school-op@example.com', 'college-op@example.com');
```

> 删之前先看它们有没有发过帖：如果 `SELECT COUNT(*) FROM posts WHERE author_id IN (那几个 id)` 不为 0，
> 直接硬删会留下没有作者的帖子。这种情况改成软删（`UPDATE users SET status='deleted'`）或者把帖子一并处理。

## 6. 一句话结论

**本地能跑通之后，最值钱的三件事是：去假数据、前端组件化、后端分页。** 它们决定这个项目能不能撑到 App 端。