# DP02 · 身份失效与 Redis 会话索引验收记录

记录日期：2026-10-22，时间使用 Asia/Shanghai。范围与完成标准来自 [DEVELOPMENT-PLAN.md](DEVELOPMENT-PLAN.md) v1.1，工程决策见 [ADR-0003](adr/0003-authentication-revocation.md)。

**当前结论：本轮实现、隔离环境系统回归和客户端自动化证据已交付，DP02 暂不整体结项。** 最终后端包的 HTTP/WebSocket 主套 19 个场景通过，故障套 3 个场景通过，通用冒烟 42 项通过；短信登录并发、真实 UI 吊销、最低系统与真机、部署及容量仍有待验项目。

## 1. 环境与证据口径

- 所有后端集成使用本机新建、独立归属的 fixture：两个应用进程共用本轮 MySQL/Redis，通用冒烟另带独立媒体服务；不连接生产数据库。
- **生产服务和既有服务器 test 实例本轮均未更新。** 本文的通过结论不表示这些环境已经运行新认证规则。
- fixture 使用运行标记、固定容器 ID 与归属标签校验；执行者已确认最后一轮 fixture 清理完成。最终冒烟 JSON 的 `cleanup_complete=true`，最终故障 JSON 的 `recovery_still_required=[]`。
- 下文证据路径相对仓库根，位于 `.logs/dp02/` 的产物为本机保留文件，不随 Git 自动发布。JSON 明确 `credentials_recorded=false`；本文不记录 token、密码或真实环境配置。
- 主套、最终故障套与最终冒烟的 `backend.sha256` 相同：`13fe5a2e8c5708b110de1c7ca4ab81c328dafc3acbd3edc6fd2ac57718b188a7`。这三份结果对应同一后端包，不能用此前绿灯替代本轮包的结果。
- “场景数”“检查条数”“测试用例数”分别列出，不相加为一个测试总数。带条件跳过的测试不记成已执行。

## 2. 要求、证据与剩余缺口

| 要求 | 本轮实际证据 | 状态与边界 |
| --- | --- | --- |
| 改密、重置、封禁/解封、角色变更、注销、退出所有设备后，旧 Cookie/access/refresh 跨实例失效 | 最终主套 R01–R07；MySQL 专项账号版本与设备校验 | 隔离 HTTP 回归通过；未进行真实 App UI 远程吊销验收 |
| 当前设备退出覆盖全部派生 token，其他设备不受影响 | 主套 R05、多次 refresh 历史凭据、WS-current-device-logout | 通过；不是只检查最后一枚 access |
| access 自然过期后仍能用有效 refresh 退出，设备绝对期限不随刷新延长 | 主套 EXPIRED-access-valid-refresh-logout；MySQL 设备寿命专项及 JWT 测试 | 已覆盖上述自动化场景；不代表所有登录并发已验收 |
| 用户安全版本不能被旧资料实体、业务事务旧快照或旧身份重新覆盖 | MySQL 13 项专项，包含旧 ORM 写入、事务回滚、并发创建设备/撤销、Repeatable Read 旧快照 | 已通过数据库级测试；短信验证码验证与撤销的完整登录竞争仍待专项 |
| Redis 会话与用户索引同步续期，活跃会话始终可批量撤销 | R06 真实 Redis 红→绿，最终 2 项执行、0 跳过；主套 logout-all | 已覆盖实际 TTL 跨越和其他用户隔离；不以 HTTP 检查代替 Redis TTL 专项 |
| 失效 Bearer 不回退 Cookie，Cookie 写操作仍要求 CSRF，运营不能管理账号提权 | 主套 R08、R09；认证与会话单测 | 隔离回归通过，含普通运营角色拒绝路径 |
| 已有 WebSocket 在身份失效后停止私人投递、拒绝上行与补发，空闲连接受有界巡检约束 | 主套 7 个 WS 安全变更场景；F02 数据库故障；Realtime 单测包含空闲、逐帧 replay、慢写、拥塞与迟到结果 | 活跃连接及故障链路已实跑；慢写/拥塞/空闲边界有受控单测，不替代大规模真实网络故障和容量验证 |
| 状态未知时不授权、不伪报退出成功，恢复后可以明确重试 | 最终 fault533 的 F02/F03，新增 Cookie-only Redis 故障退出与恢复后重试 | 通过；包含中途 401、500 与恢复过程，不要求依赖重启瞬间所有旧连接已可用 |
| iOS 恢复与运行中 401 不串号，临时故障保留凭据，私信缓存按账号隔离 | UniPOCore 88 项；SessionCredentialTests、AuthStoreTests、APIClientTests、MessagesStoreTests | 自动化通过；真实 UI 的封禁/改密/退出所有设备展示清理仍待验 |
| Keychain 的保存、覆盖、删除跨 Store 重建真实持久 | iOS hosted `KeychainPersistenceTests` 2/2，真实系统 Keychain | iPhone 17 / iOS 26.5 模拟器通过；不是 iOS 17 或真机/Release 签名验收 |
| 本轮改动不破坏已有业务链路 | 最终通用 Bash 冒烟 42/42，图片上传启用且无跳过 | 本机隔离 fixture 通过，未部署验证 |

## 3. 最终通过产物

### 3.1 后端常规测试与数据库专项

2026-10-22 09:26 的 `mvn package` 执行记录及该时间段 Surefire XML 显示：发现 **138** 项，实际执行 **123** 项，失败 **0**，错误 **0**，条件跳过 **15** 项。现存报告目录还包含以前的类报告及带 run 后缀的专项报告，不能直接把目录所有 XML 相加。

跳过的 15 项分别是 MySQL 13 项与 Redis 2 项。它们已在独立 fixture 中显式启用并实跑通过，但不应写成“本次常规 Maven 138 项全部执行”：

| 运行 | 结果 | 证据 |
| --- | --- | --- |
| 常规 Maven，2026-10-22 09:26 | 138 发现 / 123 执行 / 15 条件跳过 / 0 失败 / 0 错误 | `backend/target/surefire-reports/` 中本次 09:26 生成的无 run 后缀报告 |
| MySQL `c4d47af57e1362c1` | 13 执行 / 0 失败 / 0 错误 / 0 跳过 | `.logs/dp02/mysql-c4d47af57e1362c1/TEST-com.biecuoguo.auth.internal.AccountSecurityMySqlIntegrationTest-c4d47af57e1362c1.xml` |
| Redis `d96cc43fa0264257` | 2 执行 / 0 失败 / 0 错误 / 0 跳过 | `.logs/dp02/d96cc43fa0264257/TEST-com.biecuoguo.state.RedisSessionStoreIntegrationTest-d96cc43fa0264257.xml` |

MySQL 专项实际覆盖独立设备、绝对寿命、单设备归属、全设备版本推进、旧版本不能创建设备、禁用/注销状态、数据库最新角色、自然到期、跨用户/版本拒绝、旧凭据拒绝、普通 ORM 写入保护、事务回滚、并发创建与撤销及旧事务快照。

Redis 两项验证全设备清理并保留另一用户，以及会话跨越原用户索引寿命后仍能被撤销。对应 `.log` 均保留 `BUILD SUCCESS`。

### 3.2 双实例 HTTP/WebSocket 主套

证据目录：`.logs/dp02/http-a481dad79fe7d885/`。`identity-http-results.json` 与 `results.log` 一致：**19 个场景 PASS，0 FAIL，JSON 共 1415 条检查**；结束时间为 2026-10-22 09:33:42。

覆盖 OpenAPI、R01 重置密码、R02 封禁/解封、R03 角色降级、R04 注销、R05 设备 token 家族、R06 退出所有设备、R07 改密、R08 CSRF/载体隔离、R09 运营权限边界、响应隐私，以及封禁、logout-all、重置、改密、注销、降级、当前设备退出的 7 个 WS 场景，最后覆盖 access 自然过期后的 refresh 退出。

7 个安全变更场景的 14 条 `closed-within-five-seconds` 检查均通过，记录的关闭观察耗时为 **0.012–0.022 秒**。这些是该 fixture 中已触发交互的观测值，不能把它们推广为空闲连接、大规模负载或所有网络条件的承诺；4 秒租约与 1 秒 watchdog 的设计边界仍按 ADR-0003 理解。

两个实例的 OpenAPI 分别保留为 `openapi-A.json`、`openapi-B.json`，后端包与散列也在同目录。

### 3.3 最终故障套

证据目录：`.logs/dp02/fault-53307b3e36a4941c/`。最终结果为 **3 个场景 PASS，0 FAIL，478 条检查**：

| 场景 | 检查数 | 实际覆盖 |
| --- | --- | --- |
| F01 | 73 | A 实例重启后撤销仍生效，B 作为保持运行的对照 |
| F02 | 118 | MySQL 不可用时拒绝私人数据与 replay，恢复后校验原凭据及此前撤销 |
| F03 | 279 | Redis 不可用时没有虚假的撤销成功，Cookie-only 退出、设备退出及恢复重试 |

F03 的关键事实由 `fault_observations` 记录：

- Cookie-only 退出带 CSRF，没有 Authorization、没有 body；Redis 故障期间返回 **500**，未伪报成功。
- Redis 恢复后，原凭据经过 **11.654 秒、6 次尝试**恢复可用，期间观察到临时 401。依赖服务启动完成与应用连接恢复可用分开判定。
- Cookie-only 退出重试前原 Cookie 仍可用，重试返回 **200**，随后确认对应设备被撤销。
- 单设备退出故障期返回 500，恢复后重试 200，持久撤销已确认。
- `control-results.jsonl` 中重启 A、停启 MySQL、停启 Redis 均完成；`recovery_still_required` 为空。

该故障套没有注入宿主机崩溃、复制故障转移、分布式网络分区；replay 的健康对照不等于已验证消息顺序、游标排他性或去重。

### 3.4 业务冒烟

证据：`.logs/dp02/smoke-1542ccc7171c3582/identity-smoke-results.json`。

结果 **PASS 42/42**，`original_smoke_exit=0`，`upload_enabled=true`，`skipped=false`，`failed_checks=[]`，`cleanup_complete=true`。覆盖认证与短信测试入口、refresh 类型拒绝、帖子与互动、关注和私信、推送 token、成就、图片上传、版本化端点、游标分页、统一响应、时间和注册边界。

### 3.5 iOS Core 与系统 Keychain

Core 于 2026-10-22 09:07 由主代理执行 88 项通过；为交付持久日志，**10:30:30 再次运行同一 Core 全套，88 项通过、0 失败**，日志保存在 `.logs/dp02/final-client/core-tests.log`。

Core 使用内存替身与本地请求替身，覆盖临时网络/存储故障、候选身份验证、运行中 401 代次、单飞恢复、旧账号迟到结果、成对凭据保存、持久退出标记、旧双 key 配对、环境隔离和私信缓存/草稿归属。它不访问真实用户 Keychain，也不能代替系统 Keychain 持久化测试。

系统 Keychain 另在 hosted App 测试中实跑：

- 结果包：`/private/tmp/unipo-keychain-20261022-0925.xcresult`。
- `xcresulttool` summary 为 **Passed，2 passed，0 failed，0 skipped**；设备为 **iPhone 17 / iOS 26.5 Simulator**。
- 用例分别验证不存在项只读返回 nil，以及保存、覆盖、删除跨 Store 重建保持结果。使用随机 fixture service，仅清理测试创建的项，不访问用户实际凭据。
- 模拟器应用保留本地 ad-hoc 签名与模拟器专属 application-identifier/Keychain 组；这是本机测试条件，**不表示真机签名或 Release 发布已就绪**。

结果包位于临时目录，正式交付归档时应连同日志一起保留；本文不把临时路径当作长期托管地址。

## 4. 保留的失败与修复轨迹

早期失败产物保留为证据，不删除，也不改写为通过：

| 轮次 | 原始结果 | 本轮解释及后续 |
| --- | --- | --- |
| 旧实现 R01：`baseline-ce67bab919788a08` | `confirmed_defect=true`、`acceptance_pass=false` | 重置密码成功、新密码可登录、旧密码被拒绝，但两设备旧 access/Cookie/refresh 在 A/B 仍得到 200。它证明旧缺陷，不是通过的验收 |
| Redis R06：`cc11921946df0244`、`0672abdb7db4e7b2`、`ee7250f9af592058` | 每轮 2 项执行，其中 1 项失败 | 原用户索引已过期但活跃会话仍在，批量撤销漏掉会话；后续 `d96cc43fa0264257` 两项通过 |
| fault 第一轮：`69897164d9bc3a73` | F01/F02 通过，F03 失败 | 恢复后原本应保留的设备 A bearer 得到 401；该轮没有满足恢复验收，不能作为最终结果 |
| fault 第二轮：`a80f2007ecb514b5` | F01/F02 通过，F03 失败，共 266 条检查 | 单设备退出先得到 401；finally 中 `start_redis` 两次约 90 秒失败并遮蔽首个失败原因。fixture marker 的 600 秒 TTL 已被长启动耗尽，后续移除运行期 marker TTL、区分恢复阶段并等待原凭据真实恢复 |
| fault 第三轮：`78e4c626724bf576` | 3 场景 / 392 条检查通过 | 随后代码审查发现 Cookie-only Redis 故障退出仍可假成功，该轮未覆盖这一分支，因此不能作为修复后的最终证明 |
| 修复后最终 fault：`53307b3e36a4941c` | 3 场景 / 478 条检查通过 | 补充 Cookie-only 故障退出、恢复与重试，再次通过；对应最终后端包 |

旧 R01 证据位于对应目录的 `identity-baseline-results.json` 与 `results.log`，基线 revision 单独保留在 `source-revision.txt`。其余故障轮次各保留 `identity-fault-results.json`、`control-results.jsonl` 与 `results.log`。

## 5. 尚未完成的验收与发布边界

1. **短信登录并发专项**：需要覆盖验证码验证、读取账号版本与创建持久设备之间发生 logout-all/封禁/角色变更的窗口。MySQL 的并发创建设备测试不是完整短信登录链路。
2. **真实 UI 吊销专项**：真实 App 登录后，由另一设备/管理动作改密、封禁、退出所有设备，检查当前页面身份隐藏、私信缓存清理、草稿归属、重试和重新登录。Core 的状态测试不替代界面实测。
3. **最低系统与真机**：iOS 17、物理设备的锁屏/解锁与后台恢复、真实 Keychain 权限及正式签名未验。本轮 iOS 26.5 模拟器 Keychain 通过不能替代它们。
4. **故障覆盖的实际边界**：慢查询、慢写、校验队列拥塞及单调租约有受控单测；真实大规模空闲连接、数据库/Redis 高延迟、网络分区、主从切换与宿主机崩溃尚未形成同等系统级证据。
5. **容量**：未测目标 QPS、P95/P99、数据库池压力或高连接数下每帧权威查询开销，不宣称达到百万日活容量。
6. **部署**：本轮未部署生产或既有 server test。上线仍需安排旧 JWT/Cookie 一次性重新登录、确保所有实例采用新校验、保留具备版本/设备校验的回滚版本，并完成部署后验收。

本记录交付的是当前实现及已完成验证的边界。它不修改开发规划的排期与状态，也不将 DP02、整个 App 或 Release 标记为全部完成。
