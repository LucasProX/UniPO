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
| `frontend/src/App.vue` | 4207 行单文件，全部页面 + 状态 + 轮询 |
| `frontend/src/lib/api.ts` | 手写 axios 封装（187 行），类型在 `types.ts` |
| `docs/` | 架构 / API / 问题清单 / 移动端方案 |
| `deploy-*.sh`、`docker-compose.yml`、`Caddyfile` | 生产部署 |

## 2. 环境
- 本机：Windows 11，Git 2.55，Node 24.18，npm 11.16。
- **未安装 JDK / Maven / Docker** → 后端目前**无法本地编译和运行**。任何后端改动只能静态审查，无法验证。这是当前最大的协作成本（见 `docs/ISSUES.md` §0）。
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
后端（需要 JDK 21 + Maven）：
```bash
cd backend
mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=local
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
- `App.vue` 是 4207 行、6 个 `ViewKey` 手写状态机、无路由。**新功能不要继续堆进去**；动它之前先看 `docs/MOBILE-API-PLAN.md` §6。
- 类型在 `frontend/src/lib/types.ts` 手写，与后端 DTO 已开始漂移。接口改动要两边一起改，直到换 OpenAPI 生成。
- **禁止新增 fallback 假数据**（`fallbackPosts` / `fallbackComments` / `fallbackConversations` / `fallbackCourses`）。接口失败应显示错误或空态，不能假装有数据；现有假数据是待清理项。
- token 存 `localStorage['bcg_token']`；`localApi` 硬编码 `http://127.0.0.1:8080` 仅 DEV 生效，不要依赖。
- 唯一 `v-html` 在 `App.vue:885`，配套 `sanitizeRichHtml`；新增富文本必须走同一套清洗。

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
- 前端轮询：会话列表 3s（`App.vue:1987`）、当前会话 1.8s（`App.vue:3205`）、在线心跳 10s（`App.vue:2208`）。实时化之前别再加轮询。
- 没有测试、没有 lint、没有 CI。提交前至少 `npm.cmd run build` 绿。

## 9. 当前优先级（2026-10）
1. 安全止血：验证码放行、后门账号、管理员密码重置（P0，见 `docs/ISSUES.md`）。
2. 契约冻结：读完 `docs/API.md`，确认现状。
3. `/api/v1` 版本化 + 统一响应包 / 游标分页 / 时区（App 开发前必须完成）。
4. 让后端本地可运行（装 JDK 21 + Maven，或 Docker Desktop）。
5. 清理 notices 遗留域与前端假数据。

## 10. 文档索引
- `docs/ARCHITECTURE.md` — 系统架构、请求链路、数据模型
- `docs/API.md` — 完整接口清单与调用约定
- `docs/ISSUES.md` — 问题分级、证据、修复建议
- `docs/MOBILE-API-PLAN.md` — Web/App 双端接口方案（**有 5 个决策待拍板**）
