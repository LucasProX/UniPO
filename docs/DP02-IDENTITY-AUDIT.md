# DP02 身份失效与会话索引审计

> 后续进展（2026-10-22）：本文保留修复前审计快照。R01 旧版 HTTP 失效缺陷和 R06 Redis 索引缺陷已在独立夹具复现，新实现及当前验收见 [DP02-VERIFICATION.md](DP02-VERIFICATION.md) 与 [ADR-0003](adr/0003-authentication-revocation.md)。下文“未运行”描述的是当时状态，不能作为当前结果。

日期：2026-10-21。代码基线：`ae61c470ad1ba20745fac7185d4ca3d61a690d61`，`main`。行号以本次阅读时的后端文件为准，后续修改后按类名、方法名定位。

状态：**只读静态审计完成；下述运行时复现、修复与验收均未执行，DP02 未完成。** 本文不把控制流推导写成已观察到的接口结果。未访问服务器、生产数据库或真实凭据，也未执行任何失效动作。

## 1. 范围与依据

本次核对退出当前设备、退出所有设备、改密/重置、封禁、注销、角色变化后的 Cookie、Bearer access、refresh 和既有 WebSocket；同时检查 Redis 用户会话索引的续期与撤销路径。

依据是 [开发规划书](DEVELOPMENT-PLAN.md) §5.7、§9 的 DP02、§12 的身份测试要求，以及 [ADR-0001](adr/0001-platform-baseline.md) D3/D4：App 使用 Bearer + Keychain，Cookie 是既有后端兼容能力，两种载体应服从相同身份失效规则，实时连接也不能永久保留旧身份。

本期仍只开发原生 iOS、后端与基础设施。Cookie 后端的撤销、权限和 CSRF 兼容必须维持；本文不安排 Web 页面、管理网页或 Web UI 开发。

同轮开展的 iOS 本地恢复可靠性工作属于 DP05/DP21 的增量：网络、服务异常、Keychain 读写失败和异步旧结果不应误删或覆盖当前凭据。客户端可靠保存、清理凭据，不能代替服务端拒绝已失效的凭据，也不构成 DP02 验收。

## 2. 验收目标与当前状态

规划书要求失效动作成功后的下一次 API 请求和 refresh 拒绝旧身份；封禁、角色变化不能继续沿用旧权限。既有 WebSocket 的关闭或拒绝上行、停止私密事件投递，需要明确最长秒数并验证。

以下是当前代码控制流，不是本轮接口实测结果：

| 操作 | Cookie HTTP | 已签发 access | 已签发 refresh | 既有 WS |
| --- | --- | --- | --- | --- |
| 退出当前设备 | 撤销请求所带 session | 仅拉黑请求所带 token | 仅拉黑请求体所带 token，字段可选 | 未复验或关闭 |
| 退出所有设备 | 有底层撤销方法，无对应业务入口 | 无用户级撤销路径 | 无用户级撤销路径 | 无用户级关闭路径 |
| 重置密码 | 未撤销 | 未撤销 | 仍可换新凭据 | 未复验或关闭 |
| 封禁 | 保留 session 身份/角色快照 | 保留 JWT 身份/角色快照 | refresh 查询当前状态，会拒绝非 active 用户 | 未复验或关闭 |
| 注销 | 调用用户级撤销，但依赖 Redis 用户索引完整性 | 未统一撤销；部分接口仍接受 | refresh 查询当前状态，会拒绝 deleted 用户 | 未复验或关闭 |
| 角色变化 | 保留旧角色 | 保留旧角色至 token 到期 | 旧 refresh 仍接受，新签 token 使用数据库当前角色 | 未复验或关闭 |
| 凭据自然到期 | HTTP resolve 受 session TTL 限制 | 新 HTTP 请求校验 JWT 到期 | refresh 校验 JWT 到期 | 已绑定连接不再校验原凭据到期 |

“有方法”不等于业务已接通；“客户端退出了”不等于服务端撤销成功。动作前已在途请求的处理边界、动作提交时刻和并发刷新规则，需要在 ADR 中明确，不能只验客户端页面是否变为未登录。

## 3. 静态确认的缺口

### F01：HTTP Bearer 没有统一用户身份失效校验

[JwtAuthenticationFilter.java](../backend/src/main/java/com/biecuoguo/security/JwtAuthenticationFilter.java) 33–40 行只查单 token 黑名单，再从 JWT 解析 principal 和角色；没有读取当前账号状态、最新角色或身份版本。[JwtService.java](../backend/src/main/java/com/biecuoguo/security/JwtService.java) 67–85 行签发并还原 `sub/email/role/typ/iat/exp`，未携带用户身份版本或设备会话标识。

[AuthService.java](../backend/src/main/java/com/biecuoguo/service/AuthService.java) `resetPassword()` 304–320 行仅校验验证码、更新密码 hash 和时间；`me()` 338–342 行本身也不检查 active。仓库未找到登录后的独立改密端点；不能把重置密码覆盖视为所有改密流程已经覆盖。

[AdminService.java](../backend/src/main/java/com/biecuoguo/service/AdminService.java) 44–61 行更新 status 或 role 后，只写 users，未调用会话撤销或 token 撤销。Cookie 的 [SessionAuthenticationFilter.java](../backend/src/main/java/com/biecuoguo/security/SessionAuthenticationFilter.java) 43–47 行也直接使用存储里的角色快照。

具体受影响路径：

- 私信列表 `/api/v1/messages/conversations`：MessageService 44–85 行按 `CurrentUser.id()` 查会话，未校验发起用户当前状态。
- 私信历史 `/api/v1/messages/conversations/{id}/messages`：115–130、187–192 行只检查会话成员身份，未校验账号 active。
- 管理概览 `/api/v1/admin/analytics/overview`：SecurityConfig 75 行按认证快照的角色放行；AdminService.overview 不复验当前角色。用该只读接口验证降权，避免读取用户表中的敏感字段。

更新资料等个别服务已检查 active，并不能弥补认证入口的统一缺口。默认 access TTL 是 120 分钟，见 application.yml 139 行；部署可覆盖，不能把两小时当成所有环境的精确实测值。

### F02：重置后旧 refresh 可继续派生新凭据

AuthService.refresh 120–137 行检查单 token 黑名单、refresh 类型/签名/到期，以及当前用户是否 active，但不检查密码是否已经重置。`issueTokens()` 216–220 行每次再签发一对 access/refresh；旧 refresh 未撤销。

因此风险不只原 refresh 剩余十四天：持有者只要持续在到期前刷新，就能派生新的 refresh。默认十四天来自 application.yml 141 行；实际 TTL 仍取决于环境配置。封禁和注销期间 refresh 会因账号状态拒绝；角色改变后 refresh 会使用最新角色，这两点应保留回归。

### F03：当前设备退出缺少设备会话关联和可靠撤销结果

[AuthController.java](../backend/src/main/java/com/biecuoguo/web/AuthController.java) 100–132 行只撤销本次所带 access、可选 refresh 和 Cookie。JWT 没有设备会话标识，刷新前保存的其他 token 不能按“同一设备”一起撤销。检索未找到退出所有设备的 HTTP 入口；`SessionService.revokeAllForUser()` 当前业务调用只在注销。

`/logout` 受 SecurityConfig 的 `.anyRequest().authenticated()` 保护。App 不带 Cookie，access 已过期时请求会在进入 Controller 前失败，仍有效的 refresh 没有机会被撤销。当前 iOS APIClient.logout 用 `try?` 忽略错误，AuthStore 随后清本地；这是“本地退出完成，远端旧 refresh 仍可用”的明确代码路径。

Controller 116–121 行还把 refresh 撤销时的所有 RuntimeException 当成“过期或格式不对”忽略；若 access 撤销已成功而后续存储写失败，响应可能仍为退出成功。需要故障注入复现，不能用一条无效 token 用例代替。

### F04：Redis 用户会话索引 TTL 未跟随活跃 session 续期

[RedisSessionStore.java](../backend/src/main/java/com/biecuoguo/state/RedisSessionStore.java) 的实际 key：

- `unipo:session:{sessionId}`：带 TTL 的 session JSON。
- `unipo:session:user:{userId}`：该用户 session ID 集合，用于全部撤销。

`save()` 40–48 行给两者设置同一 TTL；`loadAndTouch()` 52–60 行只续单 session；`deleteAllForUser()` 75–83 行完全依赖用户集合查找待删 session。活跃 session 跨过初始用户索引 TTL 后，用户集合已不存在，全部撤销会漏掉该 session。注销调用的也是这条路径。

此处应先做真实 Redis 的短 TTL 测试。浏览器 Cookie 的 `Max-Age` 只在签发时设置，见 CookieSupport 41–47 行；服务端 touch 没有刷新浏览器 Cookie。因此不能声称本轮已经观察到浏览器无限保持登录，也不能靠浏览器自动丢 Cookie 证明服务端旧 session 已撤销。

### F05：既有 WS 丢失凭据上下文，不再执行失效检查

- [RealtimeHandshakeInterceptor.java](../backend/src/main/java/com/biecuoguo/realtime/RealtimeHandshakeInterceptor.java) 38–39 行只在 Cookie 握手时 resolve，此后仅保存 userId。
- [RealtimeWebSocketHandler.java](../backend/src/main/java/com/biecuoguo/realtime/RealtimeWebSocketHandler.java) 138–147 行只在首次 Bearer auth 时查黑名单并解析 JWT；125–135 行绑定后同样只保留 userId/连接。
- handler 103–117 行的 resume 只检查已经绑定 userId；投递回调与 send 不复验 token/session、状态、角色或到期。已绑定连接再次发 auth 会在 90–93 行直接返回。
- [RealtimeHub.java](../backend/src/main/java/com/biecuoguo/realtime/RealtimeHub.java) 78–93、125–148 行仅按用户投递，连接句柄没有凭据版本或到期时间。
- RedisRealtimeBroker 的消息只包含 `BrokerMessage(userId,event)`，没有跨实例撤销/关闭消息。
- MessageService 149–151 行实际投递的 message payload 含私信正文，故风险有明确的私密内容载荷。

动作路径没有调用关闭这些连接；token/session 自然到期同样不会触发认证驱动的关闭。handler 注释所写“未认证三十秒断开”在当前 WebSocketConfig 中没有对应配置或计时器证据；ping 无需认证即可 pong，iOS 又每二十秒 ping，不能把可能存在的网络空闲超时当作安全时限。

## 4. 需要额外复现的并发与兼容边界

这些是静态读写次序暴露的候选问题，尚未做确定性时序测试，不与已实测故障混写：

| 场景 | 代码线索 | 必须证明的结果 |
| --- | --- | --- |
| memory touch 与全部撤销交错 | MemorySessionStore 33 行 get，42 行 put；56–59 行独立 remove | 已撤销 session 不因旧 touch 再写回而复活 |
| Redis 创建 session 与全部撤销交错 | save 先 SET session，再 SADD 用户索引；全部撤销分步遍历与删除 | 动作成功后，旧授权在途签发的凭据不能成为可用漏网会话 |
| refresh 验证与改密/封禁/注销并发 | 先查状态，后签 token，无身份代际比较 | 旧 refresh 在失效动作前开始、之后结束也不能绕过失效边界 |
| Cookie 与失效 Bearer 同时携带 | SessionAuthenticationFilter 以认证上下文是否为空决定回退 | 明确载体优先级与 CSRF 行为，不因修复使另一载体意外恢复旧身份 |
| Redis 撤销写失败 | logout 对 refresh 撤销宽泛 catch | 不能返回“全部撤销成功”却留下可用凭据；失败可定位、可重试 |
| 重启或跨实例 | memory 状态不共享且重启丢失；Redis 是共享实现 | 用户级身份失效不能被重启或另一个实例绕过 |

HTTP 在途请求需要明确线性化边界。不能把撤销前已经通过授权的在途请求和撤销完成后发起的新请求混作同一断言；但在途 refresh 派生出的 token 仍必须在撤销后校验时失效。

## 5. 隔离复现环境与证据格式

下轮先建立可重复的测试夹具，再改实现。本轮没有创建下面的夹具、测试类或账号。

1. 新建一次性 MySQL schema、独立 Redis 和媒体桶，全部使用合成数据。推荐 Testcontainers 或独立测试 Compose 文件；现有 backend/pom.xml 尚无 Testcontainers 依赖，不能假定运行时已具备。
2. 如用本机端口，可预留 MySQL `127.0.0.1:22306`、Redis `127.0.0.1:26379`、MinIO `127.0.0.1:29000`，应用 A/B 用 `127.0.0.1:28080/28081`；执行前验证未占用，端口只是夹具建议值。
3. 不复用生产、服务器 test 实例、现有 SSH 隧道或本机日常开发库。仓库 Compose 有固定 container_name，不能只加 `-p` 就认定已隔离；测试配置应有独立名称、网络、卷和端口。
4. 测试配置显式覆盖 datasource、Redis、媒体桶与 JWT；仓库根 `.env` 会被导入，不可仅凭 `local/test` profile 推断数据库安全。集成测试优先用动态测试属性明确注入连接信息，并在动作前断言实际连接到测试 schema/实例。
5. 凭据运行时随机生成，不写入仓库，不打印 access/refresh、密码、验证码、Cookie 值。关闭演示账号种子和 APNs；仅隔离的 local/dev/test profile 可使用验证码开发开关。
6. 准备 A/B/C 三个合成普通用户及专用管理员 M。A 的设备一/二独立保存各自 Cookie jar、CSRF、access、refresh；B 用于消息和对照，C 用于验证其他用户不受影响。角色变化案例用独立的管理员被测账号，避免失去唯一操作入口。
7. 单实例 memory、单实例 Redis 先建立基线，再运行双实例共享 Redis/MySQL；两实例共享同一测试 JWT 密钥，应用端口不同。

所有动作使用 `/api/v1`。以下是请求清单，body 中变量由夹具注入，不是仓库内固定账号密码：

| 目的 | 请求 | 关键载荷/检查 |
| --- | --- | --- |
| 建立账号 | `POST /auth/verification-code` → `POST /auth/register` | 同一合成账号，随机密码，合法验证码；保存两种载体但不输出其值 |
| 第二设备登录 | `POST /auth/login` | 同一账号；独立 Cookie jar；不得覆盖设备一快照 |
| 密码重置 | `POST /auth/verification-code` → `POST /auth/reset-password` | purpose=`reset-password`；新随机密码 |
| 封禁 | `PUT /admin/users/{id}/status` | M 身份，`{status: disabled}`；解除封禁用 `active` |
| 降权 | `PUT /admin/users/{id}/role` | M 身份，`{role: USER, operatorScope: student}` |
| 注销 | `DELETE /users/me` | 被测用户身份，Cookie 写请求带该 session 的 CSRF |
| 当前退出 | `POST /auth/logout` | 本次 access + 对应 refresh；Cookie 路径单独测试 |
| 刷新 | `POST /auth/refresh` | 仅 body refreshToken，不带旧 Authorization 或 Cookie |
| 私密 API 探测 | `GET /messages/conversations` | 明确只带被测载体，不自动回退另一载体 |
| 旧权限探测 | `GET /admin/analytics/overview` | 降权前成功，降权后用原凭据重试 |
| 消息载荷 | `POST /messages/conversations/{id}/messages` | B 向 A 的既有会话发唯一标识合成文本 |

无“退出所有设备”或独立改密业务端点时，先把对应场景标成“缺少入口”；不可用注销或直接调用存储方法冒充这些业务动作通过。新端点与行为先写 ADR/契约。

每个用例记录：编号、commit、配置模式、实例路由、合成用户/设备标签、动作成功时间、后续请求时间、HTTP status/code/requestId、WS 关闭及最后事件时间、期望与实际、产物路径。日志只记标签及 requestId，不记凭据；失败测试可以保存已脱敏的响应和时序。

## 6. 按顺序执行的复现与验收用例

表中的“预期现状”仅由当前代码推导；每一项执行结果目前均为**未运行**。先用能失败的隔离测试确认缺陷，再让同一测试成为修复验收。

### R01：密码重置后旧身份

1. A 在设备一、二登录，保存两套原 Cookie/access/refresh；预先建立 A/B 会话。
2. 完成 A 的密码重置，确认动作自身成功；不要再登录覆盖旧快照。
3. 两套旧 access 和两套旧 Cookie 分别请求私密 API，两套旧 refresh 分别刷新。Cookie/CSRF 与 Bearer 请求分开。
4. 预期现状：旧凭据没有统一失效，refresh 仍能派生新的一对。
5. 修复验收：动作成功后的新请求拒绝全部旧身份；新密码可重新登录，新身份可正常使用；C 不受影响。

### R02：封禁与解除封禁

1. 保存 A 两设备凭据及既有 WS，由 M 封禁 A。
2. 用旧 access/Cookie 调私密 API，旧 refresh 调刷新，B 发私信并观察 A 旧 WS。
3. 预期现状：refresh 拒绝，旧 access/Cookie/WS 无统一拒绝。
4. 修复验收：统一拒绝，重新登录同样拒绝；解除封禁后必须明确是否要求重新认证。建议失效前旧凭据不可复活，该规则待 ADR 确认并加断言。

### R03：角色降权

1. 被测管理员的 access/Cookie 先成功调用只读管理概览，由另一个管理员 M 将其改为 USER。
2. 原 access/Cookie 再调管理概览，再尝试原 refresh；另建新登录作为对照。
3. 预期现状：原 access/Cookie 沿用旧权限；refresh 使用数据库新角色。
4. 修复验收：不保留旧管理权限；旧凭据是否整体拒绝按统一失效规格确定，新 USER 身份访问管理端点应为 403。封禁/撤销身份的 401 与合法身份权限不足的 403 分开检查。

### R04：注销与其他设备

1. A 两设备登录、建立 WS 与 A/B 会话；设备一注销。
2. 设备二原 access/Cookie 请求私密 API，原 refresh 刷新；B 向原会话发合成消息。
3. 预期现状：refresh 拒绝；未跨用户索引 TTL 时 Cookie 被删，但旧 access 与既有 WS 仍缺统一拒绝。
4. 修复验收：所有旧身份失效，私密事件停止投递；帖子/评论外键和 B/C 身份不被破坏。另在 R06 跨索引 TTL 后重做注销，防止漏撤销。

### R05：当前设备退出、过期 access 与刷新派生

1. 普通退出：A 设备一带当前 access + refresh 退出，复查这对凭据；设备二应按“当前设备”语义继续正常使用。
2. 派生凭据：设备一先刷新，保留刷新前后所有 token，跨过一个 JWT 签发时间秒，避免同秒签发同内容造成快照相同；退出后分别重试同设备历史 token。
3. 过期 access：把夹具 access TTL 缩短，等待其自然过期但 refresh 有效；调用退出，记录是否进入 Controller，随后重试原 refresh。
4. 故障注入：令 access 撤销成功、refresh 撤销存储写失败，检查响应、远端凭据和重试结果。
5. 修复验收：设备会话内所有旧 token 的行为与 ADR 一致；过期 access 不应导致仍有效 refresh 无法安全撤销；不能返回虚假的远端撤销成功。客户端即时本地退出和远端撤销失败的反馈/重试是独立断言。

### R06：真实 Redis 用户索引 TTL

先直接针对 RedisSessionStore 建集成测试，使用随机 session ID 和隔离 Redis；不需要真实手机号或等待七天：

1. `save(S, A, ttl=4s)`，断言 session 与用户集合均存在。
2. 使用有界条件等待在初始到期前调用 `loadAndTouch(S, 4s)`，例如约 t=2s；记录两 key 的 PTTL，不输出 S。
3. 跨过最初的索引到期时刻，确认续期后的 session 仍存在，再记录用户索引是否存在；当前代码预期用户索引已不存在。修复后的测试不能以“等待索引消失”为通过前提。整个用例设明确超时，勿只靠脆弱的精确 sleep 时点。
4. `deleteAllForUser(A)`，再 `loadAndTouch(S, 4s)`；当前代码预期仍返回身份。
5. 修复后应在第 3 步证明索引覆盖所有仍有效 session，并在第 4 步返回 empty；C 的 session 保留。
6. 两个 session 交错活跃、创建/撤销并发、坏 JSON、单会话删除及索引清理分别补充测试。不能只断言“调用了 expire”，要断言最终没有漏网可用 session。

通过存储复现后，再用手工 Cookie 请求与注销业务补一条端到端测试。浏览器 Cookie 自身到期不替代服务端撤销断言。

### R07：WS 撤销、到期与私密事件

1. A 分别建立 Cookie 和 Bearer WS，等待 ready，保留原连接并定时 ping。B 先发一条消息作为正常投递基线。
2. 独立账号/独立夹具分别执行退出、重置、封禁、注销、降权；动作成功后 B 发带唯一标识的合成消息，旧连接同时发 resume。
3. 记录服务端动作完成到连接关闭、拒绝上行和最后私密事件的时间；凭据自然过期再单独重复。
4. 建议 ADR 规定最长 **5 秒**关闭或拒绝上行并停止投递，作为待评审目标；该数值目前不是已批准规格，更不是当前保证。实现阶段必须确认一个明确秒数，测试使用该规格，不能临时放宽超时以求通过。
5. 失效后新建连接也应拒绝旧凭据；在截止点后生成的私密事件不得投递给旧连接，resume 不得绕过权限。
6. Redis 历史续传本身另有 DP16 验证项，不能把“续传没有返回事件”误判为认证拒绝。断言明确的鉴权错误/关闭和在线新事件投递，并用有效身份对照证明消息确实产生。

### R08：并发与双实例共享 Redis

1. 用可控 barrier/latch 固定 R04/R05 的在途 refresh 与撤销先后，不用随机压测代替稳定复现。覆盖 memory touch 与撤销交错，证明不会复活旧会话。
2. A 连接/登录驻留应用实例一，重置、封禁、注销和退出从实例二执行；后续 HTTP/refresh 分别打两个实例。反向路由重复。
3. B 从实例二发消息，验证实例一旧 WS 在同一规定秒数内停止；有效对照 WS 仍能收到，C 不受影响。
4. 对 Redis 短暂不可用、撤销消息丢失/延迟、应用重启补验身份检查，不能把只依赖一次 Pub/Sub 通知的关闭视为完整保证。
5. 修复验收：实例切换和重启不恢复旧身份；认证共享状态失败遵守既定失败语义。限流的 fail-open 不能复用为身份失效的放行策略。

## 7. ADR 与实现需要明确的决策

以下是下一轮设计问题，不代表已经选择或实现：

- 用户身份版本的权威来源、递增事务边界、跨实例读取一致性；改密、重置、封禁、注销和角色变化分别何时递增。
- 设备会话的标识与生命周期；access/refresh/Cookie 如何关联当前设备，当前设备退出与所有设备退出分别影响哪些凭据。
- HTTP 与 refresh 的统一校验入口；用户状态、最新角色、版本与单 token 黑名单的关系，避免每个业务服务各补一份状态判断。
- 老 JWT 没有版本/会话声明时的迁移策略及截止时间；不能通过永久默认版本让历史凭据绕过新规则。保留 access/refresh 的 typ 类型隔离。
- 验证与签发并发、撤销失败、Redis 故障和重启的处理；确保成功响应对应已生效的失效边界。
- Cookie 用户索引与有效 session 的一致性，以及浏览器 Cookie 固定 Max-Age 和服务端滑动 TTL 的产品语义；维持 CSRF、载体优先级、现有 HTTP 契约。
- WS 保留什么身份上下文，如何在上行、投递、重放及定时检查中复验；跨实例撤销通知与补偿检查怎样共同满足明确秒数上界。
- API 只做兼容加法，新增迁移按实施时实际最大版本递增，不修改已发布迁移。

## 8. 下轮优先顺序与完成条件

按以下顺序推进，不把当前客户端改动作为后端安全包结项：

1. **先写隔离复现**：R01–R07 中先落最危险、最确定的失败测试，固定环境、凭据快照和证据格式；再补并发时序。
2. **统一用户身份版本和设备会话 ADR**：确认失效动作、边界、载体、历史凭据和错误语义。
3. **HTTP/refresh**：实施统一拒绝入口及设备/用户撤销，复验重置、封禁、注销、降权、当前/全部设备退出。
4. **Cookie 索引**：真实 Redis 验证跨初始 TTL、交错活跃和撤销，并维持 Cookie 后端安全兼容，不新增 Web UI。
5. **WS 权限和秒数上界**：绑定可复验身份，覆盖在线投递、resume、自然到期和失效通知遗漏，写下明确最长间隔。
6. **两实例 Redis 集成**：跨实例 HTTP/refresh/WS、重启与故障回归，之后才评估 DP02 是否完成。

现有 JwtServiceTest 验 token 类型/到期，SessionServiceTest 仅用 MemorySessionStore 验手工单会话/全会话撤销，RealtimeHubTest 仅验手工 connection.close 停止投递。它们应继续通过，但没有覆盖本文业务失效路径、Redis 索引 TTL 或 WS 身份变化。

DP02 完成需同时提交 ADR、实现、隔离复现由失败转通过的记录、双设备/双实例证据、WS 明确秒数及故障回归、迁移与兼容说明。结果记录实际 commit、环境和残余限制；未运行、只静态确认或尚缺入口的场景不能标成通过。本轮仅形成本文，所有 R01–R08 仍待执行。
