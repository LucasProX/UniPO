# AGENTS.md · UniPO 协作指南

> 给 AI 协作者（Codex / Claude Code / DSH 等）与人类开发者。**每次开工先读本文件，再读 `docs/`。**
>
> **收工前必看 §11：踩过的坑要写进 [`docs/DEVLOG.md`](docs/DEVLOG.md)。**

## 0. 一句话
校园 PO（Persistent Online）信息流应用：Vue 3 Web 端 + Spring Boot 3 后端 + MySQL 8 + MinIO。
正在从「AI 生成的单文件 MVP」走向「Web / App 双端共用的正式产品」。

## 1. 目录地图
| 路径 | 说明 |
|---|---|
| `backend/src/main/java/com/biecuoguo/web/` | Controller 层（9 个），统一返回 `ApiResponse` |
| `backend/src/main/java/com/biecuoguo/service/` | 业务逻辑（10 个），N+1 与手写权限判断都在这一层 |
| `backend/src/main/java/com/biecuoguo/security/` | Spring Security + JWT + `CurrentUser` |
| `backend/src/main/java/com/biecuoguo/dto/` | 请求/响应 record |
| `backend/src/main/resources/db/migration/` | Flyway V1–V11，**只增不改** |
| `frontend/src/App.vue` | 61 行外壳：挂 AppShell + 全局弹窗 + 会话引导（2026-10-20 完成组件化） |
| `frontend/src/layouts/AppShell.vue` | 布局壳：header + 左右栏 + 底部 dock + `<router-view>` |
| `frontend/src/views/` | 路由视图：Home / Compose / Schedule / Messages / Profile / UserProfile |
| `frontend/src/components/` | `layout/` 布局件、`modals/` 弹窗、`post/` 帖子件、`ui/` 三态 |
| `frontend/src/stores/` | Pinia：`feed` / `auth` / `messages` / `schedule` / `compose` / `profile`，Web/App 两端复用 |
| `frontend/src/lib/api.ts` | 手写 axios 封装，类型在 `frontend/src/types.ts` |
| `frontend/src/api-types.ts` | **生成文件**：后端 `/v3/api-docs` 的 OpenAPI 类型，别手改 |
| `frontend/src/api-contract.ts` | 契约断言：手写 `types.ts` 必须与生成类型一致，否则 `npm run build` 报错 |
| `frontend/src/lib/postHelpers.ts` | 帖子纯函数（boardLabel/roleLabel/评分等），**零 Vue 依赖**，App 端可直接复用 |
| `frontend/src/lib/richText.ts` | 富文本清洗（`sanitizeRichHtml` / `renderRichPostContent`），**零 Vue 依赖**，App 端可直接复用 |
| `docs/` | 架构 / API / 问题清单 / 移动端方案 |
| `deploy-*.sh`、`docker-compose.yml`、`Caddyfile` | 生产部署 |

## 2. 环境
- 本机：Windows 11，Git 2.55，Node 24.18，npm 11.16。
- 已装 **Temurin JDK 21.0.12.1**（`D:\Environments\Java\jdk-21`）+ **Maven 3.9.16**（`D:\Environments\Maven\apache-maven-3.9.16`）；`JAVA_HOME` 与 PATH 已写入用户环境变量，**新开的终端直接可用**。
- 已配置阿里云 Maven 镜像（`C:\Users\36840\.m2\settings.xml`），后端首次构建约 2.5 分钟。
- **本地链路已跑通（2026-10-19 实测）**：Docker Desktop 29.8.2 + WSL 2.7.13 + MySQL 8.4 容器（`127.0.0.1:12306`）+ MinIO（`127.0.0.1:9000/9001`，`bitnamilegacy/minio`）+ 后端 + 前端；图片上传/读取已实测。完整流程见 [`docs/LOCAL-DEV.md`](docs/LOCAL-DEV.md)。
- 线上服务器 `115.190.3.204` **不再使用**；本机开发不连任何远端库。
- 后端通过仓库根 `.env`（gitignored）覆盖 `SPRING_DATASOURCE_URL`，不用改代码。
- PowerShell 里 npm 必须用 `npm.cmd`（`npm.ps1` 被执行策略拦截）。
- 全局 git 身份已配好：`LucasProX <LucasProX@users.noreply.github.com>`，凭据管理器里有 GitHub token，可直接 push。

## 3. 命令
前端（当前唯一能真正跑的部分）：
```bash
cd frontend
npm.cmd install
npm.cmd run dev -- --host 127.0.0.1 --port 5173   # vite 代理 /api -> localhost:8080
npm.cmd run build                                   # vue-tsc -b && vite build，提交前必须绿
npm.cmd run lint                                    # eslint 基线（no-debugger / no-var / prefer-const 等）
npm.cmd run gen:api                                 # 后端起着时，重生成 src/api-types.ts（改了后端 DTO 就必跑）
```
本地库（Docker Desktop 必须先运行）：
```bash
docker compose --profile local-db up -d mysql minio   # 3306/12306 + MinIO 9000/9001
```
后端（Flyway 自动建表 V1–V11）：
```bash
cd backend
mvn -DskipTests package                  # 只编译打包，安全
mvn test                                 # 22 个单测（不碰数据库：JWT / 文件头识别 / 时区 / 限流 / 内存 store）
mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=local
# 自动读仓库根的 .env（application.yml:6 的 spring.config.import）
```
冒烟（后端/MySQL/MinIO 都在跑时）：
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke.ps1   # 37 项全链路检查（含 /api/v1、游标分页、响应包、时区、注册/验证码、refresh token）
```
全栈（需要 Docker）：
```bash
cp .env.example .env
docker compose up -d --build
```

## 4. 后端约定
- 分层：`web` -> `service` -> `mapper`（MyBatis-Plus）-> `domain`。Controller 不写业务，Service 不碰 HTTP。
- **统一响应包**：成功是 `{"code":"OK","message":"","data":...,"requestId":"..."}`，错误是 `{"code":"AUTH_...","message":"中文提示","data":null,"requestId":"...","timestamp":"..."}`。
  - `code` 是**稳定契约**（`web/ErrorCode.java`），App 端 switch 它；`message` 只给人看，不要拿来做逻辑判断。
  - `requestId` 由 `web/RequestIdFilter.java` 分配（优先复用入站 `X-Request-Id`，否则生成 16 位），同时回写响应头并进 MDC，日志里能对上。
  - **HTTP 状态码保留语义**（400/401/403/404/405/413/500），不要退化成「一律 200 + code 判断」。
  - 错误体一律走 `GlobalExceptionHandler`；它管不到 Security 过滤器链里的认证异常，`SecurityConfig` 的 `authenticationEntryPoint` / `accessDeniedHandler` 手写了同一格式，改格式要两处一起改。
  - 500 兜底**不把异常 message 吐给客户端**（可能含 SQL/路径/主机名），只回通用文案 + requestId，详情打日志。
  - 新增错误码只往 `ErrorCode` 里加，不要改名或改含义。
- **时间**：数据库存 `LocalDateTime`（MySQL `DATETIME` 无时区），**接口一律返回 `OffsetDateTime`**（ISO-8601 带偏移，如 `2026-10-19T15:22:01+08:00`）。转换只走 `config/AppTime.java` 的两个方向：出参 `AppTime.toOffset(...)` 在 DTO 的 `from()` 里，入参 `AppTime.toLocal(...)` 在 Service 落库前。解释时区由 `app.time-zone`（`APP_TIME_ZONE`，默认 `Asia/Shanghai`）显式决定，**不要依赖 JVM 默认时区**。
- **版本化**：Controller 同时挂在 `/api` 与 `/api/v1` 两个前缀下（`security/ApiVersionPrefixFilterConfig.java` 把 `/api/v1/**` 改写成 `/api/**`，`SecurityConfig` 白名单只需维护一份）。前端已切到 `/api/v1`；`/api` 保留兼容，观察 2 个版本后下线。**`/api/v1` 的演进只做加法**：加字段、加端点不升版本，删字段/改语义/改类型才升 `v2`。
- **分页（强制游标）**：新端点用 `GET /api/v1/posts/page?tab=&cursor=&limit=`，返回 `{items, nextCursor, hasMore, limit}`；`limit` 默认 20、上限 50。游标编码上一页最后一条的**完整排序键**（pinned/official/dontMiss/likeCount/commentCount/publishedAt/id），不要只用 id——排序按热度排，id 小的可能排在 id 大的前面。非法游标返回 400。
- **注册/登录的「账号」不是邮箱**：`RegisterRequest` / `LoginRequest` 里字段名叫 `email`（沿用 `users.email` 列），但**不校验邮箱格式**，可以是任意字符串；`account` / `username` 是它的 JSON 别名，App 端可以直接传。
  - 注册强制三件事：账号非空且不重名、密码非空、**验证码非空**。`nickname` 不填就用账号；`confirmPassword` 可选（给了才比对）。
  - **验证码字段永远必填，开关只决定后端怎么处理它**：`POST /auth/verification-code` 与 `POST /auth/register` 在两种模式下都要走。
    `mock-accept-any-code=true`（本地）时 `VerificationService` 不写库、不发信，任意 4 位数字都通过；
    `false`（真实）时生成随机码 -> 存 BCrypt 哈希 -> 走 SMTP 发信 -> 校验后消费（10 分钟有效）。
    **两种模式下都调用 `verifyCode()`**，不要再写 `if (mock) skip` —— 那样本地和线上的调用链不一致。
  - **密码上限是 72 字节，不是 72 个字符**：BCrypt 的有效输入只有 72 字节，超出会被静默忽略（两个不同的长密码可能算出同一个哈希）。`AuthService.assertPasswordAcceptable()` 显式拒绝超长，不要改成截断。
- **「默认学校」只有一个来源**：`app.default-school-id`（`APP_DEFAULT_SCHOOL_ID`，默认 `1`）。未登录访客看 feed、新注册用户还没选学校、演示账号初始化，全都读它。**不要再在代码里写 `1L`**，也不要再写「随便挑一个用户代表访客」那种兜底（那正是 D-3）。该值必须存在于 `schools` 表，否则建号时会撞 `fk_users_school`。
- **上传只信文件头，不信 `Content-Type` 和扩展名**：`service/ImageSignature.java` 按 magic bytes 识别 JPEG/PNG/GIF/WebP，
  存进 MinIO 的扩展名和 `Content-Type` 都由识别结果决定。以前只看请求头是不是 `image/` 开头，
  一个 HTML 或带 `<script>` 的 SVG 改个请求头就能存进来，再被 `/api/media/{id}/content` 原样吐回浏览器。
  **故意不支持 SVG**（它是 XML，能带脚本），也不要把校验降级成「看后缀」。上限由 `MAX_UPLOAD_FILE_SIZE` 控制，默认 5MB。
- **两种 token，靠 JWT 的 `typ` 声明区分**（2026-10-20）：access 默认 120 分钟（`JWT_EXPIRATION_MINUTES`），只能调接口；
  refresh 默认 14 天（`JWT_REFRESH_EXPIRATION_MINUTES`），只能调 `POST /api/auth/refresh`。
  `security/JwtService.java` 的 `parse()` 只认 access、`parseRefresh()` 只认 refresh，**混用一律 401**——
  不要为了「少写一个方法」把类型检查去掉，那等于把 14 天的 token 变成能直接调接口的通行证。
  退出登录会把 access 和 refresh 一起写进 `TokenBlacklistService`；**刷新刻意不轮换** refresh token（理由见 DEVLOG：
  黑名单是进程内的，轮换会在多标签页并发刷新 / 多实例部署时把用户误踢下线）。
- **限流规则表在 `web/RateLimitFilter.java`**：按「来源 IP + 接口」做固定窗口，命中回 429 + `Retry-After`。
  新增「能被刷出成本」的接口（发邮件、上传、写库）要往 `RULES` 里加一条。
  数值是「挡脚本」不是「精确配额」，本地连着跑三四遍 `smoke.ps1` 有可能撞到，等窗口过去或临时 `RATE_LIMIT_ENABLED=false`。
  **部署在代理后面时必须开 `RATE_LIMIT_TRUST_FORWARDED_FOR`**（compose 里默认 true）：否则所有请求的 `remoteAddr` 都是代理地址，全站共用一个桶。
- DTO 一律 Java `record`，放 `dto/XxxDtos`，命名 `XxxRequest` / `XxxView`。
- 权限：`SecurityConfig` 白名单 + `/api/admin/**` 的 `hasAnyRole`；业务角色判断在 Service 手写（如 `PostService.canPostOfficial`）。**新增受保护接口必须同时改 `SecurityConfig`**，否则要么被拦要么裸奔。`/api/v1` 独有端点（如 `/api/v1/posts/page`，由 `web/v1/` 包的 Controller 自己挂前缀、不会被改写）要单独加白名单。
- **不要用 `WebMvcConfigurer.configurePathMatch` 的 `addPathPrefix` 做版本前缀**（2026-10-20 实测）：它会重建整套路径匹配配置，导致 `AntPathRequestMatcher` 白名单全局失效，连 `/api/schools`、`/api/auth/login` 都变 401。用过滤器改 URI 的方式，见 `security/ApiVersionPrefixFilterConfig.java`。
- 新增环境变量：`APP_TIME_ZONE`、`APP_DEFAULT_SCHOOL_ID`、`RATE_LIMIT_ENABLED`、`RATE_LIMIT_TRUST_FORWARDED_FOR`、
  `STATE_BACKEND`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` 只加在 `.env.example` 与 `docker-compose.yml`，
  **故意没进 `deploy-app.sh` 的 `required_vars`**——它们都有安全的默认值，强制必填会让老部署直接起不来。
- **有状态的东西都走 `com.biecuoguo.state` 的三个接口**（`RevocationStore` / `PresenceStore` / `RateLimitStore`）：
  各有一对 memory / redis 实现，由 `app.state.backend`（`STATE_BACKEND`）选，默认 `memory`。
  **不要在业务代码里直接 new 一个 Map 存这类状态**——那样多实例部署时「A 退出登录 B 还认」的坑会再出现一次（Q-4）。
  三个接口的失败语义是刻意分开的：拉黑查询抛异常 -> 按「已撤销」处理（宁可多踢一次）；
  限流计数抛异常 -> **放行**（fail-open，限流是挡脚本不是安全边界，不能让 Redis 抖动变成全站 429）；
  在线状态抛异常 -> 按离线处理。`redis` 模式启动时会 ping，连不上直接拒绝启动（否则会静默退化成「永远不生效」）。

## 5. 数据库
- Flyway 管 schema：已发布的 V1–V11 禁止修改，新增 `V12__xxx.sql`。
- **只剩一套内容模型：`posts`**。`notices` 遗留域已于 2026-10-20 整体删除（`V11__drop_notices_domain.sql` 掉了 `notices` / `notice_tags` / `user_notice_status` / `favorites` / `reminders` 五张表，以及 `comments.notice_id` 列）。**不要再往 notices 方向加东西**；`comments` 表保留，但只认 `post_id`。
- 计数冗余字段（`posts.like_count` / `comment_count` / `favorite_count` / `share_count`）在 Service 里手动加减，改互动逻辑要同步维护。
- 完整表清单见 `docs/ARCHITECTURE.md` §4。

## 6. 前端约定
- `App.vue` 已是 61 行外壳（AppShell + 全局弹窗）。**新功能写到 `views/` + `stores/`，不要再往 App.vue 堆**；新增页面流程：`views/XxxView.vue` → `router/index.ts` 加路由 → `DockNav.vue` 加入口。
- 纯展示组件放 `frontend/src/components/`，共享纯函数放 `frontend/src/lib/*.ts`（**不能 import Vue**，否则 App 端复用不了）。
- 类型分两层（2026-10-20 起）：
  - `frontend/src/api-types.ts` 是 **`npm run gen:api` 从后端 `/v3/api-docs` 生成的**，只读、别手改；
  - `frontend/src/types.ts` 是手写的业务类型（字段必填、枚举收窄成字面量联合，组件里 switch 才有穷尽性检查）。
  - `frontend/src/api-contract.ts` 用类型断言把两者钉死：后端加/删字段、改类型，`npm run build` 直接报错。
  **后端改了 DTO 就要跑一次 `cd frontend && npm run gen:api`**（后端得先起来），再按报错同步 `types.ts`。
  CI 的冒烟 job 会重生成一遍并 `git diff --exit-code`，忘了提交生成文件会红。
- **禁止新增 fallback 假数据**。`fallbackPosts` / `fallbackComments` / `fallbackCourses` 已于 2026-10-19 删除，首屏现在是 `feedLoading` / `feedError`（带重试按钮）/ 空态三态。接口失败必须显式报错，不能假装有数据。`fallbackAvatar()` / `fallbackCover()` 是占位图生成器，不算假数据，可继续用。
- token 存 `localStorage['bcg_token']`（access）+ `localStorage['bcg_refresh_token']`（refresh）；
  `lib/api.ts` 的响应拦截器在 401 时**单飞**刷新并重放原请求（同一时刻只发一个 `/auth/refresh`），
  刷新失败才清 token 并抛 401 —— 新增请求不需要自己处理刷新。
  `localApi` 硬编码 `http://127.0.0.1:8080` 仅 DEV 生效，不要依赖。
- 唯一 `v-html` 在 `components/post/PostDetailModal.vue`，内容统一过 `lib/richText.ts` 的 `sanitizeRichHtml`；新增富文本必须走同一套清洗。

## 7. 硬性禁止（安全红线）
> 2026-10-20 更新：R-1 / R-2 / R-3 / R-4 / R-5 五条已修复。下面保留为**回归防线**——这几条以前是真的漏过，别改回去。

1. **不得**把 `app.verification.mock-accept-any-code=true` 部署到非开发环境。默认已是 `false`，本地由 `application-local.yml` 打开；非 `local/dev/test` profile 下打开会被 `config/StartupSafetyGuard.java` **直接拒绝启动**。打开后任意 4 位数字都能通过验证码校验，等于任何人可以注册任意账号。
2. **不得**新增硬编码账号/密码，也**不得**把演示账号的创建逻辑从 `app.bootstrap.seed-demo-data` 后面挪出来。`AuthService` 里那三个 `ensure*` 方法只能在这个开关为 true 时调用。
3. **不得**让启动流程写 `password_hash` 或 `nickname`。`AuthService.run()` 只在管理员账号**不存在**时初始化，已存在只补 `publicUid` / `role` / `status`。以前无条件重置密码，等于永久保留一个公开的万能口令。
4. `.env` 永不入库；新增环境变量必须同步三处：`.env.example`、`docker-compose.yml` 的 `environment`、`deploy-app.sh` 的 `required_vars`。有安全默认值的可选变量可以只加前两处，但必须在 §4 记明是刻意为之（现有例外：`APP_TIME_ZONE`）。
5. **不得**给 `app.jwt.secret`（`JWT_SECRET`）留默认值，也不得把示例值写进任何会被复制的地方（`application.yml` / `.env.example` / `docker-compose.yml`）。
   签名密钥一旦公开，任何人自己签一个 `role=ADMIN` 的 token 就是管理员，**不需要注册、不需要验证码、也不留任何异常日志**。
   现在 `application.yml` 的默认值是空的，`docker-compose.yml` 用 `${JWT_SECRET:?...}` 缺失直接报错，`deploy-app.sh` 在 `required_vars` 里，
   只有 `application-local.yml` 有一个本地专用值——非 `local/dev/test` profile 下用这个值也会被 `StartupSafetyGuard` 拒绝启动。
   `security/JwtService.java` 也不再给短密钥补 `0` 凑长度（以前一个 3 位密钥会被补成 32 字节，看起来能用，实际强度还是 3 位）。
6. 不要在日志里打印 token / 密码 / 验证码。

## 8. 已知坑
- `.gitignore` 忽略 `AGENT.md` / `agent.md`（单数），但**不忽略 `AGENTS.md`**——本文件会入库，视为团队资产。
- 系统级 `core.autocrlf=true`。`.gitattributes` 现在管两件事：`*.sh` 强制 LF、`*.ps1` 强制 CRLF（2026-10-20 加，否则 Linux CI checkout 出来的 `.ps1` 是 LF）。CI 的 `files` job 会检查这两条。
- **`.ps1` 必须是 CRLF**（2026-10-20 实测）：`edit`/`write` 工具写出来的是 LF，PowerShell 5.1 解析 LF-only 的 `.ps1` 会出现「变量赋值后仍为空」这类静默诡异行为（`scripts/smoke.ps1` 里 `$v1 = ($Base.TrimEnd('/')) + '/api/v1'` 算出空串，但同样代码单独跑没问题）。改完 `.ps1` 后统一 `(Get-Content f -Raw) -replace "`r?`n","`r`n" | Set-Content f -NoNewline` 转回 CRLF，并保持 ASCII-only。
- **`smoke.ps1` 必须把控制台输出编码设成 UTF-8**（2026-10-20 实测）：PowerShell 5.1 捕获 `curl.exe` 输出用的是 `[Console]::OutputEncoding`，中文 Windows 下默认 OEM 936。UTF-8 的响应体被按 GBK 解码时，尾部字节会被吃掉，**连后面那个 JSON 引号一起吞**，`ConvertFrom-Json` 直接报「无效的 JSON 格式」。表现极具误导性：注册返回 200，但 token 取不到，后面所有带 token 的检查全 401。脚本开头已加 `$OutputEncoding` / `[Console]::OutputEncoding = $utf8`，别删。
- **匿名访客的 feed 曾经是随机的**（D-3，2026-10-20 已修，别再改回去）：`PostService.defaultViewer()` 以前会随便挑一个 active 用户借人家的 `school_id`，`baseVisibleWrapper()` 再用硬编码 `1L` 兜底——同一个匿名请求两次可能返回不同内容，那个随机用户被禁用后 feed 还会突然变样。现在访客就是 `viewer = null`，学校隔离统一走 `app.default-school-id`；同一个值也用于新注册用户和演示账号的初始学校（以前 `AuthService` 里四处各写一遍 `1L`）。**这个值必须指向 `schools` 表里真实存在的行**，否则建号会撞 `fk_users_school` 外键、启动直接失败。
- `GET /api/posts/interaction-notices` 命中 `SecurityConfig` 的 `GET /api/posts/**` 白名单，但方法内部要求登录 -> 未登录得到 401 而不是 403。
- 前端轮询（2026-10-20 已随组件化搬进 store）：会话列表 3s（`stores/messages.ts` 的 `startConversationListPolling`）、当前会话 1.8s（`startActiveConversationPolling`）、在线心跳 10s（`stores/auth.ts` 的 `startPresenceHeartbeat`）。实时化之前别再加轮询。
- **数据库默认值曾经指向一台已停用的服务器**（R-4，2026-10-20 已修，别再改回去）：那时候 `application.yml` / `application-local.yml` 默认都是 `jdbc:mysql://115.190.3.204:12306/biecuoguo`，一旦那个端口被放开，照默认启动就会对着一台陌生库跑 Flyway + 触发 `AuthService` 启动逻辑。现在的默认值全是「本机」或「compose 服务名」：`application.yml` → `127.0.0.1:3306`，`application-local.yml` → `127.0.0.1:12306`，`docker-compose.yml` → `mysql:3306`。`deploy-app.sh` / `deploy-ip.sh` **没有默认值**，`SPRING_DATASOURCE_URL` 缺失直接退出。
- **本机代理会让所有端口探测失真**：Clash/Mihomo TUN + fake-ip（198.18.0.0/15，网关 198.18.0.2，`HTTP_PROXY=127.0.0.1:7897`）下，连 `192.0.2.1:80` 都"连接成功"。判断真实可达性要用外部节点（check-host.net），或先关代理。
- `backend/src/main/resources/db/local/` 与 `pom.xml` 里的 H2 依赖已于 2026-10-20 删除（D-8）。别再往 `db/` 下加第二套 migration，只认 `db/migration`。
- **真实模式下没有 SMTP 会启动失败**（2026-10-20 加）：`StartupSafetyGuard` 会查 `spring.mail.host`（`SMTP_HOST`），空就抛 `IllegalStateException`。这是刻意的——注册和找回密码全靠邮件，配不上就是断链，而且断得很静默（进程起得来、接口返回 200）。本地开发用 `--spring.profiles.active=local` 走 mock 模式即可，不需要 SMTP。
- **`STATE_BACKEND` 默认是 `memory`，多副本部署会静默出问题**（2026-10-20）：进程起得来、接口全 200，
  只有「A 实例退出登录，B 实例还认这个 token」「A 限流拦住了，B 还在放行」这种表现。
  起多副本时必须 `STATE_BACKEND=redis` + `docker compose --profile redis up -d`；
  本地也实测过一遍：两个实例共用 Redis 时 401 / 429 都能跨实例生效，memory 模式下不能。
- **CI 有测试和 lint 了（2026-10-20），但 `smoke.ps1` 还是只在 Windows 上跑**：`.github/workflows/ci.yml` 跑
  `mvn -B package`（含 22 个单测）/ `npm ci` + `npm run lint` + `npm run build` / 脚本换行自查 /
  真 MySQL 起服务的接口冒烟 / 重生成 `api-types.ts` 后 `git diff --exit-code`。
  但它**不能代替本地 `smoke.ps1`**——37 项全链路检查（含上传、游标分页、时区、注册验证码、refresh token）只有 Windows 上那一份，CI 里没跑。
  提交前仍然要：`npm.cmd run lint` + `npm.cmd run build` 绿 + `mvn test` 绿 + `smoke.ps1` 37/37。
  改 workflow 后先在本地跑 `actionlint .github/workflows/ci.yml`——GitHub 的 YAML 报错要到网页 logs 里看，
  而「解析就失败」的那种 run 连 job 都没有（`total_count == 0`、`created_at == updated_at`），没有 logs 可看。
- **headless DOM 验证**：后台窗口截图会返回纯白（Chromium 暂停绘制被遮挡窗口），唯一可靠的是 `msedge --headless=new --dump-dom`。直接 `> file` 重定向在 `Start-Process`/`Invoke-WebRequest` 组合下可能产出 0 字节文件——**把命令写进 `.bat` 再用 `cmd /c` 跑**最稳（见 `docs/LOCAL-DEV.md` §8）。

## 9. 当前优先级（2026-10，本地开发模式）
> 完整分级见 `docs/ISSUES.md`：**B 阻塞 / D 开发体验 / R 上线阻断 / Q 质量**。

1. ~~跑通本地链路~~ ✅ **已完成（2026-10-19）**：Docker → MySQL 8.4 → 后端 → 前端，真实数据已渲染，登录/发帖链路实测通过。
2. ~~去假数据 + 前端组件化~~ ✅ **已完成（2026-10-20）**：`App.vue` 从 4207 行降到 61 行，拆成 `layouts/AppShell.vue` + 6 个 `views/` + `components/{layout,modals,post,ui}` + 6 个 Pinia store；`fallbackPosts` / `fallbackComments` / `fallbackCourses` 已删，首屏是三态。
3. ~~后端分页 + 批量查询（替掉 N+1），`/api/v1` 版本化与统一响应包~~ ✅ **已完成**：N+1 清零 + 游标分页 + `/api/v1` + 统一响应包（`code`/`requestId`）+ `OffsetDateTime`。
4. ~~上线前集中清零 **R 项**~~ ✅ **R-1 ~ R-5 全部已修（2026-10-20）**：默认值翻到安全侧 + `config/StartupSafetyGuard.java` 启动拦截（现在管危险开关、SMTP 缺失、JWT 密钥三类）；R-4 把库地址默认值改成 `127.0.0.1` / compose 服务名，两个部署脚本改成**必填**；R-5 删掉写死的 JWT 默认密钥。**R-1 的 SMTP 通道已接**（`service/VerificationMailer.java`，配置 `SMTP_HOST` 等），但还没拿真实邮箱实测过，部署前要跑一遍。
5. ~~清理 notices 遗留域~~ ✅ **已完成（2026-10-20）**：删掉 19 个 Java 文件 + 5 张表 + `comments.notice_id`，`InteractionController` / `NoticeController` / `CommentController` 整体下线，`AdminService` 概览改按 posts 口径。详见 DEVLOG 同名条目。

## 10. 文档索引
- `docs/ARCHITECTURE.md` — 系统架构、请求链路、数据模型
- `docs/API.md` — 完整接口清单与调用约定
- `docs/ISSUES.md` — 问题分级、证据、修复建议
- `docs/MOBILE-API-PLAN.md` — Web/App 双端接口方案（**有 5 个决策待拍板**）
- `docs/LOCAL-DEV.md` — 本地环境搭建、启动顺序、默认账号、常见故障
- [`docs/DEVLOG.md`](docs/DEVLOG.md) — **开发手记**：踩过的坑、当时的思路、为什么这么改

## 11. 开发手记必须同步（硬规矩）

[`docs/DEVLOG.md`](docs/DEVLOG.md) 是本项目唯一记录「为什么」的地方。`AGENTS.md` 记的是**结论和规矩**，
`DEVLOG.md` 记的是**当时怎么想到这个结论的**——现象、错误的怀疑方向、绕的弯路。两份都要维护。

**什么时候写：**

1. **踩到坑并解决之后。** 判据很简单：如果当时卡了超过 10 分钟，或者第一次的怀疑方向是错的，就值得写。
2. **做了有取舍的技术决策。** 比如「为什么数据库不改 TIMESTAMP」「为什么 code 用枚举不用字符串」。
   决策本身写进 `AGENTS.md` 的约定，**理由**写进 `DEVLOG.md`。
3. **发现了反直觉的行为。** 框架文档里没写、或者写了但和实际不符的。

**什么时候不用写：** 一次就写对的东西、纯操作步骤、别人文档里已经很清楚的内容。

**写作要求：**

- 中文口语化，**像自己写给自己的备忘**，不要写成汇报。
- 顺序固定：先说现象（什么表现、怎么发现的）→ 再说当时的怀疑（试过什么、排除了什么）→
  最后说结论和以后怎么避免。**中间那段「怀疑错了什么」最有价值**，别省。
- 标题写清楚是什么事（「addPathPrefix 把全站搞成 401」），不要写「问题记录」这种。
- 带上具体信息：文件名、报错原文、关键代码片段。
- **不要写「经过排查最终解决了」这类总结句**，那等于没写。
- 新条目加在文件末尾（按时间正序），日期格式 `## YYYY-MM-DD 一句话标题`。

**AI 协作者特别注意：** 每次任务收尾、准备提交之前，回头过一遍这次改了什么，
对照上面三条判据决定要不要补一条手记。**不要等我提醒。**