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

| 状态 | 项 | 说明 |
|---|---|---|
| ✅ 已解决 | ``没有 JDK / Maven`` | Temurin **21.0.12.1** + Maven **3.9.16** 已装；`mvn -DskipTests package` 实测 **BUILD SUCCESS**（2 分 22 秒，63.1 MB fat jar） |
| ✅ 已解决 | ``前端依赖`` | `npm install` + `npm run build` 通过（1633 modules，5.26 秒，类型检查干净） |
| 🔄 进行中 | **没有本地数据库** | 方案已定：Docker Desktop + compose 的 `mysql` 服务（profile `local-db`） |
| 🔄 进行中 | **Docker Desktop 未装，WSL2 未启用** | 安装包已下到 `D:\Environments\installers\DockerDesktopInstaller.exe`（607 MB）；`wsl --status` 报 `REGDB_E_CLASSNOTREG`，装完需重启一次 |
| ⏳ 待办 | 默认 datasource 指向远端库 | 不改代码即可绕过：`application.yml:6` 有 `spring.config.import: optional:file:../.env[.properties]`，在仓库根建 `.env` 覆盖 `SPRING_DATASOURCE_URL` 即可 |

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

> **✅ 已修复（2026-10-20，commit 见 git log）**：`application.yml` 默认值已改为 `false`，本地由 `application-local.yml` 显式打开；新增 `config/StartupSafetyGuard.java`，非 `local/dev/test` profile 下打开该开关会**直接拒绝启动**（实测：无 profile + `VERIFICATION_MOCK_ACCEPT_ANY_CODE=true` → `IllegalStateException: 拒绝启动...`）。**仍待做**：接真实 SMTP 通道，否则生产环境验证码只会打日志。

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

## 3. D — 开发体验（影响日常协作，建议边做边改）

| 编号 | 问题 | 证据 | 影响 |
|---|---|---|---|
| D-1 | **N+1 查询** | `PostService.java:502-519` `toViews` 逐条调 `toView`，每条 7 次 SQL（author + images + like + favorite + school/college/major） | 100 条 feed ≈ 700 次查询，本地都卡 |
| D-2 | **列表无分页** | `PostService.java:53-64` `selectList` 无 limit；`MessageService.messages()` 全量加载 | 数据一多前端直接卡死 |
| D-3 | 匿名视角取随机用户 | `PostService.java:470-478` 未登录硬编码 `school_id = 1`；`:646` `defaultViewer()` 随机取 active 用户 | 未登录看到的 feed 不可控 |
| D-4 | 前端 4207 行单文件 | `frontend/src/App.vue`，6 个 `ViewKey` 手写状态机，无 vue-router | 无法多人协作，也无法复用到 App |
| D-5 ✅ | ~~前端假数据兜底~~ **已删除（2026-10-19）** | 原来 `fallbackPosts` / `fallbackComments` / `fallbackCourses` 共 380 行假数据全部移除；首屏改为 **加载中 / 加载失败+重试 / 空态** 三态 | 接口挂了会明确报错，不再伪装 |
| D-6 | 轮询过密 | `App.vue:1987` 3s、`:3205` 1.8s、`:2208` 10s | 放大 D-1/D-2 |
| D-7 | notices 遗留域 | `NoticeController` / `CommentController` / `InteractionController` / `AdminController` + 12 张表，前端零调用 | 阅读与维护成本翻倍 |
| D-8 | db/local 与 H2 是死代码 | 无任何 profile 引用 `db/local`，H2 未被使用 | 误导后来者 |

## 4. Q — 质量项（长期）

- `GlobalExceptionHandler` 无兜底 `Exception` handler → 500 走 Spring 默认错误页，前端拿不到 `{message}`。
- 错误体无业务错误码，App 只能匹配中文文案。
- 时间字段全是 `LocalDateTime`，JSON 无时区；App 端跨时区必错。
- 无限流（登录、验证码、发帖、上传）。
- JWT 默认 secret、10080 分钟、无刷新令牌、存 `localStorage`。
- 登出黑名单与在线状态是单机 `ConcurrentHashMap`。
- 上传只校验 `Content-Type` 前缀。
- 无测试（0 个）、无 lint、无 CI。

## 5. 建议顺序（本地开发模式）

1. **跑通本地链路**：Docker → MySQL → 后端 → 前端 → 打开 `http://127.0.0.1:5173` 看到真实数据（不再吃 fallback）。
2. **建 `.env`**，把 datasource 指向本地，顺手把 `R-4` 的坑封死。
3. **D-5 去假数据 + D-4 拆前端**：这是后续所有 Web/App 工作的地基。
4. **D-1 / D-2 分页与批量查询**：App 上线前必须做，趁数据量小改代价最低。
5. ~~**R-1 / R-2 / R-3**~~ ✅ **已完成（2026-10-20）**：默认值全部翻成安全的一侧 + `StartupSafetyGuard` 启动拦截。**R-1 仍欠真实 SMTP 通道**，部署前必须补。
6. **D-7 清理 notices 域**：可与 3 并行，纯删除。
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