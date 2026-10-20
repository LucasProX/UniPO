# ADR-0001 · 平台基线：单契约、iOS 原生、Cookie 认证、WebSocket

- **状态**：已接受（2026-10-20）
- **决策人**：Lucas
- **影响范围**：API 形态、认证、实时通道、App 技术栈、部署形态、模块划分

## 1. 背景

UniPO 从「AI 生成的单文件 MVP」转为正式产品。明确的目标约束：

1. **按百万日活设计**，不是「先跑起来再说」。代码要模块化、可扩展、可维护。
2. **单台服务器也能完整部署运行**。规模上来了再拆，不提前上微服务。
3. 当前只有 iOS 端（Web 已有），Android 后置。

这两条看似矛盾（百万级 vs 单机），实际是**同一件事的两面**：单机跑得动，靠的是「无状态 + 外部化状态」；
能长到百万，靠的是「模块边界清晰 + 每一层都能水平扩展」。所以本 ADR 的核心是**先定边界，再定技术**。

## 2. 决策

### D1. 公开 API：单一 /api/v1 契约，不做 Web/App 双份

**决定**：所有客户端（Web、iOS、未来 Android）共用同一套 /api/v1。需要按端裁剪时，加一层**薄 BFF**，
BFF 只做聚合/裁剪，不重写业务规则。

**理由**：

- 大厂的主流做法是「一个对外契约 + 按端 BFF」，而不是给每个端复制一套业务 API。
  公开 API 一旦分叉，同一个业务规则（权限、可见性、计数）就有两份实现，漂移是时间问题。
- 我们的业务规则集中在 Service 层，App 和 Web 的差异主要在**展示形态**，不在**领域逻辑**。
- 版本化成本：/api/v1 只做加法；真要破坏性变更才升 v2 并给旧版本设下线日期。

**不做**：按端拆分的 /app/... 业务 API。它解决的是「客户端不想处理多余字段」，
而那应该由 BFF 或字段裁剪参数解决。

**代价**：契约一旦发布就要长期兼容；需要 OpenAPI + 生成客户端守住漂移（已有 api-types.ts 流水线）。

### D2. iOS：SwiftUI 原生；Android 后置，优先 Kotlin/Compose 或 KMP

**决定**：iOS 用 SwiftUI + Swift Concurrency 做原生。Android 上线时，**不为了复用 UI 而选跨端框架**；
优先评估 Kotlin/Compose 原生，或用 KMP 共享领域层/数据层。

**理由**：

- 用户明确要「原生那种动画和组件」。Flutter/RN 能做到接近，但滚动阻尼、转场、手势、
  无障碍、Live Activities、Widget、APNs 深度集成这些，原生仍然最稳。
- 百万日活的 App，包体积、冷启动、耗电、后台策略都会被放大；原生在这些指标上最可控。
- 「一次编写两端跑」在业务简单时收益大；我们的业务正在变复杂（信息流、私信、实时），
  UI 复用省下的时间会被平台适配吃掉。

**不做**：现在同时上 Android；为了跨端而选 Flutter/RN。

**代价**：Android 需要另一套 UI；缓解手段是**后端契约 + 生成的 Swift/Kotlin 客户端**，
领域模型只有一份（服务端），客户端只做展示与缓存。

### D3. 认证：Web 用 httpOnly Cookie，App 用 Bearer Token

**决定**：

- **Web**：登录态放 HttpOnly + Secure + SameSite=Lax 的 Cookie，服务端在 Redis 里维护会话；
  所有写操作加 CSRF 防护（双提交 Token 或 SameSite + 自定义头校验）。
- **App**：access/refresh 放 Keychain，走 Authorization: Bearer，不使用 Cookie。
- 两套入口共用同一个「会话/令牌服务」，只是载体不同。

**理由**：

- localStorage 里的 token 对 XSS 完全敞开；httpOnly Cookie 让脚本读不到，是 Web 端的行业基线。
- App 没有浏览器的 Cookie 语义，Bearer + Keychain 才是原生做法。
- 会话放 Redis 才能做到「踢掉所有设备」「改密即失效」——纯 JWT 做不到即时吊销。

**代价**：两套认证入口；Cookie 方案必须处理 CSRF 与 CORS 凭证；
需要 Redis（单机也跑 Redis，见 D6）。

**落地进度（2026-10-20）**：后端已完成（`SessionStore` + `SessionAuthenticationFilter` + `CsrfFilter`），
`STATE_BACKEND=redis` 下实测通过；前端仍用 Bearer，切换时再改 `lib/api.ts` 与登录流程。

### D4. 实时：WebSocket，不是 SSE

**决定**：Web/App 统一走 WebSocket。协议自定 JSON 信封，不直接把业务对象裸奔在帧里。

**理由**：

- SSE 只能服务端推、单连接单向、连接数受浏览器每域限制；私信之外还有已读回执、正在输入、
  在线状态、通知，双向通道更自然。
- WebSocket 在移动端的重连/心跳/前后台切换需要自己写，但这是可控成本。

**协议约定（草案）**：

- 信封：type / id / ts / payload 四个字段
- 客户端上行：ack（消息确认）、ping；服务端下行：pong、event
- **断线续传**：每个事件带自增序号，重连时带 lastEventId，服务端从 Redis Stream 补发最近 N 条
- 心跳 30s；服务端 2 个周期没收到 pong 就断开
- 发送失败进重试队列；客户端展示层自己维护乐观更新

**扩展路径**：单机用 Spring WebSocket + Redis Pub/Sub 广播；多实例时每个实例订阅同一频道；
再大就拆独立连接网关（Netty），业务服务只负责投递。**现在不拆。**

### D5. 形态：模块化单体（Modular Monolith），不是微服务

**决定**：后端保持一个可部署单元，但内部按**领域模块**划清边界：
auth / user / post / media / message / realtime / admin / infra。
模块之间只通过**公开接口（facade）+ 领域事件**交互，不跨模块直接调 Mapper。

**理由**：

- 百万日活的公司也不是一开始就微服务。单体的部署复杂度最低，而模块边界能在需要时**无痛拆出**。
- 真正的成本不在「拆不拆」，而在「边界有没有守住」：跨模块 join、共享表、循环依赖，才是拆不动的根因。

**纪律**：

- 每个模块有自己的 api（对外接口/DTO）与 internal（实现、Mapper、领域对象）；
- 跨模块只允许依赖 api 包；
- 跨模块的一致性用**领域事件 + Outbox**，不做分布式事务；
- 表按模块归属，禁止跨模块直接写别人的表。

### D6. 基础设施基线：该有的都上，但都能单机跑

| 能力 | 单机形态 | 规模化后 |
|---|---|---|
| 关系库 | MySQL 8.4（compose） | 主从 + 读副本，按学校/分区拆 |
| 缓存/会话/限流/广播 | Redis（compose，单实例） | Redis Cluster / 托管 |
| 对象存储 | MinIO（compose） | S3/OSS + CDN |
| 消息/异步 | Redis Streams + Outbox 表 | Kafka / 云 MQ |
| 实时 | Spring WebSocket + Redis Pub/Sub | 独立连接网关 |
| 入口 | Caddy（自动 HTTPS） | LB / API Gateway / WAF |
| 可观测 | 结构化日志 + Micrometer/Prometheus + OpenTelemetry | 托管 APM / 日志平台 |
| 部署 | docker compose 单机 | K8s / 容器编排 |

**原则**：所有状态外部化（Redis/MySQL/S3），应用实例无状态，才能「一台机器跑，十台机器也跑」。

### D7. 单机部署形态

一台服务器上：caddy + backend + frontend + mysql + redis + minio，全部 compose 管理。
备份、日志轮转、健康检查、告警从第一天就有——「单机」不等于「裸奔」。

## 3. 非目标（现在不做）

- 微服务拆分、服务网格、多集群
- 自研 API 网关 / 自研消息队列
- 同时上 Android
- 为百万 DAU 提前做分库分表（先把索引、缓存、读写分离做好）

## 4. 后果与风险

- **复杂度上升**：双认证入口、WebSocket 生命周期、Redis 成为关键依赖。任何一项挂掉都要有降级路径。
- **Redis 单点**：单机阶段 Redis 与 MySQL 同机，挂了就是全站不可用；接受，但要有持久化与快速重启。
- **契约冻结成本**：/api/v1 一旦给 App 用，就不能随便改语义；OpenAPI + 生成客户端是护栏。
- **模块纪律靠人**：没有 ArchUnit 之类的检查，边界会被慢慢侵蚀。计划加一条 CI 规则（后续）。

## 5. 演进路线

| 阶段 | 目标 | 关键动作 |
|---|---|---|
| P0（当前） | 单机可上线 | 单契约、Cookie 认证、WebSocket、Redis、观测性、备份 |
| P1 | 模块边界 | 按领域拆包、Outbox、事件总线、ArchUnit |
| P2 | 性能 | 压测、索引、缓存策略、CDN、图片处理 |
| P3 | 水平扩展 | 无状态验证、多实例、连接网关、读写分离 |

## 6. 验证方式

- 压测：单机目标（待定，建议先定 P95 与 QPS 基线）
- 混沌：杀掉 Redis / MySQL，确认降级行为符合预期
- 观测：每个请求有 requestId，关键路径有指标与告警

## 7. 待定（需要后续拍板）

1. Android 是现在就用 KMP 共享领域层，还是等 iOS 稳定后再决定？
2. WebSocket 信封是否直接采用 STOMP，还是自定 JSON（当前倾向自定，少一层语义包袱）？
3. 多学校/多租户的隔离粒度（按 school_id 分区还是独立库）？
4. 是否引入独立的消息队列（现在用 Redis Streams 够不够）？
