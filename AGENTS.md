# AGENTS.md · UniPO 协作指南

> 给 AI 协作者（Codex / Claude Code / DSH 等）与人类开发者。**每次开工先读本文件，再读 `docs/`。**

## 0. 一句话
校园 PO（Persistent Online）信息流应用：Vue 3 Web 端 + Spring Boot 3 后端 + MySQL 8 + MinIO。
正在从「AI 生成的单文件 MVP」走向「Web / App 双端共用的正式产品」。

## 1. 目录地图
| 路径 | 说明 |
|---|---|
| `backend/src/main/java/com/biecuoguo/web/` | Controller 层（12 个），统一返回 `ApiResponse` |
| `backend/src/main/java/com/biecuoguo/service/` | 业务逻辑（13 个），N+1 与手写权限判断都在这一层 |
| `backend/src/main/java/com/biecuoguo/security/` | Spring Security + JWT + `CurrentUser` |
| `backend/src/main/java/com/biecuoguo/dto/` | 请求/响应 record |
| `backend/src/main/resources/db/migration/` | Flyway V1–V10，**只增不改** |
| `frontend/src/App.vue` | 61 行外壳：挂 AppShell + 全局弹窗 + 会话引导（2026-10-20 完成组件化） |
| `frontend/src/layouts/AppShell.vue` | 布局壳：header + 左右栏 + 底部 dock + `<router-view>` |
| `frontend/src/views/` | 路由视图：Home / Compose / Schedule / Messages / Profile / UserProfile |
| `frontend/src/components/` | `layout/` 布局件、`modals/` 弹窗、`post/` 帖子件、`ui/` 三态 |
| `frontend/src/stores/` | Pinia：`feed` / `auth` / `messages` / `schedule` / `compose` / `profile`，Web/App 两端复用 |
| `frontend/src/lib/api.ts` | 手写 axios 封装（187 行），类型在 `types.ts` |
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
```
本地库（Docker Desktop 必须先运行）：
```bash
docker compose --profile local-db up -d mysql minio   # 3306/12306 + MinIO 9000/9001
```
后端（Flyway 自动建表 V1–V10）：
```bash
cd backend
mvn -DskipTests package                  # 只编译打包，安全
mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=local
# 自动读仓库根的 .env（application.yml:6 的 spring.config.import）
```
冒烟（后端/MySQL/MinIO 都在跑时）：
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke.ps1   # 17 项全链路检查
```
全栈（需要 Docker）：
```bash
cp .env.example .env
docker compose up -d --build
```

## 4. 后端约定
- 分层：`web` -> `service` -> `mapper`（MyBatis-Plus）-> `domain`。Controller 不写业务，Service 不碰 HTTP。
- 成功返回 `ApiResponse.ok(data)`，线上 JSON 是 `{"data": ...}`；列表也是 `{"data": [...]}`，没有分页元数据。
- 错误返回 `ErrorResponse{message, timestamp}`，由 `GlobalExceptionHandler` 映射 400/401/403/404/413。**没有兜底 500 处理**，未知异常走 Spring 默认错误页。
- DTO 一律 Java `record`，放 `dto/XxxDtos`，命名 `XxxRequest` / `XxxView`。
- 权限：`SecurityConfig` 白名单 + `/api/admin/**` 的 `hasAnyRole`；业务角色判断在 Service 手写（如 `PostService.canPostOfficial`）。**新增受保护接口必须同时改 `SecurityConfig`**，否则要么被拦要么裸奔。
- 时间字段目前全是 `LocalDateTime`（无时区信息）。**新接口请用 `OffsetDateTime`**，原因见 `docs/MOBILE-API-PLAN.md` §4。

## 5. 数据库
- Flyway 管 schema：已发布的 V1–V10 禁止修改，新增 `V11__xxx.sql`。
- 两套内容模型并存：`posts`（新，前端在用）与 `notices`（遗留，前端完全不用）。动内容代码前先确认改哪一套。
- 计数冗余字段（`posts.like_count` / `comment_count` / `favorite_count` / `share_count`）在 Service 里手动加减，改互动逻辑要同步维护。
- 完整表清单见 `docs/ARCHITECTURE.md` §4。

## 6. 前端约定
- `App.vue` 已是 61 行外壳（AppShell + 全局弹窗）。**新功能写到 `views/` + `stores/`，不要再往 App.vue 堆**；新增页面流程：`views/XxxView.vue` → `router/index.ts` 加路由 → `DockNav.vue` 加入口。
- 纯展示组件放 `frontend/src/components/`，共享纯函数放 `frontend/src/lib/*.ts`（**不能 import Vue**，否则 App 端复用不了）。
- 类型在 `frontend/src/lib/types.ts` 手写，与后端 DTO 已开始漂移。接口改动要两边一起改，直到换 OpenAPI 生成。
- **禁止新增 fallback 假数据**。`fallbackPosts` / `fallbackComments` / `fallbackCourses` 已于 2026-10-19 删除，首屏现在是 `feedLoading` / `feedError`（带重试按钮）/ 空态三态。接口失败必须显式报错，不能假装有数据。`fallbackAvatar()` / `fallbackCover()` 是占位图生成器，不算假数据，可继续用。
- token 存 `localStorage['bcg_token']`；`localApi` 硬编码 `http://127.0.0.1:8080` 仅 DEV 生效，不要依赖。
- 唯一 `v-html` 在 `components/post/PostDetailModal.vue`，内容统一过 `lib/richText.ts` 的 `sanitizeRichHtml`；新增富文本必须走同一套清洗。

## 7. 硬性禁止（安全红线）
1. **不得**把 `app.verification.mock-accept-any-code` 以 `true` 部署到生产（`application.yml:51` 默认就是 true，当前最严重漏洞）。
2. **不得**新增硬编码账号/密码（`AuthService` 已有 `good/good`、`xiaoba/xiaoba`、`yuanhua/yuanhua` 三个后门）。
3. **不得**在启动时重置管理员密码（`AuthService:197` 每次启动把 admin 密码改成 `BOOTSTRAP_ADMIN_PASSWORD`，compose 默认 `Admin@123456`）。
4. `.env` 永不入库；新增环境变量必须同步三处：`.env.example`、`docker-compose.yml` 的 `environment`、`deploy-app.sh` 的 `required_vars`。
5. 不要在日志里打印 token / 密码 / 验证码。

## 8. 已知坑
- `.gitignore` 忽略 `AGENT.md` / `agent.md`（单数），但**不忽略 `AGENTS.md`**——本文件会入库，视为团队资产。
- 系统级 `core.autocrlf=true`，而 `.gitattributes` 只对 `*.sh` 强制 LF。改 shell 脚本注意别提交 CRLF。
- `PostService.baseVisibleWrapper`（`PostService.java:470-478`）在用户没选学校或未登录时**硬编码 `school_id = 1`**；`defaultViewer()`（`PostService.java:646`）还会随便挑一个 active 用户当"匿名视角"。这是 bug，不是设计。
- `GET /api/posts/interaction-notices` 命中 `SecurityConfig` 的 `GET /api/posts/**` 白名单，但方法内部要求登录 -> 未登录得到 401 而不是 403。
- 前端轮询（2026-10-20 已随组件化搬进 store）：会话列表 3s（`stores/messages.ts` 的 `startConversationListPolling`）、当前会话 1.8s（`startActiveConversationPolling`）、在线心跳 10s（`stores/auth.ts` 的 `startPresenceHeartbeat`）。实时化之前别再加轮询。
- **默认 datasource 指向远端库**：`application.yml:14` 与 `application-local.yml:3` 都默认 `jdbc:mysql://115.190.3.204:12306/biecuoguo`。实测该端口**对公网不可达**（外部节点 TCP 全部超时；本机探测会被 Clash TUN 代理伪造成"可连接"，不可信），所以现在照 README 启动只会连接超时。一旦端口被放开，`spring-boot:run` 就会对远端库跑 Flyway + 触发 `AuthService` 启动逻辑（重置管理员密码、重建演示账号）。**启动前必须显式设置 `SPRING_DATASOURCE_URL` 指向本地库。**
- **本机代理会让所有端口探测失真**：Clash/Mihomo TUN + fake-ip（198.18.0.0/15，网关 198.18.0.2，`HTTP_PROXY=127.0.0.1:7897`）下，连 `192.0.2.1:80` 都"连接成功"。要判断真实可达性，用 check-host.net 这类外部节点，或先关代理。
- `backend/src/main/resources/db/local/` 与 `pom.xml` 里的 H2 依赖是死代码：没有任何 profile 引用 `db/local`，H2 也没被使用。
- 没有测试、没有 lint、没有 CI。提交前至少 `npm.cmd run build` 绿（已实测 5.3s 通过）。

## 9. 当前优先级（2026-10，本地开发模式）
> 完整分级见 `docs/ISSUES.md`：**B 阻塞 / D 开发体验 / R 上线阻断 / Q 质量**。

1. ~~跑通本地链路~~ ✅ **已完成（2026-10-19）**：Docker → MySQL 8.4 → 后端 → 前端，真实数据已渲染，登录/发帖链路实测通过。
2. 去假数据 + 前端组件化（Web/App 共用的地基）。
3. 后端分页 + 批量查询（替掉 N+1），`/api/v1` 版本化与统一响应包。
4. 上线前集中清零 **R 项**（验证码放行、后门账号、管理员密码重置）。
5. 清理 notices 遗留域。

## 10. 文档索引
- `docs/ARCHITECTURE.md` — 系统架构、请求链路、数据模型
- `docs/API.md` — 完整接口清单与调用约定
- `docs/ISSUES.md` — 问题分级、证据、修复建议
- `docs/MOBILE-API-PLAN.md` — Web/App 双端接口方案（**有 5 个决策待拍板**）
- `docs/LOCAL-DEV.md` — 本地环境搭建、启动顺序、默认账号、常见故障