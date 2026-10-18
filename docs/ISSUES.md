# 问题清单

> 快照 2026-10-19（commit `05a265d`）。按修复优先级排序，每条给出证据位置与建议动作。

## 0. 工程环境阻塞（先解决这个）

| 问题 | 影响 |
|---|---|
| ~~本机没有 JDK / Maven~~ **已解决**：装了 Temurin 21.0.12.1 + Maven 3.9.16，`mvn -DskipTests package` 已实测 **BUILD SUCCESS**（63MB fat jar）；前端 `npm run build` 也已通过 | 编译能力已具备 |
| **仍无本地数据库 / Docker** | 后端能编译但**不能启动**；唯一的默认库是生产库 |
| 仓库**0 个测试**（无 `src/test`） | 重构与安全修复没有回归保护 |
| **无 lint、无 CI**（无 `.github/workflows`、无 eslint/prettier） | 质量全靠人肉 |

建议：先装 Temurin JDK 21 + Maven 3.9（`winget install EclipseAdoptium.Temurin.21.JDK` + `winget install Apache.Maven`），或用 Docker Desktop 跑 compose。**这一步不做，后面的后端改造都只能靠读代码。**

## 1. P0 — 安全，可被直接利用

### P0-1 验证码默认放行，任意账号可被接管
证据：
- `backend/src/main/resources/application.yml:51`：`mock-accept-any-code: ${VERIFICATION_MOCK_ACCEPT_ANY_CODE:true}`
- `docker-compose.yml` 的 backend `environment` **没有**这个变量
- `.env.example` **没有**；`deploy-app.sh` 的 `required_vars` **没有**
- `VerificationService` 的 mock 分支只校验"4 位数字"

攻击链（3 个请求）：
1. `GET /api/auth/email-availability?email=目标邮箱&purpose=reset-password` → 确认账号存在
2. `POST /api/auth/verification-code` → 服务端"发码"但不校验
3. `POST /api/auth/reset-password` + 任意 4 位数字 → 密码被改

影响：任何人可接管任意已注册账号，包括运营号与管理员。**这是当前最严重的问题。**

修复（有先后依赖）：
1. **前置**：接入真实邮件通道（SMTP / Resend / SES）。现在 `mock=false` 时代码只把验证码打到日志，用户收不到 → 只改开关等于让注册/找回密码不可用。
2. `application.yml` 默认改 `false`，在 `application-local.yml` 里显式 `true` 保证本地开发。
3. 同步补齐 `.env.example`、`docker-compose.yml`、`deploy-app.sh` 三处。
4. 邮件未接通前的止血：关闭 `purpose=reset-password` 的可用性查询语义、给 `/verification-code` 与 `/reset-password` 加限流（按 IP + 邮箱）。

### P0-2 内置后门账号
证据：
- `AuthService.java:22-31` 常量 `GOOD_LOGIN=good/GOOD_PASSWORD=good`、`xiaoba/xiaoba`、`yuanhua/yuanhua`
- `AuthService.java:176-178` 每次启动 `ensureGoodUser()` / `ensureMockOperator(...)`
- `AuthService.java:226-243`、`263-274` 存在即重置密码
- `login()` 把 `good` / `xiaoba` / `yuanhua` 特判成邮箱

影响：任何看过仓库的人都能以 `SCHOOL_OPERATOR` / `COLLEGE_OPERATOR` 身份登录并发布"官方"帖（`canPostOfficial` 会认这些角色），直接伤害产品可信度。

修复：删除 `CommandLineRunner` 实现与三个 `ensure*` 方法；演示数据改为一次性 SQL 脚本（不进启动流程）；生产库中已存在的这三个账号要删除或强制改密。

### P0-3 管理员密码每次启动被重置
证据：`AuthService.java:197` `admin.setPasswordHash(encode(properties.bootstrap().adminPassword()))`；`docker-compose.yml` 默认 `BOOTSTRAP_ADMIN_PASSWORD=Admin@123456`。

影响：管理员改密无效；密码恒等于 `.env` 值；`.env` 一旦泄漏即永久失守。

修复：仅在用户不存在时创建；增加管理员自助改密接口；compose 去掉默认值改为必填（`deploy-app.sh` 的 `required_vars` 里已有，但 compose 默认值让缺失时不报错）。

### P0-4 默认配置直连生产库，本地启动会改生产数据
证据：
- `backend/src/main/resources/application.yml:14` 与 `application-local.yml:3` 的默认 `spring.datasource.url` 都是 `jdbc:mysql://115.190.3.204:12306/biecuoguo`
- 本机实测 **该端口可连通**
- README 的本地开发命令就是 `spring-boot:run -Dspring-boot.run.profiles=local`

影响链：照文档启动本地后端 -> 连上生产库 -> Flyway 对生产执行迁移 -> `AuthService.run()` 把生产管理员密码重置成 `BOOTSTRAP_ADMIN_PASSWORD`、重建三个演示账号 -> 本地测试的发帖/改资料直接写进生产数据。

修复：把默认值改成 `jdbc:mysql://127.0.0.1:3307/biecuoguo`（或直接不给默认值，缺失即启动失败）；本地库单独用 `.env` / IDE 配置提供。

## 2. P1 — 应尽快修

| 编号 | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1-1 | JWT secret 有默认值 `change-this-secret...`，缺失时用短串补齐 | `application.yml:45` | 启动时校验长度 >= 32，缺失即 fail-fast |
| P1-2 | Token 10080 分钟 + 无刷新令牌 + 存 `localStorage` | `application.yml:46`、`App.vue` 的 `localStorage` | App 上线前必须改 access 15min + refresh，Web 迁 httpOnly cookie |
| P1-3 | 登出黑名单、在线状态都是单机 `ConcurrentHashMap` | `TokenBlacklistService`、`PresenceService` | 多实例/重启即失效；换 Redis |
| P1-4 | 没有任何限流 | 全仓库无 Bucket4j / Redis | 登录、验证码、发帖、上传必须限流 |
| P1-5 | 用户枚举 | `email-availability`、`nickname-availability` 公开；`purpose=reset-password` 直接暴露注册状态 | 统一模糊返回 + 限流 |
| P1-6 | 上传只信 `Content-Type` 前缀，无魔数/尺寸校验 | `MediaService.java:45-51` | 校验文件头、重编码图片、限制像素；存储与访问分离 |
| P1-7 | 媒体内容公开可读且无归属校验 | `GET /api/media/{id}/content` 放行 | 私密场景需签名 URL；当前只有公开帖图，可接受但要记录 |

## 3. P2 — 质量与体验

- `GlobalExceptionHandler` 无兜底 `Exception` handler → 未知异常返回 Spring 默认错误页，前端拿不到 `{message}`。
- 错误体没有业务错误码，App 只能匹配中文文案。
- 时间字段全是 `LocalDateTime`，JSON 无时区；跨时区展示与 App 解析都会错。
- `PostService.baseVisibleWrapper`（`PostService.java:470-478`）未登录时硬编码 `school_id = 1`；`defaultViewer()`（`PostService.java:646`）随机取一个 active 用户当匿名视角。
- 无 requestId / 无访问日志 / 日志未脱敏。

## 4. 架构级技术债

| 编号 | 问题 | 影响 | 相关位置 |
|---|---|---|---|
| A1 | notices 遗留域（约 15 个类 + 12 张表）与 posts 新域并存，前端零调用 | 阅读成本翻倍；两套 `comments` 共用一张表；`AdminController` 只管遗留域且无前端 | `web/NoticeController`、`CommentController`、`InteractionController`、`AdminController` |
| A2 | N+1 查询 | `PostService.toView()`（`PostService.java:502-519`）每条 7 次 SQL（author + images + like + favorite + school/college/major），100 条 feed 约 700 次查询 | `PostService`、`MessageService.toView` |
| A3 | 列表无分页 | `PostService.list()`（`PostService.java:53-64`）全表返回；`messages()` 全量加载 | App 端无法做无限滚动 |
| A4 | 前端 4207 行单文件 + 假数据 + 轮询 | 无法多人协作；首屏可能展示假 PO；3s/1.8s 轮询放大 A2/A3 | `frontend/src/App.vue` |
| A5 | 课程表、公告、spotlight 前端硬编码 | 这些"功能"没有后端，无法运营 | `App.vue` |
| A6 | 无版本化接口、无契约文件 | App 发布后无法演进 | 见 `docs/MOBILE-API-PLAN.md` |

## 5. 建议修复顺序

1. **环境**：装 JDK 21 + Maven（半天）——解锁后端验证能力。
2. **P0-2 / P0-3**：删后门与密码重置（纯代码，风险低，1 小时）。
3. **P0-1 止血**：限流 + 关闭重置密码枚举；同时排期接邮件通道（1-2 天）。
4. **`P1-1 / P1-3 / P1-4**：fail-fast + Redis（限流/黑名单/在线态）。
5. **契约**：`/api/v1` + 统一响应包 + 游标分页 + 时区（3-5 天）。
6. **认证升级**：refresh token + 设备管理 + Web cookie（3-5 天）。
7. **前端**：路由化 + 组件拆分 + 清假数据（与 App 信息架构并行）。
8. **再谈实时化与推送**。
