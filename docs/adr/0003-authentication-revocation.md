# ADR-0003 · 认证撤销：账号版本、持久设备会话与实时校验

- **日期**：2026-10-21
- **状态**：决策已落实到本轮实现，DP02 集成与故障验收仍在进行；本记录不代表工作包结项。
- **关联**：[ADR-0001](0001-platform-baseline.md) D1/D3/D4/D6、[ADR-0002](0002-module-boundaries.md)、[开发规划](../DEVELOPMENT-PLAN.md) DP02。
- **影响范围**：Bearer、Cookie、refresh、账号安全变更、Redis 会话索引与 WebSocket 生命周期。

## 1. 背景与边界

仅验证 JWT 签名和过期时间，无法让改密、封禁或退出所有设备后的旧 access 立即失效。仅把某个 token 记入黑名单也覆盖不了该设备通过 refresh 派生的其他 token。Web Cookie、App Bearer 和已经建立的 WebSocket 如果各自维护有效性判断，还会出现 HTTP 已失效而私密事件仍在投递的情况。

DP02 统一的是身份有效性，不拆分客户端业务接口。所有端继续使用同一 `/api/v1`；Web 保留 httpOnly Cookie 与 CSRF，App 保留 Bearer 与 Keychain。公开业务 DTO 不因客户端类型分叉。

撤销的明确生效点是持久化事务提交后：之后开始的认证校验必须拒绝旧身份。已经通过校验并进入执行的请求或帧无法追溯撤回，本 ADR 不把撤销描述为跨进程、数据库和网络发送的全局原子操作。

## 2. 决策

### D1. MySQL 是账号及设备有效性的持久权威

V20 引入 `users.auth_version` 与 `auth_device_sessions`：

| 数据 | 职责 | 有效性规则 |
| --- | --- | --- |
| `users.auth_version` | 账号级身份代次 | 凭据版本必须等于数据库当前值，账号必须为 `active` |
| `auth_device_sessions` | 每次登录的设备会话 | 绑定用户及版本，未撤销，且未超过绝对到期时间 |
| `SessionStore` | Web Cookie 载体及 CSRF 数据 | 载体存在后仍需校验上面两项，不能单独授予身份 |
| token 黑名单 | 单 token 的补充拒绝条件 | 不承担设备族或账号级撤销的唯一责任 |
| Redis Pub/Sub | 跨实例实时事件分发 | 收到事件不等于收件连接有权接收；丢失通知不能恢复旧身份 |

跨模块通过 `auth.api.AccountSecurityApi` 创建、校验和撤销设备会话，Mapper 留在 `auth.internal`。`validate()` 暂停调用方事务，以新的已提交视图读取账号和设备行；查询禁用缓存并清理 MyBatis 本地查询缓存，避免长业务事务的旧快照或上次成功结果继续授权。返回角色来自当前数据库，不直接沿用 JWT/Cookie 中的旧角色。

创建设备会话需要提供本次身份验证主体的预期版本，通过带当前版本与 active 条件的 `INSERT ... SELECT` 落库。密码登录读取密码时保留账号版本，若随后发生安全变更，旧版本不能继续创建设备会话。验证码登录与撤销的并发窗口仍需在集成验收中覆盖，不以这一条件插入推定所有登录竞争都已验证。

设备会话 ID 使用 32 字节随机数的 Base64URL 无填充编码。它不是设备硬件标识；一次重新登录可以创建新的设备会话。应用进程重启不会删除 MySQL 中已提交的设备撤销记录。

### D2. 所有凭据绑定账号版本和同一设备会话

`CurrentUser` 携带 `authVersion` 与 `sessionId`。access 和 refresh JWT 必须包含 `av` 与 `sid`，并继续用 `typ` 区分用途；缺字段的旧 JWT 不再通过认证。Cookie 服务端记录也必须带 `authVersion` 与 `deviceSessionId`，旧记录不能作为有效身份使用。

`unipo_sid` Cookie 保存的是 Web 载体 ID；JWT 的 `sid` 和 Cookie 记录中的 `deviceSessionId` 指向 MySQL 设备会话。二者不是同一种 ID，但都受同一账号版本和设备撤销状态约束。

refresh 校验原设备后重新签发 token，保持同一 `sid`、账号版本及设备绝对到期时间，不另建设备记录、不滑动延长设备寿命。签发的 access/refresh 到期时间都不得超过设备会话到期时间。即使返回新的 token 字符串，同一设备派生的全部 token 仍属于同一撤销范围。

这是一次有意的登录态兼容切换：部署后，旧 JWT/Cookie 用户需要重新登录一次。不能为了无感迁移给缺失的 `av/sid` 补默认值，那会重新引入没有撤销依据的身份。业务 API 版本保持 `/api/v1`，认证载体升级与业务契约分端无关。

### D3. 账号级与设备级撤销分开执行

以下操作递增 `users.auth_version`，并标记该账号旧版本设备已撤销：

- 修改密码、重置密码；
- 封禁、解封和角色/运营权限变更；
- 账号注销、退出所有设备。

解封也必须推进版本，不能让封禁前的旧凭据重新变成有效。上述安全操作涉及的账号数据变更与数据库版本推进在同一事务内完成；Cookie 索引同时清理，但跨 MySQL/Redis 不宣称分布式原子提交。存储操作失败必须向调用方暴露，不能在未确认撤销完成时返回成功。

单设备退出只撤销当前 `sid`，覆盖它派生的全部 access、refresh 和 Cookie 载体，不影响其他有效设备。`/auth/logout` 在 access 已过期时仍允许用有效 refresh 标识需要撤销的设备；同时提供可解析的两种凭据时，必须属于同一用户及设备，不能把两个账号的凭据拼接使用。

普通 `User` ORM 更新禁止回写 `passwordHash`、`role`、`operatorScope`、`status` 与 `authVersion`，使用 `FieldStrategy.NEVER` 防止旧资料实体覆盖新安全状态。安全变更走显式列更新，版本递增由认证模块维护；改密更新带预期版本条件，避免并发变更被旧请求覆盖。

### D4. Cookie 回退与 Redis 索引保持明确语义

HTTP 请求只有完全没有 `Authorization` 头时才尝试 Cookie。过期、错误或格式不符的显式 Authorization 不能被另一个有效 Cookie 静默替代。Cookie 认证写请求继续校验 `X-CSRF-Token`；Bearer 路径不因共用撤销权威而改为 Cookie，也不移除原有 CSRF 防线。

`RedisSessionStore` 用 Lua 原子执行会话写入与用户索引登记、读取续期与索引修复、单会话删除及用户索引批量清理。会话续期时索引有效期至少覆盖该会话的续期窗口，避免活跃 Cookie 仍存在而用户索引先过期。

`SessionService.resolve()` 可以续期；实时鉴权使用 `peek()`，只读且不 touch TTL。连接每秒校验身份不能把一个本来不活跃的浏览器会话永久续命。Cookie 载体即使滑动续期，也不能跨越 MySQL 设备会话的绝对有效期。

Redis JSON 中 `userId` 作为字符串写入和读取，Lua 索引拼接不把大整数用户 ID 转为浮点数。旧的数字形式记录不作为可用会话继续使用，避免超过精确整数范围后清理了错误的用户索引。

### D5. WebSocket 在每个授权点查权威，空闲连接有关闭上界

WebSocket 保存不可变的认证上下文，关联账号版本、设备会话和凭据到期时间；Cookie 上下文另保存载体 ID。它不是永久有效的用户 ID 快照。

- 握手或 auth 帧验证后，注册连接时再次校验，覆盖握手与注册之间的撤销窗口。
- 已认证连接的每条下行帧发送前重新查权威，包括 ready、pong、错误响应和业务事件。
- 所有交给消息处理器的上行消息先校验，不能只检查 resume 而漏掉 ping、ack 或非文本消息。
- replay 开始前以及每条补发事件发送前都校验；批次开始时有效，不代表整个批次一直有效。

空闲连接使用基于 `System.nanoTime()` 的 **4 秒校验租约**，独立 watchdog **每 1 秒**扫描。租约用于限制无交互连接及阻塞校验的存活时间，不代替逐帧权威查询。按这两个间隔定义，正常进程调度下空闲失效连接的逻辑关闭目标上界为 **5 秒**，仍须通过 DP02 故障验收确认。

租约从校验开始时间计算，慢返回的成功结果不能越过已到期租约，也不能重新激活已经关闭的连接。watchdog 自身不执行数据库查询或 socket 写入；查询在独立有界执行器运行，拒绝提交、超时或未知结果都关闭连接。同步权威查询等待上限为 1 秒。

写入使用 **2 秒慢写阈值**，由同一个 1 秒 watchdog 检查。超过阈值先标记逻辑关闭并从投递集合移除，再异步尝试关闭传输，避免一个阻塞写拖住其他连接的校验。2 秒是检测阈值，不是保证底层阻塞调用恰在 2 秒返回；5 秒也不代表客户端一定在该时间内收到 TCP/WebSocket 关闭帧。

已经通过校验并进入 socket 写入的在途帧无法撤回。逻辑关闭保证后续校验与新投递被拒绝，不承诺收回已经进入网络的数据，也不把长时间进程停顿算作可由应用调度保证的硬实时范围。

### D6. 未知状态拒绝授权，广播不承担撤销权威

MySQL、Redis、解析、线程池或超时导致身份无法确认时，不沿用上次成功状态放行。HTTP 受保护入口不能获得已认证身份；refresh 不发新凭据；WebSocket 拒绝上行和投递并逻辑关闭。具体错误响应仍遵循统一错误体，状态存储错误不暴露连接信息或凭据。

Redis Pub/Sub 可以分发事件，未来也可以加速撤销通知，但不能成为唯一撤销依据。即使广播丢失、订阅重连或实例重启，下一次权威校验仍由数据库版本和设备记录决定有效性。

`STATE_BACKEND=memory` 仍只用于单实例：Cookie 载体、在线状态、限流、实时缓存及广播并未因此自动共享。MySQL 设备权威在 memory 模式也不丢失，但这不等于 memory 已满足多实例部署要求；多实例继续使用 Redis。

## 3. 代价与未作承诺

- 每次 HTTP 身份校验和实时逐帧校验增加数据库读取；空闲连接还会触发周期校验。当前没有正向有效性缓存，以免扩大撤销窗口。
- **QPS、P95/P99、数据库连接池压力及大规模空闲连接开销尚未压测。** 本实现确立撤销语义，不能据此宣称已经达到百万日活容量目标。后续优化需保留同等授权点与关闭边界，并用测量证明。
- 故障时拒绝授权会牺牲可用性，用户可能需要重试或重新连接；这比在无法确认身份时继续投递私人数据更符合本次要求。
- 旧 JWT/Cookie 需要一次性重新登录。上线前要安排统一切换，不能让仍接受旧凭据的实例和新校验规则长期混跑。
- 回滚镜像也必须具备这套身份版本/设备校验。回到仅验 JWT 的旧过滤器会重新接受已撤销凭据，不能把保留旧镜像误写成安全回滚方案；V20 是增量迁移，不回删设备撤销事实。
- 用户列表、封禁和角色修改仅限 ADMIN，HTTP 路径与 Service 方法都校验。普通运营角色仍可使用其原有概览入口，但不能通过账号管理接口给自己提权或处置任意账号。
- 未引入微服务、独立连接网关或新的客户端业务 API；此次只收紧现有模块边界和授权规则。

## 4. 验收门槛

DP02 必须等双实例集成与故障验收完成才能结项。单元测试、编译或本文落盘均不能替代以下验证：

1. 两个后端实例共享 MySQL/Redis，覆盖两设备 Cookie、access、refresh：改密、重置、封禁、解封、角色变更、注销和 logout-all 成功后，另一实例下一次校验拒绝旧身份。
2. 单设备退出撤销此前多次 refresh 派生的 token；access 已过期仍可用有效 refresh 退出；其他设备不受影响；refresh 不延长原设备绝对寿命。
3. 并发登录与撤销、资料更新与安全更新不能恢复旧版本、旧密码或旧权限；重启后设备撤销仍然存在。
4. Redis 会话续期不丢用户索引，peek 不续期；验证大整数用户 ID、索引清理与续期竞争，以及失效 Bearer 不回退 Cookie、Cookie 写请求仍要求 CSRF。
5. 活跃与空闲 WebSocket、全部上行类型、逐帧 replay、跨实例事件投递都遵循撤销结果；实测 4 秒租约加 1 秒 watchdog 的空闲逻辑关闭上界，以及 2 秒慢写阈值的处理。
6. 注入 MySQL/Redis 不可达或慢响应、校验执行器拥塞、socket 慢写及 Pub/Sub 中断，确认未知状态不授权、watchdog 不被阻塞、迟到成功结果不能复活连接。

## 5. 实现入口

- 数据与领域边界：`V20__account_security_sessions.sql`、`auth/api/AccountSecurityApi.java`、`auth/internal/AccountSecurityService.java`、`DeviceSessionMapper.java`。
- HTTP 与凭据：`security/CurrentUser.java`、`JwtService.java`、`AuthenticationStateService.java`、`SessionService.java`、`SessionAuthenticationFilter.java`、`CsrfFilter.java`、`web/AuthController.java`。
- 状态与写入约束：`state/SessionStore.java`、`RedisSessionStore.java`、`domain/User.java`。
- 实时边界：`realtime/RealtimeHub.java`、`RealtimeWebSocketHandler.java`、`RealtimeSecurityExecutor.java`、`config/RealtimeSecuritySchedule.java`。
